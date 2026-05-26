plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "in.jphe.storyvox.source.notion"
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
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Plugin-seam Phase 2 (#384) — emits the @SourcePlugin → @IntoSet
    // SourcePluginDescriptor Hilt module for NotionSource. Legacy
    // @IntoMap binding in di/NotionModule.kt is kept (Phase 3 removes).
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
