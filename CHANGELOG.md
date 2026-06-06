# Changelog

All notable changes to Candela (the storyvox engine) land here. Format roughly follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions track
the `versionName` in `app/build.gradle.kts` and the `v*` git tag.

Entries before v0.5.12 are reconstructed from the git log — see
`git log --oneline` for the exhaustive record.

## [Unreleased]

## [1.1.2] -- 2026-06-06

**Right book, every time.** A focused fix for a reader regression, plus the foundation for on-device UI testing.

### Fixed

- **Reader no longer shows the *previously-played* book.** Opening a different fiction (e.g. tapping the TechEmpower **Resources** tile) whose chapter body was still loading from a cold fetch could paint the prior book in the reader — and stay stuck on it if the fetch timed out. The reader now holds a loading state scoped to the fiction you actually opened until its content is ready, with a scoped retry on timeout. Completes the #638 guard for the cold-load window. (#1093)

### Added — testing foundation (internal)

- Stable `testTag`s across the navigable surface + a `testTagsAsResourceId` bridge, so Maestro and uiautomator can address Compose UI by id. (#1090)
- Instrumented Compose UI test infrastructure + an on-device launch smoke test. (#1091)
- A deterministic, debug-only plaintext reader seed for offset-precise reader/highlight tests. (#1089)

### Fixed (internal)

- `:feature` unit tests compile again (test fakes updated for the #996 PDF-folder interface members). (#1092)

## [1.1.1] -- 2026-06-06

**The candle, lit.** A small follow-up to the v1.1.0 wave — a hidden flourish of magic, plus three correctness fixes.

### Added
- **Milestone magic & easter eggs** — a one-time *"Candela 1.1 — read it your way"* candle-**ignite** celebration on the first launch of a v1.1.x build (the flame draws to life, a warm glow blooms, light-motes rise, and the sound-wave arcs pulse outward as rings of light), with a calm static fallback under reduced-motion. Plus a **hidden candle egg** — tap the empty-library sigil seven times and the flame lights, sheds a few motes, and whispers *"Candela — the SI unit of luminous intensity."* And a **book-completion streak** that lights the same rising-mote celebration on your 1st / 5th / 10th / 25th finished book. All three run on one shared `LightMotes` animation, which also upgrades the chapter-complete celebration to the same luminous language.

### Fixed
- **#1082** **Royal Road "new chapters" count** — follow-only sources reported the entire un-downloaded backlog (e.g. "65 new chapters") on every poll instead of the genuinely new ones. New-chapter notifications now report the true delta since the last check, and following a fiction no longer announces its whole back-catalog as "new." (The poll worker counted `NOT_DOWNLOADED` chapters, which for subscribe-mode follows never clears.)
- **#1085** **Restored `:app` unit-test compilation** — a required `pdfConfig` constructor parameter added in the PDF-import work (#996) was never threaded into the 16 `SettingsRepository*Test` fixtures, so the `:app` unit-test source set hadn't compiled (the required CI gate builds the release APK, which skips the test source set, so it slipped through). 202 tests green again.

### Changed
- **Release APK naming** — the published release artifact is now `candela-v*.apk` (was `storyvox-v*.apk`), the rebrand reaching the build outputs (both the local AGP rename and the CI publish step).

## [1.1.0] -- 2026-06-05

**Read it your way — and bring your own.** The first post-launch feature wave turns Candela from "play what we fetch" into "read and listen to anything, however you need to." New ways to *get* text in — make your own audiobook from pasted text, scan a physical page (OCR), import a PDF, open a shared EPUB/PDF, or stream public-domain human narration (LibriVox). New ways to *read* it — a dyslexia-friendly typeface, custom color themes and overlays, in-chapter search, focused-reading and per-word read-along modes, paragraph navigation, and persistent highlights & notes you can export. Plus accessibility-first reader controls and a deep round of sync / playback / source correctness underneath. Built on the v1.0.3 Candela identity.

### Added

- **#1003** **Make your own audiobook** — turn your own text into a saved, shareable audiobook, entirely offline. A new "Make your own audiobook" entry on the Library **+** menu takes pasted/typed text → auto-chapterizes it → narrates it with a downloaded neural voice → exports a **chaptered `.m4b`** (chapter markers + title/author/cover metadata) you can play in Candela and share via the Android share sheet / Save-As (SAF). The same export is available from any library book via Fiction detail → **Export as audiobook…**. The render runs in a background WorkManager job with a progress notification. New `:source-audiobook-writer` module holds the (unit-tested) chapterizer, Nero `chpl` chapter atom + iTunes metadata box builders, and the in-place `moov`/`udta` injector; `:core-playback` adds the AAC `MediaMuxer` encoder, the synthesizer, and the export use case + worker.
- **#1015** **LibriVox source** — public-domain, human-narrated audiobooks as a first-class source. Search the LibriVox catalog and stream real human narration alongside the neural-TTS sources, for titles where a recorded reading already exists.
- **#995** **OCR scan-to-read** — point the camera at a physical page (or pick a photo) and Candela runs on-device ML Kit text recognition → assembles the recognized text into a Fiction → narrates it. Camera + gallery capture with a runtime-permission flow; the text extraction/parsing is unit-tested in the new `:source-ocr` module.
- **#1000** **'Open With' file association** — Candela now registers as a handler for EPUB / PDF / TXT, so opening one of those from Files, a browser download, or another app offers Candela directly. The deep-link resolver ingests the incoming `content://` Uri, builds a Fiction, and drops you into the reader.
- **#996** **PDF import + read-aloud** — bring a PDF in (via 'Open With' or the import sheet), extract its text page by page, and have it narrated. The new `:source-pdf` module builds a Fiction from the document so a PDF reads like any other source.

#### Reading experience

- **#997** **Focused Reading mode** — a distraction-reduced, center-locked reading view that quiets the surrounding chrome so the current passage holds the eye. Toggleable from the reader.
- **#1001** **Paragraph-level navigation** — skip back/forward a whole paragraph at a time in the reader, in addition to chapter and sentence granularity.
- **#998** **In-text search** — find-in-chapter with jump-to-match navigation, so you can locate a passage inside a long chapter and jump straight to it.
- **#992** **OpenDyslexic + dyslexia-friendly typography** — opt into the OpenDyslexic typeface plus dyslexia-oriented reading defaults (letter/line spacing, weight) via a new Reading-text settings group, for more comfortable reading.
- **#993** **Color overlays & custom reading themes** — tinted reading overlays and custom reader color themes, with WCAG-contrast-checked presets, for visual comfort and low-vision readability.
- **#994** **Per-word read-along highlight** — a per-word highlight mode that advances word-by-word with the narration (a frame-clocked progress fill), in addition to the existing sentence-level highlight, for tighter read-along tracking.
- **#999** **Highlights & notes with export** — select text to highlight (with a color) and attach an optional note; review your highlights grouped by chapter on the book's detail screen, and export them all as **Markdown or plain text** via the share sheet / Save-As. Highlights persist locally and sync across devices. (Phase 1 — in-reader select-drag capture lands in a fast-follow, #999 phase 2 / #1079.)

### Changed / Improved

- **#974** **Tighter A/V sync** — sentence-highlight position now extrapolates from `AudioTrack.getTimestamp()` rather than polling alone, closing the residual lag between the spoken word and the highlighted word during continuous playback.
- **#1031** **Wear scrubber actually scrubs** — the Wear OS circular scrubber gained a `CMD_SEEK` path, so dragging it now seeks the phone's playback instead of being inert.

### Accessibility

- **#1025** The Audiobook ↔ Reader pane switch is now exposed as a first-class TalkBack action, so screen-reader users can move between listening and reading views without hunting for the toggle.
- **#1026** First-launch onboarding / voice-pick overlays are now isolated from TalkBack background traversal, so the screen reader can't wander into the obscured content behind a modal during setup.

### Fixed

#### Sync & data integrity
- **#1024** Settings field-stamp / snapshot-baseline read-modify-write is now guarded by a shared mutex (and folded into a single atomic store edit), closing a cross-path race where a local UI write and an incoming sync pull could interleave and re-push or drop a just-adopted preference.
- **#1027** A wrong sync passphrase no longer clobbers the remote secrets blob — entering the wrong code on a second device can't overwrite the encrypted secrets it failed to decrypt.
- **#1029** The bookmarks syncer no longer deletes local-only bookmarks — a bookmark made offline on one device survives a sync pull instead of being treated as a remote deletion.

#### Playback
- **#1034** A per-key write lease replaces the raw PCM-cache appender, stopping a corruption race when two synthesis paths target the same cache key (single-owner, foreground-wins).

#### Wear
- **#1030** Wear transport now surfaces a phone-disconnected state instead of silently swallowing the tap, so a control that can't reach the phone says so rather than appearing broken.

#### Sources
- **#1036 / #1056** The Notion source recurses into nested blocks (toggles, columns, sub-lists) on both the authenticated and anonymous read paths, so nested content narrates instead of being dropped.
- **#1035 / #1021** The EPUB parser normalizes OPF hrefs — percent-decoding, resolving `../`, and stripping a leading slash — so spine items with awkward relative paths resolve to the right chapter file.
- **#1060** Wikisource subpages are natural-sorted, so a multi-chapter work plays in human order (Chapter 2 before Chapter 10) instead of lexical order.
- **#1058** PLOS free-text Search filters to `doc_type:full`, eliminating ~6 duplicate cards per article.
- **#1061** Slack / Telegram / Matrix request paths are pinned to `Dispatchers.IO`, fixing a `NetworkOnMainThreadException` crash on those sources.
- **#1064** The Discord source treats a message author as nullable with a fallback, so one author-less message can no longer brick the whole channel.

#### Library & chat
- **#1023** A blank source title no longer freezes a synced placeholder on an eternal "Loading…" — items that can't resolve a title show a real state instead of spinning forever.
- **#1057** A stale, cancelled chat stream no longer blanks the replacement bubble when its late `onCompletion` fires.

### Tests / Infra

- **#1032** New JVM unit tests for the Wear `WearPlaybackBridge` — state-decode + version-skew resilience.
- **#1020** New unit coverage for the `ReadabilityExtractor`.
- **Candela domain & identity follow-through** — `candela.techempower.org` (DNS + GitHub Pages), the rebranded org profile / marketing site / wiki, the in-code repo-URL sweep (#46), the `candela-*` registry/feeds rename (#47), and the final `storyvox.techempower.org → candela` URL sweep (#77, incl. the Play privacy URL).
- **Wiki auto-sync** — a CI workflow now mirrors canonical `docs/` into the GitHub wiki (allowlist + anti-clobber markers).
- **VoxSherpa-TTS upstream-watch** — a workflow watches the JitPack TTS dependency Dependabot can't track.
- Removed `source-royalroad/_unintegrated/parser` dead code.

## [1.0.3] -- 2026-05-29

**Rebrand to Candela.** The app is now **Candela** — Latin for *candle*, and the SI unit of luminous intensity. It's a clean public identity for the Google Play debut, away from the crowded "storyvox" name (already live on Play as a different app, and owned by two commercial text-to-audiobook products in our exact category). *Storyvox* lives on as the engine underneath — the package namespace, deep-link scheme, and on-device data layout are all unchanged, so this is purely an identity change with no data migration.

### Changed
- **Brand** — the launcher label, onboarding, Settings hub/about, the now-playing widget, and reader/RSS copy now read **Candela**. Internal identifiers (namespace `in.jphe.storyvox`, `storyvox://` deep links, `storyvox.secrets` / `storyvox_settings` stores, HTTP user-agents) are deliberately preserved to keep existing data and external contracts intact.
- **Launcher icon** — the apex glyph is now a lit candle flame (warm amber → white-hot core with a soft glow) in place of the ambiguous pale orb, keeping the open book and ascending sound-wave arcs. The flame is the only fire-warm element on the canvas, carrying the *Candela / candlelight* read at any density.
- **`applicationId`** → `org.techempower.candela`. This is a new Play package; it installs **alongside** any prior `in.jphe.storyvox` build rather than upgrading it in place.

## [1.0.2] -- 2026-05-29

**Deeper sync correctness.** Following v1.0.1's round-trip fixes, this closes the remaining cross-device sync gaps. This is the first build promoted to the Google Play Internal Test track.

### Fixed
- **#988** Library/Follows placeholders now resolve the correct `sourceId` at creation (via `FictionSourceIdResolver`) — colon-less RoyalRoad ids were mis-tagged and could never hydrate
- **#989** Library sync now carries rebuild-essential fields (the source URL, persisted in a new `fiction.sourceUrl` column + `RememberedUrlStore`), so hash-id fictions (Readability/RSS/EPUB) reconstruct on any device instead of failing the metadata back-fill

### Changed
- **#978** Settings sync moved from whole-blob last-write-wins to **per-key field-level merge** — concurrent edits to *different* settings across devices no longer clobber each other. The settings payload gains per-key `updatedAt` stamps (diff-based, stamped at the existing write chokepoint) with a stringified `_field_stamps` sidecar that keeps the payload readable by pre-1.0.2 clients (v1↔v2 back-compat preserved; mixed-version fleets degrade safely to blob-level LWW)

## [1.0.1] -- 2026-05-29

**Sync & persistence fixes.** Cloud sync worked locally but didn't round-trip — preferences never pushed on change, secrets never pulled to a second device, and a synced library arrived as a wall of "Loading…" placeholders. This release closes that cluster.

### Fixed
- **#977** Preferences now push to the cloud on every change (debounced) — source order, favorites, and enabled/disabled state propagate immediately instead of only on cold-start/manual sync
- **#979** Secrets blob now stamps a real `updatedAt`, so encrypted secrets pull *down* to a second device (previously `updatedAt=0` meant they only ever pushed up)
- **#980** Periodic playback-position checkpoint during continuous listening — progress survives an OS-kill mid-chapter instead of rolling back to the last pause; teardown saves made cancellation-safe
- **#981** Synced library "Loading…" placeholders are now hydrated by a background metadata worker (with source-id repair for colon-less ids); items that genuinely can't be rebuilt show a clear state instead of spinning forever
- **#982** "Mark all caught up" (Follows) now actually persists read-status instead of firing a fake success event

## [1.0.0] -- 2026-05-29

**1.0 — the launch.** First public release on Google Play. Caps a pre-launch hardening sweep: a backup-restore crash blocker, playback-engine concurrency fixes, sync-layer robustness, signing-continuity correction, accessibility, and a per-chapter playback-position migration — all on top of the v0.7.x feature set.

### Added
- **#965** Per-chapter playback position — `PlaybackPosition` primary key migrated to `(fictionId, chapterId)` so each chapter remembers its own offset. v10→v11 Room table-rebuild migration (data preservation covered by a dedicated migration test + validated on a real device upgrade), DAO multi-row queries, sync wire-format bump with legacy back-compat, and Library continue-listening join updates.

### Fixed
- **#931** Startup race — `runIfUpgraded` cache-clear could race `prerenderModeWatcher`; the upgrade handler now `join()`s before the watcher starts
- **#964** `NotionUnofficialApi.pageCache` check-then-act TOCTOU — per-id `Mutex` (same pattern as #942)
- **#970** Position/highlight A/V lag — tightened the position poll cadence 100ms→50ms (deeper `getTimestamp` extrapolation tracked as #974)

### Changed
- **#943** Extracted hardcoded user-facing strings (AddByUrlSheet, ManageShelvesSheet, SyncStatusComposables) to string resources

## [0.7.4] -- 2026-05-28

**Magical reader polish + 7-bug sweep.** Two user-facing reader features (in-cover loading prompts overlay, magical auto-scroll toggle) plus seven audit-finding fixes across playback concurrency, sync layer, and library/browse polish.

### Added
- **#945** Loading prompts (`WhyAreWeWaitingPanel`, `PlaybackErrorBanner`, slow-hint) now render INSIDE the book cover behind a bottom-anchored gradient scrim, clipped to the cover's rounded silhouette. The cover stops jumping 60-120 dp every time a diagnostic appears.
- **#946** Magical auto-scroll on/off toggle in reader mode — brass `SmallFloatingActionButton` (`Icons.Outlined.AutoStories`) with `AutoAwesome` sparkle cross-fade when armed. Persists via `SettingsRepositoryUi`.

### Fixed
- **#927** `currentPunctuationPauseMultiplier` thread-visibility race in `EnginePlayer` -- writer on Main, readers on IO; added `@Volatile`
- **#928** `ChunkGapLogger` `prevChunkEndMs` / `currentChunkStartMs` not `@Volatile` -- same writer/reader-on-different-thread shape
- **#929** `inFlightAdvanceDirection` race on concurrent `advanceChapter` -- added sequence-number token guard (`AtomicLong` + `@Volatile Long`) so a later invocation's `finally` can't clear an earlier (still-in-flight) call's latch
- **#936** `HttpInstantBackend.JsonPrimitive.long` crashed sync round on non-numeric `updatedAt` (proxy error pages, schema drift) -- switched to `longOrNull` with explanatory comment + regression test
- **#937** `SyncIds.toByteArray()` not guaranteed UTF-8 on all platforms -- explicit `Charsets.UTF_8`
- **#938** Browse `customOrder` with duplicate IDs produced duplicate source descriptors -- `.distinct()` before lookup
- **#939** Library `pendingRemoval` confirm-dialog state lost on configuration change -- `remember` -> `rememberSaveable`
- **#940** `ManageShelvesSheet` row had `.clickable {}` before `.padding()`, excluding padded area from 48dp tap target -- modifier order reversed

### Changed
- Renamed `CloudflareAwareFetcher` -> `RoyalRoadChallengeFetcher` (file + class). Signal-reduction refactor; detection-string body and internal sealed-interface variants kept (they're real Cloudflare protocol markers and must stay accurate)

## [0.7.3] -- 2026-05-27

**Deprecation warning cleanup.** Resolves all Kotlin 2.3 + Compose Material deprecation warnings introduced by the v0.7.2 dependency migration.

### Fixed
- **#925** `hiltViewModel` package deprecation across all screens -- migrated from `androidx.hilt.navigation.compose` to `dagger.hilt.android.lifecycle`
- **#926** Compose Material icon and Divider deprecations -- `AutoMirrored` icons, `HorizontalDivider` replacements
- **#924** KT-73255 annotation default target warnings -- explicit `@get:` / `@field:` targets on Room + HiltWorker annotations

## [0.7.2] -- 2026-05-27

**Major dependency migration.** AGP 8.13 to 9.2, Gradle 8.13 to 9.4.1, Kotlin 2.2 to 2.3, plus 9 other dependency group bumps. `kotlinOptions` DSL migrated to `kotlin { compilerOptions {} }` across all 33 Android modules.

### Changed
- AGP 8.13.2 to 9.2.0
- Gradle 8.13 to 9.4.1
- Kotlin 2.2.21 to 2.3.21 (+ KSP 2.3.9)
- Hilt 2.57 to 2.59.2
- Room 2.7 to 2.8.1
- Media3 1.10.0 to 1.10.1
- Compose BOM 2026.04.01 to 2026.05.01
- AndroidX Wear + Test + DocumentFile bumped to latest
- `kotlinOptions { jvmTarget = "17" }` migrated to `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` across 33 modules

## [0.7.1] -- 2026-05-26

**Wave 9 features.** Wikisource filters, chapter-arrival notifications, preference sync expansion, plus 9 dependency bumps reverted in this release (re-applied in v0.7.2).

### Added
- **#797** Wikisource Author + Genre + Quality browse filters
- **#907** Chapter-arrival notifications for followed fictions
- **#916** Expanded preference sync surface to cover all user state

### Changed
- Reverted all Dependabot bumps (#848-856) that broke AGP 8.13 (Kotlin 2.3 / Hilt 2.59 / Room 2.8 require AGP 9)

## [0.7.0] -- 2026-05-26

**Wave 8 features.** Connectivity, expanded tablet layouts, Wikipedia browse tabs, settings search, full i18n, tag exclude UX.

### Added
- **#786** `ConnectivityObserver` + `OfflineBanner` -- reactive network state across all surfaces
- **#783** Expanded breakpoint layouts for tablet landscape
- **#796** Wikipedia "On This Day" + "In the News" browse tabs
- **#802** Search affordance on legacy SettingsScreen
- **#772** Strikethrough excluded tag chips in DynamicFilterSheet

### Changed
- **#799** i18n all 13 settings files -- all user-facing strings extracted to resources

## [0.6.6] -- 2026-05-26

### Fixed
- **#922** Critical playback self-cancellation bug -- `stopPlaybackPipeline()` cancelled `pipelineSetupJob` which was itself the running coroutine in `startPlaybackPipeline()`, aborting the pipeline rebuild mid-flight

## [0.6.5] -- 2026-05-26

**Wave 7 features.** Reader FAB, voices polish, error UI, thermal/power-save awareness, library search.

## [0.6.4] -- 2026-05-26

**Wave 6.** Nav, performance, and locale fixes.

## [0.6.3] -- 2026-05-26

### Fixed
- **#914** Serialized pipeline setup to prevent double AudioTrack creation

## [0.6.2] -- 2026-05-26

**Wave 4.** Medium + low priority fix sweep.

## [0.6.1] -- 2026-05-26

**Wave 3.** Medium-priority fix sweep.

## [0.6.0] -- 2026-05-26

**Wave 2 fix sweep.** 8 fixes: #866 (suspend pipeline), #886 (awaitTermination 15s), #869 (OkHttp sync patience), #883 (pause-resume chunk), #903 (WAV file leak), #885 (bookmark FK guard), #896 (mmap force-unmap), #868 (pitch handoff).

## [0.5.99] -- 2026-05-26

**Overnight high-priority fix wave.** Secondary engine leak + pause-mult race + sentenceIndex visibility fix.

## [0.5.98] -- 2026-05-26

**Full dependency upgrade.** AGP 8.7.2 to 8.13.2, Kotlin 2.0.21 to 2.2.21, Media3 1.5.0 to 1.10.0, Coil 2.7.0 to 3.4.0, compileSdk 35 to 36, plus AndroidX suite bumps. Dependabot configured for ongoing dependency management.

## [0.5.97] -- 2026-05-26

**Chapter search + library sort + sync retry + chat i18n + ExoPlayer retry.** Five parallel PRs: chapter search and jump-to-chapter (#794), library sort options (#793), sync transient failure retry with bounded backoff (#779), chat i18n + a11y labeling (#781), ExoPlayer network error retry (#807).

## [0.5.96] -- 2026-05-26

**Onboarding steps + haptics + i18n errors + dev logging.** Step indicator on onboarding, reader haptic feedback, i18n-friendly error messages, developer logging toggle.

## [0.5.95 -- 0.5.75] -- 2026-05-22 to 2026-05-26

Rapid iteration: 20+ point releases covering v1.0 Play Store readiness, voice-library engine blurbs, parallel-agent feature waves, stability fixes, and ongoing polish. See `git log v0.5.74..v0.5.95` for the exhaustive record.

## [0.5.74] — 2026-05-22

**Dream team session.** 9 PRs merged in one pass — closed all 4 open issues (#690-693) plus 6 new issues filed and 4 of those fixed same-session.

### Fixed
- **#690** a11y: wire `LocalAccessibleTouchTargets` into SettingsRow, SettingsSwitchRow, BrassButton, ChapterCard — toggle now actually enlarges touch targets (#694)
- **#691** sync: replace broken WebSocket data plane with InstantDB admin HTTP API — `JsonArray is not a JsonObject` crash on every fetch resolved (#708)
- **#692** auth: split pasted `code#state` blob for Anthropic Teams OAuth — `invalid_grant` on sign-in resolved (#701)
- **#695** browse: filter Search tab from chip strip when source declares `supportsSearch=false` — Slack/Telegram no longer show empty Search (#710)
- **#704** sync: SyncCoordinator.publish uses atomic `_status.update{}` instead of non-atomic read-modify-write — lost-update race across concurrent domain syncers fixed (#709)
- Settings hub: route `onOpenAiSettings` CTAs from HybridReaderScreen and ChatScreen to dedicated SETTINGS_AI subscreen instead of legacy long-scroll (#702)

### Added
- **#693** browse: generic filter sheets for 11 browsable backends — Gutenberg, arXiv, HackerNews, RSS, Wikipedia, Wikisource, AO3, Standard Ebooks, Notion, PLOS, Outline now have sort/language/category/dateRange filters (#707)
- **#697** llm: Claude Opus 4.7 and Sonnet 4.7 added to all provider model registries — Claude API, Bedrock, Anthropic Teams (#703)

### Chore
- **#696** ignore orphan `source-kvmr/` directory left by #417 rename (#706)

## [0.5.73] — 2026-05-17

**TTS-less device fallback CTA.** On stock Samsung tablets (Galaxy Tab A7 Lite et al.) and other devices that ship without Google TTS pre-installed, the #676 zero-download first-listen path silently degraded to the legacy Piper download. Surface a one-liner banner + Play Store deep-link so the user knows the built-in-voices path is one tap away.

### Added (#681) — `InstallSystemTtsBanner` in `VoicePickerOnboarding`
- New banner renders above the friendly Lessac/Cori voice tiles whenever `SystemTtsVoiceProvider.voices` is empty (zero TTS engines installed on the device).
- Copy: `"Install Google TTS for built-in voices, or download a Piper voice (~14 MB) below."`
- `Install Google TTS` BrassButton fires `Intent(ACTION_VIEW, market://details?id=com.google.android.tts)` with a graceful `https://play.google.com/...` fallback for non-Play-Store devices.
- TalkBack-friendly: banner copy is surfaced as a single `contentDescription`; button inherits `Role.Button` from BrassButton.
- Theme-safe: surfaceVariant background + onSurface body text + primary accent track `MaterialTheme.colorScheme` (correct in both light and dark themes).

### Plumbing
- `VoicePickerGateViewModel` now injects `SystemTtsVoiceProvider` and exposes `hasSystemTtsEngines: StateFlow<Boolean>`. The flow starts `false` and flips `true` when the OS roster enumerates at least one voice (~150 ms post-init on devices with Google TTS pre-installed; stays `false` indefinitely on TTS-less devices).

### Verified on-device (Galaxy Tab A7 Lite, R83W80CAFZB)
- Stock firmware, zero TTS engines installed → banner renders above Lessac/Cori tiles.
- Tap `Install Google TTS` → Play Store opens at the `com.google.android.tts` listing.
- Existing flow (Skip, Pick this voice, More voices) unchanged.

### Out of scope (filed for v1.1)
- Auto-refresh after returning from Play Store — today the user comes back, the System TTS roster will pick up the new engine on next cold launch or via the existing `refresh()` hook, but the banner doesn't auto-hide mid-session. Cheap follow-up if anyone files it.

## [0.5.72] — 2026-05-17

**Persistent now-playing mini-dock.** Brings the Reader one tap away from every non-player surface — Library, Browse, Follows, Inbox, History, FictionDetail, Voice library, Settings — instead of requiring a swipe-left to the Playing tab. v1.0 polish for goal-hook personas (5-year-olds, TalkBack users) and tablet users where the swipe distance was awkward.

### Added (#678) — `NowPlayingDock` Composable
- 64dp-tall card that appears at the bottom of non-player surfaces whenever a chapter is loaded (even paused).
- Layout: 40dp cover thumb (uses the existing [FictionCoverThumb] cascade) on the left, fiction + chapter titles (single line each, ellipsized) in the middle, play/pause IconButton on the right, slim 2dp progress bar at the bottom edge.
- Tablets (>= 600 dp width) also get a next-chapter IconButton next to play/pause.
- Tap the card body → navigates to the Reader (`reader/{fictionId}/{chapterId}`); tap play/pause → toggles transport in place without navigating; tap next (tablet) → advances chapter without navigating.
- TalkBack reads `"Now playing: $fictionTitle. $chapterTitle. Tap to open reader."` for the card; the play/pause and next-chapter buttons have their own focusable content descriptions ("Pause"/"Play" and "Next chapter").
- `liveRegion = LiveRegionMode.Polite` so chapter-title changes are announced when auto-advance fires while focus is elsewhere.
- Phone: dock rides along with the BottomTabBar inside the `Scaffold`'s `bottomBar` slot (Column wrapper). Tablet: dock pins to the bottom of the content area inside a new Column wrapping the NavHost — the side rail has no bottom-bar slot to ride along with.

### Verified on-device (Z Flip 3, R5CRB0W66MK)
1. Fresh install, no chapter loaded → dock invisible.
2. Tap Guides → Play → chapter loads → back to Library → dock appears with cover + "Guides" + "How to use TechEmpower.org" + Pause button.
3. Tap dock body → routed to Reader; dock auto-hides on Reader route as expected.
4. Back to Library while chapter auto-advanced → dock now shows "Free internet" (chapter 2) — `liveRegion` fired.
5. Tap Pause inside dock → toggles to Play icon without leaving Library.

### Out of scope (filed for v1.1)
- Swipe-up gesture on the dock to expand into Reader (Spotify-like).
- Per-chapter scrubber inside the dock (today's progress bar is read-only).

## [0.5.71] — 2026-05-17

**v1.0 candidate (final).** Picker auto-dismiss patch + on-device verification.

### Fixed (#682, v1.0 blocker) — Picker re-shows after Skip on first-launch (PR #683)
Caught by on-device verify of v0.5.70 on phone R5CRB0W66MK (Google TTS, 22 voices enumerated). The user walked Welcome → Pick a voice → Skip — I'll choose later → What would you like to hear? → Browse TechEmpower, and the download-only Piper picker **re-appeared**, defeating the entire #676 zero-download unlock.

Two-layer fix:
- `VoiceManager.seedSystemTtsDefaultIfUnset()` now flips `PICKER_DISMISSED = true` when it successfully writes an active System TTS voice. The previous design ("don't auto-dismiss, the user still gets to choose") was the wrong call for the goal-hook personas — the sticky-dismissed flag stops the gate from re-blocking later flows, and Settings → Voice library is the path for users who want to swap voices.
- `VoicePickerOnboarding` adds a `LaunchedEffect(Unit)` that checks `initialActive` on first composition: if a voice is already set (seed beat the picker), `onContinue()` immediately and skip to the next step.

**Known partial**: async race between seed completion (TextToSpeech.onInit can take 100-500ms) and picker composition. When the seed loses the race, two fallbacks still work: the Skip button is a manual one-tap escape, and the dismissed flag prevents re-gating on later flows. Fully synchronous seed before any UI composes is filed as a v1.x polish (would require a "preparing your voice…" splash or a foreground-blocking init pattern).

### Verified on-device (Z Flip 3 + Samsung Tab A7 Lite)
| Surface | Tablet (no TTS engine) | Phone (Google TTS, 22 voices) |
|---|---|---|
| System TTS enumeration | 0 voices, graceful fallthrough | 22 voices ✓ |
| First-launch picker | Shows download-required Piper voices (correct — no other option) | Sometimes auto-skips, otherwise Skip button works |
| Goal-hook unlock | Doesn't apply (filed #681 for Install-Google-TTS deep-link) | Works after Skip or auto-skip |
| Local empty-state CTA | ✓ Launches SAF picker | ✓ |
| Readability hint | ✓ | ✓ |
| Wikipedia titles (no &amp;lt;i&amp;gt;) | ✓ | ✓ |
| End-of-book overlay | Pending verification (long auto-advance not re-run this session) | Pending verification |
| Voice library subtitle | ✓ Reads "Browse and switch between available voices" | ✓ |

### Open issues at v0.5.71 cut
- #392 Propel partnership outreach (non-code, JP-actionable)
- #678 Now-playing mini-dock (v1.x, swipe-left still works)
- #681 TTS-less device deep-link (v1.x improvement)
- #682 Async race remaining (v1.x polish, fallbacks cover v1.0)

### Under the hood
v0.5.71 is the AAB v1.0 candidate. Same release keystore as v0.5.68+ (SHA256 `38:9F:BD:AA…85:E0:16`).

## [0.5.70] — 2026-05-17

**v1.0 candidate.** Four-PR bundle closing 8 issues caught by the parallel find-issues device sweep. Zero-download voice on first launch, terminal-state UI, three silent-empty backends fixed, three copy fixes.

### Added (#676) — Native Android TTS as a voice backend (PR #679)
The biggest goal-hook unlock of this session. Storyvox now defaults to whatever TTS engine the OS has configured (Google TTS, Samsung TTS, eSpeak, etc.) on fresh installs, so the first listen needs **zero model download**.

- New `EngineType.SystemTts(engineName, voiceName)` sealed-interface variant alongside Piper/Kokoro/Kitten/Azure, with its own `VoiceFamilyIds.SYSTEM_TTS` registry tier rendered at the top of the Voice library (zero-download first-launch unlock).
- `SystemTtsEngine` adapter (`core-playback/.../tts/SystemTtsEngine.kt`): async `onInit` lifecycle, per-utterance `UtteranceProgressListener` routing via `ConcurrentHashMap<utteranceId, CompletableDeferred<Result>>`, 44-byte WAV-header strip → raw PCM contract matching the existing engines, sample rate cached from header.
- `SystemTtsVoiceProvider` interface (`:core-data`) + `SystemTtsVoiceRoster` impl (`:app`) — two-step enumerate (list installed engines → bind each, read voices, shut down).
- `EnginePlayer` dispatch wired at every site (prewarm / loadAndPlay / sample-rate / recap / teardown).
- `seedSystemTtsDefaultIfUnset()` called from `VoicePickerGateViewModel.init` so a fresh-install user gets a working voice without ever seeing the "downloading model…" spinner.
- 14 new tests pin the WAV header at 24/16/48 kHz, catalog projection, ordering, byId behaviour.

### Fixed (#677, v1.0 blocker) — End-of-book UI freeze (PR #680)
JP caught this mid-run during the phone find-issues agent's 14-minute auto-advance test. Engine cleanly reached `BookFinished` + set `EngineState.Completed`, but no UI in `feature/*` consumed `Completed` — scrubber sat stuck at the last position with no surface explaining the book was over.
- New `ReaderUiState.bookFinished: Boolean` latch driven by a `MutableStateFlow` in `ReaderViewModel` that flips on `PlaybackUiEvent.BookFinished`.
- `HybridReaderScreen` renders a Material 3 `AlertDialog` end-of-book overlay with "Back to Library" + "Browse the realms" CTAs.
- `acknowledgeBookFinished()` resets the latch on user dismiss or `resume(fromStart=true)`.
- TalkBack-friendly (AlertDialog is a focus-claiming surface; headline reads immediately).

### Fixed (#669, v1.0 blocker) — Local backend silent-empty in Browse (PR #674)
Tablet find-issues agent caught it: tapping the **Local** chip in Browse rendered a completely blank content area — no spinner, no error, no CTA. Indistinguishable from a backend hang. Every other "not yet configured" backend (Palace, Wiki, Discord) had a clear `realm is unreachable` panel + setup path; Local did not.
- New `LocalEmptyState` composable mirrors the `RssEmptyState` shape (issue #458).
- `Choose folder` CTA launches the SAF `OpenDocumentTree` picker **directly from Browse** (no Settings round-trip for the first run).
- Persistable URI grant via `takePersistableUriPermission`.
- New `BrowseViewModel.setEpubFolderUri()` writes through `SettingsRepositoryUi`; the EPUB source re-enumerates on the next paginator tick.

### Fixed (#673, v1.0 blocker) — Readability silent-empty in Browse (PR #680)
Same pattern as #669 but on the always-on Readability backend. `popular()` intentionally returns `emptyPage()` (Readability is the URL-paste last-resort matcher, not a listing source), but the empty result fell through to silent-empty.
- New `ReadabilityHintState` composable explains the source's purpose and points at the existing "Add fiction by URL" FAB.

### Fixed (#672, v1.x polish) — Wikipedia HTML entities in titles (PR #680)
Wikipedia REST API returns titles with embedded entity-encoded HTML (`Erik Campbell (&lt;i&gt;Final Destination&lt;/i&gt;)` for italicized works — films/albums/books). Pre-fix these rendered raw on every Wikipedia chip tap; the existing `htmlToPlainText` helper does tag-strip-then-decode which leaves the encoded tags visible.
- New `cleanWikipediaTitle()` helper: decode-entities-FIRST, then strip the now-decoded tags.
- Wired at all 4 call sites (SearchHit, FeaturedArticle, MostReadArticle, Summary).

### Fixed (#670, v1.x copy) — Voice library subtitle truth (PR #675)
Settings hub Voice library card subtitle promised "Pick a voice and hear samples" but no preview UI exists — tapping a row Activates the voice. Per-voice sample-play is a v1.x enhancement; subtitle rewritten to match what the screen actually does: *"Browse and switch between available voices."*

### Fixed (#671, v1.x copy) — Outline empty-state copy (PR #675)
Outline panel read terse `Outline host or API key not set` vs Palace's helpful *"Set up your Memory Palace host in Settings → Memory Palace to browse your private fictions."* Outline now mirrors that pattern.

### Closed without code (#681 / no PR — informational)
- **#681 Visual entry to Reader from Library/Browse** (now-playing mini-dock) — filed for v1.x. The classic Spotify/Apple Music pattern; planned post-v1.0 because it touches the StoryvoxNavHost layout and the Reader navigation contract. Not blocking v1.0 launch — swipe-left to the Playing tab still works.

### Under the hood
- Open issue count after this release: stable. The remaining open items are #392 (Propel partnership outreach, JP-actionable, non-code) and #678 (now-playing mini-dock, v1.x).
- All four PRs merged in dependency order (#675 copy → #674 Local UI → #680 Readability/EOB/Wiki UI → #679 System TTS engine). #680 required a single-line rebase against #674's Browse-screen branch addition.

## [0.5.69] — 2026-05-17

**Beta-channel polish pass.** Closes the last code-side gap before the first Play Console upload.

### Fixed (#582) — secondary VoxSherpa engines now use the volatile sample-rate cache
The earlier #582 fix routed the **singleton** engine reads through `EngineSampleRateCache`, but the **secondary** (parallel-synth) engine handles at `EnginePlayer.kt:1788/1802/1820` still did direct `eng.sampleRate` reads. Each secondary is a separate `VoiceEngine`/`KokoroEngine`/`KittenEngine` instance with its own intrinsic monitor — if a secondary's `loadModel` is still in flight when `startPlaybackPipeline` runs on the main thread (the speed-chip cycling pattern from the original Z Flip 3 monkey repro), the read contends on THAT instance the same way the singleton contended.
- **Fix** (PR #667): route the secondary handles' `sampleRate` through the same volatile cache. All Piper engines share one model file and therefore one sample rate within a process, so the engine-type-scoped cache is the correct answer — using `EngineSampleRateCache.piperRate()` for both primary AND secondary handles is consistent + lock-free.
- Closes the last remaining instance-level lock-contention vector on the VoxSherpa sample-rate accessor.

### Added — `gradle-play-publisher` automation + Play Console runbook (PR #666)
Reduces future release workflow to a single `./gradlew :app:publishReleaseBundle` once the one-time service-account setup is done.
- Triple-T `gradle-play-publisher` 3.10.1 wired in `:app` with conservative defaults: track=`internal`, releaseStatus=`DRAFT`, `commit=false` so half-CI runs never auto-publish, `resolutionStrategy=IGNORE` to override stale Play Console edits.
- Reads service-account JSON path from `storyvox.playPublisher.credentialsFile` in local.properties. No-op when unset — the plugin's tasks emit "PlayPublisher requires credentials" but every other build path is unaffected.
- `docs/play-store/RUNBOOK.md` walks through one-time Play Console app entry + content rating + listing setup, Google Cloud service-account creation, IAM grants, per-release workflow, and a troubleshooting table.

### Closed without code change (#581) — finalizer "appender already closed"
Verified already-fixed in v0.5.60 (PR #587, commit 652e9683). `PcmAppender.finalize()` had shadowed `java.lang.Object.finalize()` — Kotlin doesn't require `override` for the deprecated GC hook, so `FinalizerDaemon` invoked the user method on every reclaim and `check(!closed)` threw. The fix renamed `finalize()` → `complete()` across PcmAppender, ChapterRenderJob, EngineStreamingSource, PcmCacheManifest + 5 test files; regression test reflectively asserts `finalize` is no longer declared. Issue was stale.

### Under the hood
- Open issue count: **22 → 2** across this push (v0.5.68 + v0.5.69). Remaining 2 are #582 (CI-verified fix in this release; closes on tablet re-stress) and #392 Propel partnership outreach (non-code, JP-actionable).

## [0.5.68] — 2026-05-17

**Beta-channel candidate.** Five PRs closing eleven issues — the last critical-path bundle before Play Store Internal Test.

### Fixed (#664) — PLOS Browse silently empty (`journal_key` retired)
Caught by the v1.0 tablet sweep on R83W80CAFZB. Browse → PLOS rendered no content, no spinner, no error — the silent worst-case empty state. Root cause: PLOS retired the `journal_key` Solr field at some point post-2024; `fq=journal_key:PLOSONE` now returns `numFound=0`.
- **Fix** (PR #665): swap to `journal:"PLOS ONE"` (case-insensitive Solr name match) → ~2.8M docs.
- Layered second fix on top: `fq=doc_type:full` drops per-section sub-docs (`/title`, `/abstract`, `/body`, `/references` — PLOS indexes each article N+1 times). Without it, Browse rendered the parent article alongside 5+ DOI-fragment duplicate cards. After: ~330k unique full articles with proper titles + author bylines.

### Fixed (#663) — arXiv silently empty (cleartext HTTP blocked by Android)
Same tablet sweep caught arXiv → empty body. `http://export.arxiv.org/api/query` was the endpoint since #378, on the theory that arXiv's HTTP→HTTPS 301 would be followed transparently. That works on desktop curl but not on Android: `network_security_config.xml` only allows cleartext to `palace.jphe.in`, so the OS blocks the initial HTTP request before OkHttp can follow the 301.
- **Fix**: switch `QUERY_BASE` const to HTTPS directly. `followRedirects` + `followSslRedirects` stay on as belt-and-suspenders.
- Tablet verify: cs.AI papers render — *ATLAS: Agentic or Latent Visual Reasoning?*, *EntityBench*, *Quantitative Video World Model Evaluation*.

### Fixed (#661) — Sync sign-in fails on R8/release: "element class t8.e is not available"
JP hit `element class t8./e is not available` (tablet release) and `kotlinx.serialization.json.JsonArray not available` (phone debug-minified) on his first InstantDB sync. v1.0 blocker — sync sign-in is the Beta channel onboarding path.
- **Root cause**: R8 obfuscated `kotlinx.serialization.json.JsonObject` → `t8.e`. The existing umbrella keeps `@Serializable class **` and `**$$serializer` but the JSON tree types are NOT `@Serializable` — they use hand-written serializers in `kotlinx.serialization.json.internal` that dispatch by concrete subtype name at decode time.
- Surface: `:core-sync/WsInstantBackend.kt` calls `json.encodeToString(JsonObject.serializer(), msg)` for the InstantDB WebSocket envelope.
- **Fix** (PR #662): add the canonical kotlinx-serialization-json keep set (`-keep class kotlinx.serialization.json.** { *; }` + `.internal.` + `.descriptors.` + module `.internal.`).
- Verified: `strings classes*.dex | grep -c 'kotlinx/serialization/json/'` = 201 unobfuscated refs.

### Fixed (#656) — Royal Road browse broken after sign-in
`BrowseViewModel.royalRoadSignedIn` read only the DataStore `UiSettings.isSignedIn` flag, which drifted from `AuthRepository.sessionState(ROYAL_ROAD)` after sign-in paths that flipped session state without persisting. Browse stayed "logged out" with a valid session.
- **Fix** (PR #659): OR-combine both signals. DataStore is the persisted-across-process truth; session-state covers the in-memory transition window.

### Added — Seven settings + a11y fix (PR #660)
| # | Setting | Chip values | Section |
|---|---|---|---|
| #590 | Particle / confetti intensity | None / Subtle / Lush | Appearance |
| #591 | Skeleton-shimmer style | Off / Pulse / Sigil | Appearance |
| #592 | Brass pulse intensity | Subtle / Standard / Bold | Appearance |
| #595 | Sleep-timer shake-extend amount | 5 / 10 / 15 / 30 min | Voice & Playback |
| #596 | Pre-render chapter count | N+1 / N+2 / N+3 / N+5 | Performance |
| #597 | Network patience preset | Aggressive (5s) / Default (10s) / Patient (30s) | Performance |
| #598 | Android Auto items per category | 4 / 6 / 8 / 12 | Advanced (new) |
| #651 | Voices section-header content-desc fix | — | A11y |

New `LocalParticleIntensity`, `LocalSkeletonStyle`, `LocalBrassPulseRange` CompositionLocals wired through `MainActivity`. New `AdvancedSettingsScreen.kt` + `SETTINGS_ADVANCED` route. Network-patience routed via per-call OkHttp Interceptors in royalroad / notion / rss (Dagger cycle broken by `Lazy<FictionSource>`).

### Under the hood
- Closed six v1.x deferrals as `deferred-to-v1.x`: #465 eBay, #147 knowledge graph, #244 RFC registry, #243 RR outreach, #242 RR authors, #425 CI workflow tweak.
- Open-issue count: **22 → 7** in one session.
- Tablet-verified backends post-merge: TechEmpower / Notion (default), arXiv, Hacker News, GitHub, Wikipedia, Wikisource, Standard Ebooks, Radio, PLOS. Auth-required correct silent state: AO3, Discord, Telegram, MemPalace, Outline.

## [0.5.67] — 2026-05-16

**Notion banners + tablet SQLite crash fix.** Two PRs caught by the v1.0 graphics-capture pass.

### Added (#653) — `format.page_cover` now flows to TechEmpower tile + detail covers
- Pre-fix: `AnonymousNotionDelegate.buildTechEmpowerTiles()` hardcoded `coverUrl = null` for all 4 TechEmpower fictions. The 3 detail handlers (`pageListDetail`/`collectionDetail`/`singlePageDetail`) also dropped covers. So Notion page banners JP set via "Add cover" never reached the Browse grid or FictionDetail screen.
- Fix: new `coverPageId` field on `TechEmpowerFiction` sealed-class variants, pinned in `NotionDefaults`:
  - Guides → `6c979ba4e43f48d7a4836e0027ea4178` (first chapter "How to use TechEmpower.org")
  - Resources → `2a3d706803c649409e74e9ce5ccd4c4b` (the database block itself)
  - About → `dbf0ddece2ce468fb2bf9049e6322e8a` (its own pageId)
  - Donate → `59d8a4dab0cc484f8b044d33f240ce1d` (its own pageId)
- Shared `extractCoverFromRecordMap(coverPageId, recordMap)` helper runs the standard `readCoverUrl` → `readBodyImageUrl` cascade. Browse + FictionDetail both use it.
- HTTP cost: Browse goes from 0 → 1 `loadPageChunk(rootPageId)` call (~290 KB CDN-cached). Any cover-page-id not in the welcome chunk fans out in parallel via `coroutineScope { async }.awaitAll()`. 9 new tests pin the contract.

### Fixed (#654) — Tablet Guides FictionDetail crash on second open
Caught by the v1.0 graphics-capture agent on R83W80CAFZB:
```
SQLiteConstraintException: UNIQUE constraint failed: chapter.fictionId, chapter.index
  at ChapterDao.upsertChaptersForFiction
```
- **Root cause traced** (kdoc'd in the new `ChapterDao` method): the original v0.5.65+ refresh succeeded ONCE — it parked "EBT spending" at `index=100004` (#349's feed-window park mechanism) while writing the 7 new chapters at indexes 0-6. The crash hit on the **second** open: `parkChapterIndexesFor` only acts on rows with `index < 100000`, so the 7 live rows got bumped to 100000-100006, and `100004` collided with the still-parked EBT-spending row.
- Fix: rewrote `@Transaction upsertChaptersForFiction` to `deleteByFictionId(fictionId)` THEN `insertAll(chapters)`. Body-preservation for surviving chapters still happens upstream in `FictionRepositoryImpl.upsertDetail` per-PK merge, so the wipe is safe.
- Tablet-verified (without `pm clear` so the stale DB state persists): three consecutive opens of Library → TechEmpower → Browse Resources → Guides, all clean. Logcat `grep -c SQLiteConstraint` = 0.

## [0.5.66] — 2026-05-16

**Magical loading animations.** Replaces Material's sterile `CircularProgressIndicator` arc with Library Nocturne brass sigils across every loading surface. v1.0 Play Store Internal Test candidate.

### Added (#650) — `MagicCircularProgress` + brass ember overlay + breath shimmer
- **`MagicCircularProgress`** in `:core-ui/.../component/MagicSpinner.kt` — layered brass sigil (outer dashed ring + inner six-pointed star + faint guide ring + center dot) at 48 dp default. Replaces Material 3 `CircularProgressIndicator` at 3 sites:
  - `HybridReaderScreen.ExplicitArgsLoadingPrompt` (chapter-tap → controller-emit) — was the "weird arc"
  - `SyncAuthScreen` — magic-link verify state
  - `FictionDetailScreen` — initial fiction load
- **`BrassEmberOverlay`** in `:core-ui/.../component/BrassEmberOverlay.kt` — 6 brass candle-embers drifting upward with sine sway, layered over `AudiobookView`'s warming cover alongside the existing MagicSpinner orbit.
- **`WhyAreWeWaitingPanel.PulsingBrassSigil`** upgraded — 12 dp alpha-pulse dot → 14 dp `MagicSpinner` brass ring + alpha breath.
- **`AudiobookView` "Conjuring your chapter…"** title now breathes via `shimmerAlpha()` while empty.
- **`MagicSpinner` + `MagicSkeletonTile` + `shimmerAlpha()`** now honor `LocalAnimationSpeedScale` (#589) — previously only `LocalReducedMotion`.

### Accessibility — reduced motion + Off animation speed
Every animation has a deliberate resting pose:
- `MagicCircularProgress` → outer 0° / inner 30° / pulse 1.0
- `BrassEmberOverlay` → single resting ember at bottom-center, alpha 0.55
- `MagicSpinner` → 135° resting pose
- `MagicSkeletonTile` → 0°/30°/1.0
- `shimmerAlpha()` → static 0.6
- `PulsingBrassSigil` → frozen spinner, alpha 0.85

Math gated by `scaleDurationMs(N, 0f) == 0`, pinned by existing `AnimationSpeedScaleTest`.

### Under the hood
- Tablet-verified visual: Library → Browse → Guides → uncached chapter → new sigil renders, content-desc reads correctly through TalkBack, panel disappears cleanly on back.

## [0.5.65] — 2026-05-16

**v1.0 GREEN-LIGHT candidate.** Closes the four readiness gaps caught by the persona verification on v0.5.64 (#644 tap count, #645 TalkBack dock, #646 audio-route announce, #647 Browse-TechEmpower direct-route) PLUS the long-standing #558 Notion chapter prefetch timeout.

### Fixed — #558 Notion chapter prefetch timeout (PR #648)
ch4 → ch5 auto-advance hit a 30 s timeout in v0.5.64 because ch5's body had never been fetched. Plus the underlying root cause: one of the hardcoded Guides chapter ids (`16f7018ad93542652b2b16c44464b1c3` — "EBT spending") returned a Notion `ValidationError 400` on every retry, exhausting the timeout window.
- `PrerenderTriggers.onChapterOpened(fictionId, chapterId)` — new trigger called from `EnginePlayer.loadAndPlay` that pre-fetches BODIES (not PCM) of the next 3 chapters. Cheap, dedupes by WorkManager `uniqueName`, `requireUnmetered=false`.
- `DEFAULT_PRERENDER_CHAPTERS` bumped 3 → 5 to widen the PCM render window.
- `CHAPTER_BODY_WAIT_TIMEOUT_MS` bumped 30 s → 60 s as a safety belt for cold-start mid-fiction resumes.
- Hardcoded "EBT spending" chapter id REMOVED from the Notion Guides default list — kdoc thread documents the 400 failure mode + the restoration procedure if Notion fixes the page.

**Phone-verified cascade (ch1 → ch6, ~300 ms transitions each)**:
- ch1 → ch2: 141 ms (was already fast)
- ch2 → ch3: 393 ms
- ch3 → ch4: 133 ms
- ch4 → ch5: 281 ms (was: 30 s timeout)
- ch5 → ch6: 270 ms

### Fixed — v1.0 yellow-light bundle (PR #649, closes #644 #645 #646 #647)
**#647 — "Browse TechEmpower" auto-plays chapter 1 directly** (load-bearing fix for #644). Onboarding's FirstFictionPicker → tap "Browse TechEmpower's free guides" → new `openGuidesAndAutoPlay()` path seeds Notion tiles, refreshes `notion:guides`, takes first chapter, queues with `autoPlay=true`, routes to Playing surface. Skips both the TechEmpower Home hub AND the FictionDetail cover stop.

**#644 — Tap count from cold launch to first audio: 8 → 5** (matches Play Store-ready budget):
- Allow notifications (system)
- "Get started"
- "Pick this voice"
- "Skip for now" (sync)
- **"Browse TechEmpower's free guides" — deep-link auto-plays chapter 1 within 3 s**

**#645 — TalkBack dock navigable from Reader / Voices / Settings**. `BottomTabBar.TabCell` clickable role flipped `Role.Tab` → `Role.Button` (keeping `selected = isSelected`). TalkBack no longer suppresses "double-tap to activate" on a selected tab.

**#646 — `WhyAreWeWaitingPanel` single-announce**. `Modifier.semantics(mergeDescendants = true)` collapses the panel to one TalkBack focus stop. New `joinHeadlineAndBody()` kills the `..` double-period before the body line. Unit test sweeps every `WaitReason` to pin the invariant.

### Verified on R5CRB0W66MK (release variant)
- 5-tap cold-launch-to-audio flow confirmed
- TalkBack: Library reachable via dock tap from Player + Voices + Settings
- TalkBack: 988 Crisis Lifeline still single-tap (PR #632 intact)
- Auto-advance ch1→ch6 cascade all sub-400ms
- Zero "Reconnecting — please wait" surfaces (post-#642 fix)

## [0.5.64] — 2026-05-16

**Chapter-2-silent-on-auto-advance fix.** v0.5.63 made auto-advance fire correctly — but chapter 2 then stayed silent. PR #643 fixes the parked consumer at natural chapter end.

### Fixed (#643, closes #642) — last sentence stranded → chapter 2 producer wedged
**Root cause** (captured by on-device logcat): after the producer dequeues the last real chunk and logs `serial producer: natural end (all 54 sentences emitted), pushing END_PILL`, the consumer's underrun-pause gate (`bufferHeadroomMs < BUFFER_UNDERRUN_THRESHOLD_MS`) trips because END_PILL contributes 0 ms of audio. Consumer calls `track.pause()` and parks in `runBlocking { bufferHeadroomMs.first { it >= RESUME || !pipelineRunning.get() } }`. Producer is done, so `bufferHeadroomMs` never emits again. `pipelineRunning` clause is INSIDE the predicate — only re-evaluated on emission. Park is forever. Chapter 1's last sentence never reaches speakers. When chapter 2's `loadAndPlay` runs via the watchdog, the teardown chain interrupts the stuck consumer but the new pipeline gets wedged behind the dying-resource handoff.

**Fix** in `EnginePlayer.kt`:
1. **Underrun-pause gate now ANDs `!source.producedAllSentences`** — last-chunk case skips the pause entirely → post-write fast-path fires → `handleChapterDone` dispatches naturally.
2. **New `waitForResumeOrTerminalState` helper** races the headroom flow against a 50 ms poll on `producedAllSentences` — belt-and-suspenders defense for the producer-finishes-while-consumer-parked race.

Plus `EngineStreamingSourceProducedAllRaceTest.kt` pinning the contract.

### On-device verified — auto-advance cascade
- ch1 → ch2 (How to use → Free internet): **488 ms gap**, natural-end fast-path
- ch2 → ch3 (Free internet → EV incentives): **331 ms gap**
- ch3 → ch4 (EV incentives → EBT balance): **293 ms gap**
- ch4 → ch5: watchdog fired but chapter body not pre-downloaded; #558 (Notion N+1 prefetch) territory, separate issue
- `dumpsys audio | grep storyvox` confirms `AudioTrack state:started` post-advance
- No "Reconnecting" panel surfaces (because audio actually IS reaching speakers)
- Tablet R83W80CAFZB also clean: ch1→ch2 fast-path at 18:41:57.635

### Pre-existing first-listen synth latency (NOT a regression)
The 300-500 ms transition gap on the FIRST listen at 2× speed is pre-existing behavior — PCM cache key includes speed, so 1× pre-rendered chapters miss cache at 2×. Subsequent re-listens at 2× hit cache + gap drops to <100 ms. PR-F (#86) pre-render worker generates 1× by default; future work would prerender the user's active speed.

## [0.5.63] — 2026-05-16

**v1.0 phone playback blocker fix.** Caught in JP-requested end-to-end phone verification: tap-to-play timed out after 30s with no audio, no AudioFocus, no AudioTrack on R5CRB0W66MK (Z Flip 3). PR #641 root-causes + fixes.

### Fixed (#641, closes #640) — phone playback timeout

**Root cause**: the #553 buffering-stuck watchdog couldn't tell apart **chapter-transition buffering** (legit fast 1.5s recovery target) from **intra-chapter producer-underrun** (Piper-low synth sub-realtime on phone — normal, recovers within seconds). On phone with Piper-low voice, the consumer's catch-up-pause branch in `EnginePlayer.kt` set `isBuffering=true` mid-chapter without clearing `currentSentenceRange`. Watchdog fired at 1.5s, called `advanceChapter(1)` while chapter 1 was still playing, interrupted the live consumer, and stranded chapter 2's pipeline half-built. User saw `AudioOutputStuck` ("Reconnecting — please wait") with audio focus held but no PCM moving.

The smoking-gun log line (release variant captured via diagnostic build):
```
W PlaybackController: #553 watchdog: isBuffering stuck on notion:guides::6c979ba4...
   for 1500ms; firing fallback advance via nextChapter()
```
Fired at chapter 1 position ~56s of 78s — well before natural end.

**Fix**: two-tier watchdog gated on `currentSentenceRange`:
- `currentSentenceRange == null` → chapter-transition (fast 1.5 s threshold) — preserves the original #553 worst-case recovery.
- `currentSentenceRange != null` → intra-chapter / end-of-chapter (slow 12 s threshold) — gives Piper-low underruns time to resolve without the watchdog stomping the live consumer.

Tablet wasn't affected because its faster CPU keeps the producer ahead of the consumer — underrun never holds past 1.5 s. Phone-only failure matched the issue's observation profile.

### Verified on R5CRB0W66MK (release variant)
- Cold launch → onboarding skip → TechEmpower hero → Browse Resources → Guides → tap Play
- Chapter 1 played to natural end (1:18)
- Audio focus acquired (`dumpsys audio | grep storyvox` → `gain: GAIN`)
- Mid-chapter manual skip-next transitioned to chapter 2 ("Free internet", 4:15) in **317 ms**
- Engine logs: `advanceChapter` → body wait → `loadAndPlay` → `#569 skipping loadModel` (cache hit) → `pcm-cache MISS chapter=bb5e537b...` → `advanceChapter: complete, now playing`
- No watchdog misfire

### Under the hood
- 10/10 watchdog tests green, including two new ones covering #640: `watchdog defers past fast threshold when sentence range is set` + `watchdog eventually fires on truly stuck end-of-chapter`.

## [0.5.62] — 2026-05-16

Sweeper + Play-button-overlap fix. Closes JP's animation-speed-in-Settings directive + the misleading "library awaits" empty-state that defeated the v0.5.61 tap-to-play discoverability fix.

### Added — Animation speed + skip distance + rewind window in Settings (#637, closes #589 #593 #594 #601 #631)
- **#589 Animation speed master multiplier** — `LocalAnimationSpeedScale: CompositionLocal<Float>` provided at NavHost root, `tweenScaled(N)` helper in `:core-ui/theme/Motion.kt`. Settings → Appearance → "Animation speed" chip row (Off/Slow/Normal/Brisk/Fast). `Off` sets scale to 0 → durations become instant. Honors `LocalReducedMotion`.
- **#593 Skip distance preset** — Settings → Voice & Playback → 10/15/30/45/60s chip row. New `PlaybackSkipConfig` contract in `:core-data`, hot-cached snapshot in `DefaultPlaybackController`.
- **#594 Rewind-to-start window** — Settings → Voice & Playback → 0/1/3/5/10s chip row (0 = Off). Pairs with #593; wired into `previousChapter()`.
- **#601 Playing tab CTA copy** — ResumePrompt's "Resume reading" → "▶ Start listening" (matches the tab verb).
- **#631** tracker closed as resolved by PR #635 (welcome flow shipped in v0.5.61).

### Fixed — Tap-to-play discoverability regression (#639, closes #638)
v0.5.61's PR #633 added a primary Play button to FictionDetail's BottomBar — but on phone, tapping it appeared to route to the Playing tab empty state. **Actual root cause** (discovered via on-device diagnosis): the navigation worked fine, but the Reader screen pre-fix fell through to `ResumeEmptyPrompt` ("Your library awaits / Browse the realms →") while waiting for the `playback` flow to emit — looked identical to bottom-dock taking the touch.
- **`ReaderViewModel.hasExplicitChapterArgs`** — reads `fictionId` + `chapterId` from `SavedStateHandle`
- **`HybridReaderScreen`** — when explicit args present AND `playback == null`/blank-ids, render new `ExplicitArgsLoadingPrompt`: "Loading chapter…" → "Still loading…" at 10 s → "Couldn't start this chapter" with Retry/Back at 30 s
- **`FictionDetailScreen`** — moved BottomBar (now `ActionRow`) out of `Alignment.BottomCenter` float into the LazyColumn as first item (defensive — eliminates any future overlap risk)
- Bounds verification on R5CRB0W66MK: Play button `[48,2359][522,2503]` → `[48,1053][522,1197]` (now well above any dock area).

## [0.5.61] — 2026-05-16

**v1.0 readiness wave.** JP set a new goal: "v1.0 release on app store — 5-year-old or sight-impaired user can pick it up and know what to do; beautiful, smooth, performant." 5 PRs landed addressing 38 issues, plus latent auto-advance regression root-caused.

### Fixed — auto-advance regression (#636, closes #588)
Latent bug since #156 surfaced in v0.5.60. **`Pattern.UNICODE_CHARACTER_CLASS` is unsupported on Android's `java.util.regex` runtime.** `PronunciationDict.compileEntries` requested it + caught only `PatternSyntaxException`, so first playback with any pronunciation entry threw `IllegalArgumentException`. `EngineStreamingSource` producer's silent `catch (_: Throwable)` swallowed it without setting `producedAll=true` or pushing `END_PILL`. Consumer parked on `queue.take()` forever; `handleChapterDone` never fired; `isBuffering` stayed false so the #553 watchdog couldn't fire either. Chapter stuck at end with **zero log lines** — invisible bug.

Fix:
- `PronunciationDict.kt` — drop the unsupported flag, catch `IllegalArgumentException`, wrap lazy `compiled` access in `runCatching`.
- `EngineStreamingSource.kt` — serial + parallel producer catch handlers log non-cancellation faults at WARN and ALWAYS push `END_PILL` so consumer can exit cleanly. Watchdog recovers via #553 path. All loggers `runCatching`-wrapped.
- `EnginePlayer.kt` — consumer `nextChunk()` catch logs the Throwable.

**Verified BOTH devices**: phone Notion Guides ch01 → ch02 in ~45s wall at 2× (1:18 audio + 1.5s watchdog window); tablet same path. Both within v1.0 < 2000ms bar.

### Safety — 988 hotline reachable in one tap for TalkBack (#632, closes #608)
988 Suicide & Crisis Lifeline was only reachable via long-press — TalkBack users can't reliably long-press. Split `TechEmpowerHelpIcons` into three single-tap glyphs: `MonitorHeart` → 988, `Phone` → 211, `Forum` → Discord. Each with explicit `contentDescription`. Verified with TalkBack ON on tablet: announces "Call 988, Suicide and Crisis Lifeline."

### Fixed — visible Play button + TalkBack semantics (#633, closes #604 #605 #607 #609 #611 #612 #613 #614 #615 #616 #617 #628 #630)
13 v1.0 a11y / discoverability issues in one bundle:
- **#604 / #605** — FictionDetail BottomBar leads with brass-bordered Play/Resume button; player transport play button always visible (cover-tap now redundant, not required)
- **#607** — Speed FilterChip's overriding `contentDescription` removed; built-in `selected`/`checked` semantics restored
- **#609** — Voice chip humanized via new `humanizeVoiceLabel` helper (Piper VCTK → `Pick voice. Current: Piper Lessac English (United States) Low quality`)
- **#611** — Sync icon "Sync — signed in" → "Manage sync — currently signed in"
- **#612** — `ChapterCard` `clearAndSetSemantics` collapses descendant Text nodes to single TalkBack stop
- **#613 / #628** — Library top tabs + bottom nav get explicit `Role.Tab` + `selected`
- **#614** — `WhyAreWeWaitingPanel` coalesces rapid live-region announcements with 350 ms debounce
- **#615** — Scrubber gesture Box 40dp → 48dp (WCAG 2.5.5)
- **#616** — Cover-tap `onClickLabel = "Pause" / "Play"`
- **#617** — `VoiceRow` outer semantics consolidates one focus stop with `clearAndSetSemantics` body
- **#630** — Star toggle `Modifier.toggleable(value, role = Role.Switch)`

### Added — book cover style toggle Monogram default + v1.0 beauty + tablet polish (#634, closes #618 #619 #620 #621 #622 #623 #625 #629)
- **#629 NavigationRail at 600dp+** — tablet no longer wastes wider screen on phone-shaped bottom nav. New `SideNavRail` mirrors `BottomTabBar` semantics
- **#618 Cold-launch perf CI guard** — `ColdLaunchThresholds.kt` (tablet 1500ms, phone 500ms) + `scripts/check-cold-launch.sh` + new step in `android.yml`
- **#619 humanizeVoiceLabel** — raw filenames become plain English (e.g. `piper:en_US-amy-medium` → `Amy · medium quality`)
- **#620 progress bar contrast bumped** — rail height 3→4 dp, timecodes labelMedium SemiBold
- **#621 "Demo content"** → "Showing TechEmpower starter collection" (later softened to "TechEmpower's free guides" by onboarding merge)
- **#622 21 fiction backends chip consistency** — uniform brass-outline / brass-primaryContainer styling
- **#623 Ken-Burns slow zoom on cover during warming** — honors `LocalReducedMotion`
- **#625 advanced expandable chevron** — `animateFloatAsState` 0° → 180° rotation

### Added — v1.0 first-launch onboarding flow + plain-English copy refresh (#635, closes #599 #600 #602 #603 #606 #624 #626 #627)
- **#599 Three-screen welcome flow** — Welcome ("Stories, read aloud.") → Pick a voice (friendly cards: Lessac/Cori/Amy/Ryan with one-line descriptions) → Pick what to listen to (3 big cards). Gated by `pref_onboarding_completed_v1`. Settings → Developer → "Reset onboarding" replay hook.
- **#600** voice cards use first names + "Free · NN MB" tags (no engine jargon)
- **#602** Browse Notion banner: "TechEmpower's free guides. Free, no account needed." (was "Add a Notion integration token...")
- **#603** TechEmpower hero copy: "Free help — read out loud."
- **#606** All 9 `WaitReason` headlines rewritten plain English ("Warming up Brian's voice. This happens once per session." / "Internet is slow. Hang tight." / etc.)
- **#624** `NotebookSection` hides entirely until first note lands
- **#626** Library TechEmpower hero card: "Tap to see TechEmpower's free guides →"
- **#627** "Activate" → "Pick this voice"

### Under the hood
- 7 parallel agents in worktrees (settings-coverage-auditor, autoadvance-regression-fixer, v1-readiness-auditor, v1-release-prereqs, safety-hotfix-988, onboarding-flow-builder, player-discoverability-fixer, beauty-and-tablet-polish — read-only audits + isolated fixes).
- 33 v1.0-readiness issues filed (#599-#631) — most closed in this release.
- 10 settings-coverage issues filed (#589-#598) — top-3 (animation speed, skip distance, rewind window) queued for next wave.
- Auto-advance recovery turned a 100-min investigation into a 4-line root-cause fix in `PronunciationDict.kt` — the kind of bug that hides for months because no log line ever fires.

## [0.5.60] — 2026-05-16

Crash bundle from the #579 stress-test pair (one agent driving the Z Flip 3 through monkey + scripted scenarios, one fixing in a worktree). 5 crashes / ANR-class issues closed in PR #587.

### Fixed (#587)
- **#585 (FATAL) `NetworkOnMainThreadException` across 6 source plugins** — `ArxivSource.popular` reproduced 3/3 on Browse-tab source-chip cycling. Same crash class hit `GutendexApi.get` in v0.5.58 — root cause was missing `withContext(Dispatchers.IO)` at the transport seam. Fixed across **arxiv, discord, notion, outline, plos, rss** API classes. Audited remaining sources; `ao3`, `gutenberg`, `hackernews`, `wikipedia`, `standardebooks` already wrap correctly. Follow-up audit deferred for `MatrixApi`, `SlackApi`, `RadioBrowserApi`, `TelegramApi`, `ReadabilityFetcher`, `WikisourceApi`, partial `StandardEbooksApi`, `RoyalRoadSource` `CloudflareAwareFetcher`.
- **#582 ANR-class monitor contention 2.05 s on `VoiceEngine.loadModel` ↔ `getSampleRate`** — UI thread blocked 2+ s on engine load while reading the sample rate. New `EngineSampleRateCache` (`@Volatile` per-engine fields). UI-thread reads in `startPlaybackPipeline` / `activeVoiceEngineHandle` / `startRecapPipeline` now hit the volatile instead of the contended engine lock; cache populated post-`loadModel` in `EnginePlayer` and `ChapterRenderJob`. 5 unit tests.
- **#581 finalizer `IllegalStateException 'appender already closed'`** — `PcmAppender.fun finalize()` shadowed `java.lang.Object.finalize()` (Kotlin doesn't require `override` for the deprecated GC hook), so `FinalizerDaemon` called our user method on every reclaim and `check(!closed)` threw. Renamed `finalize() → complete()` across `PcmAppender`, `ChapterRenderJob`, `EngineStreamingSource`, `PcmCacheManifest`, + 5 test files. Regression test in `PcmAppenderTest` reflectively asserts `finalize` is no longer declared.
- **#583 Sign-in raw `Malformed parameter: [\\` leak** — InstantDB server error surfaced verbatim to user. Added `isLikelyEmail` (RFC 5321 §4.5.3.1.1 64-char local cap rejects the 180-character stress repro) + `sanitizeAuthError` (strips JSON-path tokens, maps to friendly equivalents). Both wired into `SyncAuthViewModel.sendCode` + `verifyCode`. 18 unit tests pin every branch.
- **#584 Add-fiction-by-URL accepts `file:///etc/passwd` + `javascript:alert(1)`** (security) — added `isLikelyAddByUrl` scheme allowlist (http/https only, case-insensitive, post-trim). Wired into `LibraryViewModel.submitAddByUrl` before resolver call. 10 unit tests cover every rejection class.

### Deferred
- **#586 ViewRootImpl `showInsets on window that no longer has views` during settings nav monkey** — sibling stress-test agent's triage classified as "polish for QA round 5" — no crash, no visible bug, just framework log noise during heavy navigation.

### Under the hood
- Stress-test agent ran 4 monkey rounds + 12,000 random events + 30x cover-style toggles + 90x sleep timer + 150x speed-chip taps + 20x voice download cancel-restart + theme switch x10 + lock-unlock + rotation + Z Flip 3 cover-screen simulation. Surfaces that withstood all stress: 60s/120s/300s background return, 10x force-stop relaunch, BT enable/disable mid-playback.
- 6 issues filed total; 5 fixed; 1 deferred.
- Recovery note: crash-fix-from-stress agent edited absolute paths in main's working tree instead of its assigned worktree (same slip pattern as a prior crash-fixer agent). Orchestrator captured the work cleanly via stash → branch → commit.

## [0.5.59] — 2026-05-16

The "go full auto" release — 7 PRs, ~25 issues, 2 major features in one bundle. Spawned 7 parallel agents in worktrees off v0.5.58 after JP requested broad cleanup + the audit's hard `dumpsys` evidence revealed a deeper bug surface than v0.5.58 caught.

### Fixed — Crashes (#575)
- **#559 InstantDB sign-in crash** — `InstantHttpTransport.postJson` was non-suspend; `SyncAuthViewModel.sendCode → viewModelScope.launch` ran the blocking OkHttp call on `Dispatchers.Main.immediate`. `FATAL: NetworkOnMainThreadException`. Made `postJson` suspend + `withContext(Dispatchers.IO)`. Test fake updated. Verified on both devices: Send code completes the network round trip; the UI shows the server-side validation error instead of crashing.
- **#544 Background-resume crash loop** — already resolved by v0.5.53's foregroundServiceType + DataStore fix. Verified clean: 90s background → resume sweeps on both devices completed without crashes.

### Fixed — Playback fidelity (#573)
- **#564 AudioTrack churn on phone** — `userPaused` flag was getting cleared between pause/resume on Z Flip3, forcing full rebuild. Broadened the resume fast-path gate to accept any live pipeline (`haveTrack + pipelineRunning`) regardless of `userPaused`; `track.play()` is idempotent.
- **#565 Skip ±30 slop** — skip was anchored on sentence-aligned `state.charOffset` (start of current sentence). Re-anchored on `playbackPositionMs` (truthful audible position). Speed-invariant + position-anchored tests added.
- **#566 Warming scrubber drift** — `AudioTrack.playbackHeadPosition` reports stale frames during the window between `pipelineRunning=true` and first `track.play()`. `currentPositionMs()` now skips the AudioTrack-derived branch until `state.currentSentenceRange != null`.
- **#568 Cover-tap pause -6s phone regression** — pinned position was `currentPositionMs()` which could regress on Flip 3's deep audio buffer. Now pins to `max(currentPositionMs, sentenceStartMs)` — forward-biased, regression-proof by construction.
- **#569 Tier 3 Piper ONNX init 1.2s/chapter** — `loadAndPlay` unconditionally called `loadModel`. Added compound cache-key skip-guard `(voiceId, engineType, parallel-instances, parallel-threads)`; same voice + same parallel state → skip loadModel.
- **#570 F2FS SELinux denials** — SQLite WAL mode invokes `F2FS_IOC_SET_PIN_FILE` which Samsung One UI 4 audits. Set Room journal mode to TRUNCATE.

### Fixed — True gapless auto-advance (#580)
- **#553-extension** — consumer thread treated `nextChunk()=null` (END_PILL) as end-of-chapter immediately, but the AudioTrack hardware buffer still held ~1-2s of unplayed PCM. By the time those frames played, `pipelineRunning` had been flipped false by the abandon path; `handleChapterDone` never fired. The 1.5s watchdog (v0.5.57) rescued. Fix: 2-second drain deadline after END_PILL — wait for `AudioTrack.playbackHeadPosition >= totalFramesWritten` before firing `handleChapterDone` with reliable invariants. Chapter N → N+1 now fires within ~50-300ms of last audible frame instead of relying on the watchdog. Spotify-grade transitions. 206-line `EngineStreamingSourceGaplessTest`.

### Fixed — Phone narrow-width clipping + Emergency safety (#574)
- **#532 Library tab strip overflow** — Bumped `SecondaryScrollableTabRow.edgePadding` 0.dp → `spacing.md`. History reachable via swipe on 360dp.
- **#533 Top-bar 4 icons 0dp gap** — added 8dp `Spacer`s between TechEmpower icons + Sync + Settings. Now 72px gaps.
- **#534 VoiceLibrary lang chip strip at capacity** — moved horizontal padding into `contentPadding` with asymmetric end (`xl=32dp`).
- **#535 AO3 Browse tab strip clipping** — same `edgePadding` fix as #532. "Marked" clears the edge.
- **#546 Emergency Help dial picker on WiFi-only tablet (SAFETY)** — added `PackageManager.hasSystemFeature(FEATURE_TELEPHONY)` probe before `ACTION_DIAL`. On telephony-absent devices, surface `NoTelephonyFallbackDialog` with the number, Copy-to-clipboard, and Open-web fallback (`988lifeline.org/chat`, `211.org`, FCC text-to-911 guidance). Manifest now declares `<uses-feature android:name="android.hardware.telephony" android:required="false"/>`.

### Fixed — UX polish (#576)
- **#547 Voice picker re-prompt every launch** — added `voice_picker_dismissed` Boolean key. `markPickerDismissed()` flips it on Continue; `setActive(voiceId)` also sets it atomically. Verified: pm clear → picker shows → tap Continue → cold relaunch → picker stays dismissed.
- **#538 Listen button redundancy** — removed standalone "Listen" `BrassButton` from FictionDetail's BottomBar. Promoted "Add to library" to Primary. One tap from list → chapter list → tap chapter → audio.
- **#545 Single-chapter Pause→Replay** — new `derivePlayPauseIconState(isPlaying, positionMs, durationMs, chapterId)` pure helper; `PlayPauseIconState` enum with Play/Pause/Replay. Tap at Replay state seeks to 0 first.
- **#542 Widget Play/Pause TalkBack** — `RemoteViews.setContentDescription` on the transport button.
- **#549 Widget cover duplicate TalkBack announcement** — set `importantForAccessibility="no"` on 4x1/4x2 cover ImageViews.
- **#541/#548 Voice download diagnostic + retry** — new `FailedDownload(voiceId, reason, lastProgress)` data class; `_failedDownloads: MutableStateFlow<Map<String, FailedDownload>>`; per-row "Tap to retry" + "Dismiss" affordances rendered in `FailedDownloadSubtile`.

### Added — Magical "why are we waiting" diagnostic (#578)
The app now KNOWS when audio isn't reaching the speakers and surfaces a specific reason every time — JP's exact words: "we need to the app to know if the audio is actually coming out of the speakers, and if not it needs to tell us beautifully and magically why we are waiting EVERY time."

- **New `AudioOutputMonitor`** (`@Singleton` in `:core-playback`) ticks at 200ms, cross-references the truthful `AudioTrack.playbackHeadPosition` with `AudioFocusController` state, `AudioManager.getStreamVolume(STREAM_MUSIC)`/mute, and `AudioDeviceCallback` route events.
- **9 `WaitReason` variants** — `WarmingVoice`, `LoadingChapter`, `BufferingNextSentence`, `NetworkSlow`, `FocusLost`, `AudioRouteChange`, `DeviceMuted`, `VoiceDownloadFailed`, `AudioOutputStuck` (catch-all — the "EVERY time" guarantee).
- **`WhyAreWeWaitingPanel`** Composable above the cover — Library Nocturne brass surface with pulsing sigil, EB Garamond headline, optional progress bar, brass-bordered retry chip. `AnimatedVisibility` for graceful slide-in/out.

### Added — Book cover style toggle (#577)
JP's preference: the old MonogramSigilTile (v0.4.x design) over the v0.5.51-introduced BrandedCoverTile.

- **New `pref_cover_style`** in DataStore + `:core-sync` allowlist. Three values: `monogram` (DEFAULT), `branded`, `cover_only`.
- **New Settings → Appearance** subscreen reached from a Palette-icon hub row between Reading and Performance. Three picker rows + a 64dp live preview chip-row that walks the exact production cascade.
- **`FictionCoverThumb` cascade rewired** — real cover URL always wins when present; on fallback the dispatch goes through `chooseFallbackStyle(LocalCoverStyle, title)` → Monogram (default) / Branded / `DimCoverPlaceholderTile` (new — faint brass-ring outline, no title/monogram).
- **Migration**: existing users on v0.5.51-v0.5.58 don't have `pref_cover_style`, so the absent-key read resolves to Monogram on first launch — the old covers come back automatically.

### Under the hood
- **7 parallel agents in worktrees** (cap policy validated): polish-bundle-fixer, gapless-investigator, crash-fixer, ux-polish-bundle, phone-narrow-and-safety, cover-style-toggle, audio-out-diagnostic. One ran out of usage tokens mid-task; recovered by resuming the in-progress worktree.
- **~50 new tests** across the 7 PRs.
- **CI workflow `cp` self-copy bug** caught + fixed (`cp $SRC $DST` errored exit 1 when AGP's variant-rename already produced the tag-named file). v0.5.58's GitHub release publish had been silently broken until this; retag fixed it.

## [0.5.58] — 2026-05-15

A serious bug pass after v0.5.57. JP's on-device testing on the Z Flip 3 surfaced that **storyvox never called `requestAudioFocus()`** — `dumpsys audio` showed the focus stack empty for the storyvox pid. On Samsung's strict audio policy the AudioTrack would silently park while MediaSession reported PLAYING; this is the master bug behind every "silent stuck state" observation. Plus three parallel concerns: real release variant, chips visible on phone, slider → chip transition.

### Added — Real `release` build variant (#562)
- **`release` is now the primary shipped build type** — copies the prior `debug` block's settings (R8 minification, proguard rules, checked-in debug keystore for sideload continuity, Baseline Profile bundling).
- **`BuildConfig.DEBUG` is `false` in shipped APKs**, pinned by a new `ReleaseBuildConfigTest`. Demoted `debug` to non-minified developer-only variant for `./gradlew installDebug` + debugger attach.
- **APK filename**: `applicationVariants.all` block writes `storyvox-v<versionName>.apk` for release + benchmark. `./gradlew :app:assembleRelease` produces `app/build/outputs/apk/release/storyvox-v0.5.58.apk` matching the published GitHub release artifact name.
- **CI**: `.github/workflows/android.yml` runs `:app:assembleRelease`; check renamed `build-debug` → `build`, `Build debug APK` → `Build APK`; main-branch protection updated.
- **Debug overlay gate** stays user-controllable — `HybridReaderScreen.kt:96` simplified to `val debugOverlayVisible = debugEnabled` (no `&& BuildConfig.DEBUG`). The Settings → Advanced → `pref_show_debug_overlay` toggle is the sole gate, default `false`. JP requested keeping it accessible (`"no we do want the debug overlay. settings still"`).
- **No `applicationId` change** — stays `in.jphe.storyvox` for sideload continuity. No keystore change.

### Added — Chips visible on phone + slider → chip across the player (#571)
- **PlayerQuickChips not rendering on phone fixed** — root cause was `AudiobookView`'s player Column using `Modifier.fillMaxSize()` with no scroll. ~820 dp of content fit the tablet's ~1006 dp viewport but overflowed the phone's ~880 dp; with unbounded children laying out top-to-bottom and `Arrangement.spacedBy`, the chip rows measured to zero height (uiautomator on R5CRB0W66MK confirmed `bounds=[0,0][0,0]` for the chip row pre-fix). Fix: `verticalScroll(rememberScrollState())` with modifier order `fillMaxWidth().verticalScroll().padding()` (NOT `fillMaxSize().verticalScroll()` — that clashes between "fill parent height" and "vertical is unbounded", silently zeroing children even with scroll present).
- **Slider → chip presets in `VoiceQuickSheet`** — JP request: "i want chips instead of sliders for the voices speed in and pitch, and also for the voice performance sliders":
  - **Speed** chip row reuses existing `SPEED_PRESETS` (`0.75× / 1× / 1.25× / 1.5× / 2×`)
  - **Pitch** chip row uses new `PITCH_PRESETS` (`0.7× / 0.85× / 1× / 1.15× / 1.3×`) — 0.15 spacing above the perceptible pitch JND
  - **Sentence silence** chip row uses new `PUNCTUATION_PAUSE_PRESETS` (`0× / 0.5× / 1× / 2× / 3×`) — covers the practical range; 4× via "Custom…"
  - Each row keeps the slider behind a "Custom…" chip + `AnimatedVisibility` so power users keep continuous tuning. Chip taps invoke the same `onSetX` / `onPersistX` callbacks the slider used — pref keys unchanged so user settings survive the migration.
- **16 new tests** in `VoiceQuickSheetChipPresetsTest` pin preset sets, selection epsilons, format-label contracts, the `1×` neutral anchor invariant.

### Fixed — Audio focus + stuck states + debug snapshot + Resume card (#572)
- **#560 — Silent "playing but no audio" fixed at the root** — storyvox NEVER called `requestAudioFocus()`. On Samsung's strict audio policy this silently parks AudioTrack at the framework level while MediaSession claims PLAYING. New `AudioFocusController` (`@Singleton`, `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC`) acquired in `startPlaybackPipeline`, abandoned on stop/release. Focus loss (interruption by phone call, alarm, etc.) routes to `controller.pause()`. Also wired `isBuffering` → `Player.STATE_BUFFERING` in MediaSession's `getState()`. Verified: `dumpsys audio | grep storyvox` shows the focus request firing at the resume tap.
- **#561 — Debug overlay blank/wrong fields fixed** — `PlaybackState.voiceId` was never written; `loadedVoiceId` was an internal-only field; `displayEngineName(null)` returned `"—"`. The engine now emits `voiceId = active.id` into `_observableState` after voice load. `isWarmingUp` rewired to track the controller's canonical `engineState` so the overlay no longer false-positives during chapter transitions. Verified: overlay shows `name = piper_lessac_en_US_low`, `voice = piper_lessac_en_US_low`, `sentence = #9 @ 484`.
- **#563 — Resume card stale after auto-advance fixed (reframed from "wrong-chapter cache")** — original hypothesis (cache scheduler targeting wrong chapter) was wrong per the phone audit. The actual bug: `persistPosition()` in `EnginePlayer.advanceChapter` was getting skipped because the buffering watchdog's cooperative cancellation landed mid-suspend, so the persisted resume-chapter-id stayed at the pre-advance chapter. Fix: wrap `persistPosition` in `NonCancellable`. Bonus: `PrerenderTriggers.onLibraryAdded` now anchors its 3-chapter pre-render window on `resumeIndex + 1` instead of always chapter 0.
- **#567 — Watchdog `CancellationException` log noise suppressed** — when a state transition cleanly cancels the in-flight watchdog `runCatching { advanceChapter() }`, the `.onFailure` was logging `u0 was cancelled` as if it were a real error. Filter out `CancellationException` in the failure path.
- **Three pre-existing watchdog tests updated** to match v0.5.57's `BUFFERING_STUCK_WATCHDOG_MS` (8 s → 1.5 s).

### Under the hood
- 4 parallel agents in worktrees this session: build-cleanup-fixer, chip-ui-fixer, stuck-state-fixer, phone-extensive-tester (read-only QA, filed 10 issues with hard `dumpsys` evidence).
- ~880 lines of new + modified code across the 4 PRs, plus ~700 lines of tests. No cross-file conflicts on merge — file ownership was negotiated upfront in agent prompts.

### Known limitations (next pass)
- **#564 AudioTrack churn on phone**: 8 tracks in 9 min, ~1 s rebuild per transport tap. PR #552's "park AudioTrack" doesn't stick on Samsung's strict audio policy. The new `AudioFocusController` may help indirectly (no more focus-loss teardowns), but the underlying churn likely needs a separate fix.
- **#566 Scrubber 16s drift in 4s wall during warming**: the position math reads `AudioTrack.playbackHeadPosition` during the warming state when no PCM is yet queued; pin behavior to `pipelineStartCharOffset` until first sentence emit.
- **#568 Cover-tap pause -6 s on phone**: `pinnedPausePositionMs` from PR #556 isn't working on phone; needs investigation.

## [0.5.57] — 2026-05-15

The Spotify-grade gap fix: watchdog threshold dropped from 8 s to 1.5 s so the auto-advance "safety net" path is essentially imperceptible.

### Changed
- **`BUFFERING_STUCK_WATCHDOG_MS`: 8000 → 1500** — On-device testing revealed the engine-side primary advance (`handleChapterDone → advanceChapter`) silently fails to fire at natural chapter end on Notion sources. The watchdog in `PlaybackController` is currently the *only* working advance path on that backend. 8 s was chosen conservatively when the watchdog was intended as belt-and-suspenders; now that it's the primary path, the gap was too noticeable. 1.5 s is short enough to feel near-instant but still wide enough to avoid false-positives on legitimate chapter-boundary buffer spinners. `advanceChapter` itself still has a 30 s internal timeout on chapter-body wait, so a false-trigger is harmless — it just waits on the same future the engine-side advance would have.

### Known limitation (next pass)
- The engine-side `handleChapterDone` never fires on Notion chapter end. Either `naturalEnd` reads false at the end-of-pcm moment, or `pipelineRunning.get()` is already false by the time the consumer-thread check runs. The 1.5 s watchdog masks this; the next iteration should root-cause the primary path so auto-advance happens within ~50 ms of chapter end (true gapless).

## [0.5.56] — 2026-05-15

Tiny but critical fix to v0.5.55: the auto-advance watchdog from #556 was firing correctly but throwing `Player is accessed on the wrong thread` when it tried to dispatch the fallback advance. Caught by JP's on-device re-verification right after v0.5.55 shipped.

### Fixed
- **#553 follow-up — auto-advance watchdog wrong-thread crash** — Media3's `Player` object is thread-confined to the application's Main looper. `PlaybackController.runBufferingWatchdog` was launching on `scope` (which uses `Dispatchers.Default`) and calling `p.advanceChapter(1)` directly, which throws "Player is accessed on the wrong thread" deep inside the player call chain. Fix: wrap the watchdog's fallback dispatch in `withContext(Dispatchers.Main)` — same dispatcher the manual skip-next button uses via `ReaderViewModel.viewModelScope`. **On-device**: chapter 01→02 auto-advance now actually fires after the 8 s watchdog window, ~70 ms from watchdog-fire to next-chapter `loadAndPlay`. The 8 s threshold itself is the next thing to shrink for true Spotify-grade transitions.

### Why this slipped v0.5.55's verification
The agent ran a JVM unit test for the watchdog timing logic which doesn't exercise the real Media3 player thread-confinement. The on-device test path the agent measured (~340 ms) hit a different branch — chapter body already pre-rendered, engine-side advance ran on the Main thread directly. The watchdog branch only fires when the engine-side primary advance silently fails to trigger; that branch was never exercised on-device until now.

## [0.5.55] — 2026-05-15

Three residual smoothness fixes caught on R83W80CAFZB after v0.5.54 landed. Auto-advance, pause-position pinning, and speed-change position stability — the last three gaps between storyvox and Spotify/Apple Music-grade playback.

### Fixed — Playback smoothness follow-up (#556, closes #553 #554 #555)
- **#553 Auto-advance no longer stalls in Buffering forever** — `handleChapterDone → advanceChapter → loadAndPlay` had two failure modes: indefinite `observeChapter().filterNotNull().first()` park when the next chapter body wasn't downloaded yet, and uncaught throws in pre-advance Room calls that stranded the pipeline with `isBuffering=true`. Fix: `withTimeoutOrNull(30s)` on the chapter-body wait + `runCatching` per step. Plus a belt-and-suspenders buffering-stuck watchdog in `PlaybackController` that fires `advanceChapter(1)` after 8s of stuck state — the same call path the manual skip-next button uses (verified working). **On-device**: chapter 01→02 auto-advances in ~340ms.
- **#554 Cover-tap pause no longer regresses position** — `AudioTrack.playbackHeadPosition` can glitch on the pause edge, occasionally returning a stale frame counter. Fix: `pauseTts/pauseRouted` snapshot `currentPositionMs()` into `pinnedPausePositionMs`; `currentPositionMs()` returns it verbatim while paused. Position cannot regress by construction. **On-device**: 2:36 → 2:34 → 2:34 stable across 3 s of pause.
- **#555 Speed change no longer jumps position backwards** — both `EnginePlayer.estimateDurationMs` and `AppBindings.toUi` used speed-aware `charsPerSec = BASELINE × speed`, so a speed tap relabeled the axis; compounded by `state.charOffset` lagging the audible head because consumer-thread updates queue behind Main. Fix: both position and duration now live on the speed-invariant media-time (speed-1) axis; `setSpeed` captures truthful audible position via `currentPositionMs()` BEFORE the rebuild, writes it into both `speedHandoffCharOffset` (engine) and `state.charOffset` (UI anchor); `AppBindings.toUi` scales wall-elapsed by speed. **On-device**: 1:35 (1×) → tap 1.5× → 1:41 forward motion only (pre-fix: ~0:55 visible backwards jump).

### Why this slipped v0.5.54
v0.5.54's PR #552 fixed the visible *state* (Buffering subtitle) on chapter end but didn't actually wire the auto-advance trigger to the chapter-load mechanism. The on-device verification — booted on the tablet, played a Notion chapter to natural end — surfaced the gap that unit tests missed.

### Under the hood
- 10 new tests in `PlaybackSmoothnessFollowupTest.kt` covering watchdog timing + speed-invariant axis math. 229+ tests pass.
- The watchdog architecture is intentionally defensive: the engine has its own 30 s timeout on chapter-body await, and the controller has an independent 8 s watchdog that fires the manual-skip path. Belt-and-suspenders — if either layer succeeds, auto-advance works.

## [0.5.54] — 2026-05-15

Spotify-grade playback smoothness pass. Two parallel agents, two clusters of fixes covering everything the on-device audit found in the player surface, plus a magical new launcher icon.

### Added — Magical Library Nocturne launcher icon
- Adaptive icon redrawn: layered hearth-glow + candlelight bloom + constellation stars in the background; gradient-filled book pages, fine gilt text-rules, gradient sound-wave arcs, candle flame at the apex inside the 72 dp safe zone, floating brass embers. Still pure `VectorDrawable` — crisp at every density, zero APK size impact.

### Fixed — Audio fidelity (#552, closes #524 #530 #531 #536 #539 #540 #543 #550)
- **#539 Scrubber drift fixed at the source** — `EnginePlayer.currentPositionMs()` now derives position from `AudioTrack.playbackHeadPosition + pipelineStartCharOffset` instead of wall-clock interpolation. `PlaybackController.playbackPositionMs: StateFlow<Long>` polls at 100 ms. The scrubber is now the truth, not a guess.
- **#540 Pause→Resume 2.5 s rebuild gone** — `pauseTts()` parks the AudioTrack (`track.pause()` + `userPaused.set(true)`); `resume()` re-kicks via `track.play()`. Consumer thread polls the flag in 50 ms increments. Target ~150 ms vs the pre-fix 2.5 s.
- **#524 Auto-advance reliable** — `advanceChapter` flips `isPlaying=false` on book-end + sets `isBuffering=true` while awaiting the next chapter's body; controller flips `_completed` on `BookFinished` so engineState rolls to `Completed`.
- **#530 ChapterFetchFailed no longer silent** — `loadAndPlay` clears current chapter state on fetch failure; new `EngineState.Error(msg, retryable)` surfaces it. No more routing back to the previous chapter.
- **#531 Seek tap 5 % offset gone** — new `PlaybackController.seekToPositionMs(ms)` uses the same speed-aware `baseline * speed` formula as `estimateDurationMs`. Roundtrip drift ≤ 100 ms verified by test.
- **#536 MediaSession position=0 transient gone** — `setContentPositionMs` now routes through `currentPositionMs()` which has a monotonic latch (`lastTruthfulPositionMs`) that never regresses within a chapter.
- **#543 Voice warming indicator** — new `EngineState.Warming(message)` flips true the moment `play()` is invoked with a render-ready label ("Warming Brian…"); cleared on first sentence range emit. `PlaybackController.prewarmEngine()` hook for Library to anticipate.
- **#550 Skip ±30 s consistent** — root cause was wall-clock interpolation racing the pipeline-rebuild during seek; resolved by #539's truthful position feed.

### Fixed — Player UX polish (#551, closes #525 #526 #527 #529 #537 #543 (UI))
- **#525 Cover-tap no longer silent** — tap captures `isPlaying` at the moment of tap, fades a Play/Pause icon overlay for 400 ms so the user sees what happened. Long-press still unbound for chapter actions.
- **#526 Play/Pause button no longer jumps 25 dp** — fixed 96 dp host `Box` + `Crossfade` icon vector inside `FilledIconButton`. Zero layout shift between buffering and playing states.
- **#527 Speed / sleep timer / voice picker reachable from the player** — new `PlayerQuickChips` row under transport: 5 speed presets (0.75× / 1.0× / 1.25× / 1.5× / 2.0×), 6 sleep-timer presets (off / 5 / 15 / 30 / 60 min / end of chapter), voice quick-switch chip linking to Voice Library.
- **#529 Debug overlay gated** — `:feature` gains `buildConfig = true`; `HybridReaderScreen` gates the overlay on `BuildConfig.DEBUG && pref_show_debug_overlay`. Verified absent from the release variant.
- **#537 Engine state semantic** — `pipelineStateText` maps paused → "paused", truly idle → "" (empty). No more "idle" label while audio is paused.
- **#543 (UI) Voice warming surface** — `warmingMessageForVoice(voiceLabel)` derives the speaker name (Brian/Ava/Amy/Lessac/Ryan/…); surfaced as "Warming Brian…" in the player subtitle the moment the engine cold-starts.

### Under the hood
- **45 new tests** across the bundle: `PlaybackControllerSkipSeekTest` (8), `EngineStateDerivationTest` (8), `AudiobookViewTextTest`, `AudiobookViewLayoutTest`, `PlayerQuickChipsTest`, `DebugOverlayStateLabelTest`, `DebugOverlayReleaseGateTest`.
- **Two-agent contract pattern**: `audio-fidelity-fixer` published `EngineState` + the position/state flows; `player-ux-polish` consumed them in parallel via a temporary derived helper. The two agents never touched each other's files; merge was conflict-free.

## [0.5.53] — 2026-05-15

Hotfix release. v0.5.52 ships a launch-crash regression introduced in PR #520 (Royal Road tag sync); v0.5.53 fixes it.

### Fixed
- **Cold-launch crash on devices with persisted state (#522)** — `FollowedTagsStoreImpl` declared `preferencesDataStore(name = "storyvox_settings")`, a duplicate of the delegate already declared in `SettingsRepositoryUiImpl`. Android's `preferencesDataStore` enforces "one delegate per name per process" and throws `IllegalStateException` when the second is accessed. The fresh-install audit on R5CRB0W66MK didn't catch this because no prior state had touched the conflicting code path; the R83W80CAFZB audit reproduced it on every cold launch.
- **WorkManager SystemForegroundService manifest type (#522 follow-on)** — PR #520 added `FOREGROUND_SERVICE_DATA_SYNC` permission but didn't override WorkManager's merged `SystemForegroundService` with an explicit `android:foregroundServiceType="dataSync"` attribute. Android 14+ requires the type to be declared on the service. Added a manifest override with `tools:replace`.

### Why this slipped
Unit tests don't initialize the Android Application context that triggers both DataStore lazy delegates, so CI was green. The `feedback_install_test_each_iteration` memory is exactly what should have caught this — and did, on the post-merge tablet audit.

## [0.5.52] — 2026-05-15

The four-parallel-agent bundle — AO3 personal subscriptions, Royal Road two-way tag sync, Discord-as-fiction wiring, and an Emergency Help card surfacing all three crisis numbers together.

### Added — Emergency Help card on TechEmpower Home (#516)
- **5th brass-edged card** on TechEmpower Home titled "Emergency Help" — three brass-edged sub-buttons stacked **988** (Suicide & Crisis Lifeline), **211** (United Way social services), **911** (Emergency dispatch). Each sub-button dials via `Intent.ACTION_DIAL` with `tel:<n>` (not `ACTION_CALL` — no `CALL_PHONE` permission, no accidental dispatch). Card-level click is intentionally inert.
- **Visual ranking** is 988 → 211 → 911 (most-likely first-response on top, true-emergency last but discoverable) — not numeric order.
- **`EMERGENCY_DISPATCH_NUMBER = "911"`** added to `TechEmpowerLinks` alongside existing `CRISIS_HELP_NUMBER` (988) and `PRIMARY_HELP_NUMBER` (211).
- Drops the now-redundant "long-press for 988" hint from the existing Call 211 card body. Top-app-bar phone icon (tap=211, long-press=988) untouched — both surfaces coexist per spec.
- **`EmergencyHelpCardContractTest`** (7 cases) pins the three constants, `telUri` shape (no `tel://` double-slash that breaks Samsung's stock dialer), distinctness (so a copy-paste regression can't route 911 taps to 988), and that the host composable still loads by reflection.
- **US-only v1.** CA/UK/AU localization queued for v2.

### Added — Discord channel-as-fiction wiring for TechEmpower (#517)
- **Auto-seed** the TechEmpower peer-support Discord channel (`1504888494206484531`) as a default-pinned fiction when the Discord backend is enabled. `DiscordConfigState` gains `pinnedChannelIds` + `techempowerDefaultsEnabled` (both default ON).
- **`DiscordSource.popular()`** now prepends pinned channels with `author="TechEmpower"` *even before the user picks a server* — Discord's `/channels/{id}/messages` is channel-scoped, so a serverless seed renders end-to-end. `fictionDetail()` + `chapter()` paths relax the `serverId` gate for pinned-channel ids.
- **Bot-token UX (option b)** — documented in `DiscordConfig` kdoc: users supply their own Discord bot via discord.com/developers OR accept a server-published bot invite. **No TechEmpower-owned credentials shipped.** Empty-state when bot isn't invited.
- **Tests** — 4 new cases in `DiscordTechEmpowerSeedTest` pin the channel id to JP's captured value, fresh-install defaults (toggle ON + seed in list), and the opt-out state shape.

### Added — Royal Road two-way tag sync (#178)
- **`RoyalRoadTagSyncCoordinator`** mirrors RR's "Saved tags" filter ↔ storyvox's follow-filter store. Triggers: (1) immediate first-sync on the Anonymous→Authenticated edge in `StoryvoxApp.onCreate`, (2) 24h `WorkManager` periodic with `CONNECTED` + `RequiresBatteryNotLow` constraints, (3) "Sync now" brass button in **Settings → Account → Royal Road tag sync**.
- **RR endpoints** — GET `/fictions/search?globalFilters=true` (read — parses `button.search-tag.selected` + `__RequestVerificationToken`) and POST `/fictions/search` with `tagsAdd=` repeated + `saveAsFilter=true` (write). Live-probed 2026-05-15. Writer treats 4xx other than 401 as "endpoint shape changed, retry next 24h" since RR doesn't publish a documented API.
- **`TagSyncMerge`** — pure-Kotlin merge so it's testable against a fixed clock. Union-of-adds, last-write-wins-on-removes, with a 5-minute prefer-local window (`PREFER_LOCAL_WINDOW_MS`).
- **DataStore schema** — `pref_rr_tag_sync_enabled` (Boolean, default ON when signed in), `pref_rr_tag_sync_last_synced_at` (Long epoch ms). Both round-trip across devices via the `:core-sync` allowlist.
- **Settings UI** — new `RoyalRoadTagSyncRow` showing "Last synced with RR: \<relative\>", a Switch, and a "Sync now" brass button. Failures render a dim status line, never a toast (#178 spec: "we don't surface them").
- **Tests** — 12 new cases: 6 in `TagSyncMergeTest` (union, tombstone-newer-than-last-sync, 5-min-window-wins, stale-tombstone-loses, empty, identical-noop), 6 in `SavedTagsParserTest` (selected-class, aria-pressed, unauthed-redirect, inline-login-form, empty-selected, missing-CSRF).

### Added — AO3 WebView login + personal subscriptions (#426 PR2)
- **`Ao3AuthSource`** in `:source-ao3/auth/` — contributes the AO3 sign-in URL (`/users/login`), identity cookie name (`_otwarchive_session` — AO3 runs on Rails), and cookie host (`archiveofourown.org`) into the cross-source `Map<String, AuthSource>` introduced by PR1. Hilt-bound `@IntoMap @StringKey(AO3)`.
- **`Ao3AuthWebView`** composable — mirrors `RoyalRoadAuthWebView`. Watches both page-start and page-finish for the identity cookie, scrapes the AO3 username from the post-login redirect URL (`/users/<username>`) and threads it through the capture map under the `__storyvox_user` pseudo-cookie key. Reserved AO3 routes (`/users/login`, `/users/logout`, etc.) are rejected as username candidates.
- **`Ao3CookieJar` + `Ao3SessionHydrator`** — per-source OkHttp jar keyed on `archiveofourown.org`. The session hydrator is contributed `@IntoMap @StringKey(AO3)` into a new cross-source `Map<String, SessionHydrator>` so `AuthViewModel.captureCookies(sourceId, ...)` routes captured cookies to the right jar by sourceId. Legacy single-binding `SessionHydrator` still resolves to RR for non-AuthViewModel callers (StoryvoxApp's eager hydration, Settings sign-out).
- **`Ao3Api.subscriptions(username, page)` + `Ao3Api.markedForLater(username, page)`** — authed GET wrappers around `/users/<u>/subscriptions` and `/users/<u>/readings?show=marked`. Detects AO3's "login form returned instead of content" response (Rails 302 → login) and surfaces it as `FictionResult.AuthRequired`. Bodies parsed via Jsoup.
- **`Ao3WorksIndexParser`** — Jsoup parser for AO3's shared `<ol class="work index group">` HTML shape. Extracts work id, title, author (co-author-aware), summary, freeform + fandom tags, completed/ongoing status, chapter count. Tolerant of empty-state notices and malformed cards (silently dropped).
- **`Ao3AuthedSource`** public interface — surfaces `subscriptions(page)` + `markedForLater(page)` to the app-module Browse adapter, mirroring `GitHubAuthedSource`'s shape. `Ao3Source` implements it; routes through the captured username from `auth_cookie.userId`.
- **Browse → AO3 chip strip** gains **Subscribed** and **Marked** tabs when the user has an AO3 session. Signed-out users see a compact "Sign in to AO3" banner above the Popular / New / Search listing; the auth-only tabs are hidden until sign-in completes. On AO3 sign-out, the chip strip automatically falls back to Popular (mirror of the GitHub sign-out tear-down).
- **`AuthWebViewScreen` generalized** — takes a `sourceId` arg now; dispatches to `RoyalRoadAuthWebView` or `Ao3AuthWebView` based on the captured route param. `StoryvoxRoutes.AUTH_WEBVIEW` is now `auth/webview/{sourceId}`; a `StoryvoxRoutes.authWebView(sourceId)` helper centralizes the path encoding. All seven existing call sites updated to `authWebView(SourceIds.ROYAL_ROAD)` — bit-identical RR behavior. New AO3 sign-in entry point at `Browse → AO3 chip → Sign in to AO3`.
- **`AuthViewModel.captureCookies(sourceId)`** — drops the `TODO(PR2 / #426)` placeholder. Splits the AO3 username pseudo-cookie out of the capture map before building the HTTP cookie header, persists it into `auth_cookie.userId` for the AO3 row, and routes the cookie hydration through the per-source `Map<String, SessionHydrator>`. RR sign-in flow stays bit-identical via the parameter default.
- **Tests** — 8 new `Ao3WorksIndexParserTest` cases covering single-card / multi-card / co-authored / tagged / paginated / empty-state / malformed-card / no-li-id / chapter-count / no-work-ref fixtures. 11 new `Ao3AuthSourceTest` cases pinning the four AuthSource field literals + `extractUsernameFromUrl` happy paths and reserved-route rejection. 4 new `Ao3TagUrlTest` cases pinning the subscriptions / Marked-for-Later URL shape + `looksLikeLogin` heuristic.

## [0.5.51] — 2026-05-15

The six-parallel-agent release — TechEmpower becomes the default use case, plus two new chat backends, the home-screen widget, AO3 auth scaffolding, and beautiful book covers.

### Added — TechEmpower as default use case (#511, closes #500)
- **Brass-edged TechEmpower hero card** pinned at the top of Library (above ResumeCard, above empty-library hint). Sun-disk + "TechEmpower" eyebrow + mission tagline + CTA. Tap → dedicated TechEmpower Home screen.
- **TechEmpower Home screen** at `StoryvoxRoutes.TECHEMPOWER_HOME` — 96dp sun-disk hero, mission tagline, 501(c)(3) line, 4 brass-edged cards: **Browse Resources** (loads TechEmpower Notion content) · **Peer Support Discord** (`https://discord.gg/j3SVttxw7k`, deep-links to Discord app first, browser fallback) · **Call 211** (tel: intent, long-press for 988 Suicide & Crisis Lifeline) · **About** (mission + Donate + email). 8-chip "Featured guides" strip below.
- **Top-app-bar emergency icons** on Library + TechEmpower Home — phone icon (tap=211, long-press=988), Discord icon. One-tap reach from any home surface.
- **README repositioned** — leads with "storyvox is TechEmpower's accessible resource app" framing, engineering capability description second. Slack release template gets a TechEmpower italic line.
- App identity stays `in.jphe.storyvox` (load-bearing for sideload continuity); aesthetic stays Library Nocturne; TechEmpower is the content+mission identity layered on top per JP's "option B with strong in-app presence" call.

### Added — Two new chat backends (20th + 21st fiction backends)
- **`:source-slack`** (#512, closes #454) — channels-as-fictions via Bot User OAuth Token (xoxb-…). `conversations.list` for channel discovery, `conversations.history` with cursor pagination capped 5 × 200 = ~1000 messages per fiction. Same-sender coalescing parity with Discord. `supportsSearch=false` in v1 (some workspace plans restrict `search.messages`). Magic-link claim regex `^https?://(?:[\w-]+\.)?slack\.com/archives/([A-Z][A-Z0-9]+)(/p\d+)?(?:\?.*)?$` at confidence 0.9.
- **`:source-matrix`** (#513, closes #457) — rooms-as-fictions via Matrix Client-Server API v3. Federated (matrix.org, kde.org, fosdem.org, self-hosted Synapse / Dendrite / Conduit). `whoami` + `joined_rooms` + `messages?dir=b` paginated. Same-sender coalescing slider (1-30 min). Federation v1 routes every call through the configured homeserver; multi-host dispatch deferred. E2EE explicitly out of scope.

### Added — Adaptive home-screen widget (#510, closes #159)
- **`NowPlayingWidgetProvider`** with three RemoteViews buckets selected at render time: 1×1 cover-only, 4×1 cover + title + Play/Pause + Next, 4×2 cover + title + chapter + transport + Sleep + remaining chip. `resizeMode=horizontal|vertical`.
- Transport buttons dispatch via `PendingIntent` → `runSafely` → singleton `PlaybackController` (`togglePlayPause` / `nextChapter` / `toggleSleepTimer`).
- `WidgetStateObserver` collects `PlaybackController.state` narrow projection (book/chapter titles, isPlaying, cover URI, ids, sleep-timer rounded to seconds) with `distinctUntilChanged` so sentence-range churn doesn't pummel the widget host.
- Material You: `dynamicLightColorScheme()` on Android 12+, fallback to brass palette pre-31. R8 keep rule added for `NowPlayingWidgetProvider` + `WidgetEntryPoint`.

### Added — AO3 auth PR1 of #426 (#509)
- **`AuthRepository` generalized** to support multi-source sign-in. New `AuthSource` interface (`sourceId`, `signInUrl`, `identityCookieName`, `cookieHost`); per-source `sessionState(sourceId)` flow accessor; legacy `sessionState` field still returns the RR flow for back-compat.
- `AuthRepositoryImpl` now maintains a `ConcurrentHashMap<String, MutableStateFlow<SessionState>>` and scans `cookie:*` keys at init so any persisted source hydrates on cold start.
- `RoyalRoadAuthSource` contributed via Hilt `@IntoMap @StringKey(ROYAL_ROAD)` — no behavior change to RR sign-in. PR2 (AO3 WebView + 'My Subscriptions' tab) plugs into the new `TODO(PR2 / #426)` placeholder in `AuthViewModel.captureCookies`.
- 9 new tests in `AuthRepositoryImplTest` (hand-rolled fakes — no Robolectric).

### Added — Beautiful book covers for Notion-sourced fictions (#514)
- **`BrandedCoverTile`** in `:core-ui` — pure-Compose 2:3 synthetic book cover. Warm Library Nocturne vertical gradient, family watermark (TechEmpower sun-disk drawn as concentric brass circles + 8 radiating rays, or generic medallion) at ~30% alpha upper-left, title centered in EB Garamond max 3 lines, brass `TECHEMPOWER`-style author line below in Inter letterspaced uppercase, 1.5dp brass-400 border. Pure-composable: no network, no bundled drawable; survives Coil S3-URL expiry by being the very thing the error slot falls back to.
- **Fallback cascade in `FictionCoverThumb`**: `SubcomposeAsyncImage` with `ContentScale.Crop` (handles Notion's 5:1 banner aspect by center-cropping) → on null/error AND title non-blank → `BrandedCoverTile` with `coverSourceFamilyFor(sourceId)` (notion → TechEmpower, else → Generic) → title blank → existing `MonogramSigilTile` as third-tier fallback.
- **`readBodyImageUrl` in `:source-notion`** — walks the first 12 live content blocks for the first `image` block when `format.page_cover` is unset (most TechEmpower pages). Prefers `format.display_source` over `properties.source`. Cache-aggressive at the `:source-notion` layer.
- Font substitution note: Fraunces / DM Sans aren't bundled in storyvox; tile uses already-bundled EB Garamond + Inter. JP can decide whether to license + bundle for a future visual-coherence pass.

### Closed issues
- **#86** — PCM cache PR series (all 8 PRs shipped in v0.5.47-v0.5.49)
- **#487** — a11y rendered-surface audit narrative (shipped as `docs/accessibility.md` in v0.5.43)
- **#477** — WCAG contrast failures (palette tweaks shipped in v0.5.43)

### Under the hood
- **20 → 21 fiction backends** (Slack + Matrix join). Plugin-seam KSP-driven registration is the path of least resistance for new backends.
- 6 parallel agents in isolated worktrees shipped this release in one wall-clock pass. Notion-covers + Matrix both needed rebase-conflict resolution at merge time (the cost of parallel-merge cadence on overlapping files).
- New `pref_techempower_home_seen` boolean DataStore key on the InstantDB sync allowlist (future onboarding-nudge seam).
- New `pref_source_slack_token`, `pref_source_matrix_token` on the InstantDB secrets allowlist — sync cross-device alongside Discord/Notion tokens.

## [0.5.50] — 2026-05-15

### Changed
- **Playing leads the bottom dock** (`a8d8eb0`) — JP final on 2026-05-15. New dock order: `{Playing, Library, Voices, Settings}` (was `{Library, Playing, Voices, Settings}` since v0.5.48). Playing is the most-touched destination during a listening session and now sits in the leftmost cell. The NavHost startDestination is independent of HomeTab's ordinal — cold-launch still lands on Library, the dock just shifts which pill is leftmost.

### Out-of-app (no APK impact, captured here for the trail)
- **Tablet OS gestures restored** — Tab A7 Lite was missing swipe-up-home and long-press-up-recents. Root cause: Samsung Android 14 SystemUI hardcodes its QuickStep binding to `com.sec.android.app.launcher/.globalgesture.TouchInteractionService` (the Samsung One UI Launcher's path). Third-party launchers can register `QUICKSTEP_SERVICE` but Samsung SystemUI silently ignores them. The Samsung One UI Launcher had been uninstalled-for-user but was still on the system partition; restored via `adb shell cmd package install-existing com.sec.android.app.launcher`, set as HOME role, nav-mode toggle rebinds. Captured in `feedback_samsung_launcher_gestures` memory so future debugging skips the launcher-shuffle.
- **TalkBack installed on both devices** — Android Accessibility Suite (`com.google.android.marvin.talkback`) now on R83W80CAFZB + R5CRB0W66MK. The v0.5.41 audit had treated "not installed by default on Samsung tablets" as "cannot install" and substituted uiautomator-XML inspection; this was wrong. Future TalkBack swipe-through audits can run on either device. Captured in `feedback_talkback_installable` memory.

### Awaiting (deferred for separate ship)
- **PR #491** by `adminlip` (first external PR — accessibility-label improvements) sits open. The diff is good but it's bot-authored; JP deferred the merge decision.

## [0.5.49] — 2026-05-15

The five-parallel-agent release. Largest bundle of the session — six features land at once.

### Added — Magical InstantDB sign-in surface (#507, closes #500)
- The v0.5.39 cross-device sync infrastructure finally gets a discoverable Library-Nocturne-coded UI. **First-launch passphrase flow**: brass-edged dialog at NavHost root, gated on `(activeVoice != null && signedIn == null && !dismissed)` so it lands AFTER VoicePickerGate; EB Garamond word-by-word reveal (`candlelight · brass · vellum · starlight`); skip persists permanently.
- **Permanent brass cloud icon** in `LibraryScreen.CenterAlignedTopAppBar` — three states via `SyncStatusViewModel.deriveIndicator()`: `CloudDone` (signed in), `Cloud + MagicSpinner` (syncing), `CloudQueue` (signed out). Tap → ModalBottomSheet with signed-out CTA or signed-in per-domain status grid.
- 15 new tests across `SyncStatusViewModelTest`, `SyncOnboardingViewModelTest`, `PassphraseVisualizerTest`.

### Added — Palace Project library backend (#504, partial close #502)
- **19th fiction backend.** New `:source-palace` Gradle module. OPDS 1.x Atom feed parser walks `<library>.palaceproject.io` catalogs. Free / non-DRM titles route through the existing `:source-epub` pipeline. DRM'd titles surface in catalog with `FictionStatus.STUB` + `AuthRequired("Open the Palace app to borrow …")` reader CTA. `pickOpenAccessEpub()` is the load-bearing DRM boundary — strictly excludes LCP license MIMEs. UrlMatcher claims `<library>.palaceproject.io/...` URLs through the magic-link cascade.
- Libby + Hoopla deferred with detailed scope docs in `scratch/libby-hoopla-palace-scope/` — Libby v1 will be audiobook-only (MP3 borrows via `:source-radio`), Hoopla v1 reverse-engineered HLS streaming.

### Added — Voice bundles in Plugin Manager (#505, closes #501)
- **Plugin Manager grows a fully-iterated Voice bundles section**. Settings → Plugins now lists every installed voice family as a brass-edged card alongside Fiction sources and Audio streams: Piper, Kokoro (~330 MB, 53 speakers), KittenTTS (~24 MB, 8 en_US speakers), Azure HD voices (BYOK cloud, surfaces "Configure →" CTA when no key set), and VoxSherpa upstreams placeholder. Same shape as Fiction-source cards: brass voice-glyph monogram, On/Off toggle, Local/Cloud/BYOK + live voice-count chips, tap-for-details modal, "Manage voices →" deep-link.
- New **`VoiceFamilyRegistry`** singleton in `:core-playback` with declarative metadata. Adding a new family is one descriptor entry + `EngineType.X` case.
- **Voice Library filter**: when a family is OFF the picker hides its voices across favourites, installed, and available buckets.

### Added — PCM cache PR F (#503, partial close #86)
- **WorkManager background pre-render of N+1 / N+2 chapters** while N plays. `PcmRenderScheduler` + `ChapterRenderJob @HiltWorker` with `setForeground` + `DATA_SYNC`. Holds `EngineMutex` per-sentence; handles Piper / Kokoro / Kitten; skips Azure (BYOK + per-character billing). `PlaybackModeConfig.fullPrerender` flow + `PrerenderTriggers` + `FictionLibraryListener` seam.
- `EngineMutex @Singleton` hoist (#11 race protection) — foreground playback and background render serialize on the same mutex.
- R8 keep rule for `ChapterRenderJob` (WorkManager's `WorkerFactory` looks up FQN reflectively).

### Added — PCM cache PR G — Settings UI (#508, partial close #86)
- **Settings → Performance gains three rows**: Full Pre-render switch (Mode C, default OFF), 4-tile BrassButton quota picker (500 MB / 2 GB / 5 GB / Unlimited), live "Currently used: X / Y" indicator + Clear-cache button with confirmation.
- `pref_full_prerender_v1` on the InstantDB sync allowlist (the per-fiction listening preference). Cache quota stays device-local because storage capacity varies between phone and tablet.

### Added — PCM cache PR H — status icons (#506, closes #86 series)
- **Brass `ChapterCacheBadge` icons in Fiction Detail chapter rows**: `Icons.Outlined.HourglassTop` (pre-rendering, alpha-pulse) and `Icons.Filled.Bolt` (complete). Per-voice "X MB cached" labels in Voice Library. "Clear fiction cache…" overflow-menu action with confirmation dialog.
- New `CacheStateInspector` (`:core-playback`, `@Singleton`) wraps `PcmCache.isComplete` / `metaFileFor` / `rootDirectory`. StateFlow plumbing from `FictionDetailViewModel.combine` lands `ChapterCacheState` on each `UiChapter`.

### The 8-PR PCM cache series is complete
| PR | Lands | What |
|---|---|---|
| A+B+C | #100 (2026-05-08) | Filesystem layer |
| D | v0.5.47 | Streaming-tee writer (cache accumulates) |
| E | v0.5.48 | Cache-hit playback (instant replays) |
| **F+G+H** | **v0.5.49 (this release)** | **Background pre-render + Settings UI + status icons** |

### Fixed
- **NavStructureTest pinned to v0.5.48's 4-tab dock** (`5c037d0`) — the test still asserted v0.5.40's 2-tab contract, failing on every build since v0.5.48. Now pins `[Library, Playing, Voices, Settings]`.

### Under the hood
- **5 parallel agents** in isolated worktrees + 1 hotfix on main shipped this release in ~3 hours of orchestrator wall-clock. The one cross-contamination incident (PR-G agent worked on main's repo by accident via bare `cd`) was caught + recovered without dropping work.
- 18 → 19 fiction backends. `:source-palace` joins the registry.
- `VoiceFamilyRegistry` (`:core-playback`) is the seed for future out-of-tree voice engines.
- `pref_full_prerender_v1`, `pref_sync_onboarding_dismissed` on the InstantDB sync allowlist.

## [0.5.48] — 2026-05-15

### Fixed
- **Bottom nav bar disappeared on Library / home surfaces** (`2efabe6`) — PR #475 (magic-link, v0.5.40) re-registered the LIBRARY route as `library?sharedUrl={sharedUrl}` to accept a system `ACTION_SEND` intent. But the `HOME_ROUTES` set still used the bare LIBRARY constant (`"library"`), and `currentBackStackEntryAsState().destination.route` returns the *full pattern* including the query template, so `route in HOME_ROUTES` always returned false on the Library home surface. Net: bottom nav vanished from the Library tab (by far the most-visited surface) — user could only switch to Settings via the gear icon or a deep-link. Reported by JP on both tablet + Flip3 v0.5.47. Fix strips the `?` suffix in `isHome()` so the substring before the nav-arg template matches the LIBRARY constant; verified on tablet post-fix with the Library + Settings cells visible at y=1239-1300, matching the v0.5.42-v0.5.46 layout exactly.

### Added — PCM cache PR E
- **Cache-hit playback via `CacheFileSource`** (#498, PR E of the PCM cache series, partial close [#86](https://github.com/techempower-org/storyvox/issues/86)). The user-perceptible win. When `PcmCache.isComplete(key)` returns true, `EnginePlayer.startPlaybackPipeline` opens a memory-mapped `CacheFileSource` (with RAF fallback for corrupt-cache `IOException`) instead of starting `EngineStreamingSource` + synthesis. Cache-hit replays start audio in <100 ms instead of the 2-5 s synthesis warm-up. Truncated PCM or corrupt manifest falls through to streaming with a `pcm-cache hit-open FAILED` logcat event. Partial-cache wipe-and-restart from PR D handles the in-progress edge.
- **15 new Robolectric contract tests** in `CacheFileSourceTest`: byte-equality on sequential read, trailing-silence propagation, seek edge cases (before-first, mid-range, past-last), truncated-pcm `IOException` fallthrough, `bufferHeadroomMs = Long.MAX_VALUE`, `producerQueueDepth/Capacity = 0`, close-releases-fd, `startSentenceIndex` resume + past-end, `finalizeCache` no-op + idempotency, sample-rate propagation, `SentenceRange` round-trip.
- **`PcmSource` interface gained `bufferHeadroomMs` + `finalizeCache()`** with safe defaults. Sealed interface, same module, no external impls so no break.
- **New logcat events** (tag `EnginePlayer`): `pcm-cache HIT chapter=… voice=… speed=… pitch=… fromSentence=… base=<sha12>` and the matching `MISS`. Primary verification surface now that `adb run-as` is gone post-v0.5.46's `isDebuggable=false`.

### Cache-rollup arc
- **PR D** (v0.5.47): cache files start accumulating on disk as you listen.
- **PR E** (this release): tap a chapter you've already heard → instant audio.
- **PR F** (open at #499, lands as v0.5.49): WorkManager background-render N+1 / N+2 chapters while N plays — so even *first-time* playback of the next chapter skips synthesis.
- **PR G**: Settings UI for cache quota + Mode C (full-fiction pre-render).
- **PR H**: status icons.

## [0.5.47] — 2026-05-15

### Added — PCM cache PR D
- **Streaming-tee writer wires PcmAppender into EngineStreamingSource** (#497, PR D of the PCM cache series, partial close [#86](https://github.com/techempower-org/storyvox/issues/86)). Synthesized PCM now gets tee'd to disk as it streams to the audio sink, ahead of the cache-hit-replay landing in PR E. Per-sentence tee in the serial producer path; tee-from-the-sequencer (not workers) in the parallel path so byte offsets stay monotonic even when workers complete out of order. New `EngineStreamingSource` ctor param `cacheAppender: PcmAppender?` (`@Volatile`-shadowed for null-on-abandon), `finalizeCache()`, and `cacheTeeErrors: StateFlow<Int>`. `close()` + `seekToCharOffset()` abandon + null the appender. `EnginePlayer` injects `PcmCache`, constructs `PcmCacheKey` from `(chapterId, voiceId, speed, pitch, CHUNKER_VERSION, pronunciationDictHash)`, wipes stale partials per the abandon-and-restart policy, finalizes + LRU-evicts on natural chapter-end.
- **5 new contract tests** in `EngineStreamingSourceCacheTeeTest` (Robolectric): per-sentence tee, finalize → `isComplete = true`, close abandons, seek abandons, null-appender back-compat no-op, finalize idempotency.

### What user sees vs what's queued
- **This release**: cache files start accumulating on disk as you listen. Zero user-perceptible change today.
- **PR E (next)**: cache-hit playback — revisiting a chapter swaps in `CacheFileSource`, skipping synthesis entirely. That's the user-facing "instant replay" win.
- **PR F**: WorkManager / `RenderScheduler` background renders for next-chapter pre-render.
- **PR G**: Settings UI for cache quota + Mode C.
- **PR H**: status icons.

### Build-config caveat from v0.5.46 carries forward
- `adb shell run-as in.jphe.storyvox ls cache-dir` no longer works because `isDebuggable=false` blocks `run-as`. On-device cache verification will use logcat events + the in-app diagnostic surface that PR G adds. PR D's tablet smoke-test deferred to PR E (cache-hit playback is the observable signal anyway).

### Under the hood
- One spec delta from the PR D plan file: `PcmCacheKey` (shipped in PR C / #100) already carries `pronunciationDictHash` as a 6th field beyond the plan's 5-field listing. EnginePlayer snapshots `cachedPronunciationDict.contentHash` into the key so dict edits self-evict cached chapters.
- Gradle JVM heap bumped 4G → 8G (commit `40f7b9c`) — v0.5.46's tag CI hit SIGTERM during R8 + ART profile expansion; 8G prevents that.

## [0.5.46] — 2026-05-14

### Performance — the actual cold-launch win lands

- **`isDebuggable = false` on the shipped build** (#409 part 4 — JP design call resolved 2026-05-14). Activates ProfileInstaller's AOT compilation of the bundled Baseline Profile from v0.5.45. Combined with R8 (also v0.5.45), this is what makes the **~4.5 second tablet cold-launch win** actually materialize — the "Skipped 219 frames" first-composition pass is gone because the hot paths are now AOT-compiled at install time. The lost Android Studio debugger-attach capability is not in use (storyvox dev happens through agents + `./gradlew installDebug` + logcat); if a future workflow needs Studio attach, introduce a separate `localDev` build type rather than flipping this back.
- **Lint-vital escalation disabled on assemble** (#409 part 4 secondary fix) — non-debuggable build types trigger `lintVitalAnalyzeDebug` as part of `:app:assembleDebug`, and AGP's lint hit an internal bug on `:core-ui`'s `A11yLocals.kt` ("Unexpected failure during lint analysis"). The file compiles + unit-tests fine; it's a lint-internal crash. Workaround: `lint { checkReleaseBuilds = false }` skips the vital escalation while `./gradlew :app:lintDebug` still runs normally.

### Cold-launch arc summary (v0.5.42 → v0.5.46)

| Release | Change | Tab A7 Lite cold launch |
|---|---|---|
| v0.5.42 | Baseline | ~6.7 s |
| v0.5.43 | Phase 2 a11y (no perf change) | ~6.7 s |
| v0.5.44 | Three deferred-init fixes | ~6.5 s (-185 ms) |
| v0.5.45 | + R8 minification (DEX -73%) + Baseline Profile bundled (dormant) | ~6.5 s |
| **v0.5.46** | **+ `isDebuggable=false` activates BP AOT compilation** | **target ~2 s (actual measure post-install)** |

Real numbers land in the Slack post once installed.

## [0.5.45] — 2026-05-14

### Performance — Baseline Profile + R8 minification

- **R8 minification ON for the shipped build** (#496, partial close [#409](https://github.com/techempower-org/storyvox/issues/409)). `isMinifyEnabled = true` on the `debug` build type (which IS the shipped artifact today). **DEX bytecode shrinks 87.0 MB → 23.3 MB (-73.3%) uncompressed**; the 30+ pre-R8 dex files collapse to 2. APK on-disk only drops ~1.6 KB because ~95 MB of the APK is native `.so` files (libonnxruntime + libsherpa-onnx across 4 ABIs) that R8 can't touch. APK uncompressed total: 199.9 MB → 136.2 MB (-31.9%). Comprehensive `app/proguard-rules.pro` keeps every reflection surface load-bearing: the KSP-generated `in.jphe.storyvox.plugin.generated.**` package (without this, all 18 source plugins silently drop), `kotlinx.serialization` umbrella, Hilt + Room belt-and-suspenders, Jsoup/OkHttp `-dontwarn`. Manual tablet verification: cold launch + all 18 chips + Notion/HN/Royal Road/Telegram fiction detail open + zero FATAL/SerializationException entries in logcat.

- **Baseline Profile generated + bundled** (#495, partial close #409). New `:baselineprofile` Gradle module + `BaselineProfileGenerator` instrumented test that walks the cold-launch hot path: LAUNCHER → Library (default landing) → Browse (Notion chip default-selected post-`9370b39`) → Settings hub → first fiction → Reader. 3 iterations, captured into `app/src/main/generated/baselineProfiles/baseline-prof.txt` (3265 storyvox-specific entries). Refresh manually with `./gradlew :app:generateBaselineProfile` (~5 min on the tablet); NOT on the critical-path CI build. **Measured -33.6% (1965 ms → 1304 ms) cold launch on the `nonMinifiedRelease` variant** during macro-benchmark capture.

### ⚠️ The BP cold-launch win is dormant on the shipped APK

ProfileInstaller only AOT-compiles bundled profiles on **non-debuggable** APKs. Today the shipped `debug` build type still has `isDebuggable = true` (default), so the profile bundles but doesn't activate at install time. The 4.5-second latent win (Skipped 219 frames on first composition) stays unrealized until **one of**:

- (a) `isDebuggable = false` flips on the `debug` build type (one-line change; trades local Studio debugger attach for the cold-launch win), **OR**
- (b) storyvox grows a real release flavor (#16, queued v1.0 prerequisite) and CI ships that.

This is a JP design call. The infrastructure (BP profile + R8 rules + benchmark build type) is fully wired ahead of either path.

### Under the hood
- New `benchmark` build type — non-debuggable, no R8, debug-signed. Used by the BaselineProfile producer's `nonMinifiedRelease` variant to measure honest "with profile" / "without profile" deltas. Not shipped.
- `release` build type now has R8 on + the same single-keystore reuse (forward-looking placeholder for #16).
- Required `androidx.benchmark:macro` 1.4.1 — 1.3.4 chokes on Android 14's new `pm dump-profiles` stdout prefix on the Tab A7 Lite.
- Most-likely-to-bite-future-devs: the `in.jphe.storyvox.plugin.generated.**` proguard keep is load-bearing. Drop it and Browse goes blank with no compile error. Documented at the top of `app/proguard-rules.pro`.

## [0.5.44] — 2026-05-14

### Performance — partial close of #409

- **Three deferred-init fixes shave ~185 ms off Tab A7 Lite cold launch** (#494, partial close [#409](https://github.com/techempower-org/storyvox/issues/409)). Tablet 6714 ms → 6529 ms (-2.8%), Z Flip3 1342 ms → 1201 ms (-10.5%) measured via Macrobenchmark at `StartupMode.COLD`, median of 10 / 5 iterations.
- **VoxSherpa engine-bridge seeds deferred** (~100 ms tablet) — `applyPitchQualityFromSettings` + `applyPerVoiceEngineKnobsFromSettings` moved from `StoryvoxApp.onCreate` to a `Choreographer.postFrameCallback` chain in `MainActivity`. Verified: `libsherpa-onnx-jni.so` now `dlopen`s ~250 ms *after* the "Displayed" event instead of before. The engine's `Sonic` instance still doesn't construct until the first audio-buffer request, so seed always completes before any in-flight render — no behaviour change.
- **AccessibilityStateBridge eager-stateIn'd** (~30 ms tablet) — replaced the v0.5.43 cold `callbackFlow` exposure with a hot `StateFlow` built via `stateIn(scope = IO, started = Eagerly, initialValue = AccessibilityState())`. Activity-injected bridge wrapped in `Lazy<>`. Drops the `getEnabledAccessibilityServiceList` binder hop off the Compose Main-dispatcher first-composition path. Mid-session TalkBack changes still fire correctly.
- **Data-layer warm-up** (negligible measured, kept anyway) — `warmDataLayer()` in `StoryvoxApp.onCreate` kicks off `Lazy<StoryvoxDatabase>.get()` + Fiction/Shelf/PlaybackPosition repos on IO. Race-loss-safe because it shares Hilt's `DoubleCheck` cache with the foreground path. Foundation for the next bigger lift.

### Deferred (the real wins are next)
- **Baseline Profile generation** (estimated ~1.5–2.5 s tablet win) — agent in flight, lands as v0.5.45. The 4.7-second "Skipped 219 frames" first-composition pass on this CPU is Compose + Hilt graph construction on the debug-flavor (non-AOT) classpath; AOT-compiling the hot paths at install time is the surgical fix.
- **R8 minification** (estimated ~15–25% win) — currently `isMinifyEnabled = false`, queued for JP design call (reflection-heavy modules need a proguard-rules audit before flipping it on).

## [0.5.43] — 2026-05-14

### Added — Accessibility Phase 2: everything from the audit, wired

- **High-contrast theme variant** (#493, closes #486 part 1) — `brass-on-near-black` Library Nocturne. Backgrounds `#1a1410` → `#000000`; brass `#b88746` → `#ffc14a`. Hits WCAG **AAA** (~14:1+ body text) without abandoning the brand. New `LibraryNocturneHighContrastTheme` toggled by `pref_a11y_high_contrast` OR `accessibilityState.isTalkBackActive` (the bridge auto-detects). Light variant: pure-white inverse with darker brass for AAA on white.
- **Reduced-motion fold-in** (#493, closes #486 part 2 + #480) — new `LocalReduceMotion` CompositionLocal in `:core-ui`, set from `pref_a11y_reduced_motion || accessibilityState.isReduceMotionRequested` (the bridge tracks `Settings.Global.ANIMATOR_DURATION_SCALE`). 8 animation sites fold the flag in: `AnimatedVisibility` snap-rather-than-fade, `tween()` → `snap()`, `animateFloatAsState` / `animateDpAsState` → `spring(stiffness=High)`, `Crossfade` skip. Per JP design call: auto-on when system "Remove animations" is on; user override in the subscreen always wins.
- **Touch-target widener** (#493, closes #479 + #486 part 3) — new `Modifier.accessibleSize()` helper in `:core-ui/a11y/` that returns 48dp baseline, 64dp when `LocalAccessibleTouchTargets.current` is true (set from `pref_a11y_larger_touch_targets || accessibilityState.isSwitchAccessActive`). Applied at the 3 auth-WebView buttons + the VoiceLibrary favorite-star (previously 36dp → below WCAG 2.5.5 minimum).
- **TalkBack inter-sentence pacing** (#493, closes #486 part 4) — `EngineStreamingSource` adds `pref_a11y_screen_reader_pause_ms` ms of silence between sentences (slider 0–1500ms, default 500ms) **only when** `accessibilityState.isTalkBackActive`. Speed-scaled so 2× playback doubles the effective pause budget. 3 producer paths touched.
- **Chapter-header readout branching** (#493, closes #486 part 5) — `ChapterCard` content-description respects `pref_a11y_speak_chapter_mode` (`Both` / `NumbersOnly` / `TitlesOnly`). New `LocalA11ySpeakChapterMode` CompositionLocal.
- **Font-scale typography pipeline** (#493, closes #486 part 6) — `scaledTypography` multiplies `LibraryNocturneTypography` font sizes by `pref_a11y_font_scale_override` (0.85–1.5×, default 1.0) on top of system font scale. Applied via `MaterialTheme(typography = scaledTypography)`.
- **Reading-direction at NavHost root** (#493, closes #486 part 7) — when `pref_a11y_reading_direction != FollowSystem`, the entire `StoryvoxNavHost` is wrapped in `CompositionLocalProvider(LocalLayoutDirection provides Ltr|Rtl)`. Escape hatch for RTL testing.
- **TalkBack-install nudge** (#493, closes #488) — when the user touches a screen-reader-related a11y row but TalkBack isn't running, a dismissible brass-edged info card appears: "TalkBack isn't running — these settings activate when you turn on TalkBack in Android Settings → Accessibility." Persists dismissal via the new `pref_a11y_talkback_nudge_dismissed` flag (on the InstantDB sync allowlist).
- **`docs/accessibility.md`** (#492, closes #487) — canonical accessibility reference at `storyvox.techempower.org/accessibility` covering: audit method, Phase 1+2 scope, design calls, partnership-outreach plan. Settings → Accessibility "Learn more" row opens it via `LocalUriHandler`.

### Fixed — Accessibility Phase 2: static labels + Roles + content descriptions

- **Switch primitive labeled** (#492, closes #478) — `SettingsSwitchRow` refactored to `Modifier.toggleable(role = Role.Switch)`. Covers 4 of 7 unlabeled-Switch sites at once; the other 3 (`SettingsScreen.kt:1204`, `PronunciationDictScreen.kt`, `ManageShelvesSheet.kt`, `DebugScreen.kt`, `VoiceQuickSheet`) given the same shape. `PluginManagerScreen.kt:299` gets `contentDescription` on the Switch instead because the surrounding card has its own tap.
- **BottomTabBar Role.Tab + selected** (#492, closes #485) — each `TabCell` now declares `Modifier.semantics { selected = isSelected }` + `Modifier.clickable(role = Role.Tab, onClickLabel = tab.label)`. New `BottomTabBarSemanticsTest` pins the contract.
- **Progress indicator semantics** (#492, closes #484) — `SyncAuthScreen.InProgress` + `FictionDetailScreen`'s epub-export spinner get `contentDescription = "Loading: <label>"` + `liveRegion = Polite`.
- **27 bare clickable callsites swept** (#492, closes #481) — `Role.Button` / `Role.Checkbox` / `Role.Switch` + `onClickLabel` added across 13 files. `VoicePickerGate`'s intentionally Role-less tap-swallow annotated.
- **Hard-coded fontSize cleanup** (#492, closes #483) — `MilestoneDialog` (12 sites) + `GitHubSignInScreen` (1) rebound to typography ramp tokens (`titleMedium`/`bodyMedium`/`titleLarge`/`bodyLarge`/`displaySmall`). `DebugOverlay` + `DebugScreen` get kdoc notes explaining intentional bypass for the px-pinned debug HUD.
- **Default-theme contrast tweaks** (#493, closes #477) — `plum500`, `errorContainer` (dark), and both `outlineVariant` lifted to clear **WCAG AA-normal** (4.5:1+) within the existing Library Nocturne aesthetic. AAA on body text contract-tested across every surface tier.
- **Icon contentDescription verify-sweep** (#492, closes #482) — 36 `cd=null` + 28 `IconButton:MISSING` callsites audited; all confirmed correctly decorative or labeled via inner-Icon semantic merge. Documented in `docs/accessibility.md`.

### Under the hood
- 8 new contract tests: `HighContrastThemeTest`, `TypographyScaleTest`, `AccessibleTouchTargetTest`, `ReduceMotionTest`, `EnginePlayerTalkBackPacingTest`, `ReadingDirectionMappingTest`, `SettingsSwitchRowToggleableTest`, `BottomTabBarSemanticsTest`.
- New `LocalReduceMotion`, `LocalAccessibleTouchTargets`, `LocalA11ySpeakChapterMode`, `LocalIsTalkBackActive` CompositionLocals in `:core-ui/a11y/`. The state bridge from #489 stays the single source of truth; CompositionLocals are read-side fan-out.
- 7 prefs from #489 are now load-bearing — every consumer reads the `pref || detected` fold.
- 12 of 12 a11y audit findings (#477–#488) now closed.

## [0.5.42] — 2026-05-14

### Added
- **Telegram backend** (#490, closes #462) — public-channel reader via Bot API. New `:source-telegram` Gradle module + Settings card (mirrors Discord). 17 → 18 fiction backends. Architectural caveat documented in PR body: Telegram Bot API gives bots no access to message history that predates their invitation to a channel — v1 accumulates posts in-memory via `getUpdates` polling on each Browse refresh; user sees zero chapters until the channel admin posts something new post-invite. Bot token stays device-local (no InstantDB sync until follow-up coordination with the `:core-sync` allowlist).
- **Accessibility Settings scaffold** (#489, Phase 1 of the #486 epic) — new `Settings → Accessibility` subscreen with 7 functional rows (high-contrast toggle, reduced-motion toggle, larger-touch-targets toggle, screen-reader-pause slider 0–1500 ms, font-scale slider 0.85–1.5×, speak-chapter-mode selector, reading-direction selector) plus an "About these settings" info card. All wire through `SettingsViewModel` to 7 new `pref_a11y_*` DataStore keys on the InstantDB sync allowlist. **New `AccessibilityStateBridge`** in `:feature` (real impl in `:app`) exposes `(isTalkBackActive, isSwitchAccessActive, isReduceMotionRequested)` as a hot `callbackFlow` from `AccessibilityManager` + `Settings.Global.ANIMATOR_DURATION_SCALE`. **Phase 1 ships toggles + state-bridge only** — Phase 2 wires the behaviors (high-contrast theme swap, reduced-motion fold-in for `AnimatedVisibility`/`tween`, 48dp→64dp `clickable` widener, TalkBack inter-sentence pacing, chapter-header readout branching, font-scale typography pipeline, `LayoutDirection` at NavHost root). TODO/Phase-2 comments at every consumer site.

### Fixed
- **Bottom tab bar no longer blocks the Android home swipe** (`46262b0`) — `BottomTabBar.kt` called `Modifier.systemGestureExclusion()` on each tab cell to claim the ~64 dp `mandatorySystemGestures` rect for taps. That worked for tap reliability but blocked the OS swipe-up-home and long-press-up-recents gestures entirely — they hit our exclusion rect and never reached the system. Fix drops the exclusion entirely; `Modifier.windowInsetsPadding(WindowInsets.navigationBars)` already lifts cells above the visible gesture pill, which is enough for tap reliability in practice.
- **Notion pinned to first chip** (`9370b39`) — per JP design call, TechEmpower's Notion content is the default Browse landing surface when no user-Notion token is configured. `SourcePluginRegistry` sort now puts Notion at position 0 before the existing category-then-alphabetical fallback, which also makes it the default-selected chip on Browse open (`BrowseViewModel` picks `descriptors.firstOrNull { it.defaultEnabled }`).

### Closed (stale)
- **#447** Notion prefilled DB id on fresh install — closed citing #474 (the v0.5.40 design-call fallback already covers this).
- **#442** Gutenberg chapter playback hung at 0:00 — closed citing #467 (the v0.5.38 `stripTags` + zero-sentences guard already fixed this).
- **#440** Settings gear icon dumped users into Voice & Playback — closed citing #467 + the v0.5.39 SettingsHubScreen.
- **#438** Library had two adjacent tab rows with overlapping labels — closed citing #467 + the v0.5.39 nav restructure (#469).

### A11y audit findings filed (Phase 2 targets, not fixed in this release)
- **12 new accessibility issues filed (#477–#488)** by the a11y-audit agent. Body-text contrast is **AAA** (12-15:1) across both themes; failures concentrate in *secondary* colors (plum 3.31:1, error-container 4.00:1, brass-on-light-highest 4.33:1, outlineVariant 1.58-1.60:1 in both themes). 9 unlabeled clickables all trace to a shared `SettingsComposables.kt` Switch primitive — `~10` LOC fix kills 4 of 7 sites at once (#478). 0 RTL violations, 0 empty `contentDescription` strings, AudiobookView transport controls are exemplary. Phase 2 targets are #478 → #485 → #486 (the subscreen epic, now unblocked by #489).

## [0.5.41] — 2026-05-14

### Added
- **Craigslist regional feeds template** (#476, closes #464) — local-marketplace listings as audiobook chapters. Inside `:source-rss`, a curated catalogue of **51 regions** (top-50 US metros + Nevada City) × **7 categories** (all-for-sale, free stuff, cars+trucks, furniture, electronics, apartments, jobs) drives a new collapsible "Craigslist regional feed" template card in the Browse → RSS add sheet. Pick a region chip + a category chip, see the resolved feed URL live, tap Subscribe — the feed enrolls via the existing RSS pipeline (DataStore persistence, polling, FictionDetail rendering). Each region+category becomes a fiction; each listing becomes a chapter. JP's "uninstalled Facebook, would love to browse the local marketplace and have listings read to me" use case, shipped without scraping and without a new Gradle module.
- **Magic-link claims Craigslist URLs** — `RssSource.matchUrl` recognizes `<region>.craigslist.org/*` for any of the 51 curated regions, returns `RouteMatch(confidence = 0.7f, label = "Craigslist (<region>)")`. Paste a CL URL from a browser share → routes through `RssSource` → opens the Add-by-URL sheet pre-resolved.

### Fixed
- **RSS parser now handles RDF / RSS 1.0** (#476) — Craigslist serves `<rdf:RDF>` feeds (not `<rss>`), which the previous parser rejected silently. New parser branch handles the RDF root; pinned with a recorded Craigslist fixture in `:source-rss/src/test/resources/`. Unblocks any other publisher still on RSS 1.0.

### Under the hood
- `CraigslistTemplates.kt` is the single canonical region+category catalogue. `composeFeedUrl(region, category)` formats the URL; 15 parametric tests cover the matrix.
- `BrowseCraigslistTemplateCard` is a self-contained Compose composable inside `BrowseRssManageSheet` — no new screen, no new top-level Browse chip, no new Hilt graph dependencies.
- 28 new tests: 15 catalogue + 5 RDF fixture parse + 8 matcher. Pure-JVM (no Robolectric needed).

### Deferred (per #464 v1.5+ scope)
- Distance / price filters (CL exposes these as query params; just need UI).
- Saved-search composer for free-form CL queries.
- Auto-normalize pasted CL URLs to `?format=rss`.
- Region pinning (favorite a region for one-tap subscribe).
- Dedupe-miss probe: "Already subscribed" check is on lowercased URL; trailing slash + reordered query args could slip through. Worth a manual probe on tablet next release if it surfaces.

## [0.5.40] — 2026-05-14

### Added
- **Magic-link audiobook** (#475, closes #472) — paste any URL into the Add-by-URL sheet and storyvox routes to the best backend, with a Readability catch-all so **no URL is a dead-end**. New `UrlMatcher` capability interface (separate from `FictionSource` so it stays opt-in); 15 backends implement it (Hacker News, Notion, Outline, Discord, Radio, RSS, EPUB direct-link, Memory Palace, AO3, Gutenberg, Standard Ebooks, PLOS, Wikipedia, Wikisource, arXiv). Royal Road + GitHub keep their existing `UrlRouter` regex bank for the fast path. New `:source-readability` Gradle module (Readability4J extraction, single-chapter library fiction, `defaultEnabled = true`) catches anything else at confidence `0.1f`. **ACTION_SEND share-intent** on MainActivity (text/plain) lets browsers, RSS readers, podcast clients, and social-share menus hand URLs to storyvox directly — the activity routes to Library with the URL surfaced as a query arg via `DeepLinkResolver.libraryWithShare`, and the Library screen opens the Add-by-URL sheet pre-populated. v1 ships with the FAB-launched sheet as the entry point; a dedicated Magic-add card at the top of Library is a follow-up.

### Fixed — QA backlog drain (13 of 14 cleared; #461 verified-already-fixed by #468)
- **Player back-arrow content-desc was 'Settings'** (#474, closes #437) — `onBack` plumbed end-to-end with proper a11y label.
- **Notion silently fell back to TechEmpower Resources DB when no token** (#474, closes #443 + #447) — per JP design call, fallback now points at techempower.org's actual Notion content with a clear demo banner in Browse and a "Database ID (TechEmpower demo)" label in Settings; once user adds a token, the plugin switches to their root.
- **AO3 description showed `&amp;amp;`** (#474, closes #444) — fixed-point HTML entity decoder + 6 regression tests.
- **Browse Search icon disappeared on AO3 chip** (#474, closes #445) — added `BrowseTab.Search` to AO3's supportedTabs.
- **'Add by URL' copy was Royal-Road-only** (#474, closes #446) — generalised hint with supported-source one-liner; carries a `TODO(#472)` placeholder that the magic-link PR replaced with the cascade-resolver.
- **Live Radio timecode stuck at 0:00** (#474, closes #448) — scrubber hidden + pulsing "LIVE" pill when `state.isLiveAudioChapter`.
- **Radio fiction author rendered as country name** (#474, closes #449) — `author = "Live radio"` with regression test.
- **Chapter subtitle 'Ch. 0 · Chapter 1' indexing inconsistency** (#474, closes #453) — new `formatChapterLabel(index, title)` helper, 1-indexed + redundant-prefix detection.
- **Settings 'Save' accepted empty required fields silently** (#474, closes #455) — per JP design call, Outline + Notion Save now show a transient toast naming skipped fields; defaults applied silently to the model. No save-blocking, no Material error chip — lighter touch than full Material 3 supported-error.
- **Notebook empty-state pointed at a missing Chat CTA** (#474, closes #456) — copy softened.
- **RSS Browse chip showed empty screen with no helper copy** (#474, closes #458) — explicit empty-state with "Add a feed" CTA.
- **GitHub: 'The Cartographer's Lantern' showed 0 chapters** (#474, closes #460) — `fictionDetail` now falls back to root `SUMMARY.md` when `src="src"` configuration points elsewhere; regression test added.
- **GitHub plugin title hierarchy confused repo name vs README title** (#474, closes #463) — byline now prefixed with "by " on FictionDetail.

### Under the hood
- New `:source-readability` Gradle module with `ReadabilityExtractor`, `ReadabilityFetcher`, and Hilt `ReadabilityModule`. `@SourcePlugin(defaultEnabled = true)` so it auto-registers in the registry without an `:app` build.gradle change.
- `UrlResolver` cascades through `UrlRouter` → `UrlMatcher` impls → `:source-readability`. `RouteCandidate(confidence: Float, label: String, ...)` lets ranking work when multiple backends both claim a URL.
- `MultipleMatches` variant on `AddByUrlResult` for the rare ≥2-candidates-above-0.5 case — v1 auto-picks the top; chooser modal is UI follow-up if discoverability needs it.
- 14-commit QA bundle was rescued via cherry-pick after a parallel-agent branch contamination event (qa-bundle and magic-link both worked the same repo without worktrees and stepped on each other). New `feedback_parallel_agents_need_worktrees` memory captures the lesson; future parallel runs use `isolation: "worktree"` per agent.

## [0.5.39] — 2026-05-14

### Added
- **Nav restructure: Settings becomes a primary destination** (#469) — the bottom bar collapses from 5 destinations to `{Library, Settings}`. The Library tab now hosts 5 scrollable sub-tabs: `{Library, Browse, Follows, Inbox, History}`. Browse / Follows / Inbox / History are no longer separate bottom destinations — they fold into Library as embedded subscreens (`embedded: Boolean` param on Browse/Follows skips their own TopAppBar when rendered under Library). The legacy `BROWSE` / `FOLLOWS` routes are preserved for deep-link survival. Pins via `NavStructureTest` (10 cases) + `LibraryTabCollapseTest` (8 cases).
- **InstantDB sync for settings + secrets** (#470, partial follow-up to #360) — `:core-sync` now round-trips 50+ allowlisted DataStore prefs (theme override, voice tuning, AI config, source toggles + the Phase-3 plugins-enabled map, inbox mute, sleep timer, per-backend non-secret config like Wikipedia lang / Notion db id / Discord coalesce-minutes / Outline host / Memory Palace host) as a single last-write-wins JSON blob to InstantDB. Tier 2 widens the encrypted-secret allowlist to fold in Notion / Discord / Outline tokens that already lived in `EncryptedSharedPreferences`. Encryption posture: client-side envelope with PBKDF2-HMAC-SHA256/600k + AES-GCM-256 and per-user deterministic salt; no-passphrase hard-fails Permanent (never plaintext-fallbacks). Device-local flags (`SIGNED_IN`, `LAST_WAS_PLAYING`, milestone gates) are explicitly excluded.
- **Settings hub finished — 7 dedicated subscreens** (#471, closes the v0.5.38 #440 follow-up) — the SettingsHub used to route only 5 of 13 cards to focused subscreens (Voice library, Plugins, AI sessions, Pronunciation, Debug); the other 7 fell back to a legacy long-scroll Settings page. This release adds dedicated subscreens for `Voice & Playback`, `Reading`, `Performance`, `AI`, `Account`, `Memory Palace`, and `About`, each composed from a shared `SettingsSubscreenScaffold`/`Body` wrapping the row composables in `SettingsScreen.kt` (those went from `private` to `internal`). Legacy `SettingsScreen` stays as an "All settings" power-user fallback. 21 new tests across `SettingsSubscreenContractTest`, `SettingsSubscreenRoutesTest`, and `SettingsHubSectionsTest`.

### Fixed — QA UX-blocker bundle
- **Notebook + Pronunciation Add dialogs didn't shift focus on second-field tap** (#468, closes #450) — `NotebookFocusContractTest` pins the regression.
- **Library grid empty in landscape orientation** (#468, closes #452) — `LibraryGridLandscapeTest` pins it.
- **RSS Add-feed dialog Add button was overlapped by the EditText** (#468, closes #459) — `BrowseRssAddSubmitTest` pins the layout fix.
- **FictionDetail chapter list rows were non-clickable** (#468, closes #461 — `priority: high`, blocked the read flow on every fiction).

### Under the hood
- `:core-sync` `SettingsSyncer` (new): exact-key allowlist of 50+ DataStore prefs (typed as `Set<String>`), per-backend non-secret config routed through each `*ConfigImpl` setter on apply. `SettingsSyncerTest` (round-trip on every allowlisted key + device-local-key rejection) + `SecretsSyncerExtensionTest` (Tier 2 allowlist widening) + 24 more = 26 new tests.
- CI workflow gained a self-hosted-runner-specific step that copies `local.properties` from `/home/jp/Projects/storyvox/` into the runner's checkout pre-assemble, so release APKs bake the real `INSTANTDB_APP_ID` into `BuildConfig` instead of the `PLACEHOLDER` sentinel.

## [0.5.38] — 2026-05-14

### Fixed — QA bundle from the exhaustive Flip3 pass
- **Browse only showed 5 of 17 backend chips on fresh install** (#467, closes #436) — 12 `@SourcePlugin(defaultEnabled = false)` annotations were wrong defaults on the new backends (GitHub, Memory Palace, EPUB, Outline, AO3, Standard Ebooks, Wikipedia, Wikisource, Hacker News, arXiv, PLOS, Discord). Fresh installs of v0.5.32–v0.5.37 saw only Gutenberg / Royal Road / RSS / Notion / Radio in the chip strip. Flipped all 12 + matching `LegacySourceKeys.ALL` to `defaultEnabled = true`. Upgrade users with explicit OFF settings retain their preferences via the legacy-key migration. 7 new tests pinning the default + migration contract.
- **Library had two adjacent tab rows with overlapping labels** (#467, closes #438) — top strip had `All / Reading / Inbox / History` (section selector), second strip had `All / Reading / Read / Wishlist` (shelf selector). Material's anti-pattern: same string in two nested navigation surfaces. Collapsed to 3 top tabs (`Library / Inbox / History`); the shelf chip row inside Library tab is now the canonical Reading affordance. 6 new tests.
- **Settings gear icon dumped users into Voice & Playback section** (#467, closes #440) — no Settings root menu. New `SettingsHubScreen` + `SETTINGS_HUB` nav route landing on a hub of 13 brass-edged section cards: Voice library, Plugins, AI sessions, Pronunciation, Debug (dedicated subscreens for 5; legacy long-scroll for the other 7 + an "All settings" escape hatch). 6 new tests.
- **Gutenberg chapter playback hung at 0:00 'Buffering…' indefinitely** (#467, closes #442 — `priority: high`) — two compounding causes: (a) `GutenbergSource.stripTags` was a permissive `<[^>]+>` regex that left `<head>` / `<script>` / `<style>` contents in the output, so the synth queue saw Project Gutenberg's inline CSS instead of prose and Piper synthesized punctuation-heavy CSS while the UI showed `state=PLAYING / position=0` indefinitely; (b) `EnginePlayer.loadAndPlay` had no zero-sentences guard, so the silent failure mode had no diagnostic. Fixed both — pre-strips non-visible regions in `stripTags` (DOTALL, case-insensitive) + typed `PlaybackError.ChapterFetchFailed("This chapter has no readable text…")` early-return with a hot-path synth breadcrumb log. 7 new tests.
- **Calliope v0.5.00 milestone celebration window closes after v0.5.5** (#451, closes #435 + #439) — the dialog had been firing on fresh installs of every build past v0.5.0 with the hand-tuned headline "storyvox 0.5.00" because `Milestone.qualifies()` returned true for anything `≥ 0.5.0`. Fresh installer on v0.5.36 saw a stale headline; reported as "onboarding splash shows hardcoded 0.5.00". Fix: added an upper bound. Users who installed during the window keep their dismissal flag; fresh installers past v0.5.5 silently never see the dialog. Dialog code stays for history.

### Backend defaults
Post-#436 fix, fresh installs see all 17 backends in the Browse chip strip: Royal Road, GitHub, Memory Palace, RSS, EPUB, Outline, Gutenberg, AO3, Standard Ebooks, Wikipedia, Wikisource, Radio, Notion, Hacker News, arXiv, PLOS, Discord. Plus the 12 specialty content surfaces (audio-stream Radio category, the AI heavies trifecta from v0.5.30–v0.5.36, etc.).

## [0.5.37] — 2026-05-14

### Fixed
- **Pause/Resume on radio chapters actually pause/resume now** (#434) — `PlaybackController.pause()` hard-called `EnginePlayer.pauseTts()`, which tears down the TTS pipeline only. For audio-stream chapters (KVMR + the v0.5.32 radio backend), the sibling ExoPlayer kept streaming on top of the dead TTS state. Pause was a visual lie on radio. Fix: new `EnginePlayer.pauseRouted()` checks `isLiveAudioChapter` and drops `playWhenReady` on `audioStreamPlayer` for radio, falls through to `pauseTts()` for TTS. Same routing added at the top of `EnginePlayer.resume()` so Play button recovers correctly. Reported by JP on tablet R83W80CAFZB v0.5.36.

## [0.5.36] — 2026-05-14

### Added
- **AI chat multi-modal image input** (#433, closes #215) — attach button in the chat composer launches SAF `OpenDocument` for `image/*`. Picked image is downscaled to 1280px-long-edge + JPEG q=85 + base64-encoded via a new `ImageResizer` (same pattern as the screenshot-compress hook for the multimodal API safety envelope), then sent as a `LlmContentBlock.Image` alongside the text in the LLM request. Composer shows a 200dp thumbnail above the text input with an x to remove; the user's message bubble renders the image inline via Coil. Last of the three AI heavies in the v0.5.30–v0.5.36 wave (#217 cross-fiction memory ✓, #216 function calling ✓, #215 image input ✓).
- **Provider coverage v1**: Anthropic (Claude direct + Teams OAuth) and OpenAI return `supportsImages = true` and serialize image content blocks natively. Vertex / Bedrock / Foundry / Ollama silently drop image parts and the chat surface shows a one-shot info banner ("Image input not supported on this provider — sending text only"); per-provider wiring is straightforward follow-up.

### Under the hood
- New `LlmContentBlock` sealed class in `:core-llm` with `Text(content)` + `Image(base64, mimeType)` variants. `LlmSessionRepository.chat()` and `chatWithTools()` now accept `userParts: List<LlmContentBlock>?` alongside the existing string-text path; DB rows stay text-only (image bytes are in-memory for the send round only — Room storage of base64 blobs is a follow-up if we want full chat-history rendering of past images).
- 8 new unit tests: `ContentBlocksTest` (sealed-class serialization), `ClaudeImageRequestTest` + `OpenAiImageRequestTest` (provider wire-format snapshots), `ImageResizerTest` (1280px clamp + JPEG q=85 round-trip + base64), two new `ChatViewModelTest` cases for composer state transitions + URI overlay.

## [0.5.35] — 2026-05-14

### Performance
- **Cold-launch ~14% faster on low-end Android** (#432, partial fix for #409). Wrapped every `@Inject lateinit var` in `StoryvoxApp` and `MainActivity` in `dagger.Lazy<T>` (except `HiltWorkerFactory` which WorkManager needs eagerly), and punted `workScheduler.ensurePeriodicWorkScheduled()` + `syncCoordinator.initialize()` off the main thread onto a shared `Dispatchers.IO` scope. Measured on Galaxy Tab A7 Lite (Helio P22T, 2.0 GHz Cortex-A53): **6825ms → 5861ms** averaged over 5 cold launches; Choreographer skips dropped from 160 frames → 142 frames. No regression on Z Flip3 (1240ms → 1200ms). The remaining ~5.9s on the tablet is dominated by Compose first-recomposition cost (~2.4s of frame-skip) — needs a Macrobenchmark target to slice further; #409 stays open for that ongoing work.

## [0.5.34] — 2026-05-14

### Added
- **KittenTTS as the third in-process voice family** (#431, closes #119) — smallest tier alongside Piper (compact) and Kokoro (multi-speaker). The fp16 nano model is ~24 MB across 3 flat files (model + sentencepiece config + voices), shared across all 8 speakers (F1–F4 / M1–M4, en_US). Lives between Kokoro and Azure in the Voice Library as a "Kitten (Lite)" section. Designed for slow devices where even Piper struggles. Cross-repo: upstream `techempower-org/VoxSherpa-TTS` v2.8.0 ships `KittenEngine` mirroring `KokoroEngine`'s multi-speaker shape via sherpa-onnx's existing `OfflineTtsKittenModelConfig`. JitPack coordinate bumped from v2.7.14 → v2.8.0.
- `VoiceManager.kittenSharedDir()` + a download branch for the shared-model sentinel pattern (single download → 8 speakers); EnginePlayer dispatcher routes `EngineType.Kitten(speakerId)` at four sites including Tier 3 parallel-synth via a new `secondaryKittenEngines` pool. 10 new unit tests covering catalog contract (8 voices, en_US, Low tier), engine-dispatcher routing (Kitten vs Piper vs Kokoro), and VoiceManager download branch.

## [0.5.33] — 2026-05-14

### Added
- **AI function calling — 5 tools** (#430, closes #216) — the chat AI can now actually *do* things in storyvox, not just answer questions. Five v1 tools wired end-to-end:
  - `add_to_shelf(fictionId, shelf)` — Reading / Read / Wishlist
  - `queue_chapter(fictionId, chapterIndex)` — sets the play queue to a specific chapter
  - `mark_chapter_read(fictionId, chapterIndex)` — flips the read state
  - `set_speed(speed)` — clamped to [0.5, 2.5]
  - `open_voice_library()` — navigates to Voice picker
- **Per-tool brass card in chat** — when a tool fires, a small brass-edged card renders in the chat stream showing in-flight / success / error state. Examples: "Adding *Frankenstein* to your Reading shelf…" → "✓ Added." or "✗ Couldn't find fiction with id 'xyz'." Card collapses after settling.
- **Settings → AI → "Allow the AI to take actions" toggle** — default ON. Gates the catalog advertisement to the LLM; with the toggle OFF the AI gets a tools-free system prompt and falls back to text replies.
- **Provider coverage v1**: Anthropic (Claude direct + Teams OAuth) + OpenAI both implement the full model→tool→model loop bounded to 5 rounds. Vertex / Bedrock / Foundry / Ollama get a graceful default `chatWithTools` fallback (plain text reply, no tool support) until each provider's tool-format wiring lands as follow-ups.

### Under the hood
- New `:core-llm/tools/` module sub-tree: `ToolSpec`, `ToolCatalog`, `ToolHandler`, `ChatStreamEvent` (rich flow type that carries text deltas + tool-call events + tool-result events in one stream). Provider chat clients gain `chatWithTools()` alongside the existing `chat()`.
- `:feature/chat/tools/ChatToolHandlers` binds the 5 tool names to typed suspend functions wired to the existing `ShelfRepository`, `ChapterRepository`, `PlaybackControllerUi`, and `NavController` — pure execution, no LLM logic. The wiring lives outside `:core-llm` so the chat domain owns the side-effects.

### Tests
- 18 new unit tests: catalog contract (5 tools present + valid JSON schemas), Anthropic + OpenAI wire-shape snapshots (incl. tool-result round-trip via MockWebServer), handler clamp/reject paths, `set_speed` range enforcement, navigation event firing for `open_voice_library`, unsupported-provider fallback behavior.

## [0.5.32] — 2026-05-14

### Added
- **Magical voice settings icon on the play screen** (#428, closes #418) — replaces the buried `⋮` overflow's voice-settings section with a brass-edged soundwave-with-sparkle icon at the top bar. Tap → Material 3 bottom sheet with 5 live-applying rows (Speed / Pitch / Voice picker chip / Sentence silence / Sonic high-quality) + an "Advanced" expander deep-linking to Voice Library for the v0.5.30 per-voice lexicon + Kokoro phonemizer pickers. Long-press → straight to Voice Library. `PlayerOptionsSheet` split into `PlayerOverflowSheet` (sentence step, sleep timer, bookmark, recap, AI chat) — voice rows moved out cleanly. 5 new tests covering row spec, live-audio pitch hiding, speed-first ordering, long-press routing, voice-chip label formatting.
- **Generalized `:source-kvmr` → `:source-radio` with Radio Browser API search** (#429, closes #417) — rename + reshape lands 5 curated stations (KVMR 89.3, Capital Public Radio KXPR, KQED 88.5, KCSB 91.9, SomaFM Groove Salad) plus a new Search tab on Browse → Radio backed by Radio Browser's free `de1.api.radio-browser.info` directory (30k+ stations worldwide). User can star Radio Browser results to add them to their library — starred station descriptors persist in a dedicated `storyvox_radio_starred` DataStore (full record stored, so a star survives the upstream directory culling the station). `KvmrEnabledToRadioEnabledMigration` seeds `pref_source_radio_enabled` from the legacy `pref_source_kvmr_enabled` on first read; Hilt `RadioModule` dual-binds `RadioSource` under both `radio` and `kvmr` StringKeys so persisted `kvmr:live` library rows resolve unchanged. KNCO trimmed from v1 (no stable stream URL discoverable). 24 new tests.

### Fixed
- **Light theme selection had no effect at the renderer** (#427, closes #412) — `MainActivity` called `LibraryNocturneTheme { ... }` without arguments, so the theme wrapper's `darkTheme` defaulted to `isSystemInDarkTheme()` and ignored the saved `themeOverride` preference. Settings → Reading → Theme picker (System / Dark / Light) saved the selection to `pref_theme_override` and showed checked in the UI, but the renderer never read it. Light selection was cosmetic only. Fix: inject `SettingsRepositoryUi` into MainActivity, collect the `settings` flow as State, map `ThemeOverride { System, Dark, Light }` to an explicit `darkTheme` Boolean, pass to `LibraryNocturneTheme`. System falls back to `isSystemInDarkTheme()`; Dark/Light force the corresponding palette regardless of device setting. **High-pri regression that masked the parchment-cream daytime aesthetic entirely.**

### Backend count
- **17 fiction backends** total still (Radio is the rename of KVMR + the new search surface; not a separate addition). The Radio backend now scales from "one station" to "any of 30k+ via Radio Browser star-to-add", so the same source slot is much more useful.

## [0.5.31] — 2026-05-14

### Added
- **Plugin manager Settings tab** (#404) — new Settings → Plugins screen iterating `SourcePluginRegistry.descriptors`. Three category sections: Fiction sources (16 in-tree), Audio streams (KVMR; v2 will grow this as the radio backend generalization in #417 lands), Voice bundles ("Coming in v2" placeholder until the voice bundle registry lands as a follow-up). Each plugin renders as a brass-edged card with a brass monogram icon, plugin name + description, capability chips (Search / Follow / Audio / Text), brass-edged switch, and tap-for-details modal sheet showing capabilities, plugin id, and source URL. Top of the screen has a search input (substring on displayName / description / id) and three filter chips (On / Off / All). Adding a new backend (a `@SourcePlugin`-annotated class) automatically surfaces a new card — no edit to the screen file needed.
- **`@SourcePlugin.description` + `@SourcePlugin.sourceUrl`** (#404) — annotation gains two new fields backfilled across all 17 in-tree backends. The KSP processor emits both into the generated descriptor; the plugin manager card uses `description` for the subtitle and the details sheet uses `sourceUrl` for the "where this plugin reads from" row.

### Changed
- **Plugin seam Phase 3 — single source of truth** (#384, last phase) — the legacy `BrowseSourceKey` enum is deleted; the 17 hand-rolled `sourceXxxEnabled` boolean fields on `UiSettings` are deleted; the 17 `setSourceXxxEnabled` setters on `SettingsRepositoryUi` and `SettingsViewModel` are deleted; the `BrowseScreen` + `BrowseViewModel` exhaustive `when (BrowseSourceKey)` branches collapse into id-keyed lookups + a registry-iteration picker. The dual-write that mirrored each per-source toggle into both a JSON map and a per-id boolean key is gone — there's one shape now (`sourcePluginsEnabled: Map<String, Boolean>`). A one-shot migration on first launch of v0.5.31 reads the legacy `pref_source_*_enabled` boolean keys ONCE and seeds the JSON map; subsequent toggles go through `SettingsRepositoryUi.setSourcePluginEnabled(id, enabled)`. New `BrowseSourceUi` side-table in `:feature/browse` carries the per-source UI hints (chip strip label, supported tabs, filter sheet shape, search hint copy) keyed by `SourceIds` constant — adding a new backend is one row here next to the `@SourcePlugin` annotation backfill, not 17 touchpoints across modules. **The "~17 touchpoints per new backend" → "~4" goal from #384's opening statement now holds.**
- **Settings → Library & Sync → Sources sub-card** simplified — the 17 inline per-backend toggle rows are gone; the sub-card now shows one "Plugins" link row (→ Settings → Plugins) plus the inline config rows for per-plugin configuration that doesn't belong in a checkbox toggle (EPUB folder picker, Outline host + API key, Wikipedia language code, Notion db + token, Discord bot token + server + coalesce window). The plugin manager handles every enable/disable + capability surface.

### Tests
- 6 Phase 3 tests: `RegistryDescriptorsAliasTest` (alias contract + description / sourceUrl fields), `BrowseSourceUiTest` (chipLabel / supportedTabs / filterShape / searchHint for all 17 ids + fallback), `NoBrowseSourceKeyRegressionTest` (greps the production tree to fail the build if `BrowseSourceKey` ever leaks back outside kdoc), `SettingsRepositorySourcePluginsTest` (round-trip for all 17 ids, legacy-key first-launch migration, no-dual-write into legacy booleans, forward-compat for unknown ids).
- 4 plugin manager tests: `PluginManagerLogicTest` covering `filterPlugins` (On / Off / All chip × search query × composition) and `groupPluginsForManager` (category split + order preservation + empty input).

## [0.5.30] — 2026-05-14

### Added
- **Discord as the 17th fiction backend** (#416, closes #403) — first chat-platform backend. Server → top-level filter, channel → one fiction, message → one chapter (optionally coalescing consecutive same-author messages within a configurable 1-30 min window, default 5). Auth is user-supplied bot token: user creates a Discord app, generates a bot token, invites their bot with `READ_MESSAGE_HISTORY` scope, pastes in Settings. No bundled token, no auto-join, no selfbot. OkHttp wrapper over 4 endpoints with structured 429 + `Retry-After` handling. 6 new unit tests covering coalesce edge cases + JSON-parse fixtures. Default OFF on fresh installs.
- **Cross-fiction AI memory + per-book Notebook tab** (#414, closes #217) — `FictionMemoryEntry` Room entity + `FictionMemoryRepository`, prompt-builder appends a "Cross-fiction context" block listing entries from OTHER fictions where detected names match in the current chat turn (capped ~500 tokens, oldest dropped). Per-fiction "Notebook" sub-tab on `FictionDetailScreen` shows recorded entities (characters / places / concepts) with manual-add / delete; Settings → AI → "Carry memory across fictions" toggle (default ON). Population is regex-based name detection on AI replies for v1 — approximate but cheap; follow-ups for structured LLM-call extraction, InstantDB sync, per-author/world partitioning, confidence scoring documented in the PR. Room schema v8 → v9 (additive). 25 net new tests.
- **Per-voice lexicon + Kokoro phonemizer language overrides** (#415, closes #197 #198) — Settings → Voice → per-active-voice Advanced expander gains SAF `.lexicon` file picker (#197 — IPA pronunciation overrides for hard-to-pronounce names; great for Royal Road's "Wei Wuxian" / "Lianhua" / "Aelindra" pain) and Kokoro phonemizer-lang chip strip (#198, 9 documented Kokoro codes). Per-voice (not global): each voice carries its own overrides; switching active voice re-applies the bridge static fields before the next chapter renders. **Cross-repo work**: upstream `techempower-org/VoxSherpa-TTS` v2.7.14 ships `voiceLexicon` + `phonemizerLang` static volatile fields; JitPack coordinate bumped from v2.7.13. 24 new tests across `VoiceEngineQualityBridgeTest` + `SettingsRepositoryVoiceLexiconLangTest` + `LexiconPathSafParseTest`.

### Fixed
- **`NetworkOnMainThreadException` on Wikipedia + Wikisource first chip tap** (#421, closes #419) — both backends' `getRaw` were non-suspend, doing blocking `client.newCall(req).execute()` without a `withContext(Dispatchers.IO)` wrapper. Same class of bug as the earlier Gutenberg fix. Crash caught by the QA-rerespawn agent on tablet + Flip3; logcat stacks captured on #419. **High-pri regression that affected two of the 16 backends.**

### Build state
- Four-PR bundle merged in order: #421 (smallest fix, no overlap) → #416 (Discord, new module + chip) → #415 (VoxSherpa knobs, voice picker) → #414 (cross-fiction memory + Room v8 → v9 migration). Each PR was independently CI-green before the wave landed; GitHub's mergeStateStatus recomputed BEHIND between each merge and the next merge fast-forwarded cleanly with no manual rebase needed.
- **17 fiction backends** total now: Royal Road, GitHub, Memory Palace, RSS, EPUB, Outline, Gutenberg, AO3, Standard Ebooks, Wikipedia, Wikisource, KVMR (audio-stream), Notion, Hacker News, arXiv, PLOS, Discord (new).

### Infrastructure (this session, not a release feature but worth recording)
- New global PostToolUse Bash hook at `~/.claude/hooks/screenshot-compress.sh` — auto-downscales any fresh PNG in `~/.claude/projects/` scratch dirs to JPEG q=80 / max 1280px on the long edge. Avoids the "400 Could not process image" multimodal API failure that killed the first QA agent. Verified end-to-end via `hook-tester` subagent. Lives outside storyvox but unblocks future tablet/phone QA passes.

## [0.5.29] — 2026-05-14

### Added
- **Wear OS Library Nocturne theme + circular scrubber** (#406, closes #192) — `:wear` module gets a brass-on-warm-dark theme matching the phone/tablet. `NowPlayingScreen` now wraps a Coil-loaded cover with a brass `CircularScrubber` on round watches (square form factor falls back to a brass-tinted linear scrubber). 3-button transport row wired to the existing `PhoneWearBridge`. Five `@Preview` entries cover round/playing, round/paused, round/buffering, small-round, and square so the visual diff is reviewable from the preview pane.
- **Voice library search + language filter** (#413, closes #264) — sticky search bar + horizontally-scrolling language filter chips at the top of the voice picker. Type-to-filter on voice display name (200ms debounce); chips derived dynamically from installed-voice languages (English-first, then alphabetical). Filters apply via `combine(installedVoices, query, language)` in the ViewModel; the existing favorites star + Starred surface still pin voices on top. Closes the "1188 voices, no search, unusable on Flip3" pain. 15 new `VoiceFilterTest` cases.
- **Room+Robolectric DAO test layer** (#410, closes #48) — 43 new DAO tests covering `FictionDao`, `ChapterDao`, `PlaybackDao` with slim-projection regression coverage, `@Transaction` boundaries, and `CASCADE` behavior. Tests run on a real in-memory Room database under Robolectric so SQL fidelity is preserved across the dependency graph. Pure additive — no production code touched.

### Changed
- **Browse source picker is now a scrollable `LazyRow` of `FilterChip`s** (#411, closes QA-found #407) — replaces the previous segmented-button row which mid-word-wrapped on tablet and silently hid the rightmost chips (arXiv / PLOS / Notion couldn't be reached on narrow viewports). Active chip carries the brass active-state coloring; the row pans horizontally so every backend stays reachable regardless of viewport width.

### Refactored
- **`AuthRepositoryImpl` now uses `@ApplicationScope CoroutineScope`** (#405, closes #30) — replaces the bare-`CoroutineScope` `init { ... }` block flagged as deferred-to-v1.0-hardening. Injected scope uses `SupervisorJob` for structured concurrency and is trivially swappable with `TestScope` in unit tests. No behavior change at runtime; auth init is structurally identical from the user's perspective.

### Build state
- All five PRs (#405 / #406 / #410 / #411 / #413) shipped as a bundle merge with no inter-PR conflict — each PR was independently CI-green before the wave landed, and the wave merged in dependency order (refactor → tests → small fix → voice picker → Wear) so cross-touching files (`BrowseScreen.kt`, voice picker) re-rebased cleanly between merges.

## [0.5.28] — 2026-05-13

### Added — four new backends, all using the v0.5.27 `@SourcePlugin` pattern from day one
- **Wikisource** (#376, #399) — Wikimedia project for transcribed public-domain texts (Shakespeare, classic novels, historical documents). Browse landing reads `Category:Validated_texts` (the double-proofread quality tier); free-form search via MediaWiki Action API. Multi-part works walked via `/Subpage` traversal; single-page works fall back to Wikipedia-style heading_1 splits. 14 unit tests. Default OFF on fresh installs.
- **arXiv** (#378, #400) — open-access academic pre-print server (physics, math, CS). Default category for browse is `cs.AI`; free-form search via the public `export.arxiv.org/api/query` Atom feed. Each paper is one fiction; v1 chapter is the abstract + title + author byline + comments rendered from the `arxiv.org/abs/<id>` HTML page. Full-PDF body extraction is an explicit follow-up scope cut. 12 unit tests. Default OFF.
- **Hacker News** (#379, #401) — front-page tech-news threads as single-chapter fictions. Popular surfaces the first 50 of HN's top-stories Firebase list; Search hits the Algolia HN Search API. Link stories include the title + URL + top 20 comments (threaded with `—` depth prefixes); Ask HN / Show HN use the story `text` field directly. 8 unit tests. Default OFF.
- **PLOS / Public Library of Science** (#380, #402) — open-access peer-reviewed science (PLOS ONE, Biology, Medicine, Comp Biology, Genetics, Pathogens, Neglected Tropical Diseases). Browse landing reads recent PLOS ONE sorted by publication date; Search hits the same Solr endpoint with free-form `q=`. Each article (one DOI) is one fiction; v1 chapter is the abstract + first ~3 body sections. 11 unit tests. Default OFF.

### Total backend count
- **16 fiction backends** now: Royal Road, GitHub (curated registry), Memory Palace, RSS, EPUB, Outline, Gutenberg, AO3, Standard Ebooks, Wikipedia, Wikisource (new), KVMR (audio-stream), Notion, Hacker News, arXiv (new), PLOS (new). All four new backends register via `@SourcePlugin` and surface in the `SourcePluginRegistry` automatically — adding new backends is now ~4 touchpoints instead of 17 (per the Phase 2 #384 work that shipped in v0.5.27).

### Under the hood
- Bundle merge orchestrated per `feedback_no_eager_merge_in_bundle`: all four PRs (#399, #400, #401, #402) opened in parallel from worktrees, merged in order (Wikisource → HN → arXiv hand-rebased → PLOS hand-rebased) once each backend's CI was independently green. arXiv and PLOS branches required hand-rebase + union-resolve of additive enum + Settings UI + test-fake additions; the agent-curated commits and tests survived intact.
- Five test fakes (`ChatViewModelTest`, three `SettingsViewModel*Test` flavors, and `RealPlaybackControllerUiTest`) updated with `setSourceArxivEnabled` / `setSourcePlosEnabled` stubs to match the new `SettingsRepositoryUi` interface members.

## [0.5.27] — 2026-05-13

### Added
- **Plugin seam — Phase 2: 11 remaining backends migrated to `@SourcePlugin`** (#384) — every fiction source (`royalroad`, `github`, `mempalace`, `rss`, `epub`, `outline`, `gutenberg`, `ao3`, `standard_ebooks`, `wikipedia`, `notion`) now carries a `@SourcePlugin(id=…, displayName=…, defaultEnabled=…, category=Text, supportsFollow=…, supportsSearch=…)` annotation on its `FictionSource` impl, with `ksp(project(":core-plugin-ksp"))` wired in each module's `build.gradle.kts`. The KSP processor emits one `@Provides @IntoSet SourcePluginDescriptor` Hilt module per annotated class, so the `SourcePluginRegistry` singleton now exposes the full 12-plugin roster (the 11 fiction backends + KVMR from Phase 1). Existing `@IntoMap @StringKey` Hilt bindings are intentionally kept — Phase 2 is additive over the legacy wiring; Phase 3 will delete the legacy `BrowseSourceKey` enum and the per-source `sourceXxxEnabled` booleans on `UiSettings` once the registry-driven UI lands.
- **`SourcePluginRegistry` duplicate-id guard** (#384) — registry `init` block now hard-fails at app startup with an `IllegalStateException` listing the offending ids when two `@SourcePlugin` annotations declare the same id. Catches a copy-paste mistake on a fresh-source addition before Hilt's silent which-one-wins multibinding behaviour can ship.
- Two new `SourcePluginRegistryTest` cases: the duplicate-id guard, and a Phase 2 roster contract test that asserts the registry surfaces all 12 expected `SourceIds.*` ids via `byId` and matches the expected size.



### Added
- **Cross-source Inbox tab in Library** (#383) — new fourth Library sub-tab (`All / Reading / Inbox / History`) surfaces a chronological feed of source-emitted events: "3 new chapters in The Wandering Inn", "KVMR live now", and (future) Wikipedia article updates. Tap a row to deep-link to the chapter/program; an unread-count badge sits on the tab itself. The feed is source-agnostic — one timeline across every backend that emits update events.
- **Per-source Inbox notification toggles** (#383) — Settings → Library & Sync gets an "Inbox notifications" sub-card with one switch per emitting backend (`Royal Road`, `KVMR`, `Wikipedia`). Default ON; flipping a toggle OFF stops the backend's update emitter from writing rows to the cross-source feed without affecting library updates or the source's visibility in Browse.
- **`InboxRepository` + `inbox_event` table** (#383) — Room migration v7 → v8 lands the append-only event table backing the Inbox. Repository coalesces consecutive "N new chapters" events for the same fiction so the feed doesn't flood after a long offline gap. No FK to fiction/chapter — events deliberately survive parent-row removal so the user can still see "Wikipedia: X updated" after they unfollow the article.
- `NewChapterPollWorker` (#383) now records an `InboxEvent` row alongside its existing chapter-diff persistence whenever a polled source has missing chapters and the user hasn't muted that source in the Inbox toggles. KVMR live-program emission + Wikipedia article-diff emission are tracked as follow-ups — v1 wires the seam but only Royal Road's existing poll path emits today.
- **Vertex AI service-account JSON auth** (#219, #397) — Settings → AI → Vertex gains a SAF JSON file picker alongside the existing API-key field. Picked service-account JSON is parsed/validated, encrypted at rest in `EncryptedSharedPreferences`, and used to mint 1-hour OAuth access tokens on demand (JWT-bearer RFC 7523, signed in-process with `java.security` — no `google-auth-library` dep). Tokens cached until ~5 min before `expires_in` and refreshed transparently. Mutually exclusive with the API-key mode at the storage layer. 20 new tests cover parse validation, JWT sign+verify, token cache lifecycle, and end-to-end Vertex SA dispatch via MockWebServer.
- **Plugin seam — Phase 1 scaffolding** (#384, #396) — `@SourcePlugin` annotation in `:core-data`, a KSP SymbolProcessor in the new `:core-plugin-ksp` module that emits Hilt `@IntoSet` factories for each annotated `FictionSource`, and a `SourcePluginRegistry` singleton that consumes the multibinding. New `pref_source_plugins_enabled_v1` JSON map preference (id → enabled) seeded from the existing per-source `SOURCE_*_ENABLED` boolean keys via the one-shot `SourcePluginsMapMigration`, with dual-write from every legacy setter so the old `UiSettings.sourceXxxEnabled` observers stay in sync. `:source-kvmr` migrated as the worked example (one `@SourcePlugin(id="kvmr", …)` line + a `ksp(project(":core-plugin-ksp"))` dep — its existing `@IntoMap @StringKey` Hilt binding is intentionally kept for Phase 1). Phase 2 (follow-up PRs) migrates the remaining 11 backends and switches BrowseScreen + Settings to iterate the registry.

## [0.5.25] — 2026-05-13

### Added
- **Anonymous Notion-site reader mode** (#393, closes the v0.5.24 known limitation) — `:source-notion` now reads public-shared Notion pages via the *unofficial* `www.notion.so/api/v3` surface (`loadPageChunk`, `queryCollection`, `syncRecordValuesMain`, `getPublicPageData` — the same set [react-notion-x](https://github.com/NotionX/react-notion-x)'s `notion-client` package uses). Zero setup: a fresh install opens Browse → Notion and immediately surfaces TechEmpower's content as narratable audio, with no integration token required.
- **Four-fiction TechEmpower layout (revised mid-cycle)** — Browse → Notion shows **four tiles**, one per top-level section of the techempower.org navigation. Each section is its own fiction, and each article inside it is its own chapter:
  - **Guides** — 8 chapters, one per curated guide page (How to use TechEmpower, Free internet, EV incentives, EBT balance, EBT spending, Findhelp, Password manager, Free cell service). Chapter order matches `techempower/site.config.ts` `pageUrlOverrides`.
  - **Resources** — N chapters (~80), one per row in the TechEmpower Resources database (queried via `queryCollection`). Each chapter renders the row's underlying Notion page content.
  - **About** — single-chapter fiction with the About page content.
  - **Donate** — single-chapter fiction with the Donate page content.
  This is a course correction from the v0.5.25-rc design that landed in PR #394 as a single-fiction-multi-chapter layout — JP redirected to "four books, each article a chapter" mid-cycle. The delegate, NotionDefaults, and AnonymousNotionDelegateTest were rewritten before tagging v0.5.25 so the released APK has the new shape.
- **`NotionConfig` mode enum** — new `NotionMode { ANONYMOUS_PUBLIC, OFFICIAL_PAT }` selects the read path. Anonymous mode reads any public-shared root page id; PAT mode keeps the original integration-token + database-id flow for private workspaces. The mode is implicit: blank token → anonymous, non-blank token → PAT. Existing users with a stored PAT keep their current experience unchanged.

### Fixed
- **Stale "TODO placeholder" rejection in `NotionApi.requireConfigured`** — v0.5.23 shipped a check that fast-failed when `databaseId == TECHEMPOWER_DATABASE_ID`; v0.5.24 replaced that id with the real TechEmpower Resources DB but left the check, silently breaking the bundled default for anyone with a PAT shared to the Resources DB. v0.5.25 removes the equality check; gating is now token presence alone in PAT mode and root-page-id presence in anonymous mode.

### Implementation
- `NotionUnofficialApi` (new) — OkHttp client for the four `/api/v3` endpoints with hand-crafted JSON bodies (the queryCollection loader shape is deeply nested; full kotlinx-serialization round-trips would be more code than the bodies). Process-lifetime in-memory cache keyed on hyphenated page id; deduplicates round-trips within a Browse → detail flow. Every HTTP call is wrapped in `withContext(Dispatchers.IO)` so the source can be safely called from any coroutine context.
- `AnonymousNotionDelegate` (new) — implements the FictionSource surface against `NotionUnofficialApi`. Builds a single Browse tile from the configured root page and resolves its chapter list from `NotionDefaults.techempowerChapters` (a hand-curated list of `ChapterSpec.Page` / `ChapterSpec.Collection` entries). Page chapters render their underlying Notion page's blocks via `renderPageBody`; collection chapters query the database via `queryCollection` and render a row-title list. Tombstoned blocks (`alive:false`) are filtered.
- `NotionConfigImpl` (modified) — persists a new `pref_notion_root_page_id` DataStore key. Defaults to `NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID` on first install; users can override via Settings.
- `NotionApi.requireConfigured` (bug fix) — removed the stale `databaseId == TECHEMPOWER_DATABASE_ID` placeholder check that v0.5.24 silently broke when it baked the real DB id into the same constant.
- 23 new unit tests in `AnonymousNotionDelegateTest` + `NotionUnofficialModelsTest` covering the recordMap envelope decode, decoration-array title extraction, page-id hyphenation, collection_view metadata read, chapter spec resolution (TechEmpower vs. generic), page-body rendering with tombstone filtering, collection-row title extraction + sorting, HTML/plain projection of the unofficial block types, mode-posture defaults, and tolerance for unknown top-level recordMap fields.

### Known caveats
- The unofficial `www.notion.so/api/v3` endpoints are undocumented; Notion may change their shape without notice. Storyvox decodes permissively (all block-payload fields are `JsonElement`) so unknown variants degrade silently rather than breaking parsing. Surface errors come back as structured `NotionUnofficialError` envelopes (`{isNotionError, errorId, name, message}`) which we map to standard `FictionResult.AuthRequired`/`NotFound`/`RateLimited`/`NetworkError`.

## [0.5.24] — 2026-05-13

### Fixed
- **`NotionDefaults.TECHEMPOWER_DATABASE_ID` now points at the real TechEmpower Resources database** (`2a3d706803c649409e74e9ce5ccd4c4b`, from `techempower/site.config.ts` line 48). Replaces the `TODO_FILL_IN_...` placeholder that shipped in v0.5.23. Users with their own Notion integration token shared with the database now see TechEmpower's Resources content as the default Notion fiction.

### Known limitation
- v0.5.24 still requires the user to paste a Notion integration token. The TechEmpower content lives at a publicly-shared `techempower.notion.site` URL, which is readable anonymously via Notion's *unofficial* `www.notion.so/api/v3/{loadPageChunk,queryCollection}` endpoints — but the official Notion REST API (which `:source-notion` currently uses) always returns 401 without auth, even for public content. v0.5.25 will land the anonymous-read mode + extend the tree to cover Guides + About + Donate alongside the Resources database (#393).

## [0.5.23] — 2026-05-13

### Added
- **Notion as a 12th fiction backend** (#391, closes #233 #390) — `:source-notion` module brings Notion databases into Browse alongside the other eleven backends. Database query → fiction list; pages split into chapters on every `heading_1`. PAT-based auth (Notion integration token), stored encrypted alongside the Outline / Palace / Royal Road / Wikipedia tokens. The token + database id are pasted in Settings → Library & Sync → Notion. 21 unit tests cover the API mappers, paginator, and config plumbing.
- **Notion default-on** (#390) — `sourceNotionEnabled = true` for fresh installs; existing users keep their stored preference. The default `databaseId` points at a `TODO_FILL_IN_TECHEMPOWER_DATABASE_ID` placeholder that returns a clean "Notion database id not configured" empty-state until JP pastes the real id (see [[NotionDefaults.kt]]).

### Changed
- Browse → Notion shows up as the rightmost source chip; Settings → Library & Sync has an inline NotionConfigRow with database-id + token fields.
- README + docs/index.md updated to "Twelve fiction sources" — the recurring "Six" / "Eleven" framing finally catches up to the actual surface.

### Known limitation
- The default `TECHEMPOWER_DATABASE_ID` is a placeholder string. The techempower.org website is backed by a Notion **page** (root page id `0959e445...`), not a database. Storyvox's `:source-notion` queries the Notion API's `databases/query` endpoint, which is a different object kind. A future change either (a) creates a separate Notion *database* in the techempower workspace to point at, or (b) extends `:source-notion` with a page-rooted hierarchy mode similar to the way `react-notion-x` traverses the website's root page. Until then, the default install shows the empty-state "Notion database id not configured (TODO placeholder still in use)" until the user pastes their own database id.

## [0.5.22] — 2026-05-13

### Infrastructure
- **Four sibling repos moved jphein → techempower-org** — storyvox-registry, speech-to-cli, cloud-chat-assistant, gnome-speaks. The `:source-github` Featured-row fetcher (`Registry.kt`) had a hardcoded URL pointing at `raw.githubusercontent.com/jphein/storyvox-registry/main/registry.json` — flipped to `techempower-org`. The old URL still 200s via GitHub's permanent raw redirect (so existing v0.5.21 installs keep working), but the new canonical URL is now baked into the v0.5.22 binary.
- USER_AGENT updated `storyvox/0.4 (+https://github.com/jphein/storyvox)` → `storyvox/0.5 (+https://github.com/techempower-org/storyvox)` so server-side logs / Plausible-style analytics see the canonical UA from this release forward.
- README + docs/index.md + docs/ROADMAP.md + settings.gradle.kts comment swept for `jphein/` references; spec docs left frozen as historical record.

## [0.5.21] — 2026-05-13

### Infrastructure
- **Repo moved from `realm-watch` to `techempower-org`** — second transfer of the day. realm-watch was originally framed around homelab theming; `techempower-org` is JP's company org and the more permanent home for storyvox and its product-line siblings. Eight repos transferred together: storyvox, storyvox-feeds, VoxSherpa-TTS, forageforall, techempower.org (the website), mempalace, palace-daemon, multipass-structural-memory-eval. realm-watch stays alive for future homelab projects.
- Owners / Maintainers / Contributors teams replicated on techempower-org with the same admin / maintain / push permissions; all eight repos team-bound. CODEOWNERS team mentions updated to `@techempower-org/*`. Branch protection on `main` re-applied (CODEOWNERS review + green CI + no force-push + no deletion + conversation resolution required).
- VoxSherpa-TTS JitPack coordinate: `com.github.techempower-org:VoxSherpa-TTS:v2.7.13` (was `com.github.realm-watch:...`, was `com.github.jphein:...`). Verified the new coordinate builds with `--refresh-dependencies` before flipping.
- SIGIL_REPO updated; CLAUDE.md memories swept of `realm-watch/storyvox` references.

## [0.5.20] — 2026-05-13

### Added
- **Audio-stream backend category** (#389, closes #373) —
  `ChapterContent` gains optional `audioUrl: String?`. When non-null
  the playback engine bypasses the TTS pipeline and routes the URL
  through a sibling Media3 ExoPlayer instance; when null (every
  existing backend) the TTS path is unchanged. Schema migration
  v6→v7 adds the `audioUrl` column so live-stream URLs persist
  across reboots. Pitch slider hides on live audio (Sonic
  pitch-shifting applies to engine-rendered PCM, not network audio).
- **KVMR community radio** (#389, closes #374) — first concrete
  entry in the audio-stream category. JP's local station; single
  live fiction whose one chapter (`Live`) carries the AAC stream
  URL from KVMR's public listen-live page. Defaults ON. Browse →
  KVMR → Live; lockscreen MediaSession surfaces "KVMR Community
  Radio" with transport controls.

### Changed
- **FictionDetail Follow button generalized** (#388, closes #382) —
  the hardcoded `sourceId == "royalroad"` check becomes
  `FictionSource.supportsFollow: Boolean` (default false), plumbed
  through `FictionSummary.supportsFollow` and
  `UiFiction.sourceSupportsFollow`. RoyalRoadSource opts in;
  future AO3 / GitHub-watch / Wikipedia-watchlist / etc backends
  opt in with one line of override.

### Infrastructure
- **Repo transferred from `jphein` to the `realm-watch` org** —
  storyvox + storyvox-feeds + VoxSherpa-TTS all moved. Public on
  the free tier. Branch protection on `main` requires CODEOWNERS
  review + green CI; no force-push, no deletion. Three teams
  (Owners / Maintainers / Contributors) wired with admin / maintain
  / push permissions. CODEOWNERS routes sensitive paths
  (`storyvox-debug.keystore`, `.github/workflows/`, `CLAUDE.md`) to
  Owners and build/release-affecting files to Maintainers. Org-level
  Projects v2 board "storyvox roadmap" with Priority + Area fields.
  VoxSherpa-TTS JitPack coordinate updated to
  `com.github.realm-watch:VoxSherpa-TTS:v2.7.13`.

## [0.5.19] — 2026-05-13

### Added
- **Three new fiction backends** landed in parallel:
  - **Archive of Our Own** (#385, closes #381) — fanfiction via per-tag
    Atom feeds + official EPUB downloads. Zero scraping. Six curated
    fandoms in v1 (Marvel/HP/SW/Original Work/Sherlock/Good Omens).
    Defaults OFF (Explicit-rated possibility).
  - **Standard Ebooks** (#386, closes #375) — curated typographically
    polished public-domain classics. Catalog via SE's public HTML
    listing (schema.org RDFa structured data), content via per-work
    EPUB. Pairs with Gutenberg as the "polished classics" companion.
  - **Wikipedia** (#387, closes #377) — first non-fiction long-form
    backend. Each article = one fiction, each top-level section = one
    chapter. Search via opensearch, Popular = Today's Featured Article
    + mostread cluster. Per-language host configurable in Settings.

### Changed
- **Sonic pitch-interpolation quality toggle** (#372, closes #193) —
  new Settings → Voice & Playback switch *"High-quality pitch
  interpolation"*, defaults ON. Cross-repo with VoxSherpa-TTS v2.7.13
  which parameterized `Sonic.setQuality` via static fields on both
  VoiceEngine and KokoroEngine.

## [0.5.18] — 2026-05-13

### Fixed
- **Gutenberg Browse tap no longer crashes** (#371) —
  `GutendexApi.{request, downloadEpub}` now wrap their OkHttp
  `execute()` calls in `withContext(Dispatchers.IO)`. `suspend`
  alone doesn't move work off the main thread; the previous
  implementation tripped StrictMode's `NetworkOnMainThreadException`
  on the first DNS lookup. Pattern now matches `:source-outline` /
  `:source-rss`.
- **Pick-a-voice picker** (#369) — surfaces three Piper quality
  tiers of Lessac (en_US) plus two of Cori (en_GB), not a mixed
  Cori/Lessac/Aoede grab-bag. Removes Aoede's misleading "1 MB"
  size (Kokoro `sizeBytes = 0` is correct — shared model — but
  rounded nonsensically). Strips stale ⭐ from Lessac/Ryan/Amy
  `displayName` so favorites can own the glyph unambiguously.

### Changed
- **Voices is now a first-class bottom-nav slot** (#370, closes
  #264 nav part) — replaces Settings in the bottom bar (last
  position, `RecordVoiceOver` icon). Settings moves to a gear
  IconButton in every main screen's top bar (Library, Browse,
  Follows, Playing, Voices). Voice-picking is a high-frequency
  activity for an audio-first app, not a set-once preference.
- First-launch default voice changes from `piper_cori_en_GB_high`
  (114 MB) to `piper_lessac_en_US_low` (63 MB) — the smallest of
  the new starter triplet. Users who want richer audio pick
  Medium or High in the picker before the gate dismisses.

## [0.5.17] — 2026-05-13

### Added
- **Follow on Royal Road button on FictionDetail** (#368, closes
  #211) — inline action bar gains a third button next to *Add to
  library* and *Listen*, visible only on RR-sourced fictions.
  Pushes the follow state to RR's account via the existing
  `RoyalRoadSource.setFollowed()` (CSRF + POST to
  `/fictions/setbookmark`). Anonymous tap routes to the same
  `AUTH_WEBVIEW` Browse and Settings already use. Closes the
  two-way sync loop — pull from `/my/follows` was already wired in
  v0.4.x.

### Changed
- `UiFiction` and `FictionSummary` gain `sourceId` and
  `followedRemotely` fields (defaulted to be backward-compatible
  with all existing construction sites).

## [0.5.16] — 2026-05-13

### Changed
- **RSS feed management moves to a Browse FAB** (#367, closes #247) —
  add / list / remove / suggested-feeds all live on a `+ Add feed`
  FAB-launched sheet from Browse → RSS now. The Settings page keeps
  only the on/off toggle (its subtitle points users at the new home).
  Same underlying repository API; only the home screen changed.

## [0.5.15] — 2026-05-13

### Added
- **Project Gutenberg backend** (#366, closes #237) — 70,000+
  public-domain books via Gutendex. New `:source-gutenberg` module:
  catalog browsing via the JSON API; add-to-library downloads each
  book's EPUB to `cacheDir/gutenberg/<id>.epub` and renders chapters
  through `:source-epub`'s parser. Most-legally-clean source in the
  storyvox roster — PG actively encourages programmatic access.
  Defaults to ON for fresh installs.

### Changed
- New `BrowseSourceKey.Gutenberg` chip in the picker; supports
  Popular / NewReleases / Search tabs. BestRated has no analogue on
  PG. No filter sheet in v1 — topic search through the Search tab
  covers the discovery cases.

## [0.5.14] — 2026-05-13

### Added
- **Royal Road soft sign-in gate on Browse listings** (#365, closes
  #241) — when the user is not signed in to RR, the Browse → RR
  Popular / NewReleases / BestRated / filter-active tabs render a
  brass sign-in CTA instead of firing an anonymous request. Search
  and Add-by-URL stay open anonymously. Authenticated traffic
  removes the "anonymous bot" framing — every listing fetch now
  carries a real RR session cookie. Closes #240 as superseded
  (#241's soft alternative chosen).

### Changed
- `BrowseViewModel` — three auth signals (gh sign-in, gh repo scope,
  rr sign-in) now bundle through a single `AuthSnapshot` flow so the
  outer controls combine stays under the 5-arg overload ceiling
  after gaining a third boolean.

## [0.5.13] — 2026-05-13

### Added
- **EPUB export from FictionDetail** (#364, closes #117) — overflow-menu
  "Export as EPUB" action. New `:source-epub-writer` module mirrors
  the reader/import that landed in #235: persisted rows assemble into
  a valid EPUB 3.0 zip at `cacheDir/exports/<sanitized-title>.epub` and
  hand off to the Android share-sheet through a scoped FileProvider
  (`xml/file_paths.xml` only exposes `exports/`, not the rest of
  cacheDir). `<dc:source>` metadata names the original backend
  (Royal Road, RSS, Outline, GitHub, EPUB).

### Changed
- `ChapterDao.allChapters(fictionId)` — new single-pass query returning
  every chapter row (including bodies) for export. Independent of the
  shelves/history v6 schema; no migration.

## [0.5.12] — 2026-05-13

### Added
- **InstantDB cloud sync foundation** (#360, #158-adjacent) — new
  `core-sync` module syncing library, follows, playback positions,
  bookmarks, pronunciation dictionary, and secrets through InstantDB.
  Magic-code sign-in screen, conflict policies per syncer, 24h tombstone
  TTL so re-adds propagate, PBKDF2 600k rounds (NIST 2024 / OWASP) for
  user-derived keys, format-v2 envelope. App cold-start initializes the
  sync graph.
- **Library shelves** (#362, closes #116) — predefined Reading / Read /
  Wishlist shelves with many-to-many membership. Chip-row filter above
  the library grid (visible on the All sub-tab), long-press a cover to
  open the manage-shelves bottom sheet. Empty state copy reads per
  shelf instead of the generic "library is empty".
- **Reading history sub-tab** (#363, closes #158) — Library now has
  All / Reading / History sub-tabs. History is a chronological feed of
  every chapter open, most-recent first, with relative-time labels
  ("2h ago"). Tapping a row opens the reader at that chapter without
  auto-starting audio. Forever retention.
- **Magical resume prompt on the Playing tab** (#361) — when the user
  has paused mid-chapter, opening Playing surfaces a brass-themed
  Library Nocturne prompt to resume from the saved offset.

### Changed
- `LibraryTab.Reading` coerces the chip filter to `OneShelf(Reading)`
  internally, so the same shelf-scoped Room flow drives both surfaces.
  Chip row is hidden on the Reading and History tabs (the tab is the
  filter / history is its own feed).
- Room database schema bumped to **v6**. Migration chain is `1→2→3→4→5→6`
  with all steps purely additive — no existing data touched. `v5` adds
  `fiction_shelf` (junction), `v6` adds `chapter_history` (one row per
  fiction+chapter, upsert on open).

### Fixed
- (Post-merge fix) `ChapterDao.allBookmarks()` newly-abstract member
  broke the test fixtures' two `FakeChapterDao` stubs; both now
  implement the override.

## [0.5.11] — 2026-05-12

### Fixed
- Bottom-tab taps lost under playback recomposition (#359) — dropped
  `popUpTo + saveState/restoreState` with mixed enter/exit transitions
  that committed the back-stack swap without rendering. Trade-off is
  lost tab-scroll-position memory.

## [0.5.07] — 2026-05-12

### Fixed
- RSS chapter reorder crash (#350) — atomic two-phase chapter upsert
  parks existing indexes to `+100_000` inside a `@Transaction` before
  upserting the fresh batch, preventing SQLite's immediate UNIQUE
  constraint check from firing mid-batch.

## Earlier — see `git log`

Releases v0.5.00 through v0.5.10 predate this changelog. The git log
captures their contents — every `release: vX.Y.ZZ` commit has a
substantive body. Notable milestones:

- **v0.5.00** (2026-05-10) — milestone release. UX wave (audit, research,
  build, grind), played indicators, nav/playback survival, settings
  shimmer, browse polish.
- **v0.5.07** (2026-05-12) — RSS reorder UNIQUE-constraint crash fix.
- **v0.5.10** (2026-05-12) — chapter bookmarks (#121) + self-hosted CI
  runner (#358) migrating off the capped jphein hosted-Actions minutes.
- **v0.5.11** (2026-05-12) — library nav fix while audio is playing.
