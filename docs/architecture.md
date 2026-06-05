---
layout: default
title: Architecture
description: Candela's thirty-four Gradle modules, plugin-seam fiction sources via @SourcePlugin + KSP, in-process TTS engine, optional cloud TTS backend, and cross-device InstantDB sync.
---

# Architecture

Candela is **thirty-four Gradle modules** (up from 13 at v0.4.x; 29 at v0.5.38). The split keeps fiction sources, playback, UI, theming, sync, and the LLM provider matrix independently testable, and makes adding a new fiction source (or swapping the TTS backend) a localized change. Since v0.5.27 each fiction source is annotated with `@SourcePlugin`; the `:core-plugin-ksp` KSP processor emits a Hilt `@IntoSet` factory per annotated class, so `SourcePluginRegistry` discovers backends at startup. Adding a new backend is ~4 touchpoints today.

```
┌─────────────────────────────────────────────┐
│  :app                                       │
│  Hilt root · NavHost · Settings adapters    │
└──────┬──────────────┬───────────────────────┘
       │              │
       │              ▼
       │       ┌──────────────────────┐
       │       │  :feature            │
       │       │  Library / Follows / │
       │       │  Browse / Reader /   │
       │       │  Detail / Settings / │
       │       │  AI Chat / TechEmp.  │
       │       │  Home / Voices       │
       │       └──┬──────┬─────┬──────┘
       │          │      │     │
       ▼          ▼      ▼     ▼
┌────────────┐ ┌─────────────┐ ┌───────────────┐ ┌───────────────┐
│ :core-data │ │ :core-      │ │  :core-llm    │ │  :core-ui     │
│  Room +    │ │  playback   │ │  Provider     │ │  Library      │
│  repos +   │ │  EnginePlyr │ │  matrix       │ │  Nocturne     │
│  @Source-  │ │  + PcmCache │ │  (Claude /    │ │  theme +      │
│  Plugin    │ │  + Voice    │ │   Teams /     │ │  components   │
│  registry  │ │  Manager +  │ │   OpenAI /    │ │               │
│  + plugin  │ │  Sentence   │ │   Vertex /    │ │               │
│  manager   │ │  Tracker    │ │   Bedrock /   │ │               │
│  state     │ │  (in-proc + │ │   Foundry /   │ │               │
│            │ │   Azure)    │ │   Ollama)     │ │               │
└─────┬──────┘ └──────┬──────┘ └───────┬───────┘ └───────────────┘
      │               │                │
      │               │ JitPack:       │ ChatStreamEvent +
      │               │ VoxSherpa-TTS  │ ToolCatalog
      │               │ :engine-lib    │ (function calling) +
      │               │ (Piper /       │ ImageContentBlock
      │               │  Kokoro /      │ (multi-modal)
      │               │  KittenTTS,    │
      │               │  in-process)   │
      ▼               │                ▼
┌─────────────────────────────┐  ┌──────────────────────┐
│ Fiction sources (21)        │  │ :core-sync           │
│ ─────────────────────────── │  │ InstantDB sync —     │
│ :source-royalroad           │  │ library / follows /  │
│ :source-github              │  │ positions / book-    │
│ :source-rss                 │  │ marks / pronuncia-   │
│ :source-epub                │  │ tion / secrets       │
│ :source-outline             │  │ (#360 v0.5.12)       │
│ :source-mempalace           │  └──────────────────────┘
│ :source-gutenberg           │
│ :source-ao3                 │  ┌──────────────────────┐
│ :source-standard-ebooks     │  │ :core-plugin-ksp     │
│ :source-wikipedia           │  │ KSP processor —      │
│ :source-wikisource          │  │ @SourcePlugin →      │
│ :source-radio               │  │ Hilt @IntoSet        │
│ :source-notion              │  │ factory (v0.5.27)    │
│ :source-hackernews          │  └──────────────────────┘
│ :source-arxiv               │
│ :source-plos                │  ┌──────────────────────┐
│ :source-discord             │  │ :baselineprofile     │
│ :source-telegram   (v0.5.51)│  │ Producer module —    │
│ :source-palace     (v0.5.51)│  │ UI Automator hot-    │
│ :source-slack      (v0.5.51)│  │ path walk emits      │
│ :source-matrix     (v0.5.51)│  │ baseline-prof.txt    │
│                             │  │ (#409 v0.5.46) —     │
│ Catch-all + writer          │  │ cold launch 6.7 s    │
│ ─────────────────────────── │  │ → 0.8 s on Tab A7    │
│ :source-readability         │  │ Lite                 │
│ :source-epub-writer         │  └──────────────────────┘
│                             │
│ TTS backends                │
│ ─────────────────────────── │
│ :source-azure (cloud BYOK)  │
└─────────────────────────────┘
                         ▼
                   (audio out)
```

## Module map

| Module | Role | Key types |
|---|---|---|
| `:app` | Hilt root, NavHost, top-level wiring. | `StoryvoxApp`, `MainActivity`, `AppBindings` |
| `:feature` | Compose UI for every screen. | `LibraryScreen`, `BrowseScreen`, `ReaderScreen`, `SettingsScreen`, `VoiceLibraryScreen`, `ChatScreen` |
| `:core-data` | Room schema, repositories, fiction-source contract. | `FictionSource`, `ChapterRepository`, `FictionRepository`, `SettingsRepositoryUi` |
| `:core-playback` | Audio engine, voice management, sentence tracking, multi-engine producer. | `EnginePlayer`, `EngineStreamingSource`, `PcmCache`, `VoiceManager`, `VoiceCatalog`, `SentenceTracker` |
| `:core-llm` | Provider matrix for AI chat — Claude direct, Anthropic Teams (OAuth), OpenAI, Vertex, Bedrock, Foundry, Ollama. `ChatStreamEvent` flow type carries text deltas + tool-call + tool-result events; `ToolCatalog` powers function calling; `ImageContentBlock` carries multi-modal images. | `LlmProvider`, `ChatRepository`, `GroundingContext`, `RecapEngine` |
| `:core-ui` | Library Nocturne theme, shared components. | `LibraryNocturneTheme`, `BrassButton`, `BrassProgressTrack`, spacing/color tokens |
| `:core-plugin-ksp` | KSP SymbolProcessor — emits a Hilt `@IntoSet` factory per `@SourcePlugin`-annotated `FictionSource`, so `SourcePluginRegistry` discovers backends at startup. Pure Kotlin/JVM module (runs in the Kotlin compiler). | `SourcePluginSymbolProcessor`, `SourcePluginAnnotation` |
| `:core-sync` | InstantDB cross-device sync — library, follows, positions, bookmarks, pronunciation overrides, encrypted secrets. Magical sign-in surface. | `InstantDbClient`, `SyncRepository`, `SignInController` |
| `:source-royalroad` | Royal Road implementation of `FictionSource`. | `RoyalRoadSource`, `RoyalRoadFetcher`, `RoyalRoadParsers`, `LoginWebView` |
| `:source-github` | GitHub-repo implementation of `FictionSource`. | `GithubSource`, `GithubFetcher`, `BookTomlParser`, `CommonmarkRenderer`, `DeviceFlowAuth` |
| `:source-rss` | RSS / Atom-feed implementation. Pulls suggested-feeds list from [storyvox-feeds](https://github.com/techempower-org/storyvox-feeds). | `RssSource`, `RssFetcher`, `RssParser`, `RssFeed` |
| `:source-epub` | Local-EPUB-folder implementation via the Storage Access Framework + OPF parser. | `EpubSource`, `EpubParser`, `EpubModels` |
| `:source-epub-writer` | EPUB export — convert a fiction's downloaded chapters into a portable .epub for offline / cross-device reading. | `EpubWriter`, `OpfBuilder` |
| `:source-outline` | Outline (self-hosted wiki) implementation — collections as fictions, articles as chapters. | `OutlineSource`, `OutlineApi`, `OutlineConfig` |
| `:source-mempalace` | Read-only LAN-only [MemPalace](https://github.com/techempower-org/mempalace) source — wings as collections, rooms as fictions, drawers as chapters. | `MempalaceSource`, `MempalaceFetcher`, `PalaceDaemonClient` |
| `:source-gutenberg` | Project Gutenberg implementation — 70,000+ public-domain books, search by author/title/subject. | `GutenbergSource`, `GutenbergApi`, `GutenbergParser` |
| `:source-ao3` | Archive of Our Own — per-tag feeds + official EPUBs. Auth PR1 landed (v0.5.51, #426); login wiring in PR2. | `Ao3Source`, `Ao3Parsers`, `Ao3Auth` |
| `:source-standard-ebooks` | Hand-curated typographically-polished public-domain classics. | `StandardEbooksSource` |
| `:source-wikipedia` / `:source-wikisource` | Any Wikipedia article (heading-split chapters); Wikisource walks multi-part works as `/Subpage` chapters. | `WikipediaSource`, `WikisourceSource` |
| `:source-radio` | Radio Browser API search + five curated stations (KVMR, Cap Public, KQED, KCSB, SomaFM). | `RadioSource`, `RadioBrowserApi` |
| `:source-notion` | Notion page or database — defaults to techempower.org's resource library (Guides, Resources, About, Donate, ~80 entries). Beautiful page-cover + body-image fallback with brass-edged synthetic tiles (v0.5.51, #514). | `NotionSource`, `NotionAnonymousReader`, `NotionPageWalker`, `BrandedCoverTile` |
| `:source-hackernews` / `:source-arxiv` / `:source-plos` | Hacker News top + Ask/Show with comments; arXiv abstracts; PLOS open-access papers. | `HackerNewsSource`, `ArxivSource`, `PlosSource` |
| `:source-discord` | Discord channels as fictions — channels = fictions, messages = chapters, bot-token auth. | `DiscordSource`, `DiscordApi`, `DiscordCoalescer` |
| `:source-telegram` *(v0.5.51, #462)* | Telegram Bot API — public channels as fictions, messages as chapters. Simpler shape than Discord (no threads, no servers, no search). | `TelegramSource`, `TelegramBotApi` |
| `:source-palace` *(v0.5.51, #502)* | Palace Project library backend — first library-borrowing source. OPDS catalog walker. Ships non-DRM titles in this PR; LCP DRM deferred. | `PalaceSource`, `OpdsWalker`, `PalaceCatalog` |
| `:source-slack` *(v0.5.51, #454)* | Slack Web API — channels as fictions, messages as chapters. Bot-token (xoxb-…) auth. Default OFF on fresh installs (workspaces are private, onboarding is high-friction). | `SlackSource`, `SlackWebApi`, `SlackCursorPager` |
| `:source-matrix` *(v0.5.51, #457)* | Matrix Client-Server API — federated open-standard chat (matrix.org, kde.org, FOSDEM, self-hosted Synapse / Dendrite / Conduit). Room = fiction, message = chapter with same-sender coalescing. | `MatrixSource`, `MatrixCsApi`, `MatrixCoalescer` |
| `:source-readability` *(#472)* | Readability4J catch-all — always-on, lowest-confidence match (0.1) so any HTTP(S) URL not otherwise claimed produces a single-chapter "article" fiction. "No URL is a dead-end." | `ReadabilitySource`, `Readability4JAdapter` |
| `:source-azure` | Optional remote TTS backend — Azure Cognitive Services HD voices via SSML. BYOK; falls back to a local voice if the network drops or your key fails. | `AzureVoiceEngine`, `AzureSpeechClient`, `AzureSsmlBuilder`, `AzureCredentials` |
| `:baselineprofile` *(#409 v0.5.46)* | Pure test APK (`com.android.test` + `androidx.baselineprofile`) that drives a hot-path walk through `:app` via UI Automator and emits a `baseline-prof.txt`. Regenerated on tag pushes or material nav-graph changes. Cold launch 6.7 s → 0.8 s on Tab A7 Lite. | `StoryvoxBaselineProfileGenerator` |
| `:wear` | Wear OS companion (experimental). | `WearMainActivity`, `WearTransport` |

## Fiction-source plug-in pattern (`@SourcePlugin` + KSP)

Since v0.5.27 each fiction source is annotated with `@SourcePlugin`. The `:core-plugin-ksp` KSP processor emits a Hilt `@IntoSet` factory per annotated class at compile time, and `SourcePluginRegistry` discovers backends at startup. Adding a new source is roughly:

1. Implement `FictionSource` in a new `:source-foo` module — `browse()`, `detail(id)`, `chapter(id)`, `latestSha(id)` etc.
2. Annotate the implementation with `@SourcePlugin` (no manual `@IntoMap @StringKey` Hilt module needed — the KSP processor emits it).
3. Done. The Plugin manager in **Settings → Plugins** iterates the registry, so the new source auto-surfaces with on/off toggle and details modal.

UI never imports a source module directly. RSS, EPUB, Outline, Notion, Telegram, Palace, Slack, Matrix, and the readability catch-all all landed against this contract without changes elsewhere — the abstraction is paying for itself.

## TTS-backend plug-in pattern

Same shape, applied to voice engines. The local Piper/Kokoro engine and the optional Azure HD backend both implement a `VoiceEngine` interface in `:core-playback` and bind into a `Map<EngineKind, VoiceEngine>`. `EnginePlayer` routes synthesis to whichever engine the currently-selected voice belongs to, with offline fallback to the local engine if Azure errors or the network drops mid-chapter.

## Playback pipeline

Playback is **independent of UI**. `EnginePlayer` is a Media3 `SimpleBasePlayer` subclass that exposes a standard player surface to MediaSession (lock-screen art, BT transport, headphone media buttons) while internally pipelining sentence synthesis against AudioTrack writes.

```
sentences ──► VoiceEngine (Piper or Kokoro inference)
              │
              ▼
       ┌──────────────────┐
       │  Producer        │  (tts.synthesize)
       │  prefetch queue  │
       │  8 PCM chunks    │
       └────┬─────────────┘
            │
            ▼
       ┌──────────────────┐
       │  PcmCache        │  ← optional disk cache (PCM bytes + sentence index)
       │  appender        │
       └────┬─────────────┘
            │
            ▼
       ┌──────────────────┐
       │  URGENT_AUDIO    │  (consumer thread)
       │  → AudioTrack    │
       │  → SentenceTrack │
       └──────────────────┘
```

The PCM cache (landed in v0.4.31) renders each chapter's audio to disk on first play, so replays are gapless on any device. See the [PCM cache design spec](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-07-pcm-cache-design.md) for the full pipeline diagram and cache-key rules.

For tuning tradeoffs (warm-up wait, catch-up pause, buffer headroom, full pre-render, punctuation cadence, multi-engine sliders), see **Settings → Performance** in the app.

### Tier 3 multi-engine producer

In v0.4.78+ the producer can run **1–8 VoxSherpa engine instances side-by-side**, each with its own thread pool. The sentence chunker hands work to a round-robin engine queue so the next sentence's chunks are already rendering before the current one finishes streaming to AudioTrack. Twin sliders in **Settings → Performance** let you tune Engines × Threads/engine for your CPU. The producer thread itself is pinned to `URGENT_AUDIO` priority so it doesn't get descheduled by background work mid-chapter.

## In-process TTS

Candela links the local TTS engine **in-process** via the [VoxSherpa-TTS](https://github.com/techempower-org/VoxSherpa-TTS) `:engine-lib` AAR (published to JitPack). That AAR re-projects [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) inference plus the Piper and Kokoro engine wrappers into a single dependency.

We bypass Android's `TextToSpeech` framework entirely, manage our own `AudioTrack` with a fat buffer, and pipeline next-sentence generation against current playback. **No second APK, no install gate, no engine-binding handshake** — synthesis runs in Candela's own process.

Voice model weights are downloaded on demand by `VoiceManager` from the `voices-v2` GitHub release. See [Voices](voices/) for catalog details.

## Optional cloud TTS — Azure HD

For users who want studio-grade narration on slow devices, `:source-azure` wires Azure Cognitive Services HD voices into the same `VoiceEngine` interface the local engine uses. Add a key + region in **Settings → Voice & Playback → Azure** (BYOK; never billed by Candela). The Azure engine uses SSML to drive the same per-voice speed/pitch/punctuation knobs you set for local voices, with retries on transient HTTP errors. If the key fails or the network drops mid-chapter, playback falls back to your selected local voice for the remainder of the chapter — it never just stops on you.

## Storage

- **Room** — fictions, chapters, follows, library state, voice download status.
- **DataStore Preferences** — settings (speed, pitch, theme, buffer headroom, mode toggles, multi-engine sliders, etc.).
- **EncryptedSharedPreferences** — Royal Road session cookies, GitHub OAuth tokens, Azure key + region.
- **Filesystem** — PCM cache files (`{cacheDir}/pcm/{chapterId}.{voiceId}.{speed}x.{pitch}.pcm`) plus sentence-index sidecars.
- **OkHttp disk cache** — chapter HTML and image responses.

## Specs and design docs

The `docs/superpowers/specs/` directory in the repo holds the canonical design specs:

- [`2026-05-05-storyvox-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-05-storyvox-design.md) — original architecture
- [`2026-05-06-github-source-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-06-github-source-design.md) — GitHub fiction source
- [`2026-05-07-pcm-cache-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-07-pcm-cache-design.md) — chapter PCM cache
- [`2026-05-08-azure-hd-voices-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-08-azure-hd-voices-design.md) — Azure HD voices (shipped v0.4.61–v0.4.66)
- [`2026-05-08-github-oauth-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-08-github-oauth-design.md) — GitHub OAuth Device Flow (shipped)
- [`2026-05-08-settings-redesign-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-08-settings-redesign-design.md) — Settings UI redesign
- [`2026-05-08-voxsherpa-knobs-research.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-08-voxsherpa-knobs-research.md) — VoxSherpa knobs research
- [`2026-05-08-mempalace-integration-design.md`](https://github.com/techempower-org/storyvox/blob/main/docs/superpowers/specs/2026-05-08-mempalace-integration-design.md) — MemPalace as fiction source
