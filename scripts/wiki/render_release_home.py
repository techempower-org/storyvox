#!/usr/bin/env python3
"""Render the release-current regions of the wiki Home.md from CHANGELOG.md.

Owned by .github/workflows/release-docs-sync.yml, which direct-pushes the wiki
on every release tag (no PR, no PAT — the .wiki repo is unprotected). This is
the ONLY writer of wiki Home.md; the companion wiki-sync.yml deliberately never
touches Home.md, so the two workflows never collide.

Three regions are regenerated from the tag's CHANGELOG.md section:

  1. The current-version line          `_Current version: vX.Y.Z_`
  2. The "Latest release" line         `📦 **Latest release:** [vX — Title](url) — tagline`
  3. The "What's new" section          fenced by AUTO-GENERATED markers and
                                       rebuilt from the release's bullets.

Everything else in Home.md (the intro, the Pages list, "Recent highlights",
Quick links) is hand-authored and left untouched — this script only rewrites
the three regions above and fails loudly if it cannot.

Usage:  REPO=owner/name TAG=v1.1.3 CHANGELOG=/path/to/CHANGELOG.md \\
            render_release_home.py /path/to/Home.md
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

# Fence markers for the auto-owned "What's new" section. Stable across runs so
# the section is replaced in place rather than appended.
WN_BEGIN = "<!-- AUTO-GENERATED: release-whats-new BEGIN (release-docs-sync.yml) -->"
WN_END = "<!-- AUTO-GENERATED: release-whats-new END -->"


def parse_changelog_section(changelog: str, version: str) -> tuple[str, list[str]]:
    """Return (tagline, bullets) for `## [version] -- date` in the changelog.

    `tagline` is the bold lead sentence + its summary (the line after the
    heading, e.g. "**Mark the page.** In-reader text highlighting.").
    `bullets` are the top-level `- ` list items across all `###` subsections,
    in document order, with their multi-line continuations folded to one line.
    """
    # Match the heading for this exact version, capture body up to the next
    # `## [` heading (or EOF). Version may carry a leading 'v'.
    v = version.lstrip("v")
    heading_re = re.compile(
        r"^##\s*\[" + re.escape(v) + r"\][^\n]*\n(?P<body>.*?)(?=^##\s*\[|\Z)",
        re.M | re.S,
    )
    m = heading_re.search(changelog)
    if not m:
        raise SystemExit(f"render_release_home: no CHANGELOG section for [{v}]")
    body = m.group("body").strip("\n")

    # Tagline: the first non-empty line of the body, before any `###` subsection.
    tagline = ""
    for line in body.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.startswith("#"):
            break
        tagline = stripped
        break

    # Bullets: every top-level `- ` item (deeper indented continuations are
    # folded onto the preceding bullet). Bullets under a `###` subsection whose
    # heading is flagged "(internal)" are dropped — the flag lives on the
    # SUBSECTION HEADING (e.g. "### Fixed (internal)"), not on the bullet text,
    # so we track the active subsection and skip accordingly.
    bullets: list[str] = []
    cur: str | None = None
    skip_subsection = False

    def flush() -> None:
        nonlocal cur
        if cur is not None and not skip_subsection:
            bullets.append(cur)
        cur = None

    for line in body.splitlines():
        if line.startswith("#"):                       # a `###` subsection heading
            flush()
            skip_subsection = "(internal)" in line.lower()
        elif re.match(r"^- ", line):
            flush()
            cur = line[2:].strip()
        elif re.match(r"^\s+\S", line) and cur is not None:
            cur += " " + line.strip()
        elif line.strip() == "":
            flush()
    flush()

    # Also drop any individual bullet that self-flags internal, belt-and-braces.
    bullets = [b for b in bullets if "(internal)" not in b.lower()]
    return tagline, bullets


def render_whats_new(version: str, tagline: str, bullets: list[str]) -> str:
    """Build the fenced 'What's new' section body."""
    lines = [WN_BEGIN, f"## What's new in {version}", ""]
    if tagline:
        lines.append(tagline)
        lines.append("")
    if bullets:
        lines.extend(f"- {b}" for b in bullets)
    else:
        lines.append("_See the [release notes](RELEASE_URL) for details._")
    lines.append(WN_END)
    return "\n".join(lines)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: render_release_home.py <Home.md>")
    home_path = Path(sys.argv[1])
    tag = os.environ["TAG"]                      # e.g. v1.1.3
    repo = os.environ["REPO"]                    # owner/name
    changelog_path = Path(os.environ["CHANGELOG"])

    release_url = f"https://github.com/{repo}/releases/tag/{tag}"
    changelog = changelog_path.read_text(encoding="utf-8")
    tagline, bullets = parse_changelog_section(changelog, tag)

    # Title for the "Latest release" line: the bold lead of the tagline, e.g.
    # "**Mark the page.** ..." -> "Mark the page". Fall back to the tag.
    title_m = re.match(r"\*\*(?P<t>[^*]+?)\.?\*\*", tagline)
    release_title = title_m.group("t").strip() if title_m else tag
    # The descriptive remainder after the bold lead (for the Latest-release line).
    remainder = tagline[title_m.end():].strip() if title_m else tagline

    s = home_path.read_text(encoding="utf-8")
    orig = s

    # 1. Current-version line.
    ver_re = re.compile(r"^_Current version: v\d+\.\d+\.\d+_\s*$", re.M)
    ver_line = f"_Current version: {tag}_"
    if ver_re.search(s):
        s = ver_re.sub(ver_line, s, count=1)
    else:
        # Insert just after the first H1.
        s = re.sub(r"(\A#[^\n]+\n)", lambda m: m.group(1) + "\n" + ver_line + "\n",
                   s, count=1)

    # 2. Latest-release line.
    latest_re = re.compile(r"^📦 \*\*Latest release:\*\*.*$", re.M)
    latest_line = (
        f"📦 **Latest release:** [{tag} — {release_title}]({release_url})"
        + (f" — {remainder}" if remainder else "")
    )
    if latest_re.search(s):
        s = latest_re.sub(lambda _m: latest_line, s, count=1)
    else:
        raise SystemExit("render_release_home: no '📦 **Latest release:**' line in Home.md")

    # 3. "What's new" section (fenced + idempotent).
    whats_new = render_whats_new(tag, tagline, bullets).replace("RELEASE_URL", release_url)
    fenced_re = re.compile(re.escape(WN_BEGIN) + r".*?" + re.escape(WN_END), re.S)
    if fenced_re.search(s):
        s = fenced_re.sub(lambda _m: whats_new, s, count=1)
    else:
        # First adoption: replace the legacy, unfenced "## What's new in vX"
        # block (up to the next `## ` heading) with the fenced version.
        legacy_re = re.compile(r"^## What's new in v\d+\.\d+\.\d+.*?(?=^## )", re.M | re.S)
        if legacy_re.search(s):
            s = legacy_re.sub(whats_new + "\n\n", s, count=1)
        else:
            raise SystemExit("render_release_home: no '## What's new' section to take over")

    if s != orig:
        home_path.write_text(s, encoding="utf-8")
        print(f"render_release_home: Home.md updated to {tag} "
              f"({len(bullets)} bullet(s)).")
    else:
        print(f"render_release_home: Home.md already current for {tag}.")


if __name__ == "__main__":
    main()
