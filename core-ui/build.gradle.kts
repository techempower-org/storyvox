plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "in.jphe.storyvox.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.coroutines)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // PR-H (#86) — `ChapterCacheBadge` consumes the `ChapterCacheState`
    // enum that lives next to `CacheStateInspector` in `:core-playback`.
    // Keeping the enum in core-playback (rather than redefining it in
    // core-ui) means the inspector return type and the UI state type are
    // literally the same value — no feature-layer mapping, no risk of the
    // two enums drifting. core-playback already depends on core-data
    // (one direction); core-ui pulling core-playback is the next link
    // in the same DAG and doesn't introduce a cycle.
    implementation(project(":core-playback"))

    testImplementation(libs.junit)
}
