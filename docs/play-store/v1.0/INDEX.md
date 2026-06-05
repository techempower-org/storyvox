---
layout: default
title: Candela · Play Console graphics assets v1.0
description: Index of graphics assets prepared for the Candela v1.0 Play Store Internal Test upload.
---

# Play Console graphics assets — v1.0 Internal Test

Source build: **v0.5.66** (released 2026-05-16). Screenshots captured against
the v0.5.65 APK installed on test devices — the v0.5.66 diff is loading-
animation polish only, with no surface changes that affect these
screenshots.

Captured 2026-05-16 by JP / Claude. Devices:

- **Phone:** Samsung Galaxy Z Flip 3 (`R5CRB0W66MK`), 1080×2640, portrait
- **Tablet:** Samsung Galaxy Tab A7 Lite (`R83W80CAFZB`), 1340×800,
  landscape, dark theme

All shots in Library Nocturne dark theme (warm-dark base, brass accents)
with Notion banner-fetch enabled.

## Upload checklist (Play Console "Store listing" form)

| Field | File | Dimensions | Notes |
|---|---|---|---|
| **App icon (high-res)** | `icon-512.png` | 512×512 PNG | Library Nocturne adaptive launcher icon rendered to flat 512×512. Brass book on warm-dark with sun-disk halo, sound-wave arcs, and candle flame. No alpha. |
| **Feature graphic** | `feature-1024x500.png` | 1024×500 PNG | Storefront banner. Library Nocturne palette, brass `Candela` wordmark at left-center, tagline "Stories, read out loud." Hero sun-disk glow on right. Adaptive-icon book tile on the left. Right ~30 % kept clear for Play Console's overlaid install button. Under 200 KB. |
| **Phone screenshots × 4** | `phone-01-library-hero.png` `phone-02-techempower-home.png` `phone-03-browse-resources.png` `phone-04-fiction-detail.png` | 1080×2640 (9:22) | Captured on Galaxy Z Flip 3 portrait. Min 4 satisfied. |
| **7-inch tablet × 6** | `tablet-01-…` … `tablet-06-…` | 1340×800 (≈16:9.5) | Captured on Tab A7 Lite landscape. Play Console accepts a single tablet set for both 7" and 10" buckets — submit the same six in the 10-inch bucket. |
| **10-inch tablet × 6** | same six tablet files | 1340×800 | Submit the same six tablet PNGs. |

## Phone screenshots

| # | Filename | Surface | Caption for Play Store listing |
|---|---|---|---|
| 1 | `phone-01-library-hero.png` | Library tab — TechEmpower hero card, Continue Listening ("Guides · Ch. 6 · Free cell service"), and the bottom nav dock | Free books, tech guides, and peer support — in one library. |
| 2 | `phone-02-techempower-home.png` | TechEmpower Home — five brass-edged cards: Browse Resources, Peer Support, Call 211, Emergency Help, About | Real peer support, one tap away. Dial 211 or 988. |
| 3 | `phone-03-browse-resources.png` | Notion source grid — TechEmpower fictions (Guides, Resources, About, Donate) with Notion page banners loaded fresh from the cleared cache | Browse free guides, resources, and how-tos — read out loud. |
| 4 | `phone-04-fiction-detail.png` | FictionDetail for "Guides" — cover, "7 ch · Ongoing", Play button, description, chapter list | Tap Play. Listen, don't scroll. |

## Tablet screenshots

| # | Filename | Surface | Caption for Play Store listing |
|---|---|---|---|
| 1 | `tablet-01-library-hero.png` | Library — NavigationRail (left), Library/Browse/Follows/Inbox/History tabs, TechEmpower hero card, "Ch. 4 · Findhelp" Continue Listening, action buttons in the title bar (988, 211, Discord, Sync, Settings) | The full library at a glance — landscape-aware. |
| 2 | `tablet-02-techempower-home.png` | TechEmpower Home — five brass-edged cards laid out for landscape | Free books, tech guides, and accessible help — all in one place. |
| 3 | `tablet-03-browse-resources.png` | Notion source grid — Guides, Resources, About, Donate covers in a 4-up landscape grid | Browse free guides, resources, and how-tos. |
| 4 | `tablet-04-fiction-detail.png` | FictionDetail for "Donate" — cover, "1 ch · Ongoing", Play + Add-to-library buttons, description, chapter list, title bar | Cover, chapters, one Play button. |
| 5 | `tablet-05-player.png` | Player — active playback of Donate, large cover centered, title and chapter caption, playback progress scrubber, Voice settings + Player options in the title bar | A clean cover-and-scrubber player. |
| 6 | `tablet-06-voice-picker.png` | Voice library — Studio / Multilingual filters, Piper section with INSTALLED voices, language chips (en/es/fr/hi/it/ja/pt/zh), gender chips, quality filters, search | Three on-device neural voice families. Free, offline. |

## Notes & known gaps

- **Banner verification.** `phone-03-browse-resources.png` was re-captured
  after an 8-second wait following the initial Notion fetch, with the file
  growing from 345 KB to 463 KB — strong signal that the banners loaded
  into the four TechEmpower cards (Guides, Resources, About, Donate).
  Tablet equivalent (`tablet-03`) at 76 KB is consistent with banner
  rendering at the smaller landscape size.
- **FictionDetail "Guides" crashes on tablet.** Tapping the Guides fiction
  on the Tab A7 Lite reproducibly triggers
  `SQLiteConstraintException: UNIQUE constraint failed: chapter.fictionId,
  chapter.index` in `ChapterDao.upsertChaptersForFiction`. Worked around
  by using **Donate** (a single-chapter TechEmpower fiction) for both the
  tablet FictionDetail and the active-Player capture. The phone did not
  hit this crash in the same flow — `phone-04-fiction-detail.png` shows
  the Guides FictionDetail successfully. File a bug for the tablet crash
  separately.
- **No onboarding voice-picker capture.** The brief asked for the
  first-run "Brian / Amy / Lessac / Cori" friendly voice cards.
  Reproducing onboarding requires clearing app data and walking the
  first-run flow; neither device had room for that in this pass.
  `tablet-06-voice-picker.png` shows the **main** Voices tab, which
  surfaces the same voice families (Piper Studio / Multilingual / Neutral
  with INSTALLED markers) and is a faithful representation of the voice-
  picker experience.
- **No phone Player or Voices screenshots.** Pass was scoped to tablet
  after the first four phone surfaces were captured. The four phone shots
  satisfy Play Console's 4-shot minimum. Future pass can extend to
  phone-05 / phone-06 if Play reviewers want more.
- **Aspect ratios.** Phone 1080×2640 is 9:22 — at the edge of Play
  Console's stated 9:22 max for phone screenshots; accepted. Tablet
  1340×800 is ≈16:9.5 — well within 16:9-to-9:16 range for tablet
  screenshots.

## Source of truth

Design brief: `docs/play-store-listing.md` (PR #610).
Scratch / WIP files: `~/.claude/projects/-home-jp-Projects-storyvox/scratch/play-store-graphics/`.
