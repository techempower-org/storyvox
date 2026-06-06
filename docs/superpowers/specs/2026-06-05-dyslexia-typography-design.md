# #992 — OpenDyslexic font + dyslexia-friendly reading typography

**Issue:** techempower-org/candela#992 (`area:reader`, `accessibility`, `priority:high`)
**Branch:** `feat/992-dyslexia-typography`
**Author:** Morpheus

## Goal

Give the reading surface (chapter text) its own typography controls — font
family (incl. OpenDyslexic), font size, line height, letter spacing, paragraph
margin — persisted across sessions and applied **only to the reading text**, not
to app chrome. Today the reader hardcodes `MaterialTheme.typography.bodyLarge`
and the only font knob (`pref_a11y_font_scale_override`, #493) scales *all*
chrome.

## Non-goals (YAGNI)

- No color overlays / reading themes (that's #993, separate).
- No per-word highlight (that's #994, separate).
- No change to app-chrome typography or the brand `Typography.kt`.
- No font *download* flow — fonts are bundled offline (accessibility tool used
  in low-connectivity settings; consistent with privacy posture — no Play
  Services font fetch for the reading surface).
- Only Regular + Bold weights bundled per family (italics not needed for the
  reader body; keeps APK growth small per the issue).

## Architecture

The pref data lives in `:app` (DataStore / `SettingsRepositoryUiImpl`); the
renderer lives in `:core-ui` (`SentenceHighlight`). To avoid inverting the
module dependency (core-ui must not depend on app), the seam is a
**CompositionLocal**, exactly mirroring the existing `LocalMotion` /
`LocalSpacing` / `LocalReducedMotion` pattern already in
`core-ui/.../theme/`.

```
SettingsRepositoryUiImpl (DataStore keys)         [:app]
        │  5 prefs: family, sizeSp, lineHeightMult, letterSpacingEm, paragraphSpacingMult
        ▼
UiSettings model (UiContracts.kt)                 [:feature/api]
        │  exposed as fields + a derived ReaderTypography
        ▼
ReaderView: CompositionLocalProvider(             [:feature/reader]
    LocalReaderTypography provides typo)
        ▼
SentenceHighlight reads LocalReaderTypography     [:core-ui]
    .current → builds TextStyle (replaces hardcoded bodyLarge)
```

### New types (`:core-ui`, `theme/ReaderTypography.kt`)

```kotlin
enum class ReaderFontFamily { Default, OpenDyslexic, AtkinsonHyperlegible }

/** Plain, Android-DataStore-free description of how reader text renders.
 *  Consumed via LocalReaderTypography; provided by the reader subtree. */
data class ReaderTypography(
    val family: ReaderFontFamily = ReaderFontFamily.Default,
    val fontSizeSp: Float = 18f,          // issue: ~10..80; we clamp 12..48 for the body
    val lineHeightMultiplier: Float = 1.55f, // 28/18 today
    val letterSpacingEm: Float = 0.0125f,  // ~0.2sp at 18sp today, expressed relative
    val paragraphSpacingMultiplier: Float = 1f, // scales inter-paragraph margin
) {
    fun toTextStyle(base: TextStyle): TextStyle  // resolves family→FontFamily, sizes
}

val LocalReaderTypography = staticCompositionLocalOf { ReaderTypography() }
```

`toTextStyle` maps `family` to the bundled `FontFamily` (Default = existing
`BookBodyFamily`/EB Garamond; OpenDyslexic / Atkinson = bundled `res/font`
families), and applies size/line-height/letter-spacing. The default values
reproduce *today's* `ReaderBodyStyle` (18sp / 28sp / 0.2sp) so an untouched
install renders pixel-identical.

### Font assets (`:core-ui`, `res/font/`)

- `opendyslexic_regular.otf`, `opendyslexic_bold.otf` (SIL-OFL).
- `atkinson_hyperlegible_regular.ttf`, `atkinson_hyperlegible_bold.ttf` (SIL-OFL).
- XML font families `font/open_dyslexic.xml`, `font/atkinson_hyperlegible.xml`.
- **`OFL.txt` committed alongside** — SIL-OFL requires the license to travel
  with redistributed binaries. Fonts fetched from upstream
  (`antijingoist/opendyslexic`; Google Fonts Atkinson) to guarantee provenance
  + license file + current version. Add a NOTICE/attribution line to the repo's
  third-party-licenses doc if one exists.

### Settings (`:app` + `:feature`)

- 5 DataStore keys in `SettingsRepositoryUiImpl` (string for family enum, floats
  for the rest), following the `A11Y_FONT_SCALE_OVERRIDE` precedent — including
  sync registration if the existing reader prefs are synced (theme is). Default
  to "synced" to match `themeOverride`, since per-device reading prefs are the
  kind of thing users want to carry across devices.
- Fields on the UI settings model in `UiContracts.kt`; setters on
  `SettingsViewModel` delegating to `repo.setX(...)` (mirrors `setTheme`).
- New "Reading text" `SettingsGroupCard` in `ReadingSettingsScreen`:
  - `SettingsSegmentedBlock` for font family (Default / OpenDyslexic / Atkinson).
  - Sliders (or stepper rows) for size, line height, letter spacing, paragraph
    spacing — each with a live "The quick brown fox…" preview row rendered in the
    selected reader typography so the user sees the effect without leaving
    settings.
  - A "Reset reading text" row restoring defaults.

### Apply point (`:feature/reader/ReaderView.kt`)

Wrap the reader `Column` (currently lines ~193-224) in
`CompositionLocalProvider(LocalReaderTypography provides typo)` where `typo` is
built from the collected settings state. `SentenceHighlight` changes its single
hardcoded `style = MaterialTheme.typography.bodyLarge` to
`LocalReaderTypography.current.toTextStyle(MaterialTheme.typography.bodyLarge)`.
The paragraph-spacing pref feeds the `Column`/paragraph padding in `ReaderView`.

`SentenceHighlight` is called from exactly **one** site (ReaderView.kt:214), so
the renderer change has a single integration point.

## Coexistence with #998 (Vesper, in-text search)

Both touch the reader surface. My changes are:
- New files: `ReaderTypography.kt`, font assets, a settings subsection — Vesper
  won't touch these.
- Edits to `SentenceHighlight.kt` (swap one style line) and `ReaderView.kt`
  (wrap the Column in a provider). If Vesper also edits `ReaderView`, the
  conflict is confined to a few additive lines there — straightforward union at
  merge. Lead sequences the merge; I rebase on Vesper if hers lands first.

## Error / edge handling

- Pref values clamped on read (size 12..48sp, line-height 1.0..2.5, letter
  spacing -0.05..0.25em, paragraph spacing 0.5..3.0) so a corrupt/synced
  out-of-range value can't break layout.
- Missing/unknown family string → `Default` (defensive enum parse).
- Bundled fonts are static resources — no async load state to handle (unlike the
  GoogleFont downloadable provider used for brand fonts).

## Testing

Pure JVM unit tests (no Robolectric, per project convention):
- `ReaderTypographyTest`: defaults reproduce today's 18/28/0.2 style; clamping of
  each out-of-range field; family→FontFamily resolution returns the right family;
  unknown family string parses to Default.
- Settings round-trip: if a lightweight `SettingsRepositoryUiImpl` test seam
  exists, assert the 5 keys persist + reload; otherwise cover the enum
  serialization helper in isolation.

The camera-style "don't unit-test the Composable" rule applies — we test the
`ReaderTypography` builder/mapping, not the `SentenceHighlight` draw.

## Files touched (estimate)

| File | Change |
| --- | --- |
| `core-ui/.../theme/ReaderTypography.kt` | NEW — data class, enum, CompositionLocal, toTextStyle |
| `core-ui/src/main/res/font/*` | NEW — 4 font files + 2 XML families + OFL.txt |
| `core-ui/.../component/SentenceHighlight.kt` | swap hardcoded bodyLarge → LocalReaderTypography |
| `app/.../SettingsRepositoryUiImpl.kt` | 5 DataStore keys + getters/setters (+ sync reg) |
| `feature/.../api/UiContracts.kt` | 5 fields on UI settings model + derived ReaderTypography |
| `feature/.../settings/SettingsViewModel.kt` | 5 setters |
| `feature/.../settings/ReadingSettingsScreen.kt` | "Reading text" group + preview |
| `feature/.../reader/ReaderView.kt` | provide LocalReaderTypography; paragraph spacing |
| `feature/src/main/res/values/strings.xml` | settings labels |
| tests | `ReaderTypographyTest` + settings round-trip |

## Open questions for the lead

1. Sync the reading-text prefs (like `themeOverride`) or keep per-device? Default
   plan: **sync**, matching theme.
2. Include Atkinson Hyperlegible as a third family, or ship OpenDyslexic only for
   v1? Default plan: **include both** (issue lists Atkinson as a recommended
   high-legibility option; marginal APK cost).
