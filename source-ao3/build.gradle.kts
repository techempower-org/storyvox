plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // PR2 of #426 — AO3 WebView sign-in lives in this module (mirrors
    // :source-royalroad). Compose is needed for the [Ao3AuthWebView]
    // composable; without the plugin we can't expose the @Composable
    // entry point to :app's [AuthWebViewScreen].
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "in.jphe.storyvox.source.ao3"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    // Issue #381 — AO3 titles render their chapters from the
    // per-work EPUB download. Reuse the parser already on
    // `:source-epub` (added for #235) — same pattern as the
    // Gutenberg backend (#237).
    implementation(project(":source-epub"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    // PR2 of #426 — parse the authed `/users/<u>/subscriptions` +
    // `/users/<u>/readings?show=marked` HTML index pages with Jsoup.
    // AO3 has no JSON API for these surfaces; the index pages are
    // well-structured `<ol class="work index group">` lists of
    // `<li class="work blurb group">` cards, same shape RR uses
    // and the same library RR's parsers already lean on.
    implementation(libs.jsoup)

    // PR2 of #426 — Compose surface for the AO3 WebView login flow.
    // Mirrors the :source-royalroad dependency set.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    // #719 — `BackHandler` ships in `androidx.activity:activity-compose`.
    // Same rationale as `:source-royalroad` — the BOM does not pin it
    // and we don't pull navigation-compose here.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Plugin-seam Phase 2 (#384) — emits the @SourcePlugin → @IntoSet
    // SourcePluginDescriptor Hilt module for Ao3Source. Legacy
    // @IntoMap binding in di/Ao3Module.kt is kept (Phase 3 removes).
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
