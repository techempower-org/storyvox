plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.source.readability"
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
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core-data"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Mozilla Readability port — the whole point of this module.
    // Issue #472 catch-all article extractor. ~50 KB; pulls in JSoup
    // transitively (already on the classpath via other sources).
    implementation(libs.readability4j)
    implementation(libs.jsoup)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Emits the @SourcePlugin → @IntoSet SourcePluginDescriptor Hilt
    // module so the readability backend joins SourcePluginRegistry
    // automatically alongside the other 17 sources.
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
