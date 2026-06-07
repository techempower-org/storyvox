# Candela Maestro flows

Live-app UI flows driven by [Maestro](https://maestro.mobile.dev). Maestro reads
the Compose **semantics** tree, so it works where uiautomator can't — *provided*
the app exposes testTags as resource-ids.

## The testTagsAsResourceId bridge — validated 2026-06-06
Compose testTags are invisible to Maestro/uiautomator unless the app sets
`Modifier.semantics { testTagsAsResourceId = true }` at the Compose root
(MainActivity, #1090). With it, every `testTag` surfaces as a resource-id.

**Proven on-device (R83W80CAFZB, v1.1.2):** `maestro hierarchy` returns the whole
Compose tree as text — onboarding copy + all nav/screen testTags as resource-ids —
where the pre-bridge uiautomator dump was empty. `nav-smoke` passes 10/10.

## Run (tablet on katana)
    export PATH="$PATH:$HOME/.maestro/bin"
    maestro test .maestro/nav-smoke.yaml
    maestro hierarchy            # perceive primitive — Compose as text

## Flows
- `nav-smoke.yaml` — bridge validation + nav destinations. **GREEN (10/10).**
- `open-reader.yaml` — seed the bundled sample (#1089) → open reader. *Pending:* needs a debug build (the LOAD_SAMPLE intent is BuildConfig.DEBUG-gated).
- `highlight.yaml` — #1079 select→highlight→assert. *Pending* #1088 + highlight-control testTags.

## Fresh-install precondition
A fresh install runs 3-step onboarding ending in a voice-download gate
(Lessac, 63 MB) + a skippable sign-in step. Flows assume the app is past
onboarding. (See docs/testing/SEED.md.)

## Seeding a book (deterministic, no network — #1089, debug build only)
    adb shell am start -a android.intent.action.VIEW \
      -n org.techempower.candela/in.jphe.storyvox.MainActivity \
      --ez in.jphe.storyvox.debug.LOAD_SAMPLE true
Known passage offsets (e.g. "lamplighter" 4–15) live in docs/testing/SEED.md.
