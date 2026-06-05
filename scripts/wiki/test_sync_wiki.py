#!/usr/bin/env python3
"""Tests for sync_wiki.py — run with: python3 scripts/wiki/test_sync_wiki.py

Pure-stdlib (no pytest dependency) so it runs anywhere python3 is present,
matching the repo convention of not adding test frameworks unprompted.
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

# Env that the module reads at import/call time.
os.environ.setdefault("REPO", "techempower-org/candela")
os.environ.setdefault("OWNER", "techempower-org")
os.environ.setdefault("NAME", "candela")
os.environ.setdefault("SRC_SHA", "abcdef1234567890")

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sync_wiki as sw  # noqa: E402


class StripFrontMatter(unittest.TestCase):
    def test_strips_leading_jekyll_block(self):
        raw = "---\nlayout: default\ntitle: X\n---\n# Heading\n\nBody\n"
        self.assertEqual(sw.strip_front_matter(raw), "# Heading\n\nBody\n")

    def test_no_front_matter_passthrough(self):
        raw = "# Heading\n\nBody\n"
        self.assertEqual(sw.strip_front_matter(raw), raw)

    def test_only_strips_first_block(self):
        raw = "---\na: 1\n---\nbody\n---\nfooter rule\n---\n"
        out = sw.strip_front_matter(raw)
        self.assertTrue(out.startswith("body\n"))
        self.assertIn("footer rule", out)


class RewriteLinks(unittest.TestCase):
    def test_intra_doc_known_target_to_wiki_slug(self):
        # compose-gotchas.md is in PAGE_MAP -> Compose-gotchas
        out = sw.rewrite_links("see [gotchas](compose-gotchas.md) here")
        self.assertIn("[gotchas](Compose-gotchas)", out)

    def test_intra_doc_with_anchor_preserved(self):
        out = sw.rewrite_links("[a](architecture.md#data-flow)")
        self.assertIn("[a](Architecture#data-flow)", out)

    def test_intra_doc_unknown_target_to_pages_site(self):
        out = sw.rewrite_links("[x](some-other-doc.md)")
        self.assertIn(
            "[x](https://techempower-org.github.io/candela/some-other-doc.md)", out
        )

    def test_root_relative_to_pages(self):
        out = sw.rewrite_links("![s](/screenshots/03-reader.png)")
        self.assertIn(
            "![s](https://techempower-org.github.io/candela/screenshots/03-reader.png)",
            out,
        )

    def test_absolute_links_untouched(self):
        raw = "[ext](https://example.com/x.md)"
        self.assertEqual(sw.rewrite_links(raw), raw)


class RenderDoc(unittest.TestCase):
    def test_marker_and_provenance_prepended(self):
        out = sw.render_doc("docs/architecture.md", "---\ntitle: A\n---\n# Arch\n")
        self.assertTrue(out.startswith(sw.MARKER_PREFIX))
        self.assertIn("docs/architecture.md", out)
        self.assertIn("abcdef1", out)  # short sha
        self.assertIn("# Arch", out)

    def test_idempotent_render(self):
        raw = "---\ntitle: A\n---\n# Arch\n\nbody\n"
        self.assertEqual(
            sw.render_doc("docs/architecture.md", raw),
            sw.render_doc("docs/architecture.md", raw),
        )


class ParseModules(unittest.TestCase):
    SETTINGS = (
        'rootProject.name = "x"\n'
        'include(":app")\n'
        'include(":source-rss")\n'
        'include( ":core-data" )\n'
        'include(":app")\n'  # duplicate
    )

    def test_dedup_and_sorted(self):
        self.assertEqual(
            sw.parse_modules(self.SETTINGS),
            [":app", ":core-data", ":source-rss"],
        )

    def test_commented_out_includes_ignored(self):
        text = (
            'include(":app")\n'
            '// include(":source-disabled")\n'
            '  //include(":also-disabled")\n'
            '/* include(":block-disabled") */\n'
            'include(":core-data")\n'
        )
        self.assertEqual(
            sw.parse_modules(text),
            [":app", ":core-data"],
        )

    def test_single_quoted_include_accepted(self):
        text = "include(':source-rss')\ninclude(\":app\")\n"
        self.assertEqual(
            sw.parse_modules(text),
            [":app", ":source-rss"],
        )


class RenderModulesPage(unittest.TestCase):
    def test_groups_and_count(self):
        mods = [":app", ":core-data", ":core-plugin-ksp", ":source-rss", ":wear", ":baselineprofile"]
        page = sw.render_modules_page("settings.gradle.kts", mods)
        self.assertTrue(page.startswith(sw.MARKER_PREFIX))
        self.assertIn("**6 modules**", page)
        self.assertIn("## Application", page)
        self.assertIn("## Fiction sources", page)
        self.assertIn("`:source-rss`", page)
        # core-plugin-ksp is reclassified as build tooling, not core
        bt = page.split("## Build / tooling")[1]
        self.assertIn(":core-plugin-ksp", bt)


class ShouldWrite(unittest.TestCase):
    def test_new_page_writes(self):
        with tempfile.TemporaryDirectory() as d:
            dest = Path(d) / "New.md"
            ok, _ = sw.should_write(dest, force=False)
            self.assertTrue(ok)

    def test_marked_page_overwrites(self):
        with tempfile.TemporaryDirectory() as d:
            dest = Path(d) / "Owned.md"
            dest.write_text(sw.marker("docs/x.md") + "\nstuff\n")
            ok, _ = sw.should_write(dest, force=False)
            self.assertTrue(ok)

    def test_handauthored_page_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            dest = Path(d) / "Human.md"
            dest.write_text("# Hand written\n\nNo marker here.\n")
            ok, reason = sw.should_write(dest, force=False)
            self.assertFalse(ok)
            self.assertIn("hand-authored", reason)

    def test_handauthored_page_force_overrides(self):
        with tempfile.TemporaryDirectory() as d:
            dest = Path(d) / "Human.md"
            dest.write_text("# Hand written\n")
            ok, reason = sw.should_write(dest, force=True)
            self.assertTrue(ok)
            self.assertIn("FORCE", reason)


class WriteIfChanged(unittest.TestCase):
    def test_no_change_returns_false(self):
        with tempfile.TemporaryDirectory() as d:
            dest = Path(d) / "P.md"
            dest.write_text("same\n")
            self.assertFalse(sw.write_if_changed(dest, "same\n"))

    def test_change_returns_true(self):
        with tempfile.TemporaryDirectory() as d:
            dest = Path(d) / "P.md"
            dest.write_text("old\n")
            self.assertTrue(sw.write_if_changed(dest, "new\n"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
