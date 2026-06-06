# storyvox app-level R8 / ProGuard rules (#409 part 3)
#
# Build types: BOTH `debug` and `release` flip `isMinifyEnabled = true` in
# app/build.gradle.kts. Storyvox ships a single signed-debug APK to
# sideloaders (no release flavor today — see PR #16 / v1.0 prerequisite),
# so these rules apply to every shipped build.
#
# Rule shape: catch-all umbrella for every reflection-using subsystem,
# even where the consuming module ships its own consumer-rules.pro. The
# tradeoff is a small fraction of dead code kept; in return any future
# module that uses kotlinx-serialization / @SourcePlugin / Hilt without
# adding its own consumer-rules continues to work.
#
# Source-of-truth audit lives in
# scratch/r8-audit-409/audit.md (worktree) and the PR body.

# ----------------------------------------------------------------------
# Plugin seam — KSP-generated Hilt modules (#384)
# ----------------------------------------------------------------------
# `:core-plugin-ksp` emits one `<SourceName>_SourcePluginModule` per
# `@SourcePlugin`-annotated class into the package
# `in.jphe.storyvox.plugin.generated.*`. Hilt's aggregator picks them up
# via FQN scan at compile time, so the .class files are wired into the
# `@Multibinds Set<SourcePluginDescriptor>`. R8 doesn't always see those
# objects as roots from its DCE pass — without this rule, ALL 18 source
# plugins drop out of the registry on minified builds and the Browse
# screen shows zero chips.
# Note: proguard rule files take JVM-style FQNs — `in` is a Kotlin
# source-level keyword that requires backticks in *.kt files, but in
# .class file bytecode (and therefore in R8 rule files) it's just a
# normal package segment.
-keep class in.jphe.storyvox.plugin.generated.** { *; }

# The @SourcePlugin annotation itself is Retention.BINARY — not visible
# at runtime, so it does NOT need to be kept. The generated descriptor
# *callsites* read concrete values into a SourcePluginDescriptor at
# Hilt-module-init time, no reflection. The keep above covers the
# generated module objects' .provideDescriptor() methods.

# ----------------------------------------------------------------------
# kotlinx-serialization (15 callsites across source-* and core-* modules)
# ----------------------------------------------------------------------
# Each @Serializable class gets a synthesised `Companion.serializer()` +
# a sibling `*$$serializer` object that R8 doesn't connect to the
# referenced data class unless we tell it to. The shape below is the
# canonical kotlinx-serialization umbrella, lifted from the upstream
# README and adapted to keep allowobfuscation/allowshrinking so R8 can
# still rename / shrink members that aren't actively used.

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep all classes that are explicitly @Serializable. allowobfuscation
# lets R8 rename them (kotlinx-serialization references them by their
# generated companion, not by source name) and allowshrinking lets R8
# DCE them if literally unreferenced (the companion-keep rule below
# pins the ones that matter).
-keep,allowobfuscation,allowshrinking @kotlinx.serialization.Serializable class **

# Keep the generated $$serializer singletons — these ARE referenced
# reflectively (by the kotlinx-serialization runtime walking suffix
# patterns) so they must keep all members. allowobfuscation here is
# safe because the kotlinx-serialization runtime accepts renamed FQNs
# as long as the suffix is preserved (R8 keeps the $$serializer suffix
# under standard class-renaming rules).
-keep,allowobfuscation class **$$serializer { *; }

# The companion's serializer() method is how `Json.encodeToString(value)`
# discovers the serializer from a reified type. Keep it on every class
# that has one.
-keepclassmembers class **$Companion {
    public static *** serializer(...);
}

# Default constructors on @Serializable data classes — kotlinx-serialization
# uses them via the synthesised constructor that takes the bitmask of
# present-fields. Without this, optional fields error at decode time.
-keepclassmembers @kotlinx.serialization.Serializable class * {
    static **$Companion Companion;
    *** Companion;
    <init>(...);
}

# The kotlinx-serialization runtime API contract types. allowobfuscation
# is safe (R8 obfuscates the class but the runtime accepts any FQN as
# long as the type contract is preserved).
-keep,allowobfuscation class kotlinx.serialization.KSerializer
-keep,allowobfuscation class kotlinx.serialization.Serializable
-keep,allowobfuscation class kotlinx.serialization.SerialName

# Issue #661 — JsonElement tree types (JsonObject / JsonArray / JsonPrimitive)
# are NOT @Serializable themselves. They use hand-written serializers in
# kotlinx.serialization.json.internal that look up the concrete subtype at
# decode time. Without these keeps, R8 obfuscates JsonObject → "t8.e" and
# the runtime polymorphic dispatch fails with
#     "element class t8.e is not available"
# on the first sync transaction (#661). Surfaced because :core-sync's
# WsInstantBackend builds raw JsonObject/JsonArray envelopes via
# Json.encodeToString(JsonObject.serializer(), msg). Keep the public json
# package AND its `.internal.` siblings — the descriptor lookup walks both.
-keep class kotlinx.serialization.json.** { *; }
-keep class kotlinx.serialization.json.internal.** { *; }
-keep class kotlinx.serialization.descriptors.** { *; }
-keep class kotlinx.serialization.internal.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }

# ----------------------------------------------------------------------
# Hilt / Dagger — belt-and-suspenders
# ----------------------------------------------------------------------
# Hilt's AAR ships consumer-rules that handle 99% of cases. These are
# the few we belt-and-suspenders here because the storyvox graph spans
# many modules and a missed root would cascade through `inject()` sites
# with cryptic NPE chains at runtime.

# Application class — the @HiltAndroidApp entry point.
-keep class in.jphe.storyvox.StoryvoxApp { *; }

# @AndroidEntryPoint activities / services. Hilt's bytecode rewrite
# emits Hilt_<ClassName> shims that the framework instantiates via
# Class.forName(activity-name) — must not be stripped. AGP's default
# rules + the Android manifest references usually keep activities,
# but services + custom Application classes are easier to lose.
-keep class in.jphe.storyvox.MainActivity { *; }
-keep class in.jphe.storyvox.playback.StoryvoxPlaybackService { *; }
-keep class in.jphe.storyvox.playback.auto.StoryvoxAutoBrowserService { *; }

# Dagger-generated factories: keep their constructors because Hilt
# instantiates them reflectively at component-build time.
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}
-keepclasseswithmembers class * {
    @javax.inject.Inject <fields>;
}

# Multibinding contracts — keep the Set<SourcePluginDescriptor> binding
# surface so the Hilt-generated DaggerStoryvoxApp_HiltComponents sees
# the multibinding type intact.
-keep class in.jphe.storyvox.data.source.plugin.SourcePluginDescriptor { *; }

# ----------------------------------------------------------------------
# Room — entities, DAOs, type converters, generated impls
# ----------------------------------------------------------------------
# :core-data/consumer-rules.pro keeps the entity / dao / work packages
# for downstream consumers. App-side belt-and-suspenders below covers
# the converter package + TypeConverter classes that aren't in entity/.
-keep class in.jphe.storyvox.data.db.entity.** { *; }
-keep class in.jphe.storyvox.data.db.dao.** { *; }
-keep class in.jphe.storyvox.data.db.converter.** { *; }
-keep class in.jphe.storyvox.data.db.StoryvoxDatabase { *; }
-keep class in.jphe.storyvox.data.work.** { *; }
-keep @androidx.room.TypeConverter class * { *; }

# Room-generated Impl classes (StoryvoxDatabase_Impl, *Dao_Impl).
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class **_Impl { *; }

# ----------------------------------------------------------------------
# Realm-sigil — BuildConfig fields read by sigil/Sigil.kt
# ----------------------------------------------------------------------
# BuildConfig is referenced statically by Sigil.kt, so AGP keeps the
# fields. Defensive `-keep` in case future refactors read it dynamically
# (debug screen "version json" affordance, etc.).
-keep class in.jphe.storyvox.BuildConfig { *; }

# ----------------------------------------------------------------------
# Jsoup — Royal Road + Readability HTML scraping
# ----------------------------------------------------------------------
# :source-royalroad/consumer-rules.pro keeps org.jsoup.**. Mirror for
# the readability source which doesn't have its own consumer-rules.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# slf4j-api is a transitive dep (jsoup → slf4j-api) but no -impl
# binding is bundled. R8 sees the LoggerFactory.bind() reference and
# flags org.slf4j.impl.StaticLoggerBinder as missing. Standard outcome
# for any project that uses slf4j-api without an impl — the
# no-operation fallback inside LoggerFactory handles it at runtime.
-dontwarn org.slf4j.impl.**
-dontwarn org.slf4j.**

# ----------------------------------------------------------------------
# androidx.startup InitializationProvider (manifest-referenced)
# ----------------------------------------------------------------------
# AGP keeps the provider via manifest reference. No action needed; this
# block is documentation of why no rule appears.

# ----------------------------------------------------------------------
# kotlinx-coroutines + DataStore — already shipped by upstream AARs.
# OkHttp ships its own consumer-rules. No app-side action.
# ----------------------------------------------------------------------

# ----------------------------------------------------------------------
# Compose
# ----------------------------------------------------------------------
# Compose runtime is reflection-light (kotlin reflect is NOT a runtime
# dep). proguard-android-optimize.txt + AGP defaults handle the rest.
# `-dontwarn` for a few internal Compose classes that R8 mis-detects as
# missing references (purely a warning silencer).
-dontwarn androidx.compose.**

# ----------------------------------------------------------------------
# OkHttp — bundled consumer-rules.pro inside the AAR. Only need
# -dontwarn for the optional Conscrypt / Bouncy Castle hooks.
# ----------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ----------------------------------------------------------------------
# Source plugins — keep every concrete FictionSource impl so Hilt's
# @Inject constructor resolution sees the class even if it's only
# referenced from the KSP-generated module's parameter type.
# ----------------------------------------------------------------------
-keep class in.jphe.storyvox.source.** implements in.jphe.storyvox.data.source.FictionSource { *; }
-keep class in.jphe.storyvox.source.**.*Source { *; }

# ----------------------------------------------------------------------
# Home-screen widget (#159)
# ----------------------------------------------------------------------
# NowPlayingWidgetProvider is referenced from AndroidManifest.xml by
# fully-qualified name; Android system instantiates it via reflection
# (Class.forName + newInstance) whenever the AppWidgetHost dispatches
# APPWIDGET_UPDATE or our custom action broadcasts. R8 doesn't see the
# manifest → class edge, so without this rule the class is stripped on
# the minified build and every widget update crashes with
# ClassNotFoundException inside the framework's broadcast dispatch.
#
# `{ *; }` keeps the constructor + onUpdate/onReceive/etc. methods.
# Companion-object string constants are read by manifest <action>
# matching and from the same Kotlin file's own callsites; the rule
# covers them too.
-keep class in.jphe.storyvox.widget.NowPlayingWidgetProvider { *; }
-keep class in.jphe.storyvox.widget.NowPlayingWidgetProvider$WidgetEntryPoint { *; }

# ----------------------------------------------------------------------
# PdfBox-Android (#996 :source-pdf) — optional JPEG-2000 decoder.
# ----------------------------------------------------------------------
# com.tom_roush.pdfbox.filter.JPXFilter references
# com.gemalto.jp2.JP2Decoder, an OPTIONAL JPEG-2000 image decoder that
# PdfBox-Android only needs to rasterise JPX-compressed images embedded
# in a PDF. We extract the text layer, never images, so we deliberately
# don't ship the (native, heavyweight) com.gemalto.jp2:jp2-android dep.
# R8 sees the dangling reference and, since AGP treats missing classes
# as a hard error in release builds, fails minifyRelease without this
# -dontwarn. Debug builds skip R8 entirely, which is why this only
# surfaced in CI's :app:assembleRelease and not in compileDebugKotlin.
# AGP auto-generates exactly this rule into
# build/outputs/mapping/release/missing_rules.txt.
-dontwarn com.gemalto.jp2.JP2Decoder
