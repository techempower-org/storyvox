plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "in.jphe.storyvox.source.radio"
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
}

dependencies {
    implementation(project(":core-data"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Issue #417 — Radio Browser API client (OkHttp + kotlinx-serialization).
    // Radio Browser is a public CC0 directory at
    // https://www.radio-browser.info/ — read-only JSON, no auth.
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Plugin-seam (#384) — emits the @SourcePlugin → @IntoSet
    // SourcePluginDescriptor Hilt module for RadioSource. A legacy
    // @IntoMap binding in di/RadioModule.kt is also present (dual-wire)
    // so the repository's existing Map<String, FictionSource> keeps
    // resolving the renamed source. The renamed module registers under
    // "radio"; SourceIds.KVMR is kept as a one-migration-cycle alias so
    // persisted fictions with sourceId="kvmr" still resolve through
    // RadioSource (the source advertises both keys; see RadioModule).
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
