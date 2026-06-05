#!/usr/bin/env python3
"""Sync canonical repo docs into the GitHub wiki working tree.

Source of truth is the repo (docs/, CHANGELOG.md, settings.gradle.kts), never the
wiki. This script regenerates only the wiki pages it OWNS and stages them; the
calling workflow (.github/workflows/wiki-sync.yml) commits and pushes.

Two-layer guard against clobbering hand-authored wiki pages:

  1. PAGE_MAP allowlist — only pages listed here are ever written. Pages not in
     the map (Home, Getting-started, Fiction-sources, AI-chat, Voices,
     Voice-catalog, Settings-reference, Building-from-source, Troubleshooting)
     are never read, written, or deleted.

  2. Ownership marker (MARKER_RE) — each generated page starts with an
     AUTO-GENERATED comment. Before overwriting an EXISTING page the script
     checks for that marker: present => we own it, overwrite; absent => a human
     owns it, SKIP with a warning. Takeover of a hand-authored page is opt-in
     via FORCE_TAKEOVER=true (workflow_dispatch input), never silent.

Pages are never deleted: removing a source doc leaves the wiki page in place
(logged, not removed).

The script is deterministic, so re-running with unchanged sources stages no
changes and the workflow makes no commit (idempotent).

Environment:
  WIKI_DIR         path to the cloned .wiki working tree (required)
  REPO             owner/name slug, e.g. techempower-org/candela (required)
  OWNER            repository owner, for Pages URLs (required)
  NAME             repository name, for Pages URLs (required)
  SRC_SHA          source commit SHA, recorded in the provenance line (optional)
  FORCE_TAKEOVER   "true" to overwrite marker-less existing pages (optional)
  GITHUB_WORKSPACE repo checkout root (provided by Actions; falls back to cwd)
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Allowlist: canonical source (relative to repo root) -> wiki page filename.
# A source of None means the page is generated (see GENERATORS below).
# ---------------------------------------------------------------------------
PAGE_MAP: dict[str, str] = {
    "docs/architecture.md": "Architecture.md",
    "docs/accessibility.md": "Accessibility.md",
    "docs/sync.md": "Cloud-sync.md",
    "docs/compose-gotchas.md": "Compose-gotchas.md",
    "docs/ROADMAP.md": "Roadmap.md",
    "CHANGELOG.md": "Changelog.md",
}

# Wiki pages produced from repo state rather than a single source file.
# name -> (generator key, human-readable source label)
GENERATED_PAGES: dict[str, str] = {
    "Modules.md": "settings.gradle.kts",
}

# Maps a source doc's intra-repo link target (basename) to the wiki page slug
# it should point at. Built from PAGE_MAP plus the docs-site landing pages that
# have no wiki equivalent (those are left pointing at the published site).
def _wiki_slug(page_filename: str) -> str:
    """`Architecture.md` -> `Architecture` (GitHub wiki link slug)."""
    return page_filename[:-3] if page_filename.endswith(".md") else page_filename


# Basename of a synced source doc -> wiki slug it should resolve to.
INTRA_DOC_LINKS: dict[str, str] = {
    Path(src).name: _wiki_slug(dst) for src, dst in PAGE_MAP.items()
}

MARKER_PREFIX = "<!-- AUTO-GENERATED FROM"
MARKER_RE = re.compile(r"^<!-- AUTO-GENERATED FROM .* -->\s*$", re.M)

FRONT_MATTER_RE = re.compile(r"\A---\n.*?\n---\n", re.S)

# Root-relative asset/link like `](/screenshots/x.png)` or `](/voices/)`.
ROOT_REL_LINK_RE = re.compile(r"\]\((/[^)]+)\)")
# Intra-doc markdown link like `](architecture.md)` or `](architecture.md#anchor)`.
INTRA_DOC_LINK_RE = re.compile(r"\]\((?!https?://|/|#)([^)#]+\.md)(#[^)]*)?\)")


def repo_root() -> Path:
    ws = os.environ.get("GITHUB_WORKSPACE")
    return Path(ws) if ws else Path.cwd()


def pages_base_url() -> str:
    owner = os.environ["OWNER"]
    name = os.environ["NAME"]
    return f"https://{owner}.github.io/{name}"


def marker(source_label: str) -> str:
    return (
        f"<!-- AUTO-GENERATED FROM {source_label} BY "
        f".github/workflows/wiki-sync.yml — DO NOT EDIT HERE. "
        f"Edit the source in the repo and push to main. -->"
    )


def provenance(source_label: str) -> str:
    sha = os.environ.get("SRC_SHA", "")
    short = sha[:7] if sha else "main"
    return (
        f"_Synced from [`{source_label}`]"
        f"(https://github.com/{os.environ['REPO']}/blob/main/{source_label}) "
        f"@ `{short}`. Edit there, not here._"
    )


def strip_front_matter(text: str) -> str:
    return FRONT_MATTER_RE.sub("", text, count=1)


def rewrite_links(text: str) -> str:
    base = pages_base_url()

    def _intra(m: re.Match) -> str:
        target = m.group(1)
        anchor = m.group(2) or ""
        name = Path(target).name
        slug = INTRA_DOC_LINKS.get(name)
        if slug:
            # GitHub wiki links don't carry .md; anchors pass through.
            return f"]({slug}{anchor})"
        # Unknown sibling doc — point at the published docs site so it resolves.
        return f"]({base}/{target}{anchor})"

    text = INTRA_DOC_LINK_RE.sub(_intra, text)
    # Root-relative -> absolute Pages URL (images, /voices/, etc.).
    text = ROOT_REL_LINK_RE.sub(lambda m: f"]({base}{m.group(1)})", text)
    return text


def render_doc(source_label: str, raw: str) -> str:
    body = strip_front_matter(raw)
    body = rewrite_links(body)
    return f"{marker(source_label)}\n{provenance(source_label)}\n\n{body.lstrip()}"


def parse_modules(settings_text: str) -> list[str]:
    # Line-by-line so commented-out includes don't leak into the wiki, and
    # accept either quote style. settings.gradle.kts uses double quotes, but a
    # disabled module is often left as `// include(":foo")` during refactors —
    # publishing that as a live module would be wrong.
    mods: list[str] = []
    include_re = re.compile(r"""include\(\s*['"](:[^'"]+)['"]\s*\)""")
    for line in settings_text.splitlines():
        stripped = line.strip()
        if stripped.startswith(("//", "/*", "*")):
            continue
        match = include_re.search(stripped)
        if match:
            mods.append(match.group(1))
    # Deterministic order for idempotency.
    return sorted(set(mods))


def render_modules_page(source_label: str, mods: list[str]) -> str:
    groups: dict[str, list[str]] = {
        "Application": [],
        "Feature": [],
        "Core": [],
        "Fiction sources": [],
        "Build / tooling": [],
        "Other": [],
    }
    for m in mods:
        name = m.lstrip(":")
        if name in ("app",):
            groups["Application"].append(m)
        elif name in ("feature", "wear"):
            groups["Feature"].append(m)
        # Build tools must be matched before the generic core-/source- prefixes:
        # core-plugin-ksp is core-prefixed but is build tooling, not a core lib.
        elif name in ("baselineprofile", "core-plugin-ksp"):
            groups["Build / tooling"].append(m)
        elif name.startswith("core-"):
            groups["Core"].append(m)
        elif name.startswith("source-"):
            groups["Fiction sources"].append(m)
        else:
            groups["Other"].append(m)

    lines = [
        marker(source_label),
        provenance(source_label),
        "",
        "# Modules",
        "",
        f"Candela is a multi-module Gradle project — **{len(mods)} modules** "
        f"as declared in [`settings.gradle.kts`]"
        f"(https://github.com/{os.environ['REPO']}/blob/main/settings.gradle.kts). "
        "This page is generated; the source file is canonical. See "
        "[Architecture](Architecture) for the dependency graph and module roles.",
        "",
    ]
    for group, members in groups.items():
        if not members:
            continue
        lines.append(f"## {group}")
        lines.append("")
        lines.append("| Module |")
        lines.append("|--------|")
        for m in members:
            lines.append(f"| `{m}` |")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def should_write(dest: Path, force: bool) -> tuple[bool, str]:
    """Return (write?, reason). Honors the ownership-marker guard."""
    if not dest.exists():
        return True, "new page"
    existing = dest.read_text(encoding="utf-8")
    if MARKER_RE.search(existing):
        return True, "owned (marker present)"
    if force:
        return True, "FORCE_TAKEOVER override"
    return False, "hand-authored (no marker) — skipping, not clobbering"


def write_if_changed(dest: Path, content: str) -> bool:
    """Write only if bytes differ. Return True if the file changed on disk."""
    if dest.exists() and dest.read_text(encoding="utf-8") == content:
        return False
    dest.write_text(content, encoding="utf-8")
    return True


def main() -> int:
    # Validate required env upfront so a missing var fails with a clear message
    # instead of a mid-run KeyError traceback that's painful to diagnose in CI.
    required = ["WIKI_DIR", "REPO", "OWNER", "NAME"]
    missing = [v for v in required if not os.environ.get(v)]
    if missing:
        print(
            f"::error::missing required env var(s): {', '.join(missing)}",
            file=sys.stderr,
        )
        return 1

    wiki_dir = Path(os.environ["WIKI_DIR"])
    root = repo_root()
    force = os.environ.get("FORCE_TAKEOVER", "false").lower() == "true"

    changed: list[str] = []
    skipped: list[str] = []

    # 1. File-backed pages.
    for src_rel, page in PAGE_MAP.items():
        src = root / src_rel
        if not src.exists():
            print(f"::warning::source missing, skipping: {src_rel}")
            continue
        dest = wiki_dir / page
        ok, reason = should_write(dest, force)
        if not ok:
            print(f"::warning::{page}: {reason} (source {src_rel})")
            skipped.append(page)
            continue
        content = render_doc(src_rel, src.read_text(encoding="utf-8"))
        if write_if_changed(dest, content):
            changed.append(page)
            print(f"{page}: written ({reason}) from {src_rel}")
        else:
            print(f"{page}: unchanged")

    # 2. Generated pages.
    for page, source_label in GENERATED_PAGES.items():
        dest = wiki_dir / page
        ok, reason = should_write(dest, force)
        if not ok:
            print(f"::warning::{page}: {reason}")
            skipped.append(page)
            continue
        if source_label == "settings.gradle.kts":
            settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
            content = render_modules_page(source_label, parse_modules(settings))
        else:
            print(f"::warning::no generator for {page}; skipping")
            continue
        if write_if_changed(dest, content):
            changed.append(page)
            print(f"{page}: written ({reason}) — generated from {source_label}")
        else:
            print(f"{page}: unchanged")

    # 3. Stage ONLY managed pages (never `git add -A`). Use a subprocess arg
    #    list (not a shell string) so page names can never be a shell-injection
    #    sink — they are hardcoded constants here, but the arg-list form keeps it
    #    safe regardless of how PAGE_MAP evolves.
    managed = list(PAGE_MAP.values()) + list(GENERATED_PAGES.keys())
    if changed:
        result = subprocess.run(
            ["git", "add", "--", *managed],
            cwd=wiki_dir,
        )
        if result.returncode != 0:
            print("::error::git add failed")
            return 1

    print(f"\nSummary: {len(changed)} written, {len(skipped)} skipped (hand-authored).")
    if changed:
        print("  written: " + ", ".join(changed))
    if skipped:
        print("  skipped: " + ", ".join(skipped))
    return 0


if __name__ == "__main__":
    sys.exit(main())
