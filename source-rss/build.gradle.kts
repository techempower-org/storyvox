plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.source.rss"
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
            // Issue #464 — Robolectric-backed unit tests need Android
            // resources on the classpath so `android.util.Xml` (used by
            // [RssParser]) resolves via the SDK shim. Also gives
            // android.util.Log a default-returning stub so debug logging
            // in production code doesn't blow up JVM tests.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-data"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Plugin-seam Phase 2 (#384) — emits the @SourcePlugin → @IntoSet
    // SourcePluginDescriptor Hilt module for RssSource. Legacy
    // @IntoMap binding in di/RssModule.kt is kept (Phase 3 removes).
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Issue #464 — Robolectric is needed so `android.util.Xml` resolves
    // for the RDF/RSS 1.0 (Craigslist) and RSS 2.0 parser paths in unit
    // tests. Same dep is already used by :core-data and :core-llm.
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
