plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.source.royalroad"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
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
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    // #719 — `BackHandler` ships in `androidx.activity:activity-compose`.
    // The Compose BOM does NOT pin it, and we don't pull
    // `navigation-compose` here (the auth WebView is hosted by `:app`),
    // so the dep has to be declared explicitly.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)

    // WorkManager — Royal Road tag-sync periodic worker (#178). The
    // shared schedule helper lives in `:core-data`'s WorkScheduler,
    // but the @HiltWorker class itself is here next to the
    // tag-sync coordinator because it's RR-specific.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    // Plugin-seam Phase 2 (#384) — emits the @SourcePlugin → @IntoSet
    // SourcePluginDescriptor Hilt module for RoyalRoadSource. The
    // legacy @IntoMap binding in di/RoyalRoadModule.kt is kept
    // alongside (Phase 2 invariant: registry is additive; Phase 3
    // removes the legacy map binding).
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
