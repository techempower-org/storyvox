# storyvox

Android app that turns text from 20+ sources into narrated audiobooks via TTS. Kotlin, Jetpack Compose, Hilt, Room, OkHttp.

## Build

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :feature:compileDebugKotlin # fast compile check (no APK)
./gradlew :source-ao3:testDebugUnitTest  # single module tests
./gradlew testDebugUnitTest           # all tests
```

CI runs on self-hosted runner (katana). Tags trigger release APK build + GitHub release.

## Module layout

- **app** — nav graph (`StoryvoxNavHost`), DI wiring (`AppBindings`), `SettingsRepositoryUiImpl`
- **feature** — all UI: `browse/`, `reader/`, `library/`, `settings/`, `chat/`, `fiction/`, `voicelibrary/`, `onboarding/`
- **core-data** — `FictionSource` interface, `SearchQuery`, `FilterDimension`/`FilterState`, Room DB, models
- **core-playback** — TTS engine (`EnginePlayer`), voice catalog, audio focus
- **core-llm** — AI chat, summaries
- **core-sync** — InstantDB cloud sync
- **core-ui** — shared theme, spacing, composables
- **core-plugin-ksp** — `@SourcePlugin` annotation processor → Hilt `@IntoSet` factories
- **source-*** — 24 source modules, each implements `FictionSource`

## Key patterns

**Source plugin contract**: Each source module has a `*Source.kt` implementing `FictionSource`. Annotated with `@SourcePlugin` — KSP generates Hilt bindings into `SourcePluginRegistry`. To add a source: create module, implement `FictionSource`, annotate class, add to `settings.gradle.kts`.

**Browse filters**: Sources declare `filterDimensions()` returning `List<FilterDimension>` (Sort, Select, TagSet, NumberRange, DateRange, Toggle, Text). `DynamicFilterSheet` renders them generically. Sources implement `applyFilters(base, state)` to translate UI state → `SearchQuery`.

**Navigation**: `StoryvoxNavHost.kt` defines all routes as `StoryvoxRoutes` constants. Bottom bar: Playing, Library, Follows, Browse, Voice Library, Settings Hub.

**Testing**: JUnit 4, no Robolectric. Tests use fake `FictionSource` implementations (see `PluginManagerLogicTest` for the pattern). Compose UI tests use `createComposeRule()`.

## Large files (read with offset/limit)

- `EnginePlayer.kt` — 4400 lines
- `SettingsScreen.kt` — 3900 lines (legacy long-scroll, being replaced by hub)
- `SettingsRepositoryUiImpl.kt` — 2900 lines
- `AudiobookView.kt` — 2200 lines
- `UiContracts.kt` — 2200 lines

## Versioning

`app/build.gradle.kts` — `versionName` (semver) + `versionCode` (monotonic int). Bump both for every tagged release. realm-sigil provides deterministic build names.

## Ship pipeline

commit → push → PR → CI green → merge → version bump on main → tag → CI builds APK → download → install on R83W80CAFZB + R5CRB0W66MK → phone check → Slack

**CI wait**: Use ONE `run_in_background` call, never poll manually:
```bash
until gh run view $RUN_ID --json status --jq '.status' | grep -q completed; do sleep 30; done
```

**Version bump**: Edit `app/build.gradle.kts` — increment both `versionCode` and `versionName`. Commit as `chore(release): v$VERSION — $tagline`. Tag and push in one shot: `git push && git tag v$VERSION && git push origin v$VERSION`.

**Phone check**: Run `./scripts/phone-check.sh <serial> <versionCode>` on both devices. NOT a subagent — the script is 30 lines and costs ~500 tokens vs ~39K for an agent.

**Slack**: `~/.claude/scripts/slack-storyvox.sh '<message>'` — takes TEXT, not files. Template structure:
```
:candle: *storyvox $VERSION* — $tagline
✦  _$SIGIL_NAME_  ✦
> $poetic_line
_TechEmpower — Technology for All. Access Made Easy._ (links)
*What's new* (feature bullets with emoji from docs/slack-release-template.md palette)
*Under the hood* (infra/test bullets)
*Install* (APK link, release link, compare link)
_Installed on ... · clean launch._
:hammer_and_wrench:  realm `fantasy`  ·  built `$time`  ·  commit `$hash`
```
Sigil: `python3 -c "..."` with realm-sigil word lists from `~/Projects/realm-sigil/words/realms.json`.
