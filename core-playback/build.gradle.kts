plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "in.jphe.storyvox.playback"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Robolectric needs Android resources on the unit-test classpath
            // so VoiceManagerTest can spin up an ApplicationContext (filesDir +
            // DataStore) without an emulator. Required for the #28 cleanup
            // policy tests.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core-data"))
    // PR-4 (#183) — wires AzureVoiceEngine into EnginePlayer's
    // generateAudioPCM dispatch table. Pure-JVM module, no AAR; brings
    // OkHttp + the SSML builder + the engine handle adapter.
    implementation(project(":source-azure"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // OkHttp + okio power VoiceManager's voice-model downloads, with
    // redirect-following + streaming writes into context.filesDir/voices/{id}/.
    implementation(libs.okhttp)

    // Persistent voice settings (active voice id, installed voice ids).
    implementation(libs.androidx.datastore.preferences)

    // Direct in-process synthesis. The :engine-lib AAR from realm-watch's
    // VoxSherpa fork brings KokoroEngine/VoiceEngine/Sonic into our process
    // and pulls sherpa-onnx (the actual ML inference) as a transitive dep.
    // Lets storyvox bypass TextToSpeech.speak() and manage its own
    // AudioTrack with a fat buffer for smooth pipelined playback.
    // JitPack publishes our fork's `engine-lib` Gradle module as the
    // single-module path `com.github.realm-watch:VoxSherpa-TTS:vX.Y.Z`
    // (it collapses multi-module configs to one root coordinate). The
    // actual AAR file at this URL is engine-lib's release artifact.
    // Coordinate ownership moved from `jphein` to `realm-watch` on
    // 2026-05-13 with the storyvox-to-org transfer; GitHub permanently
    // redirects the old URL but JitPack treats the username as part
    // of the coordinate, so the new path is canonical.
    // v2.7.13 (storyvox #193) parameterizes Sonic.setQuality via
    // VoiceEngine.sonicQuality / KokoroEngine.sonicQuality static
    // fields (default 1 — high quality). Storyvox exposes a
    // Settings toggle that writes both fields via
    // [VoiceEngineQualityBridge]. Pre-rendered PCM (post-#97) means
    // the ~20% CPU hit lands once per chapter, not per playback.
    // v2.7.14 (storyvox #197 + #198) adds two more static knobs:
    // - VoiceEngine.voiceLexicon / KokoroEngine.voiceLexicon —
    //   comma-separated `.lexicon` paths passed to
    //   OfflineTts{Vits,Kokoro}ModelConfig.setLexicon() for IPA
    //   phoneme overrides on specific tokens (#197).
    // - KokoroEngine.phonemizerLang — language code forcing the
    //   Kokoro phonemizer to a non-default language, useful for
    //   English voices reading embedded Spanish/Japanese/etc.
    //   dialogue (#198). Ignored by VoiceEngine (Piper auto-derives
    //   language from voice metadata).
    // Both are read at engine construction time, so a flip from
    // Settings requires the next voice load — Storyvox forces this
    // by calling VoiceEngineQualityBridge.applyLexicon/applyLang
    // BEFORE the engine instantiates for the new voice on switch.
    // v2.8.0 (storyvox #119) adds KittenEngine — third in-process
    // voice family wrapping sherpa-onnx's OfflineTtsKittenModelConfig
    // path. Smallest tier: ~25 MB fp16 nano model, 8 voices, 24 kHz,
    // Apache-2.0. Structural twin of KokoroEngine (multi-speaker
    // shared model + speaker index, public no-arg ctor for Tier 3
    // parallelism, XNNPACK→CPU fallback). Storyvox dispatches via
    // EngineType.Kitten in this PR.
    implementation("com.github.techempower-org:VoxSherpa-TTS:v2.8.0")
    implementation("com.github.k2-fsa:sherpa-onnx:1.12.26")

    // Media3 — session, player base classes
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    // Issue #373 — audio-stream backend category. ExoPlayer handles
    // HTTP audio URLs (KVMR's AAC stream, future LibriVox MP3s, etc.)
    // that the TTS path can't (and shouldn't) touch. Lives alongside
    // EnginePlayer; EnginePlayer.loadAndPlay routes by ChapterContent.audioUrl.
    implementation(libs.androidx.media3.exoplayer)

    // Auto support — MediaBrowserServiceCompat lives here
    implementation(libs.androidx.media)

    // Wear OS data layer (phone side talks to it via play-services-wearable)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // PR-F (#86) — background PCM cache pre-render via WorkManager.
    // ChapterRenderJob is a @HiltWorker that synthesizes a chapter's
    // PCM into the cache on a background coroutine, scheduled by
    // PcmRenderScheduler from PrerenderTriggers' lifecycle hooks
    // (library-add, chapter-natural-end, Mode C flow flip).
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Compose — for the engine consent dialog atom
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric supplies a JVM-resident ApplicationContext (filesDir +
    // DataStore) for VoiceManagerTest, which exercises the #28 partial-file
    // cleanup policy without needing an emulator.
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
}
