plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.source.standardebooks"
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
    // Issue #375 — Standard Ebooks titles render their chapters from the
    // downloaded EPUB. Reuse the parser already on `:source-epub` (added
    // for #235 and reused for #237/Gutenberg) instead of re-implementing
    // spine + manifest walking. Same pattern as `:source-gutenberg`.
    implementation(project(":source-epub"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Plugin-seam Phase 2 (#384) — emits the @SourcePlugin → @IntoSet
    // SourcePluginDescriptor Hilt module for StandardEbooksSource.
    // Legacy @IntoMap binding in di/StandardEbooksModule.kt is kept
    // (Phase 3 removes).
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // #735 — MockWebServer for the EPUB-download interstitial regression
    // test. Same version :source-github and :source-azure already pin.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
