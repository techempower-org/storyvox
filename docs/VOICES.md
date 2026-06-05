# Voice catalog & model hosting

## Where voices come from

Candela embeds the [VoxSherpa-TTS](https://github.com/techempower-org/VoxSherpa-TTS) engine via JitPack, which itself wraps [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) for the actual ONNX inference. The neural model weights — Piper voices, the multi-speaker Kokoro model, and the **KittenTTS** lightest-tier model (shipped v0.5.36) — are not bundled in the APK (they'd add 1+ GB) but downloaded on demand by `VoiceManager` from the `voices-v2` GitHub release on `techempower-org/VoxSherpa-TTS`.

Three voice families ship today: **Piper** (compact, ~14–30 MB per voice), **Kokoro** (multi-speaker, ~330 MB shared), and **KittenTTS** (~24 MB shared across 8 en_US speakers — the "first chapter in 10 seconds" tier for slow devices).

`voices-v2` is a re-hosting of the upstream k2-fsa tarballs as flat, single-file downloads. Each Piper voice ships as `{lang}-{voice}-{quality}.onnx` + `{lang}-{voice}-{quality}.tokens.txt`. Kokoro ships as `kokoro-model.onnx` + `kokoro-voices.bin` + `kokoro-tokens.txt`. The flattening is deliberate: extracting `.tar.bz2` archives on modest hardware (Tab A7 Lite) is slow enough to delay the first chapter for tens of seconds. Doing it once server-side and serving plain files moves that cost off the device.

## The fp32 / int8 thing

The previous `voices-v1` release used INT8-quantized models (~3× smaller download, but a vocoder run through INT8 dynamic quantization adds audible noise to spectral coefficients — the "fuzz" symptom users heard on Samsung tablets in v0.4.x).

`voices-v2` uses **fp32** weights, exactly what's inside the upstream `vits-piper-*.tar.bz2` and `kokoro-multi-lang-v1_1.tar.bz2` archives. Larger downloads, clean speech.

The `_int8` suffix in `VoiceCatalog.kt` voice IDs (e.g. `piper_lessac_en_US_high_int8`) is historical — kept stable so already-installed users don't have to re-pick a voice when they update. The actual on-disk files and URLs no longer involve quantization.

## Refreshing the catalog

```bash
# Compare upstream to voices-v2 (no changes, no auth needed)
./scripts/voices/refresh-voices-v2.sh --check-only

# Pull new tarballs, extract, upload (needs gh CLI auth with write
# access to techempower-org/VoxSherpa-TTS; uses ~5 GB temp space)
./scripts/voices/refresh-voices-v2.sh
```

The script:
1. Lists `tts-models` assets on `k2-fsa/sherpa-onnx` matching `vits-piper-(en_US|en_GB)-*.tar.bz2` (no `-int8`, no `-fp16` suffix) and `kokoro-multi-lang-v1_1.tar.bz2`.
2. Diffs against assets currently on `voices-v2`.
3. Downloads any missing tarballs (parallel x8).
4. Extracts and flattens — drops `MODEL_CARD`, `espeak-ng-data/`, and the various Kokoro lexicon/dict files (Candela bundles its own espeak data).
5. Uploads everything to `voices-v2` with `--clobber` so reruns are idempotent.

When you add a new upstream voice, also add a `CatalogEntry` to `core-playback/src/main/kotlin/in/jphe/storyvox/playback/voice/VoiceCatalog.kt`. The script does **not** edit the catalog automatically — that part is a deliberate human review.

## Automated drift detection

`.github/workflows/voice-catalog-check.yml` runs on the 1st of every month (and on manual dispatch). It performs the same diff as `--check-only` and, if there's drift, files a GitHub issue listing the new upstream voices. It does not auto-publish — refreshing `voices-v2` requires write access to `techempower-org/VoxSherpa-TTS` which the default `GITHUB_TOKEN` doesn't have.

If the issue comes in: pull the storyvox repo, run `./scripts/voices/refresh-voices-v2.sh`, edit `VoiceCatalog.kt` to add the new entries, ship.

## Why we don't auto-update the in-app catalog

`VoiceCatalog.kt` is compiled into the APK. To pick up new voices the user has to install a new app version. That's intentional:
- The catalog includes language tags, quality tier hints, recommended voices, etc. that need human curation.
- Adding voices is rare enough (months between k2-fsa releases) that a per-release update doesn't hurt.
- A network-fetched dynamic catalog would add a runtime failure mode and an "is the index loading?" UX state. Not worth it for the cadence.

If the cadence ever picks up, switch to a JSON file in the same `voices-v2` release that the app fetches and caches; `VoiceCatalog.kt` becomes a fallback. That's a v1.x feature, not v0.4.x.
