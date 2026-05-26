/*
 * Issue #409 — Baseline Profile producer module.
 *
 * What this module does:
 *   1. Installs the :app `benchmark` variant on a connected non-rooted
 *      device (target = `in.jphe.storyvox`).
 *   2. Runs `BaselineProfileGenerator` (UI Automator-driven) which walks
 *      the cold-launch hot path 3 times: LAUNCHER → Library → Browse →
 *      Settings → Library → FictionDetail → Reader → back → cold-stop.
 *   3. Pulls the resulting `baseline-prof.txt` off the device and the
 *      AndroidX Baseline Profile Gradle plugin copies it into
 *      `app/src/main/baseline-prof.txt` (and `app/src/main/baseline-prof-rewritten.txt`
 *      for the older format if needed).
 *   4. The next `:app:assembleDebug` / `:app:assembleBenchmark` bundles
 *      that profile into the APK; ProfileInstaller AOT-compiles it on
 *      install. Cold-launch on AOT-compiled hot paths drops measurably
 *      on slower hardware (1.5–2.5 s on Tab A7 Lite Helio P22T per the
 *      AndroidX guide; verified empirically by the StartupBenchmark
 *      below).
 *
 * Run command (against the Tab A7 Lite):
 *   ./gradlew :app:generateBaselineProfile \
 *       -P android.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile \
 *       -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
 *
 * The simpler form (preferred — handles device targeting via adb's
 * connected device list) is just:
 *   ./gradlew :app:generateBaselineProfile
 *
 * To measure with vs without the profile (StartupBenchmark):
 *   ./gradlew :baselineprofile:connectedBenchmarkAndroidTest \
 *       -P android.testInstrumentationRunnerArguments.class=`
 *           in.jphe.storyvox.baselineprofile.StartupBenchmark`
 *
 * Why this is a `com.android.test` module rather than an
 * `androidTest` source set inside :app:
 *   - Macrobenchmark requires the test APK to run in a SEPARATE process
 *     from the target APK so the target can be cold-launched cleanly.
 *     The `com.android.test` plugin is the only AGP variant that
 *     produces a standalone test APK with its own application id
 *     (`in.jphe.storyvox.baselineprofile`).
 *   - Same reason `androidx.baselineprofile` requires it.
 */
plugins {
    // `com.android.test` shares the AGP classpath with
    // `com.android.application` / `com.android.library`. Once one of
    // those is on the classpath (from :app / :core-data / etc.), the
    // Gradle plugin resolution can't apply a versioned variant of
    // `android-test`; the version-less form picks up the same AGP
    // already resolved.
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "in.jphe.storyvox.baselineprofile"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        // minSdk = 28 is the floor for Baseline Profile generation.
        // The macrobenchmark library uses AOT compilation hooks that
        // shipped in Pie. (:app's minSdk is 26, but the test APK only
        // needs to install on devices that can actually generate
        // profiles. The Tab A7 Lite is Android 14.)
        minSdk = 28
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The producer points at :app's `benchmark` variant (non-debuggable,
    // no R8, debug-signed). See the matching `create("benchmark")`
    // block in app/build.gradle.kts for the trade-off rationale.
    targetProjectPath = ":app"

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

// Baseline Profile plugin configuration. The `useConnectedDevices = true`
// path is what JP's self-hosted runner uses (katana has adb access to
// the Tab A7 Lite over USB). The alternative — Gradle Managed Devices
// with a Pixel emulator — would work in cloud CI but generates a
// profile tuned for emulator hot paths, which differs subtly from the
// physical-device path we care about.
baselineProfile {
    // The profile generation runs against the benchmark variant.
    useConnectedDevices = true
    // Keep 3 iterations (default). Higher iteration counts converge to
    // the same profile faster but stretch the test wall-clock. 3
    // iterations on the Tab A7 Lite hot path is ~90 s; 5 would be ~150 s.
    // The BaselineProfileRule itself handles the stable-profile
    // convergence check.
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.rules)
    implementation(libs.androidx.test.uiautomator)
}

// Each variant of the producer module corresponds to a variant of the
// target (`:app`) it'll exercise. The baselineprofile plugin
// auto-creates `benchmarkBenchmark` from the benchmark build type.
androidComponents {
    onVariants { variant ->
        // Provides a sensible default test target for the AndroidStudio
        // run gutter. The CLI path uses
        // `:app:generateBaselineProfile` which works regardless.
        val artifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
        variant.instrumentationRunnerArguments.put(
            "targetAppId",
            "in.jphe.storyvox",
        )
    }
}
