# Candela roadmap

A short-form list of shipped + in-flight + planned work. Detailed designs live under
`docs/superpowers/specs/`. Touching Compose? See
[`docs/compose-gotchas.md`](compose-gotchas.md) for known scope-shadowing
and stability traps before searching the internet for confusing errors.

Last refreshed for **v0.7.3** (AGP 9 migration + deprecation cleanup). Prior major refresh was v0.5.51.

---

## Shipped in the v0.5 line

The full v0.5 line landed the bulk of the user-visible surface — twenty-one fiction
backends, three voice families, the AI chat heavies, performance work that hit the
0.8 s cold-launch target, the a11y pass, and the TechEmpower repositioning.

### Voice + audio
- [x] **KittenTTS** lightest-tier voice family (v0.5.36, [#119](https://github.com/techempower-org/candela/issues/119)) — ~24 MB shared across 8 en_US speakers, "first chapter in 10 seconds" on slow devices
- [x] **Per-voice lexicon override** ([#197](https://github.com/techempower-org/candela/issues/197)) — IPA pronunciation dictionaries per voice
- [x] **Kokoro phonemizer-lang override** — per-voice language pinning for proper-noun fixes
- [x] **Full PCM cache series** (v0.5.47–v0.5.49) — streaming-tee, cache-hit playback, background pre-render, Settings UI for cache size + eviction, status icons on chapter rows
- [x] **Magical brass voice-settings icon** on the play screen replacing the buried `⋮` overflow

### Performance
- [x] **Cold launch 6.7 s → 0.8 s** on Galaxy Tab A7 Lite (v0.5.46) — R8 minification + Baseline Profile ([#409](https://github.com/techempower-org/candela/issues/409)) + `isDebuggable=false` in release builds
- [x] **`:baselineprofile` producer module** — UI Automator walks the hot path on `:app` and emits `baseline-prof.txt` for the AndroidX plugin to wire back

### Accessibility (v0.5.43)
- [x] **Twelve a11y audit findings closed** — high-contrast brass-on-near-black theme, `prefers-reduced-motion` collapses fold-in animations, TalkBack pacing tuned to chapter-list patterns
- [x] **Accessibility settings subscreen** — high-contrast toggle, reduced-motion toggle, dyslexia-friendly font opt-in

### Navigation (v0.5.40, settling v0.5.50)
- [x] **Nav restructure** — Settings becomes a primary destination; Browse and Follows tuck under Library
- [x] **Final four-tab dock** — `{Playing · Library · Voices · Settings}` (v0.5.50 revision)
- [x] **InstantDB cross-device sync** ([#360](https://github.com/techempower-org/candela/issues/360), v0.5.12) — library / follows / positions / bookmarks / pronunciation / encrypted secrets
- [x] **Magical sign-in surface** — InstantDB auth with brass-edged onboarding

### AI chat heavies
- [x] **Cross-fiction memory** ([#217](https://github.com/techempower-org/candela/issues/217)) — character/place/concept entities surface in per-book Notebook tab with manual edit
- [x] **Function calling** ([#216](https://github.com/techempower-org/candela/issues/216)) — "Add this to my Reading shelf", "Queue chapter 5", "Open Voice Library" route through `ToolCatalog`; brass-edged tool cards show in-flight state
- [x] **Multi-modal image input** ([#215](https://github.com/techempower-org/candela/issues/215)) — paste cover art/scene refs into chat (Anthropic + OpenAI native, auto-downscale to 1280 px / JPEG q=85)

### TechEmpower-as-default (v0.5.51, six-parallel-agent bundle)
- [x] **Library brass TechEmpower hero card** at the top of the Library tab ([#511](https://github.com/techempower-org/candela/pull/511))
- [x] **TechEmpower Home** — dedicated screen with Discord peer support + dial 211 + Browse library + About cards
- [x] **README repositioning** — leads with TechEmpower's mission, audiobook engine framed as "under the hood"
- [x] **Beautiful Notion covers** ([#514](https://github.com/techempower-org/candela/pull/514)) — body-image fallback + brass-edged synthetic `BrandedCoverTile` for cover-less pages
- [x] **Home-screen widget** — Continue Listening + Play/Pause on Android home/lock surfaces
- [x] **AO3 auth PR1** ([#426](https://github.com/techempower-org/candela/issues/426)) — auth surface scaffolding; PR2 wires the WebView login flow

### Four new fiction backends (v0.5.51)
- [x] **`:source-telegram`** ([#462](https://github.com/techempower-org/candela/issues/462)) — public Telegram channels via Bot API
- [x] **`:source-palace`** ([#502](https://github.com/techempower-org/candela/issues/502)) — Palace Project free-library catalog walker (OPDS); non-DRM titles only, LCP DRM deferred
- [x] **`:source-slack`** ([#454](https://github.com/techempower-org/candela/issues/454)) — Slack channels as fictions via Web API
- [x] **`:source-matrix`** ([#457](https://github.com/techempower-org/candela/issues/457)) — federated Matrix rooms as fictions

### Earlier v0.5 (fiction backends — running totals)
- [x] **17 → 21 fiction backends** — Telegram + Palace + Slack + Matrix (v0.5.51) on top of Hacker News, arXiv, PLOS, Discord, Wikisource, Radio Browser (v0.5.38)
- [x] **`@SourcePlugin` annotation + KSP processor** (v0.5.27) — adding a backend is ~4 touchpoints; Plugin manager auto-discovers
- [x] **Plugin manager Settings hub** — brass-edged card grid iterating the registry

---

## In flight

- [ ] **AO3 PR2 — login wiring** ([#426](https://github.com/techempower-org/candela/issues/426)) — wire the WebView login on top of PR1's auth surface. Unlocks bookmarks/marked-for-later sync and adult-content gating.
- [ ] **Discord channel-as-fiction wiring follow-up** ([#502](https://github.com/techempower-org/candela/issues/502) follow-up) — improvements after the initial Palace Project pass.
- [ ] **Settings hub follow-through** — the v0.5.38 hub has 13 cards; 5 route to subscreens, 7 still fall back to the legacy long-scroll. Each one wants its own subscreen.

## Toward v1.0

- [ ] **v1.0 release keystore** ([#16](https://github.com/techempower-org/candela/issues/16)) — generate a proper release keystore + signing config; ship a stable signed APK from a CI secret. Currently shipping with the checked-in debug keystore (stable since v0.4.15 but not the production posture).
- [ ] **VoxSherpa knob exposure** ([research draft](superpowers/specs/2026-05-08-voxsherpa-knobs-research.md)) — loudness normalization, breath pause, pitch envelope as user-tunable settings.
- [ ] **Auto integration** — Media3 `MediaSessionService` + `MediaBrowserService` exposes the library to Android Auto.
- [ ] **Wear OS pairing polish** — `:wear` builds; pairing-and-discovery via `play-services-wearable` works in principle but isn't a polished experience yet.

## Backlog

- [ ] **`AudioTrack.Builder` + `AudioAttributes`** — swap the legacy six-arg `AudioTrack(STREAM_MUSIC, …)` ctor for the modern Builder form with `USAGE_MEDIA` / `CONTENT_TYPE_SPEECH`. Defer until we're sure the v0.4.x fuzz fix sticks across all chips.
- [ ] **Voice-tagging in `candela.json`** — let a fiction author specify a preferred narrator voice (`narrator: "en-US-Andrew"`). Per-fiction default; user can still override.
- [ ] **Sleep timer end-of-chapter mode** — fade-out tail polish.
- [ ] **Knowledge graph for fiction** ([#147](https://github.com/techempower-org/candela/issues/147)) — per-book Notebook seeding into MemPalace. Cross-fiction memory ([#217](https://github.com/techempower-org/candela/issues/217)) is the read path; the seed-into-MemPalace write path is the remaining piece.

## Docs / site

- [x] **Comprehensive site + docs sweep for v0.5.51** — landing page, install, voices, architecture, ROADMAP, screenshots, README, wiki, version.json (this PR).
