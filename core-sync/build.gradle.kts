import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * InstantDB app id read from `local.properties` at configure time. Falls
 * back to the literal `"PLACEHOLDER"` sentinel so a clean checkout (CI,
 * new contributor, secrets-rotated dev machine) still builds — the sync
 * layer's DI graph routes to [DisabledBackend] when the sentinel is seen.
 *
 * Pattern mirrors how android-sdk.dir is sourced. We deliberately do NOT
 * accept the value via `-P` on the command line — that pattern leaks
 * secrets into shell history and CI logs. local.properties is gitignored
 * and lives only on JP's machine + the self-hosted runner.
 */
val instantAppId: String = run {
    val propsFile = rootProject.file("local.properties")
    if (!propsFile.exists()) return@run "PLACEHOLDER"
    val props = Properties().apply { propsFile.inputStream().use(::load) }
    (props.getProperty("INSTANTDB_APP_ID") ?: "PLACEHOLDER").trim().ifBlank { "PLACEHOLDER" }
}

android {
    namespace = "in.jphe.storyvox.sync"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "INSTANTDB_APP_ID", "\"$instantAppId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    // Issue #778 — `LwwBlobSyncer.reconcile` was instrumented with
    // `android.util.Log` calls in commit 7b89fe88 (sync FK guard), which
    // throws `RuntimeException: Method d in android.util.Log not mocked`
    // under the JVM unit-test runner. Returning default values makes
    // Log.d a no-op in tests — same shape every other Android module
    // uses for its unit-test source set. This unblocks both the new
    // `PronunciationDictSyncerTest` and the pre-existing
    // `SettingsSyncerTest` that started failing on the same commit.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-data"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.okhttp)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
}
