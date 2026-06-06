pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // mavenLocal hosts the engine-lib AAR during local development
        // (./gradlew :engine-lib:publishToMavenLocal in the VoxSherpa fork).
        // Resolved before JitPack so we can iterate without waiting for
        // JitPack's GitHub→build sync each tag bump.
        mavenLocal()
        google()
        mavenCentral()
        // JitPack hosts the techempower-org/VoxSherpa-TTS :engine-lib AAR. We link the
        // engines in-process to bypass Android's TextToSpeech framework path
        // (its small AudioTrack buffer underruns between sentences on modest
        // hardware, which is the gappy-playback problem v0.4.0 fixes).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "storyvox"

include(":app")
include(":wear")
include(":core-data")
// Plugin-seam Phase 1 (#384) — KSP SymbolProcessor module that emits
// Hilt @IntoSet contributions for @SourcePlugin-annotated FictionSource
// classes. Pure Kotlin/JVM (the processor runs in the Kotlin compiler).
include(":core-plugin-ksp")
include(":core-llm")
include(":core-playback")
include(":core-ui")
include(":source-royalroad")
include(":source-github")
include(":source-mempalace")
include(":source-azure")
include(":source-rss")
include(":source-epub")
include(":source-epub-writer")
// Issue #1003 — pure-JVM chapterizer + M4B chapter-marker math for the
// "Make your own audiobook" export. Android encode/mux lives in :core-playback.
include(":source-audiobook-writer")
// Issue #995 — OCR scan-to-read. Camera / gallery capture → on-device
// ML Kit Text Recognition (offline, bundled model) → a fiction the
// EnginePlayer narrates. The assistive-tech bridge from the printed
// world to accessible audio. Shares the :core-data OcrTextRecognizer
// seam so #996's scanned-PDF import reuses the same recognizer.
include(":source-ocr")
include(":source-outline")
include(":source-gutenberg")
include(":source-ao3")
include(":source-standard-ebooks")
include(":source-wikipedia")
include(":source-wikisource")
// Issue #417 — generalized :source-kvmr → :source-radio. The renamed
// module still serves persisted KVMR fictions via a one-cycle
// SourceIds.KVMR alias declared in :source-radio's Hilt module.
include(":source-radio")
// Issue #1015 — LibriVox: free public-domain audiobooks read by
// volunteers. storyvox's first pre-recorded (human-narrated) source —
// sections stream archive.org MP3s through Media3 (audio-stream backend
// #373), no TTS. Catalog from the public no-auth JSON feed at
// librivox.org/api/feed/audiobooks/.
include(":source-librivox")
include(":source-notion")
include(":source-hackernews")
include(":source-arxiv")
include(":source-plos")
include(":source-discord")
// Issue #462 — Telegram Bot API backend. Public-channel reader (bot
// gets invited to channels, messages become chapters). Architectural
// twin to :source-discord but simpler — no thread coalescing, no
// search, no server picker.
include(":source-telegram")
// Issue #454 — Slack Web API backend. Channels-as-fictions /
// messages-as-chapters, mirrors :source-telegram's leaf shape but
// with Slack's cursor-based pagination. Bot Token (xoxb-…) auth via
// Settings. Default OFF on fresh installs because workspaces are
// private and bot-token onboarding is high-friction.
include(":source-slack")
// Issue #457 — Matrix Client-Server API backend. Federated
// open-standard chat (matrix.org, kde.org, fosdem.org, self-hosted
// Synapse / Dendrite / Conduit, etc.) — room = fiction, message =
// chapter (with same-sender coalescing). Architectural twin to
// :source-discord; default OFF on fresh installs (the Settings →
// Plugins screen surfaces it; bot-token / access-token onboarding
// gates content visibility).
include(":source-matrix")
// Issue #472 — Readability4J catch-all for the magic-link paste flow.
// Always-on, lowest-confidence match (0.1) so any HTTP(S) URL not
// otherwise claimed by one of the 17 specialized backends still
// produces a single-chapter "article" fiction. "No URL is a dead-end."
include(":source-readability")
// Issue #502 — Palace Project library backend (OPDS catalog walker).
// First library-borrowing backend in the storyvox roster; ships
// non-DRM titles only in this PR (LCP DRM is explicitly deferred —
// see scratch/libby-hoopla-palace-scope/lcp-drm-scope.md). Libby +
// Hoopla follow-ups defer to their own PRs (see sibling scope notes).
include(":source-palace")
include(":core-sync")
include(":feature")
// Issue #409 — Baseline Profile producer module. Pure test APK
// (`com.android.test` + `androidx.baselineprofile`) that drives a
// hot-path walk through :app via UI Automator and emits a `.txt`
// profile that the AndroidX Baseline Profile Gradle plugin wires
// back into `app/src/main/baseline-prof.txt`. Not in CI's default
// critical-path (instrumented test, needs a connected device);
// regenerated on tag pushes or when the nav graph changes
// materially. See baselineprofile/README-style docstring at the top
// of `baselineprofile/build.gradle.kts` for the run command.
include(":baselineprofile")
