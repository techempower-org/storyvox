plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.source.palace"
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

    testOptions {
        unitTests {
            // Issue #502 — Robolectric-backed unit tests need Android
            // resources on the classpath so `android.util.Xml` (used by
            // [OpdsParser]) resolves via the SDK shim. Same posture as
            // `:source-rss` (#464) — the OPDS 1.x parser is XmlPullParser
            // -based for the same "ship an Android-friendly parser
            // without dragging in a 1.5 MB Rome dependency" reason.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-data"))
    // Issue #502 — Palace OPDS catalog returns EPUB acquisition links
    // for non-DRM titles. Route the downloaded bytes through the parser
    // already on `:source-epub` (added for #235) — same pattern as
    // `:source-gutenberg` (#237), `:source-standard-ebooks` (#375),
    // `:source-ao3` (#381).
    implementation(project(":source-epub"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Plugin-seam Phase 2 (#384) — emits the @SourcePlugin → @IntoSet
    // SourcePluginDescriptor Hilt module for PalaceSource. Legacy
    // @IntoMap binding lives in di/PalaceModule.kt for parity with the
    // other source modules until Phase 3 removes that pattern.
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric is needed so `android.util.Xml` resolves for the OPDS
    // 1.x Atom-flavoured XML parsing path in unit tests. Same dep is
    // used by `:source-rss`, `:core-data`, `:core-llm`.
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
