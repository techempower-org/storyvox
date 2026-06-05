plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "in.jphe.storyvox.source.librivox"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-data"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Issue #1015 — LibriVox API client (OkHttp + kotlinx-serialization).
    // LibriVox publishes a public, no-auth JSON catalog at
    // https://librivox.org/api/feed/audiobooks/ ; per-section audio is
    // hosted on archive.org. Read-only, same transport shape as the
    // Radio Browser client in :source-radio.
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Plugin-seam (#384) — emits the @SourcePlugin → @IntoSet
    // SourcePluginDescriptor Hilt module for LibriVoxSource. A legacy
    // @IntoMap binding in di/LibriVoxModule.kt is also present
    // (dual-wire) so the repository's existing Map<String, FictionSource>
    // keeps resolving the source — same pattern :source-radio uses.
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
