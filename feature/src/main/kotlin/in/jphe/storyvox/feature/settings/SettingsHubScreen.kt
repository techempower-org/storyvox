package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import `in`.jphe.storyvox.feature.settings.components.SectionHeading
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #773 — Settings hub search filter. Threaded to every
 * [SettingsHubRow] via a CompositionLocal so the call sites in
 * [SettingsHubScreen] stay declarative: the rows skip themselves
 * when the current query doesn't match title or subtitle. Empty
 * query (the default) matches everything.
 */
private val LocalSettingsHubQuery = staticCompositionLocalOf { "" }

private fun matchesHubQuery(query: String, title: String, subtitle: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return title.contains(q, ignoreCase = true) || subtitle.contains(q, ignoreCase = true)
}

/**
 * Issue #440 — Settings hub screen. Follow-up to #467 wired every
 * named section to a dedicated subscreen.
 *
 * v0.5.36 wired the gear icon directly to [SettingsScreen], a 3,600-line
 * flat-scroll page that opened on the Voice & Playback section with no
 * top-of-page map. New users had no way to discover what Settings
 * contained without scrolling past every card; the top bar still read
 * "Voice & Playback" while the user scrolled through Reading, Performance,
 * AI, etc., so the title disagreed with what was actually on screen.
 *
 * The hub is the new gear-icon destination: a short list of section
 * cards, each carrying a one-line subtitle that previews its
 * contents and routes to a dedicated subscreen:
 *
 *  - Voice & Playback → [VoiceAndPlaybackSettingsScreen]
 *  - Voice library → [VoiceLibraryScreen][in.jphe.storyvox.feature.voicelibrary.VoiceLibraryScreen]
 *  - Reading → [ReadingSettingsScreen]
 *  - Performance → [PerformanceSettingsScreen]
 *  - AI → [AiSettingsScreen]
 *  - Accessibility → [AccessibilitySettingsScreen] (Phase 1 scaffold)
 *  - AI sessions → [SessionsScreen][in.jphe.storyvox.feature.sessions.SessionsScreen]
 *  - Plugins → [PluginManagerScreen][in.jphe.storyvox.feature.settings.plugins.PluginManagerScreen]
 *  - Pronunciation dictionary → [PronunciationDictScreen][in.jphe.storyvox.feature.settings.pronunciation.PronunciationDictScreen]
 *  - Account → [AccountSettingsScreen]
 *  - Memory Palace → [MemoryPalaceSettingsScreen]
 *  - Developer → [DebugScreen][in.jphe.storyvox.feature.debug.DebugScreen]
 *  - About → [AboutSettingsScreen]
 *
 * The long [SettingsScreen] is preserved as an "All settings" escape
 * hatch for power users who want everything on one searchable page;
 * a dedicated row at the bottom of the hub routes there explicitly
 * so the affordance isn't lost.
 *
 * ## Section row order
 *
 * Most-touched first (matches the section ribbon order in
 * [SettingsScreen]):
 *
 * 1. Voice & Playback — voice, speed, cadence, pitch.
 * 2. Voice library — dedicated subscreen.
 * 3. Reading — theme, sleep timer.
 * 4. Performance — buffering, parallel synth, decoder choice.
 * 5. AI — chat model, grounding, recap.
 * 6. Accessibility — TalkBack / Switch Access scaffolding (Phase 1).
 * 7. AI sessions — dedicated subscreen.
 * 8. Plugins — registry-driven plugin manager (#404 surface).
 * 9. Pronunciation dictionary — dedicated subscreen.
 * 10. Account — Royal Road / GitHub sign-ins.
 * 11. Memory Palace — daemon host config + probe.
 * 12. Developer — Debug screen + advanced toggles.
 * 13. About — version sigil + open-source notices.
 * 14. All settings (legacy long page) — escape hatch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(
    onNavigateBack: () -> Unit,
    onOpenAllSettings: () -> Unit,
    onOpenVoiceLibrary: () -> Unit,
    onOpenPluginManager: () -> Unit,
    onOpenAiSessions: () -> Unit,
    onOpenPronunciationDict: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenVoicePlayback: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenPerformance: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenMemoryPalace: () -> Unit,
    onOpenAbout: () -> Unit,
    /**
     * v1 settings-bundle-7 — Advanced subscreen. Default no-op so
     * callers (and the smoke test) that haven't wired it yet still
     * compile; production wiring lives in
     * [`in.jphe.storyvox.navigation.StoryvoxNavHost`].
     */
    onOpenAdvanced: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    var query by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .verticalScroll(rememberScrollState())
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            // The hub renders as a single brass-edged group card. Same
            // brass surface as the rest of Settings — SettingsGroupCard
            // wraps Card(surfaceContainerHigh, shapes.large) and a 1-dp
            // inter-row peek. One card with many link rows reads as a
            // navigation index rather than a fragmented card grid.
            SectionHeading(
                label = "Storyvox",
                icon = Icons.Outlined.AutoAwesome,
                descriptor = "Pick a section to configure.",
            )
            // Issue #773 — search filter. Case-insensitive substring
            // match against each row's title and subtitle; non-matching
            // rows skip themselves via [LocalSettingsHubQuery].
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search settings") },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            CompositionLocalProvider(LocalSettingsHubQuery provides query) {
            SettingsGroupCard {
                // Voice & Playback — most-touched, first.
                SettingsHubRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = "Voice & Playback",
                    subtitle = "Voice, speed, cadence, pitch.",
                    onClick = onOpenVoicePlayback,
                )
                // Voice library — dedicated subscreen.
                SettingsHubRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = "Voice library",
                    subtitle = "Browse and switch between available voices.",
                    onClick = onOpenVoiceLibrary,
                )
                SettingsHubRow(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = "Reading",
                    subtitle = "Theme, sleep timer.",
                    onClick = onOpenReading,
                )
                // v0.5.59 (#cover-style-toggle) — Appearance. Book-
                // cover fallback style (Monogram / Branded / Cover
                // only). Sits next to Reading because both are
                // visual-style knobs; future visual rows land here.
                SettingsHubRow(
                    icon = Icons.Outlined.Palette,
                    title = "Appearance",
                    subtitle = "Book cover style.",
                    onClick = onOpenAppearance,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.Speed,
                    title = "Performance",
                    subtitle = "Buffer, parallel synth, decoder choice.",
                    onClick = onOpenPerformance,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "AI",
                    subtitle = "Chat model, grounding, recap.",
                    onClick = onOpenAi,
                )
                // Accessibility — Phase 1 scaffold (v0.5.42). Positioned
                // between AI and AI sessions per spec: user-facing tier,
                // not buried with the advanced rows further down.
                SettingsHubRow(
                    icon = Icons.Outlined.Accessibility,
                    title = "Accessibility",
                    subtitle = "TalkBack, contrast, motion, font scale.",
                    onClick = onOpenAccessibility,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.AutoStories,
                    title = "AI sessions",
                    subtitle = "Review past chats and delete history.",
                    onClick = onOpenAiSessions,
                )
                // Plugin manager (#404). Dedicated subscreen with its own
                // search + filter chips + capability legend.
                SettingsHubRow(
                    icon = Icons.Outlined.Extension,
                    title = "Plugins",
                    subtitle = "Toggle backends — Fiction, Audio streams, Voice bundles.",
                    onClick = onOpenPluginManager,
                )
                SettingsHubRow(
                    icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                    title = "Pronunciation dictionary",
                    subtitle = "Per-word phonetic overrides.",
                    onClick = onOpenPronunciationDict,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.AccountCircle,
                    title = "Account",
                    subtitle = "Royal Road, GitHub.",
                    onClick = onOpenAccount,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.Cloud,
                    title = "Memory Palace",
                    subtitle = "Daemon host, probe, integration.",
                    onClick = onOpenMemoryPalace,
                )
                // v1 settings-bundle-7 — Advanced subscreen. Power-
                // user knobs (Android Auto bucket size, future
                // integration tunables). Sits next to Developer
                // because both are infrequently-touched surfaces;
                // Advanced is user-facing while Developer is for
                // debugging.
                SettingsHubRow(
                    icon = Icons.Outlined.Tune,
                    title = "Advanced",
                    subtitle = "Android Auto, integration tunables.",
                    onClick = onOpenAdvanced,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.BugReport,
                    title = "Developer",
                    subtitle = "Debug overlay, log ring, advanced toggles.",
                    onClick = onOpenDebug,
                )
                SettingsHubRow(
                    icon = Icons.Outlined.Info,
                    title = "About",
                    subtitle = "Version, sigil, open-source notices.",
                    onClick = onOpenAbout,
                )
                // Escape hatch — the legacy flat-scroll SettingsScreen
                // still works; users who want the old experience reach
                // it via this row. Subtitle pre-empts confusion: "yes,
                // everything you used to scroll through is still here".
                SettingsHubRow(
                    icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                    title = "All settings",
                    subtitle = "Every setting on one long page (legacy).",
                    onClick = onOpenAllSettings,
                )
            }
            }
        }
    }
}

/**
 * A hub link row. Shaped like [SettingsLinkRow] but with a brass-tinted
 * leading icon — wraps the [SettingsRow] primitive directly so we don't
 * have to widen [SettingsLinkRow]'s contract for the hub.
 *
 * Visible to [SettingsHubScreenSmokeTest] (`internal`) so the test can
 * count rows and assert their click handlers all wire through.
 */
@Composable
internal fun SettingsHubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    // Issue #773 — search filter. When [LocalSettingsHubQuery] is
    // non-blank and matches neither title nor subtitle, the row
    // skips itself so the hub collapses to just the matches.
    if (!matchesHubQuery(LocalSettingsHubQuery.current, title, subtitle)) return
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailing = {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * Issue #440 — the canonical Settings hub row catalog. Tests pin this
 * list so an accidental reorder or removal of a row in
 * [SettingsHubScreen] surfaces here first.
 *
 * The list is constructed lazily because the icon objects live in the
 * Compose runtime classloader; consumers either iterate it (tests) or
 * use it as documentation of the hub's intended shape. The actual
 * rendering in [SettingsHubScreen] mirrors this list manually, since
 * row-specific click handlers don't fit cleanly into a data class.
 *
 * Order matches the row order in [SettingsHubScreen]'s kdoc.
 */
data class SettingsHubSection(val title: String, val subtitle: String)

val SettingsHubSections: List<SettingsHubSection> = listOf(
    SettingsHubSection("Voice & Playback", "Voice, speed, cadence, pitch."),
    SettingsHubSection("Voice library", "Browse and switch between available voices."),
    SettingsHubSection("Reading", "Theme, sleep timer."),
    // v0.5.59 (#cover-style-toggle) — Appearance.
    SettingsHubSection("Appearance", "Book cover style."),
    SettingsHubSection("Performance", "Buffer, parallel synth, decoder choice."),
    SettingsHubSection("AI", "Chat model, grounding, recap."),
    // Phase 1 scaffold — v0.5.42. Phase 2 wires the actual behavior.
    SettingsHubSection("Accessibility", "TalkBack, contrast, motion, font scale."),
    SettingsHubSection("AI sessions", "Review past chats and delete history."),
    SettingsHubSection("Plugins", "Toggle backends — Fiction, Audio streams, Voice bundles."),
    SettingsHubSection("Pronunciation dictionary", "Per-word phonetic overrides."),
    SettingsHubSection("Account", "Royal Road, GitHub."),
    SettingsHubSection("Memory Palace", "Daemon host, probe, integration."),
    SettingsHubSection("Advanced", "Android Auto, integration tunables."),
    SettingsHubSection("Developer", "Debug overlay, log ring, advanced toggles."),
    SettingsHubSection("About", "Version, sigil, open-source notices."),
    SettingsHubSection("All settings", "Every setting on one long page (legacy)."),
)
