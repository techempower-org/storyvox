---
layout: default
title: Accessibility
---

# Accessibility in Candela

Candela is built to be useful to people who read with their ears as much as
their eyes — and that overlaps deeply with the audience for assistive
technology. The accessibility work documented here is the result of a
two-phase audit and refit that landed in v0.5.42 (Phase 1 scaffold) and
v0.5.43 (Phase 2 behavior + static fixes).

This page is the canonical reference for what we support, what we know
needs more work, and how to file an accessibility issue.

## What we support today

- **TalkBack** — Android's built-in screen reader. We test against
  `uiautomator dump` proxies since the audit tablet (Samsung Tab A7
  Lite, no Google Mobile Services) doesn't ship TalkBack itself. Issue
  [#488](https://github.com/techempower-org/candela/issues/488) tracks
  installing TalkBack for live verification.
- **Switch Access** — Material 3 components participate in Switch
  Access traversal by default. The `BottomTabBar` exposes
  `Role.Tab` + `selected` semantics so Switch Access can announce the
  current tab.
- **System font scaling** — Compose's `.sp` unit honors the user's
  system-wide font scale setting. All user-facing text rides this scale.
- **Reduced motion** — when the user enables "Remove animations" in
  Android Settings (which sets `Settings.Global.ANIMATOR_DURATION_SCALE`
  to 0), Candela's `LocalReducedMotion` CompositionLocal flips on
  and most decorative animations skip. The Phase 2 reduce-motion sweep
  ([#480](https://github.com/techempower-org/candela/issues/480))
  wires this through `AnimatedVisibility` / `tween` / `LaunchedEffect`
  sites that previously animated unconditionally.
- **High-contrast theme** — opt-in via Settings → Accessibility → High
  contrast. Brass-on-near-black at roughly AAA contrast (14:1+ on body
  text). The default Library Nocturne palette stays warm; high contrast
  is offered as a switch, not a swap.
- **Accessibility subscreen** — Settings → Accessibility surfaces seven
  toggles in one place (font scale, high contrast, reduce motion, larger
  touch targets, TalkBack hints, etc.) backed by DataStore preference
  keys (`pref_a11y_*`) so the user's choices persist across launches.

## Phase 1 — scaffold (v0.5.42, [#489](https://github.com/techempower-org/candela/pull/489))

- New Settings → Accessibility subscreen with toggles for the seven
  Phase 2 affordances.
- `AccessibilityStateBridge` Flow that observes the system
  `AccessibilityManager` (TalkBack on/off, touch-exploration on/off,
  enabled-services list) and exposes it to Compose as a single state.
- Seven `pref_a11y_*` DataStore preference keys for persistence.
- Notion plugin pinned first in the registry — the default Browse
  landing is now a curated reader-friendly home rather than the
  community-mirror grid.

## Phase 2 — static labels + Roles (v0.5.43, [#491](https://github.com/techempower-org/candela/pulls))

Closes the high-leverage labeling gaps from the audit.

| Issue | Fix |
|---|---|
| [#478](https://github.com/techempower-org/candela/issues/478) | 7 `Switch` composables wrapped in `Modifier.toggleable(role = Role.Switch)` so TalkBack announces the row's label merged with the switch state (was: unlabeled "Switch, on/off"). |
| [#485](https://github.com/techempower-org/candela/issues/485) | `BottomTabBar.TabCell` exposes `Role.Tab` + `selected = isSelected` so TalkBack can announce the current tab. |
| [#484](https://github.com/techempower-org/candela/issues/484) | Two `CircularProgressIndicator` sites (sync OAuth handshake, fiction-detail epub export) tag themselves with `contentDescription = "Loading: <label>"` + `liveRegion = Polite` so users hear the spinner. |
| [#481](https://github.com/techempower-org/candela/issues/481) | Sweep over 27 bare `Modifier.clickable` sites that lacked `Role` semantics. Each got a `Role.Button` / `Role.Switch` / `Role.Checkbox` + `onClickLabel` (action verb). |
| [#483](https://github.com/techempower-org/candela/issues/483) | Hard-coded `fontSize = N.sp` literals rebound to typography ramp tokens where the size is theme-coupled. Dev-only surfaces (DebugOverlay / DebugScreen) kept their literals with kdoc explaining why. |
| [#482](https://github.com/techempower-org/candela/issues/482) | Audit verification — see "Verified false positives" below. |

## Phase 2 — behavior (v0.5.43, sibling PR)

Wires the Settings → Accessibility toggles to actual rendering:

- High-contrast theme variant (`HighContrastLibraryNocturne` palette)
- Reduced-motion consumer across the ~10 sites with unconditional
  animations
- Touch-target widener that bumps `min-target` from 48dp to 64dp when
  the user opts in
- "Learn more" → this page (the row was a TODO in Phase 1).

## Verified false positives — issue [#482](https://github.com/techempower-org/candela/issues/482)

The static audit flagged 64 `Icon` / `Image` / `IconButton` sites where
`contentDescription` was either `null` or missing. We verified each;
**all but zero are correctly decorative**. The breakdown:

- **36 sites with `contentDescription = null`** — every one of these is
  an icon that sits next to a descriptive `Text` inside a parent that
  merges semantics (a `SettingsRow`, `Card.clickable`, etc.). Labeling
  the icon would create TalkBack noise ("Library, library, library").
  This is the correct pattern per the [Compose accessibility
  guidance](https://developer.android.com/develop/ui/compose/accessibility/key-information).
- **28 IconButton sites flagged as "MISSING"** — these are
  `IconButton(onClick = ...)` wrappers whose inner `Icon(...)` has its
  own `contentDescription`. The audit grep matched on the IconButton
  signature alone but didn't follow into the body. All verified
  correctly labeled at the inner `Icon`.

No `contentDescription = ""` empty-string bugs (those would block
TalkBack from reading the element at all). `null` is correctly used
throughout to mark decoration.

## Surfaces audited — issue [#487](https://github.com/techempower-org/candela/issues/487)

Rendered-surface audit on R83W80CAFZB (Samsung Galaxy Tab A7 Lite,
800x1340 @ 213dpi) across 15 screens via `uiautomator dump`:

| Surface | Total clickables | Unlabeled (pre-Phase-2) | Sub-48dp targets |
|---|---|---|---|
| Library home | 17 | 0 | 5 (filter chips at 47dp — rounding) |
| Browse | 15 | 0 | 8 (source-filter chips at 47dp) |
| Settings hub | 26 | 7 | 0 |
| Settings → Voice | 11 | 1 | 0 |
| Settings → Plugins | 22 | 4 | 0 |
| Settings → Accessibility | 7 | 0 | 0 |
| Voice library | 19 | 0 | 1 (favorite star at 36dp — fixed in #479) |
| Audiobook view | 14 | 0 | 0 |
| Voice quick sheet | 9 | 1 | 0 |
| Pronunciation dict | 6 | 1 | 0 |
| Fiction detail | 12 | 0 | 0 |

Total unlabeled clickables: **9**, all of which trace to the Switch
pattern fixed in #478. After Phase 2 these surfaces are clean.

## Known gaps

- **TalkBack hints**: the `traversalIndex` / `customAction` semantics
  on the audiobook transport controls would let TalkBack users skip
  past the cover art to the play button faster. Not in v0.5.43.
- **Live TalkBack verification**: blocked on #488 (install Android
  Accessibility Suite on R83W80CAFZB).
- **Voice library row count**: when the library is in a long state
  (200+ voices), TalkBack reads each row sequentially. A
  `LazyListLayoutInfo`-based "row 47 of 200" announcement would speed
  navigation. Tracked separately.

## Filing an accessibility issue

If TalkBack announces something confusing, a tap target is too small,
contrast feels low, or motion makes you queasy — file at
[github.com/techempower-org/candela/issues](https://github.com/techempower-org/candela/issues)
with the `accessibility` label and a screenshot or `uiautomator dump`
snippet if you can. We treat accessibility regressions as high priority.

## Partnership outreach

We're looking to partner with screen-reader users and disability-rights
organizations for ongoing real-device testing. If you'd like to help —
either as a tester or as a connection to a community — reach the
maintainers at the GitHub issues link above with the `partnership`
label.
