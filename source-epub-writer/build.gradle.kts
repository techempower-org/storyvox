plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Issue #117 — EPUB 3 export writer. Distinct from `:source-epub`, which is the
// *import* side (parses .epub into storyvox fictions). This module produces
// .epub files from already-imported fictions, for share/Save-As flows out of
// the Fiction detail screen.
//
// Kept as a separate module so the writer logic stays testable in plain JUnit
// (no Compose / Hilt-Android wiring needed). The use case takes a Context for
// cache-dir resolution + FileProvider Uri building, but the EpubWriter itself
// is pure Kotlin + java.util.zip.
android {
    namespace = "in.jphe.storyvox.source.epub.writer"
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
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
