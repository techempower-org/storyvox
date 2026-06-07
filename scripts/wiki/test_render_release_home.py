#!/usr/bin/env python3
"""Tests for render_release_home.py — run with:
    python3 scripts/wiki/test_render_release_home.py

Pure-stdlib (no pytest), matching the sibling test_sync_wiki.py convention.
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

os.environ.setdefault("REPO", "techempower-org/candela")
os.environ.setdefault("TAG", "v1.1.3")
os.environ.setdefault("CHANGELOG", "/dev/null")

sys.path.insert(0, str(Path(__file__).resolve().parent))
import render_release_home as rr  # noqa: E402

CHANGELOG = """\
# Changelog

## [Unreleased]

## [1.1.3] -- 2026-06-07

**Mark the page.** In-reader text highlighting.

### Added

- **In-reader highlights.** Long-press to select text in the reader, pick a
  color, and attach a note. (#1079 / #1088)

### Tested

- The select->highlight gesture is verified on-device.

### Fixed (internal)

- Some internal-only plumbing that should NOT appear on the wiki.

## [1.1.2] -- 2026-06-06

**Right book, every time.** Older release.

### Fixed

- An older fix that must not bleed into the 1.1.3 section.
"""

HOME = """\
# Candela wiki

_Current version: v0.5.51_
**Candela is TechEmpower's accessible resource app.**

📦 **Latest release:** [v0.5.51 — Luminous Quartz](https://github.com/techempower-org/candela/releases/tag/v0.5.51) — old summary.

---

## Pages

- **[Getting started](Getting-started)**

## What's new in v0.5.51

- Old bullet that should be replaced.

## Recent v0.5 highlights

- A hand-curated highlight we do NOT own.

## Quick links

- [Releases](https://github.com/techempower-org/candela/releases)
"""


def render(home_text: str, tag: str = "v1.1.3", changelog: str = CHANGELOG) -> str:
    with tempfile.TemporaryDirectory() as d:
        cl = Path(d) / "CHANGELOG.md"
        cl.write_text(changelog)
        home = Path(d) / "Home.md"
        home.write_text(home_text)
        os.environ["TAG"] = tag
        os.environ["CHANGELOG"] = str(cl)
        sys.argv = ["render_release_home.py", str(home)]
        rr.main()
        return home.read_text()


class ParseChangelog(unittest.TestCase):
    def test_tagline_and_bullets(self):
        tagline, bullets = rr.parse_changelog_section(CHANGELOG, "v1.1.3")
        self.assertEqual(tagline, "**Mark the page.** In-reader text highlighting.")
        self.assertEqual(len(bullets), 2)  # Added + Tested; internal dropped
        self.assertIn("In-reader highlights", bullets[0])
        self.assertIn("on-device", bullets[1])

    def test_internal_bullets_dropped(self):
        _, bullets = rr.parse_changelog_section(CHANGELOG, "v1.1.3")
        self.assertFalse(any("internal" in b.lower() for b in bullets))

    def test_does_not_bleed_into_older_release(self):
        _, bullets = rr.parse_changelog_section(CHANGELOG, "v1.1.3")
        self.assertFalse(any("older fix" in b.lower() for b in bullets))

    def test_missing_version_raises(self):
        with self.assertRaises(SystemExit):
            rr.parse_changelog_section(CHANGELOG, "v9.9.9")


class RenderHome(unittest.TestCase):
    def test_version_line_updated(self):
        out = render(HOME)
        self.assertIn("_Current version: v1.1.3_", out)
        self.assertNotIn("_Current version: v0.5.51_", out)

    def test_latest_release_line(self):
        out = render(HOME)
        self.assertIn(
            "📦 **Latest release:** [v1.1.3 — Mark the page]"
            "(https://github.com/techempower-org/candela/releases/tag/v1.1.3)"
            " — In-reader text highlighting.",
            out,
        )

    def test_whats_new_fenced_and_populated(self):
        out = render(HOME)
        self.assertIn(rr.WN_BEGIN, out)
        self.assertIn(rr.WN_END, out)
        self.assertIn("## What's new in v1.1.3", out)
        self.assertIn("In-reader highlights", out)
        self.assertNotIn("Old bullet that should be replaced", out)

    def test_legacy_unowned_section_preserved(self):
        out = render(HOME)
        self.assertIn("## Recent v0.5 highlights", out)
        self.assertIn("A hand-curated highlight we do NOT own", out)

    def test_idempotent(self):
        once = render(HOME)
        twice = render(once)
        self.assertEqual(once, twice)

    def test_next_release_replaces_in_place_no_dupes(self):
        once = render(HOME)
        cl2 = CHANGELOG.replace(
            "## [Unreleased]",
            "## [Unreleased]\n\n## [1.2.0] -- 2026-07-01\n\n"
            "**Next one.** A new summary.\n\n### Added\n\n- A 1.2.0 bullet.",
        )
        twice = render(once, tag="v1.2.0", changelog=cl2)
        self.assertEqual(twice.count(rr.WN_BEGIN), 1)
        self.assertIn("## What's new in v1.2.0", twice)
        self.assertNotIn("## What's new in v1.1.3", twice)

    def test_missing_latest_release_line_raises(self):
        broken = HOME.replace("📦 **Latest release:**", "Latest release:")
        with self.assertRaises(SystemExit):
            render(broken)


if __name__ == "__main__":
    unittest.main(verbosity=2)
