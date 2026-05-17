package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.BUFFER_DANGER_MULTIPLIER
import `in`.jphe.storyvox.feature.api.BUFFER_MAX_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_MIN_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_RECOMMENDED_MAX_CHUNKS
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_LONG_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_MAX_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_MIN_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_NORMAL_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_OFF_MULTIPLIER
import `in`.jphe.storyvox.feature.api.AzureProbeResult
import `in`.jphe.storyvox.feature.api.PalaceProbeResult
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiAiSettings
import `in`.jphe.storyvox.feature.api.UiAzureConfig
import `in`.jphe.storyvox.feature.api.UiChatGrounding
import `in`.jphe.storyvox.feature.api.UiGitHubAuthState
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.feature.api.UiPalaceConfig
import `in`.jphe.storyvox.feature.settings.components.SectionHeading
import `in`.jphe.storyvox.feature.settings.components.StatusPill
import `in`.jphe.storyvox.feature.settings.components.StatusTone
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.SkeletonBlock
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    onOpenVoiceLibrary: () -> Unit,
    onOpenSignIn: () -> Unit,
    onOpenGitHubSignIn: () -> Unit,
    onOpenGitHubRevoke: () -> Unit = {},
    onOpenTeamsSignIn: () -> Unit = {},
    onOpenPronunciationDict: () -> Unit,
    onOpenAiSessions: () -> Unit = {},
    /** Vesper (v0.4.97) — opens Settings → Developer → Debug. The
     *  default no-op is preview/test-only; production callsites wire
     *  this through `navController.navigate(SETTINGS_DEBUG)`. */
    onOpenDebug: () -> Unit = {},
    /** Phase 3 (#404) — opens Settings → Plugins. The plugin manager
     *  surfaces every `@SourcePlugin`-registered backend as a
     *  brass-edged card with toggle + capability chips + details
     *  sheet. Default no-op is preview/test-only. */
    onOpenPluginManager: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    // Issue #281 — Settings used to flash black for ~1-2s on tab open
    // while the DataStore-backed flow hydrated. `state.settings == null`
    // is the loading edge; before the fix we returned and rendered
    // nothing. Show a brass-shimmer skeleton instead so the user knows
    // the tab is loading, matching the FictionDetailSkeleton / Library
    // skeleton pattern shipped in v0.4.96.
    val s = state.settings ?: run {
        SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(spacing.md))
        return
    }

    Scaffold { padding ->
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        // ── 1. Voice & Playback ──────────────────────────────────────
        // The auditory knobs a listener touches *for this story, this
        // session*: which voice, how fast, how pitched, how to say
        // tricky names, how long to breathe between sentences.
        // Most-touched section → first.
        //
        // Iona (settings overhaul): Punctuation cadence migrated here
        // from Performance & buffering — it's a *voice* preference, not
        // a perf knob. Section now answers "how does this voice sound?"
        // end-to-end.
        SectionHeading(
            label = "Voice & Playback",
            icon = Icons.Outlined.RecordVoiceOver,
            descriptor = "How storyvox sounds — voice, speed, cadence.",
        )
        SettingsGroupCard {
            SettingsLinkRow(
                title = "Voice library",
                // Issue #670 — until per-voice sample-play exists, the
                // subtitle should not promise "hear samples". The
                // current row only Activates the voice; preview-without-
                // activation is filed as a v1.x enhancement.
                subtitle = "Browse and switch between available voices.",
                onClick = onOpenVoiceLibrary,
            )
            // #195 — sliders read+write the *effective* value (the
            // active voice's override, falling back to the global
            // default). The slider still appears global from the
            // user's perspective; the persistence is per-voice
            // silently. Switching voices brings their stored values
            // back automatically.
            // Speed range 0.5..4.0 puts the natural 1× thumb at ~14 %
            // from the left, which made the Pitch slider (0.6..1.4,
            // 1× at 50 %) look "set higher" even when both read 1.00×.
            // A single brass tick label anchors the natural value
            // explicitly so the visual offset reads as designed instead
            // of as a glitch (#273). Same pattern as
            // PunctuationPauseTickLabels — tap snaps the slider to 1×.
            //
            // Range constants are declared once and reused by both the
            // Slider's `valueRange` and the tick's fraction calc so the
            // tick can't drift if a range is ever rebalanced.
            val speedMin = 0.5f
            val speedMax = 4.0f
            val naturalValue = 1.0f
            val speedNaturalFraction = (naturalValue - speedMin) / (speedMax - speedMin)
            SettingsSliderBlock(
                title = "Speed",
                valueLabel = "${"%.2f".format(s.effectiveSpeed)}×",
                slider = {
                    Column {
                        Slider(
                            value = s.effectiveSpeed,
                            onValueChange = viewModel::setSpeed,
                            // Widened past Thalia's P1 #5 (commute listeners
                            // benefit from 3-4× on familiar narrators).
                            valueRange = speedMin..speedMax,
                            modifier = Modifier.semantics {
                                contentDescription = "Default speech speed"
                                stateDescription = "%.2f times".format(s.effectiveSpeed)
                            },
                        )
                        SliderTickLabels(
                            ticks = listOf("▲ 1×" to speedNaturalFraction),
                            onTickTap = { viewModel.setSpeed(naturalValue) },
                        )
                    }
                },
            )
            val pitchMin = 0.6f
            val pitchMax = 1.4f
            val pitchNaturalFraction = (naturalValue - pitchMin) / (pitchMax - pitchMin)
            SettingsSliderBlock(
                title = "Pitch",
                valueLabel = "${"%.2f".format(s.effectivePitch)}×",
                slider = {
                    Column {
                        Slider(
                            value = s.effectivePitch,
                            onValueChange = viewModel::setPitch,
                            // Narration-friendly band — matches AudiobookView. Hard
                            // floor at 0.6: Sonic introduces artifacts below ~0.7.
                            valueRange = pitchMin..pitchMax,
                            modifier = Modifier.semantics {
                                contentDescription = "Default pitch"
                                stateDescription = "%.2f, neutral at one".format(s.effectivePitch)
                            },
                        )
                        SliderTickLabels(
                            ticks = listOf("▲ 1×" to pitchNaturalFraction),
                            onTickTap = { viewModel.setPitch(naturalValue) },
                        )
                    }
                },
            )
            // Punctuation cadence — #109 continuous slider (was 3-stop
            // in #93). Range 0..4× matches the engine's internal clamp.
            // Iona (settings overhaul): moved here from Performance &
            // buffering — this is a voice/cadence preference, not a
            // memory/CPU trade.
            PunctuationPauseSlider(
                multiplier = s.punctuationPauseMultiplier,
                onMultiplierChange = viewModel::setPunctuationPauseMultiplier,
            )
            // Issue #193 — Sonic pitch-interpolation quality toggle.
            // Default ON for chapter-PCM-cached storyvox (post-#97);
            // OFF available for users on slow hardware who'd rather
            // burn the CPU cycles elsewhere.
            SettingsSwitchRow(
                title = "High-quality pitch interpolation",
                subtitle = if (s.pitchInterpolationHighQuality) {
                    "Smoother pitch-shifted audio. ~20% extra CPU per chapter render."
                } else {
                    "Faster pitch shifting. Some grittiness at non-neutral pitch."
                },
                checked = s.pitchInterpolationHighQuality,
                onCheckedChange = viewModel::setPitchInterpolationHighQuality,
            )
            SettingsLinkRow(
                title = "Pronunciation",
                subtitle = "Teach the voice how to say specific names and words.",
                onClick = onOpenPronunciationDict,
            )
        }

        // ── 2. Reading ───────────────────────────────────────────────
        // Visual reading knobs. Theme today; future home for font size
        // override, sentence highlight intensity, page-turn animation.
        // Sleep-shake also lives here for now (set-once switch); when
        // a dedicated Sleep & timers section materializes the
        // shake-to-extend toggle migrates there.
        SectionHeading(
            label = "Reading",
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            descriptor = "How chapter text and the reader behave.",
        )
        SettingsGroupCard {
            SettingsSegmentedBlock(
                title = "Theme",
                subtitle = "System matches the device's day/night.",
                options = ThemeOverride.entries.map { it.name },
                selectedIndex = ThemeOverride.entries.indexOf(s.themeOverride).coerceAtLeast(0),
                onSelected = { idx -> viewModel.setTheme(ThemeOverride.entries[idx]) },
            )
            // #150 — when the sleep timer hits its 10s fade tail,
            // shaking the device re-arms the timer for 15 minutes
            // and ramps volume back up. Off for users in moving
            // vehicles where accidental shakes are a problem.
            SettingsSwitchRow(
                title = "Shake to extend sleep timer",
                subtitle = "Shake during the fade-out to add 15 more minutes.",
                checked = s.sleepShakeToExtendEnabled,
                onCheckedChange = viewModel::setSleepShakeToExtendEnabled,
            )
        }

        // ── 3. Performance & buffering ───────────────────────────────
        // Daily knobs visible up top; advanced/experimental knobs tucked
        // into an [AdvancedExpander] so first-time users see two
        // controls instead of six.
        //
        // Iona (settings overhaul): Catch-up Pause and Buffer ride at
        // the top because they're the two perf knobs an everyday
        // listener actually touches when audio degrades on their
        // device. Warm-up Wait, Voice Determinism, and the Tier-3
        // parallel-synth sliders all live behind "More" — these are
        // either set-once preferences (warm-up, determinism) or deeply
        // experimental (parallel synth, #88). Punctuation cadence
        // moved to Voice & Playback in the same overhaul.
        SectionHeading(
            label = "Performance & buffering",
            icon = Icons.Outlined.Speed,
            descriptor = "Trade memory and CPU for smoother playback.",
        )
        SettingsGroupCard {
            // Mode B — Catch-up Pause. Default ON. ON: pause+resume on
            // underrun (PR #77). OFF: drain through underrun.
            SettingsSwitchRow(
                title = "Catch-up Pause",
                subtitle = if (s.catchupPause) {
                    "Pause briefly when the voice falls behind, then resume cleanly."
                } else {
                    "Drain through underruns; no buffering spinner."
                },
                checked = s.catchupPause,
                onCheckedChange = viewModel::setCatchupPause,
            )
            // Buffer slider keeps its custom rendering — colored
            // amber/red past the recommended tick is the whole point.
            BufferSlider(
                chunks = s.playbackBufferChunks,
                onChunksChange = viewModel::setPlaybackBufferChunks,
            )
            // PCM cache PR-G (#86) — Mode C + cache size + Clear cache.
            // Mirrors the rows in PerformanceSettingsScreen so users who
            // land here from "All settings" search see the same controls
            // in the same order. The two real-cache composables live in
            // PerformanceSettingsScreen (single source of truth);
            // a future refactor that fully removes this legacy section
            // can drop these three lines without touching the
            // subscreen.
            SettingsSwitchRow(
                title = "Full Pre-render",
                subtitle = if (s.fullPrerender) {
                    "Cache every chapter of every library fiction in the background. " +
                        "Aggressive disk + CPU use; gapless playback everywhere."
                } else {
                    "Cache the next few chapters only (1-3 on add, +2 on chapter end). " +
                        "Lighter on disk."
                },
                checked = s.fullPrerender,
                onCheckedChange = viewModel::setFullPrerender,
            )
            // Advanced/experimental cluster. Three rarely-touched perf
            // controls + the Tier-3 parallel-synth pair, all behind one
            // expander so first-time users aren't confronted with five
            // knobs at once. AdvancedExpander hides itself if fewer
            // than 3 entries; we have 4 (counting parallel-synth as
            // one logical row), so it always renders.
            var perfAdvancedOpen by remember { mutableStateOf(false) }
            AdvancedExpander(
                titlesPreview = listOf(
                    "Warm-up Wait",
                    "Voice Determinism",
                    "Parallel synth (engines + threads)",
                ),
                expanded = perfAdvancedOpen,
                onToggle = { perfAdvancedOpen = !perfAdvancedOpen },
            ) {
                // Mode A — Warm-up Wait. Default ON. ON: brass spinner +
                // frozen scrubber while engine warms up. OFF: silent start.
                SettingsSwitchRow(
                    title = "Warm-up Wait",
                    subtitle = if (s.warmupWait) {
                        "Wait for the voice to warm up before playback starts."
                    } else {
                        "Start playback immediately; accept silence at chapter start."
                    },
                    checked = s.warmupWait,
                    onCheckedChange = viewModel::setWarmupWait,
                )
                // Issue #85 — Voice Determinism preset. ON = VoxSherpa
                // calmed VITS defaults (replay-stable). OFF = sherpa-onnx
                // upstream Piper defaults (more variable prosody). Flips
                // force a model reload — handled by EnginePlayer.
                SettingsSwitchRow(
                    title = "Voice Determinism",
                    subtitle = if (s.voiceSteady) {
                        "Steady — identical text plays the same each time."
                    } else {
                        "Expressive — slight variation, fuller prosody."
                    },
                    checked = s.voiceSteady,
                    onCheckedChange = viewModel::setVoiceSteady,
                )
                // Tier 3 (#88) — experimental parallel synth sliders.
                // Two independent knobs: how many engine INSTANCES storyvox
                // loads (1..8) and how many THREADS each instance gets
                // inside sherpa-onnx (Auto..8). Both Piper and Kokoro
                // honor both knobs. Restart playback to apply changes.
                ParallelSynthSliders(
                    instances = s.parallelSynthInstances,
                    threadsPerInstance = s.synthThreadsPerInstance,
                    onInstancesChange = viewModel::setParallelSynthInstances,
                    onThreadsChange = viewModel::setSynthThreadsPerInstance,
                )
            }
        }

        // ── 4. AI ────────────────────────────────────────────────────
        // Smart features — Recap, character lookup, Q&A chat in Reader.
        // Configure-once-per-provider; positioned between perf (engine
        // tuning) and library (network syncing).
        SectionHeading(
            label = "AI",
            icon = Icons.Outlined.AutoAwesome,
            descriptor = "Smart features — Recap, character lookup, chat.",
        )
        SettingsGroupCard {
        AiSection(
            ai = s.ai,
            probeOutcome = state.probeOutcome,
            onSetProvider = viewModel::setAiProvider,
            onSetClaudeKey = viewModel::setClaudeApiKey,
            onSetClaudeModel = viewModel::setClaudeModel,
            onSetOpenAiKey = viewModel::setOpenAiApiKey,
            onSetOpenAiModel = viewModel::setOpenAiModel,
            onSetOllamaBaseUrl = viewModel::setOllamaBaseUrl,
            onSetOllamaModel = viewModel::setOllamaModel,
            onSetVertexKey = viewModel::setVertexApiKey,
            onSetVertexModel = viewModel::setVertexModel,
            onSetVertexServiceAccountJson = viewModel::setVertexServiceAccountJson,
            vertexSaError = viewModel.vertexSaError.collectAsStateWithLifecycle().value,
            onClearVertexSaError = viewModel::clearVertexSaError,
            onSetFoundryKey = viewModel::setFoundryApiKey,
            onSetFoundryEndpoint = viewModel::setFoundryEndpoint,
            onSetFoundryDeployment = viewModel::setFoundryDeployment,
            onSetFoundryServerless = viewModel::setFoundryServerless,
            onSetBedrockAccessKey = viewModel::setBedrockAccessKey,
            onSetBedrockSecretKey = viewModel::setBedrockSecretKey,
            onSetBedrockRegion = viewModel::setBedrockRegion,
            onSetBedrockModel = viewModel::setBedrockModel,
            onSetSendChapterText = viewModel::setSendChapterTextEnabled,
            onSetChatGroundChapterTitle = viewModel::setChatGroundChapterTitle,
            onSetChatGroundCurrentSentence = viewModel::setChatGroundCurrentSentence,
            onSetChatGroundEntireChapter = viewModel::setChatGroundEntireChapter,
            onSetChatGroundEntireBookSoFar = viewModel::setChatGroundEntireBookSoFar,
            // Issue #217 — cross-fiction memory toggle. Default ON for
            // fresh installs; users can opt out here. The wire-up keeps
            // the Settings → AI surface as the single source of truth
            // for AI behaviour toggles (parallel to the grounding-level
            // switches above).
            onSetCarryMemoryAcrossFictions = viewModel::setCarryMemoryAcrossFictions,
            onSetAiActionsEnabled = viewModel::setAiActionsEnabled,
            onTestConnection = viewModel::testAiConnection,
            onClearProbeOutcome = viewModel::clearProbeOutcome,
            onResetAi = viewModel::resetAiSettings,
            onOpenTeamsSignIn = onOpenTeamsSignIn,
            onSignOutTeams = viewModel::signOutTeams,
        )
        // #218 — surface for past chats + recap history. Lives at the
        // bottom of the AI card so the section's "Forget all AI
        // settings" destructive action stays visually adjacent.
        SettingsLinkRow(
            title = "Sessions",
            subtitle = "Review and manage past chats and chapter recaps.",
            onClick = onOpenAiSessions,
        )
        }

        // ── 5. Library & Sync ────────────────────────────────────────
        // Network preferences for keeping the library current. Renamed
        // from "Downloads" — "Library & Sync" matches storyvox's bottom-
        // tab language and reads as "what storyvox does in the
        // background to keep the library current."
        // Iona (settings overhaul): split into a Sources card and a
        // Sync card. The mixed paradigm in the old single card —
        // backend-visibility toggles next to network-poll knobs — was
        // confusing. Two cards with their own headers + descriptors
        // separates "which fictions show up in Browse" from "how
        // storyvox stays up to date".
        //
        // The redundant "Show in Browse picker." subtitle on every row
        // is gone; the section descriptor now carries that meaning,
        // and per-row subtitles convey actually-useful per-backend
        // hints (auth scope, file location).
        SectionHeading(
            label = "Library & Sync",
            icon = Icons.AutoMirrored.Outlined.LibraryBooks,
            descriptor = "Where stories come from and how often we check for updates.",
        )

        // ── Sources sub-card ──────────────────────────────────────
        // Plugin-seam Phase 3 (#384) + Plugin manager (#404) — the
        // inline per-backend toggle list (17 hard-coded rows) is gone;
        // the manager screen iterates `SourcePluginRegistry.descriptors`
        // and renders each plugin as a brass-edged card with toggle,
        // capability chips, and tap-for-details. Adding a new backend
        // (the `@SourcePlugin` annotation diff) automatically surfaces
        // a new row in the manager — no edit to this file required.
        SettingsGroupCard {
            SettingsLinkRow(
                title = "Plugins",
                subtitle = "Fiction sources, audio streams, and voice bundles.",
                onClick = onOpenPluginManager,
            )
            // Inline config rows that hang off specific plugins —
            // EPUB folder picker, Outline host/token, Wikipedia
            // language code, Notion db+token, Discord bot+server.
            // These stay accessible from the same section so the user
            // doesn't have to bounce through the plugin manager for
            // routine adjustments; the manager's per-plugin detail
            // sheet links here too.
            EpubFolderPickerRow(viewModel = viewModel)
            OutlineConfigRow(viewModel = viewModel)
            WikipediaLanguageRow(
                languageCode = s.wikipediaLanguageCode,
                onLanguageCodeChange = viewModel::setWikipediaLanguageCode,
            )
            NotionConfigRow(
                databaseId = s.notionDatabaseId,
                tokenConfigured = s.notionTokenConfigured,
                onDatabaseIdChange = viewModel::setNotionDatabaseId,
                onApiTokenChange = viewModel::setNotionApiToken,
            )
            val discordGuilds by viewModel.discordGuilds.collectAsStateWithLifecycle()
            DiscordConfigRow(
                tokenConfigured = s.discordTokenConfigured,
                serverId = s.discordServerId,
                serverName = s.discordServerName,
                coalesceMinutes = s.discordCoalesceMinutes,
                guilds = discordGuilds,
                onApiTokenChange = viewModel::setDiscordApiToken,
                onServerSelected = viewModel::setDiscordServer,
                onCoalesceMinutesChange = viewModel::setDiscordCoalesceMinutes,
                onRefreshGuilds = viewModel::refreshDiscordGuilds,
            )
            val telegramBot by viewModel.telegramBotUsername.collectAsStateWithLifecycle()
            val telegramChannels by viewModel.telegramChannels.collectAsStateWithLifecycle()
            TelegramConfigRow(
                tokenConfigured = s.telegramTokenConfigured,
                botUsername = telegramBot,
                channels = telegramChannels,
                onApiTokenChange = viewModel::setTelegramApiToken,
                onRefreshProbe = viewModel::refreshTelegramProbe,
            )
        }

        // ── Inbox notifications sub-card (#383) ────────────────────
        // Same section header — these are part of "Library & Sync"
        // because the toggles gate the cross-source Inbox feed and
        // the optional system notifications that fire alongside it.
        // One row per backend that emits update events today; the
        // matching field on UiSettings stays inert when the backend
        // doesn't poll for diffs yet (Wikipedia, Standard Ebooks —
        // tracked as follow-ups in #383's scope notes).
        SettingsGroupCard {
            SettingsSwitchRow(
                title = "Inbox: Royal Road",
                subtitle = "New-chapter events from your Royal Road follows.",
                checked = s.inboxNotifyRoyalRoad,
                onCheckedChange = viewModel::setInboxNotifyRoyalRoad,
            )
            SettingsSwitchRow(
                title = "Inbox: KVMR",
                subtitle = "Live programs starting on KVMR Community Radio.",
                checked = s.inboxNotifyKvmr,
                onCheckedChange = viewModel::setInboxNotifyKvmr,
            )
            SettingsSwitchRow(
                title = "Inbox: Wikipedia",
                subtitle = "Updates to followed Wikipedia articles. (No diff poller yet — see #383 follow-up.)",
                checked = s.inboxNotifyWikipedia,
                onCheckedChange = viewModel::setInboxNotifyWikipedia,
            )
        }

        // ── Sync sub-card ─────────────────────────────────────────
        // Same section header — these are still part of "Library &
        // Sync" — just visually distinct so the user reads the card
        // boundary as a paradigm shift (visibility → polling).
        SettingsGroupCard {
            SettingsSwitchRow(
                title = "Wi-Fi only",
                subtitle = "Don't poll on cellular.",
                checked = s.downloadOnWifiOnly,
                onCheckedChange = viewModel::setWifiOnly,
            )
            SettingsSliderBlock(
                title = "Update check interval",
                valueLabel = "Every ${s.pollIntervalHours}h",
                slider = {
                    Slider(
                        value = s.pollIntervalHours.toFloat(),
                        onValueChange = { viewModel.setPollHours(it.toInt().coerceIn(1, 24)) },
                        valueRange = 1f..24f,
                        modifier = Modifier.semantics {
                            contentDescription = "Update check interval"
                            stateDescription = "Every ${s.pollIntervalHours} hours"
                        },
                    )
                },
            )
        }

        // ── 6. Account ───────────────────────────────────────────────
        // Sign-in surfaces for fiction sources. Renamed from "Sources" —
        // the sources themselves don't have settings worth listing here
        // anymore (the feature is sign-in / sign-out + OAuth state).
        SectionHeading(
            label = "Account",
            icon = Icons.Outlined.AccountCircle,
            descriptor = "Sign-in for fiction sources that need it.",
        )
        SettingsGroupCard {
            // Royal Road row — status pill on top so the user sees
            // signed-in state without having to scan the row's trailing
            // button. Issue #91.
            StatusPill(
                text = if (s.isSignedIn) "Royal Road · signed in" else "Royal Road · not signed in",
                tone = if (s.isSignedIn) StatusTone.Connected else StatusTone.Neutral,
            )
            if (s.isSignedIn) {
                SettingsRow(
                    title = "Royal Road",
                    subtitle = "Signed in",
                    trailing = {
                        BrassButton(
                            label = "Sign out",
                            onClick = viewModel::signOut,
                            variant = BrassButtonVariant.Secondary,
                        )
                    },
                )
            } else {
                SettingsRow(
                    title = "Royal Road",
                    subtitle = "Sign-in unlocks Premium chapters and your Follows list.",
                    trailing = {
                        BrassButton(
                            label = "Sign in",
                            onClick = onOpenSignIn,
                            variant = BrassButtonVariant.Primary,
                        )
                    },
                )
            }
            // GitHub row (#91). Always shown — sign-in is additive
            // (lifts the anon 60 req/hr cap to 5,000 req/hr).
            // GitHub status pill — mirrors the Royal Road one above so
            // both sign-in surfaces read with the same affordance.
            StatusPill(
                text = when (val g = s.github) {
                    UiGitHubAuthState.Anonymous -> "GitHub · not signed in"
                    is UiGitHubAuthState.SignedIn ->
                        g.login?.let { "GitHub · signed in as @$it" }
                            ?: "GitHub · signed in"
                    UiGitHubAuthState.Expired -> "GitHub · session expired"
                },
                tone = when (s.github) {
                    UiGitHubAuthState.Anonymous -> StatusTone.Neutral
                    is UiGitHubAuthState.SignedIn -> StatusTone.Connected
                    UiGitHubAuthState.Expired -> StatusTone.Error
                },
            )
            GitHubSignInRow(
                state = s.github,
                privateReposEnabled = s.githubPrivateReposEnabled,
                onSignIn = onOpenGitHubSignIn,
                onSignOut = viewModel::signOutGitHub,
                onOpenRevokePage = onOpenGitHubRevoke,
                onSetPrivateReposEnabled = viewModel::setGitHubPrivateReposEnabled,
            )
        }

        // ── 7. Memory Palace ─────────────────────────────────────────
        // Post-spec section — the palace is a fiction source with its
        // own host/key config (substantial enough to keep separate from
        // Account, which is just sign-in flows).
        SectionHeading(
            label = "Memory Palace",
            icon = Icons.Outlined.AutoStories,
            descriptor = "Browse your local mempalace as fictions (LAN only).",
        )
        SettingsGroupCard {
            MemoryPalaceSection(
                palace = s.palace,
                probe = state.palaceProbe,
                probing = state.palaceProbing,
                onSetHost = viewModel::setPalaceHost,
                onSetApiKey = viewModel::setPalaceApiKey,
                onClear = viewModel::clearPalaceConfig,
                onTest = viewModel::testPalaceConnection,
            )
        }

        // ── 8. Cloud Voices (#182) ───────────────────────────────────
        // BYOK config for Azure HD voices. Voice rows in the picker
        // stay greyed-out until PR-4 (the engine wiring) lands; this
        // section ships first as a "preparation" release so users can
        // configure their key ahead of the engine.
        SectionHeading(
            label = "Cloud voices",
            icon = Icons.Outlined.Cloud,
            descriptor = "Bring your own Azure key for HD Neural and Dragon HD voices.",
        )
        SettingsGroupCard {
            AzureSection(
                azure = s.azure,
                probe = state.azureProbe,
                probing = state.azureProbing,
                onSetKey = viewModel::setAzureKey,
                onSetRegion = viewModel::setAzureRegion,
                onClear = viewModel::clearAzureCredentials,
                onTest = viewModel::testAzureConnection,
                fallbackEnabled = s.azureFallbackEnabled,
                fallbackVoiceId = s.azureFallbackVoiceId,
                installedVoices = state.voices,
                onSetFallbackEnabled = viewModel::setAzureFallbackEnabled,
                onSetFallbackVoice = viewModel::setAzureFallbackVoiceId,
            )
        }

        // ── 9. Developer ─────────────────────────────────────────────
        // Vesper (v0.4.97) — power-user diagnostics. The overlay master
        // switch lives here so it's discoverable in the same section
        // tree as every other UX preference; the dedicated /debug screen
        // is one tap away for deeper drill-down. Section sits just
        // before About because both are "you only look here when
        // something's wrong" surfaces, and About is the very last
        // bookend.
        SectionHeading(
            label = "Developer",
            icon = Icons.Outlined.BugReport,
            descriptor = "Live pipeline diagnostics + bug-report export.",
        )
        SettingsGroupCard {
            SettingsSwitchRow(
                title = "Show debug overlay",
                subtitle = if (s.showDebugOverlay) {
                    "A compact pipeline-state card floats above the reader."
                } else {
                    "Off — the reader stays clean."
                },
                checked = s.showDebugOverlay,
                onCheckedChange = viewModel::setShowDebugOverlay,
            )
            SettingsLinkRow(
                title = "Open Debug screen",
                subtitle = "Pipeline · engine · Azure · network · events · export.",
                onClick = onOpenDebug,
            )
            // Issue #599 (v1.0) — manual reset of the first-launch
            // welcome flow. Flips `pref_onboarding_completed_v1` back
            // to false so the three-screen welcome runs on next cold
            // launch (or, on a hot re-entry to the LIBRARY route, on
            // the next OnboardingHost recomposition). Surfaced here
            // rather than as a top-level affordance because it's
            // strictly a QA / dress-rehearsal control.
            SettingsLinkRow(
                title = "Reset onboarding",
                subtitle = "Show the first-launch welcome flow again. Restart the app to see it.",
                onClick = { viewModel.resetOnboarding() },
            )
        }

        // ── 10. About ────────────────────────────────────────────────
        // Realm-sigil "name" is deterministic adjective+noun from the
        // fantasy realm word list, keyed on the build's git hash. Same
        // hash → same name across rebuilds. The brass sigil name is
        // the visual sign-off — full-width below the version line so
        // it doesn't crowd narrow screens.
        SectionHeading(
            label = "About",
            icon = Icons.Outlined.Info,
            descriptor = "Build identity for bug reports.",
        )
        SettingsGroupCard {
            Column(
                modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                Text(
                    text = "storyvox v${s.sigil.versionName}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = s.sigil.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = buildString {
                        append(s.sigil.branch)
                        if (s.sigil.dirty) append(" · dirty")
                        append(" · built ")
                        append(s.sigil.built.take(10))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Calliope (v0.5.00) — graduation milestone pill. Brass
                // outline, no fill, small. Shown only when the build's
                // VERSION_NAME parses ≥ 0.5.00; on 0.4.x and lower it's
                // absent so the About card stays minimal. We check
                // versionName here rather than the persisted milestone
                // flag because the pill is a permanent build-identity
                // marker, not a "have I seen the dialog" trace — every
                // qualifying build wears the badge, regardless of
                // whether the user has dismissed the celebration.
                if (isV0500MilestoneBuild(s.sigil.versionName)) {
                    MilestoneBadgePill()
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().padding(top = spacing.lg))
    }
    }
}

/**
 * Sources → GitHub row. Issue #91.
 *
 * Three states matching [UiGitHubAuthState]:
 *  - Anonymous → "Sign in to GitHub" CTA + scope explainer.
 *  - SignedIn → "Signed in as @login" + Sign-out button + revoke deep-link.
 *  - Expired → "Session expired — sign in again" CTA + same revoke link.
 *
 * Issue #203 adds an "Enable private repos" SettingsSwitchRow that's
 * only visible when signed in. Toggling it doesn't auto-upgrade the
 * live token — the user has to sign out + back in for the new scope
 * to take effect; the subtitle nudges them when their current scope
 * doesn't match the requested scope.
 *
 * Spec § Settings UI surface, lines 384-395 of the design doc.
 */
@Composable
internal fun GitHubSignInRow(
    state: UiGitHubAuthState,
    privateReposEnabled: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onOpenRevokePage: () -> Unit,
    onSetPrivateReposEnabled: (Boolean) -> Unit,
) {
    when (state) {
        UiGitHubAuthState.Anonymous -> {
            SettingsRow(
                title = "GitHub",
                subtitle = "Sign in lifts the 60 req/hr anon cap to 5,000 req/hr and " +
                    "unlocks repository READMEs as fictions. read:user + public_repo only.",
                trailing = {
                    BrassButton(
                        label = "Sign in",
                        onClick = onSignIn,
                        variant = BrassButtonVariant.Primary,
                    )
                },
            )
        }
        is UiGitHubAuthState.SignedIn -> {
            SettingsRow(
                title = "GitHub",
                subtitle = state.login?.let { "Signed in as @$it · scope ${state.scopes}" }
                    ?: "Signed in · scope ${state.scopes}",
                trailing = {
                    BrassButton(
                        label = "Sign out",
                        onClick = onSignOut,
                        variant = BrassButtonVariant.Secondary,
                    )
                },
            )
            // #203 — "Enable private repos" toggle. Only visible
            // signed-in. Token-scope upgrade is opt-in and triggered
            // by the user re-running sign-in; the subtitle calls that
            // out when the live session's scope doesn't yet match.
            val tokenHasRepoScope = state.scopes.split(' ', ',').any { it.trim() == "repo" }
            val needsResign = privateReposEnabled && !tokenHasRepoScope
            val downgradePending = !privateReposEnabled && tokenHasRepoScope
            SettingsSwitchRow(
                title = "Enable private repos",
                subtitle = when {
                    needsResign ->
                        "ON. Sign out and back in to upgrade to the `repo` scope " +
                            "(read/write to private + public repos)."
                    downgradePending ->
                        "OFF. Current token still carries the `repo` scope; sign out " +
                            "and back in to drop down to `public_repo`."
                    privateReposEnabled ->
                        "Sign-in requests `repo` (full repo, read/write, includes private)."
                    else ->
                        "Sign-in requests `public_repo` only (least privilege)."
                },
                checked = privateReposEnabled,
                onCheckedChange = onSetPrivateReposEnabled,
            )
            SettingsLinkRow(
                title = "Revoke at github.com",
                subtitle = "Sign-out clears the local token; use this to revoke storyvox's " +
                    "authorization on GitHub's side too.",
                onClick = onOpenRevokePage,
            )
        }
        UiGitHubAuthState.Expired -> {
            SettingsRow(
                title = "GitHub",
                subtitle = "Session expired — sign in again to recover.",
                trailing = {
                    BrassButton(
                        label = "Sign in",
                        onClick = onSignIn,
                        variant = BrassButtonVariant.Primary,
                    )
                },
            )
        }
    }
}

/**
 * Memory Palace section for the Settings screen (#79).
 *
 * Two text fields (host, optional API key) plus a Test/Clear row. The
 * status pill above the fields shows the last probe result; user-typed
 * edits clear the previous status (the address has changed; previous
 * verdict no longer authoritative).
 *
 * Spec: docs/superpowers/specs/2026-05-08-mempalace-integration-design.md.
 */
@Composable
internal fun MemoryPalaceSection(
    palace: UiPalaceConfig,
    probe: PalaceProbeResult?,
    probing: Boolean,
    onSetHost: (String) -> Unit,
    onSetApiKey: (String) -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
) {
    val spacing = LocalSpacing.current
    // Status pill renders FIRST inside the card — first row of the
    // section, sized via the shared StatusPill component. Body fields
    // sit underneath in their own padded column so the pill reads as
    // a card-level header, not a tagged-on text node.
    val (statusText, statusTone) = when {
        !palace.isConfigured -> "Set host to enable" to StatusTone.Neutral
        probe == null -> "Tap “Test connection” to verify" to StatusTone.Neutral
        probe is PalaceProbeResult.Reachable ->
            "Connected · daemon ${probe.daemonVersion}" to StatusTone.Connected
        probe is PalaceProbeResult.Unreachable ->
            // "Off home network" mis-blamed the symptom on connectivity even
            // when the device was clearly on the LAN (e.g. cleartext-blocked,
            // daemon refused, parse error). Prefix now describes the symptom
            // honestly; the message body carries the actual cause from the
            // PalaceDaemonResult mapping in SettingsRepositoryUiImpl.
            "Couldn't reach palace · ${probe.message}" to StatusTone.Error
        probe is PalaceProbeResult.NotConfigured ->
            "Set host to enable" to StatusTone.Neutral
        else -> "" to StatusTone.Neutral
    }
    StatusPill(text = statusText, tone = statusTone)

    Column(
        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
    // Host field. Local state lets the user type freely; the persisted
    // setter fires onValueChange so changes are picked up across config
    // recreations. We don't debounce — DataStore is fine with the rate.
    var hostInput by remember(palace.host) { mutableStateOf(palace.host) }
    OutlinedTextField(
        value = hostInput,
        onValueChange = {
            hostInput = it
            onSetHost(it)
        },
        label = { Text("Palace host (e.g. 10.0.6.50:8085)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )

    // API key — masked. Stored in EncryptedSharedPreferences alongside
    // Royal Road cookies. Empty is fine for unauthenticated daemons.
    var apiKeyInput by remember(palace.apiKey) { mutableStateOf(palace.apiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = apiKeyInput,
        onValueChange = {
            apiKeyInput = it
            onSetApiKey(it)
        },
        label = { Text("API key (optional)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (apiKeyVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        // BrassButton.loading keeps the button at its measured width and
        // overlays a brass spinner — the row no longer reflows when the
        // probe starts. Replaces the older swap-to-CircularProgressIndicator
        // pattern (off-palette grey + width jump from "Test connection" to
        // "Testing…").
        BrassButton(
            label = "Test connection",
            onClick = onTest,
            variant = BrassButtonVariant.Primary,
            loading = probing,
        )
        if (palace.isConfigured) {
            BrassButton(
                label = "Clear",
                onClick = {
                    hostInput = ""
                    apiKeyInput = ""
                    onClear()
                },
                variant = BrassButtonVariant.Secondary,
                enabled = !probing,
            )
        }
    }
    }
}

/**
 * Settings → Cloud voices → Azure section (#182, PR-3).
 *
 * BYOK config for Azure HD voices. Mirrors [MemoryPalaceSection]'s
 * shape: status pill at the top, text fields, then Test/Clear buttons.
 * The "host" equivalent here is the **region** (a chip strip) since
 * Azure resource keys are region-scoped — the wrong region returns 401
 * even with a valid key.
 *
 * **The picker stays greyed-out for Azure voices until PR-4 wires the
 * engine.** This section ships first as a "preparation" release so
 * users can configure their key ahead of the engine landing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AzureSection(
    azure: UiAzureConfig,
    probe: AzureProbeResult?,
    probing: Boolean,
    onSetKey: (String?) -> Unit,
    onSetRegion: (String) -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit,
    fallbackEnabled: Boolean,
    fallbackVoiceId: String?,
    installedVoices: List<`in`.jphe.storyvox.feature.api.UiVoice>,
    onSetFallbackEnabled: (Boolean) -> Unit,
    onSetFallbackVoice: (String?) -> Unit,
) {
    val spacing = LocalSpacing.current
    val uriHandler = LocalUriHandler.current

    // Status pill renders FIRST inside the card — first row of the
    // section, sized via the shared StatusPill component, mirroring
    // the Memory Palace section. Body content below sits in its own
    // padded Column so the pill reads as a card-level header.
    val (statusText, statusTone) = when {
        !azure.isConfigured ->
            "No key configured" to StatusTone.Neutral
        probe == null ->
            "Tap “Test connection” to verify" to StatusTone.Neutral
        probe is AzureProbeResult.Reachable ->
            "Connected · ${probe.voiceCount} voices available" to StatusTone.Connected
        probe is AzureProbeResult.AuthFailed ->
            "Key rejected · re-paste from Azure portal" to StatusTone.Error
        probe is AzureProbeResult.Unreachable ->
            "Offline · ${probe.message}" to StatusTone.Error
        probe is AzureProbeResult.NotConfigured ->
            "No key configured" to StatusTone.Neutral
        else -> "" to StatusTone.Neutral
    }
    StatusPill(text = statusText, tone = statusTone)

    Column(
        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            "Bring your own Azure Speech Services subscription key to use " +
                "HD Neural and Dragon HD voices for synthesis. The key is " +
                "stored encrypted on this device only — storyvox never " +
                "sees it. Pricing is paid to Azure directly (≈ $30 / 1M " +
                "characters; F0 free tier covers 500K chars/month).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Region chip strip. Mirrors the Browse source-picker chip
        // pattern — 4 curated regions, picked one at a time. The
        // picker isn't a free-text "Other" affordance for v1; users
        // with a region outside the curated list (the rare case) can
        // still set it via [SettingsRepositoryUi.setAzureRegion]
        // programmatically, just not from Settings UI in PR-3.
        Text(
            "Region — must match the region your Azure resource is in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            listOf(
                "eastus" to "US East",
                "eastus2" to "US East 2",
                "westus" to "US West",
                "westus2" to "US West 2",
                "westeurope" to "West Europe",
                "eastasia" to "East Asia",
            ).forEach { (id, label) ->
                FilterChip(
                    selected = azure.regionId == id,
                    onClick = { onSetRegion(id) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }

        // Subscription key — masked. Same shape as the palace API-key
        // field; the encrypted persistence happens on every keystroke
        // via the onValueChange so a navigation-away mid-edit doesn't
        // lose the in-progress paste.
        var keyInput by remember(azure.key) { mutableStateOf(azure.key) }
        var keyVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = keyInput,
            onValueChange = {
                keyInput = it
                onSetKey(it)
            },
            label = { Text("Subscription key") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            // "Show key" toggle — useful when the user wants to verify
            // they pasted the right thing. Same shape as the palace
            // section's "show" pattern, but inline rather than a
            // trailing-icon button.
            // a11y (#481): toggleable text — TalkBack reads "Show key, switch, off" / "Hide key, switch, on".
            Text(
                if (keyVisible) "Hide key" else "Show key",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.toggleable(
                    value = keyVisible,
                    role = Role.Switch,
                    onValueChange = { keyVisible = it },
                ),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            BrassButton(
                label = "Test connection",
                onClick = onTest,
                variant = BrassButtonVariant.Primary,
                loading = probing,
            )
            if (azure.isConfigured) {
                BrassButton(
                    label = "Forget key",
                    onClick = {
                        keyInput = ""
                        onClear()
                    },
                    variant = BrassButtonVariant.Secondary,
                    enabled = !probing,
                )
            }
        }

        // Help link — opens the Azure portal docs in the browser. The
        // 4-step path is "Azure portal → Speech resource → keys &
        // endpoint → paste here", which the linked page covers; we
        // don't reproduce it inline because it'd rot when Microsoft
        // reorganizes their portal navigation.
        // Microsoft Learn's Speech Service overview — stable canonical
        // URL for "what is this and how do I get a key." The earlier
        // "/get-started" path 404'd after a docs reorg.
        val helpUrl = "https://learn.microsoft.com/en-us/azure/ai-services/speech-service/overview"
        val annotated = buildAnnotatedString {
            append("New here? ")
            withStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            ) {
                append("How do I get an Azure Speech key?")
            }
        }
        // a11y (#481): Role.Button for the inline help link.
        Text(
            annotated,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open Azure Speech docs") {
                uriHandler.openUri(helpUrl)
            },
        )

        // ── PR-6 (#185): offline fallback ─────────────────────────
        // Only meaningful with a key configured AND with at least
        // one local voice installed. Hide entirely otherwise — a
        // toggle the user can't act on is just clutter.
        if (azure.isConfigured && installedVoices.isNotEmpty()) {
            Divider(
                modifier = Modifier.padding(vertical = spacing.xs),
                color = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                "Offline fallback",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "If Azure is unreachable mid-chapter (network out, " +
                    "Azure servers misbehaving, or quota hit), " +
                    "auto-swap to a local voice for the rest of the " +
                    "chapter. The next chapter will try Azure again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // a11y (#478): wrap the row in Modifier.toggleable so
            // TalkBack announces a single Role.Switch node with the
            // visible label merged in. The Switch itself opts out of
            // independent click handling (onCheckedChange = null).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = fallbackEnabled,
                        role = Role.Switch,
                        onValueChange = onSetFallbackEnabled,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Fall back to local voice when offline",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = fallbackEnabled,
                    onCheckedChange = null,
                )
            }
            if (fallbackEnabled) {
                Text(
                    "Fallback voice — pick from your installed local voices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    installedVoices.forEach { v ->
                        FilterChip(
                            selected = fallbackVoiceId == v.id,
                            onClick = { onSetFallbackVoice(v.id) },
                            label = { Text(v.label) },
                            colors = FilterChipDefaults.filterChipColors(),
                        )
                    }
                }
                if (fallbackVoiceId == null) {
                    Text(
                        "No fallback voice picked yet — toggle is on but " +
                            "won't fire until you select one above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Tier 3 (#88) — twin sliders for the experimental parallel-synth
 * feature. Replaces the pre-slider Boolean toggle. Two knobs:
 *
 *  - **Engines (1..8)**: how many VoiceEngine / KokoroEngine instances
 *    storyvox constructs at loadModel time. 1 = serial. Each instance
 *    is ~150 MB (Piper) or ~325 MB (Kokoro) resident.
 *  - **Threads/engine (Auto, 1..8)**: numThreads passed to sherpa-onnx
 *    per instance. Auto = VoxSherpa's getOptimalThreadCount heuristic
 *    (defaults to the device's core count). Lower this on Snapdragon
 *    888-class chips that thermally throttle after sustained inference.
 *
 *  Total compute = engines × threads. Both sliders cap at 8 because
 *  beyond that the OS scheduler dominates and instance memory cost
 *  becomes pathological even on flagship hardware.
 */
@Composable
internal fun ParallelSynthSliders(
    instances: Int,
    threadsPerInstance: Int,
    onInstancesChange: (Int) -> Unit,
    onThreadsChange: (Int) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            "Experimental: parallel synth",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Two knobs for trading RAM and CPU for sustained throughput. " +
                "Restart playback to apply changes — the engines load on " +
                "the next chapter or voice swap.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Engines slider
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Engines",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (instances <= 1) "1 (serial)" else "$instances",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = instances.toFloat(),
            onValueChange = { onInstancesChange(it.toInt().coerceIn(1, 8)) },
            valueRange = 1f..8f,
            steps = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = when {
                instances <= 1 -> "Single engine, no extra RAM."
                else -> "$instances Piper engines ≈ ${instances * 150} MB · " +
                    "$instances Kokoro engines ≈ ${instances * 325} MB"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Threads-per-engine slider
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.sm),
        ) {
            Text(
                text = "Threads / engine",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (threadsPerInstance == 0) "Auto" else "$threadsPerInstance",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = threadsPerInstance.toFloat(),
            onValueChange = { onThreadsChange(it.toInt().coerceIn(0, 8)) },
            valueRange = 0f..8f,
            steps = 7,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = when {
                threadsPerInstance == 0 ->
                    "Auto — uses your device's core count (default)."
                else ->
                    "$threadsPerInstance threads per engine. Lower this if " +
                        "playback degrades after sustained use (Snapdragon 888 " +
                        "et al. thermally throttle when all cores are pegged)."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Average sentence duration in seconds at 1.0× speed. Empirical, used only
 *  for the human-readable "≈ N seconds" hint under the slider. The actual
 *  knob is queue depth (chunks). */
private const val AVG_SENTENCE_SEC: Float = 2.5f

/** Approximate bytes per chunk @ 22050 Hz mono 16-bit, 2.5 s avg.
 *  22050 × 2 × 2.5 ≈ 110 KB. Used for the "≈ N MB" hint. */
private const val APPROX_BYTES_PER_CHUNK: Int = 110_000

/**
 * Settings → Audio buffer slider.
 *
 * The buffer is the pre-synthesized PCM queue depth fed to AudioTrack. On
 * fast voices / fast CPUs the default of 8 chunks is plenty; on slow voices
 * (Piper-high) and slow CPUs (Helio P22T) the producer falls behind and the
 * listener hears mid-sentence underruns. Letting users dial up the queue
 * trades RAM for resilience.
 *
 * The mechanical max ([BUFFER_MAX_CHUNKS]) intentionally goes well past
 * where we believe Android's Low Memory Killer will start marking the app —
 * issue #84 is the experimental probe to find that line. Past the
 * [BUFFER_RECOMMENDED_MAX_CHUNKS] tick we (a) intensify the warning copy and
 * (b) shift the slider track color amber → red so the user knows they've
 * crossed into experimental territory.
 */
@Composable
internal fun BufferSlider(
    chunks: Int,
    onChunksChange: (Int) -> Unit,
) {
    val spacing = LocalSpacing.current
    val pastTick = chunks > BUFFER_RECOMMENDED_MAX_CHUNKS
    val dangerThreshold = BUFFER_RECOMMENDED_MAX_CHUNKS * BUFFER_DANGER_MULTIPLIER
    val danger = chunks > dangerThreshold

    // Two distinct color states above the tick (amber → red) so the user has
    // a tactile sense of "I'm in unknown territory" → "I'm in dangerous
    // unknown territory". Below the tick we use the brand primary.
    val amber = Color(0xFFE0A040)
    val red = Color(0xFFD05030)
    val activeColor = when {
        danger -> red
        pastTick -> amber
        else -> MaterialTheme.colorScheme.primary
    }
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

    val approxSeconds = (chunks * AVG_SENTENCE_SEC).toInt()
    val approxMb = (chunks.toLong() * APPROX_BYTES_PER_CHUNK / 1_048_576L).toInt()

    // Experimental-zone gate (#138). Default-locked: slider clamps at the
    // danger threshold (recommended max × danger multiplier = 256) so a
    // casual drag can't walk into the LMK kill zone. JP wants the past-
    // danger range available for probing; tap "Unlock experimental zone"
    // and the slider extends to BUFFER_MAX_CHUNKS. Lock state is
    // composition-local — leaving Settings re-locks it, an intentional
    // friction so the buffer doesn't stay unlocked across sessions and
    // catch a future user off-guard.
    var unlocked by remember { mutableStateOf(chunks > dangerThreshold) }
    val effectiveMax = if (unlocked) BUFFER_MAX_CHUNKS else dangerThreshold

    Column(
        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        // Title + brass value, matching SettingsSliderBlock.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Buffer",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$chunks chunks · ~${approxSeconds}s · ~${approxMb} MB",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = if (unlocked) {
                "Recommended max: $BUFFER_RECOMMENDED_MAX_CHUNKS chunks · experimental zone unlocked"
            } else {
                "Recommended max: $BUFFER_RECOMMENDED_MAX_CHUNKS chunks · capped at ${dangerThreshold} (4× recommended)"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Slider(
            value = chunks.toFloat().coerceAtMost(effectiveMax.toFloat()),
            onValueChange = { onChunksChange(it.toInt().coerceIn(BUFFER_MIN_CHUNKS, effectiveMax)) },
            valueRange = BUFFER_MIN_CHUNKS.toFloat()..effectiveMax.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = activeColor,
                activeTrackColor = activeColor,
                inactiveTrackColor = inactiveColor,
            ),
            modifier = Modifier.semantics {
                contentDescription = "Playback buffer size"
                stateDescription = if (unlocked) {
                    "$chunks chunks, recommended max $BUFFER_RECOMMENDED_MAX_CHUNKS, experimental zone unlocked"
                } else {
                    "$chunks chunks, recommended max $BUFFER_RECOMMENDED_MAX_CHUNKS, capped at $dangerThreshold"
                }
            },
        )

        // Recommended-max tick label — anchored to its proportional position
        // on the slider via SliderTickLabels' weighted-spacer trick.
        TickMarker(
            tickValue = BUFFER_RECOMMENDED_MAX_CHUNKS,
            min = BUFFER_MIN_CHUNKS,
            max = effectiveMax,
        )

        // Lock/unlock toggle. Always rendered so the affordance is visible
        // even before the user reaches the cap — they can choose to enter
        // experimental mode pre-emptively. When locked + at the cap, copy
        // intensifies to "tap to unlock and probe further."
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
        ) {
            Text(
                text = if (unlocked) {
                    "⚠️ Past ${dangerThreshold}, Android may kill the app. " +
                        "Probing helps us find the exact line."
                } else if (chunks >= dangerThreshold) {
                    "Tap to probe past ${dangerThreshold} chunks (Android may kill the app)."
                } else {
                    "Locked at ${dangerThreshold} chunks (4× recommended)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (unlocked) red else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            BrassButton(
                label = if (unlocked) "Re-lock" else "Unlock",
                onClick = {
                    val nowUnlocked = !unlocked
                    unlocked = nowUnlocked
                    if (!nowUnlocked && chunks > dangerThreshold) {
                        // Re-lock pulls the value back to the threshold so
                        // a stale past-danger value doesn't sit hidden.
                        onChunksChange(dangerThreshold)
                    }
                },
                variant = if (unlocked) BrassButtonVariant.Text else BrassButtonVariant.Secondary,
            )
        }

        // One-liner explainer — full multi-paragraph rationale lives in
        // #84 + #138 issue bodies, not the Settings card.
        Text(
            text = "Pre-synthesizes audio ahead of where you're listening. " +
                "Larger buffer hides slow voices; past the recommended max risks " +
                "Android killing the app in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.xs),
        )

        if (pastTick) {
            Text(
                text = if (danger) {
                    "Danger zone — Android is likely to kill the app while " +
                        "you're not looking. Pull back unless you're actively probing."
                } else {
                    "Past recommended — Android may kill the app in the background. " +
                        "Help us find the line by reporting what works."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (danger) red else amber,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

/**
 * A discrete label rendered at the slider's tick fraction. Delegates
 * placement to [SliderTickLabels] so the visual ▲ caret aligns with the
 * Material3 Slider thumb position for the given value (rather than the
 * raw row-width fraction, which the legacy weight-spacer layout used and
 * which drifted by the label's intrinsic width — same root cause Tessa
 * fixed for the punctuation slider in #146).
 *
 * Doesn't paint onto the slider canvas (Material3 Slider's track-painter
 * customization is verbose for what we need); renders below the slider
 * at the thumb-anchored horizontal offset.
 */
@Composable
private fun TickMarker(
    tickValue: Int,
    min: Int,
    max: Int,
) {
    val fraction = ((tickValue - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
    SliderTickLabels(ticks = listOf("▲ $tickValue" to fraction))
}

/**
 * Settings → Punctuation Cadence slider (issue #109).
 *
 * Continuous multiplier on the inter-sentence silence storyvox splices after
 * each TTS sentence. The base pause table (350/200/120/60 ms by terminator)
 * lives in `EngineStreamingSource.trailingPauseMs` and is scaled by this
 * value before being emitted as PCM zeros.
 *
 * Range is [PUNCTUATION_PAUSE_MIN_MULTIPLIER]..[PUNCTUATION_PAUSE_MAX_MULTIPLIER]
 * (0×..4×) — wider than the legacy 3-stop selector's 0×/1×/1.75× because
 * the engine has always coerced to 0..4 internally and #109 surfaces that
 * full range. Tick labels anchor the legacy stops + the new ceiling so
 * users who liked "Long" can find it precisely (1.75×).
 *
 * Same brass-styled aesthetic as [BufferSlider] — primary-colored thumb +
 * active track, surface-variant inactive track. No "warning zone": every
 * point on this slider is mechanically safe; it's purely a cadence
 * preference. Single decimal-place readout (e.g. "1.8×") is enough
 * precision for a perceptual knob.
 */
@Composable
internal fun PunctuationPauseSlider(
    multiplier: Float,
    onMultiplierChange: (Float) -> Unit,
) {
    val spacing = LocalSpacing.current

    Column(
        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text("Pause after . , ? ! ; :", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Inter-sentence breath. 0× sprints, 1× is the audiobook " +
                "default, 4× is theatrical.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "${"%.2f".format(multiplier)}×",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Slider(
            value = multiplier,
            onValueChange = {
                onMultiplierChange(
                    it.coerceIn(PUNCTUATION_PAUSE_MIN_MULTIPLIER, PUNCTUATION_PAUSE_MAX_MULTIPLIER),
                )
            },
            valueRange = PUNCTUATION_PAUSE_MIN_MULTIPLIER..PUNCTUATION_PAUSE_MAX_MULTIPLIER,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            // TalkBack #160 — "Off" is the visible label at multiplier 0,
            // so we mirror that for screen-reader users; otherwise read
            // the multiplier directly with a "times" suffix to match the
            // visible "Pause after punctuation: 1.50×" line above.
            modifier = Modifier.semantics {
                contentDescription = "Pause after punctuation"
                stateDescription = if (multiplier <= PUNCTUATION_PAUSE_MIN_MULTIPLIER) {
                    "Off"
                } else {
                    "%.2f times".format(multiplier)
                }
            },
        )

        // Tick labels at the legacy stops + the new 4× ceiling. Spatial
        // anchoring uses the same weight-trick as [TickMarker] so the labels
        // sit under their actual slider positions without painting onto the
        // canvas. Material3 Slider's `steps` parameter would draw real ticks
        // but only at evenly-spaced fractions of the range; our stops aren't
        // evenly spaced (0/1/1.75/4 fractions are 0, 0.25, 0.4375, 1) so
        // we render them as a separate row.
        //
        // Issue #261 — labels are tappable; tap snaps the slider to the
        // tick's value. The '▲' caret pointing up at the slider already
        // reads as 'tap to move me here'; the click handler delivers on
        // that affordance.
        PunctuationPauseTickLabels(onSnap = onMultiplierChange)
    }
}

/**
 * Anchored row of legacy-stop labels under [PunctuationPauseSlider]. Each
 * label sits at its true fractional position along the [0..4] range, with
 * placement delegated to [SliderTickLabels]. Labels include the legacy
 * stop names (Off / Normal / Long) so users coming from the 3-stop
 * selector can find their preferred cadence at a glance.
 */
@Composable
private fun PunctuationPauseTickLabels(
    onSnap: (Float) -> Unit,
) {
    val total = PUNCTUATION_PAUSE_MAX_MULTIPLIER - PUNCTUATION_PAUSE_MIN_MULTIPLIER
    // Issue #261 — labels now snap the slider when tapped. The '▲'
    // caret already points up at the slider as if to say 'I will move
    // you here'; the click handler makes the affordance honest. The
    // tick values mirror the position fractions so the same array can
    // drive both placement and snap targets.
    val tickValues = listOf(
        PUNCTUATION_PAUSE_OFF_MULTIPLIER,
        PUNCTUATION_PAUSE_NORMAL_MULTIPLIER,
        PUNCTUATION_PAUSE_LONG_MULTIPLIER,
        PUNCTUATION_PAUSE_MAX_MULTIPLIER,
    )
    SliderTickLabels(
        ticks = listOf(
            "▲ Off" to ((PUNCTUATION_PAUSE_OFF_MULTIPLIER - PUNCTUATION_PAUSE_MIN_MULTIPLIER) / total),
            "▲ 1×" to ((PUNCTUATION_PAUSE_NORMAL_MULTIPLIER - PUNCTUATION_PAUSE_MIN_MULTIPLIER) / total),
            "▲ Long" to ((PUNCTUATION_PAUSE_LONG_MULTIPLIER - PUNCTUATION_PAUSE_MIN_MULTIPLIER) / total),
            "▲ 4×" to 1f,
        ),
        onTickTap = { index -> onSnap(tickValues[index]) },
    )
}

/**
 * Reusable row of slider tick labels, anchored to their true fractional
 * positions along the slider track. Extracted from the punctuation
 * slider (#139, Tessa's #146) so the buffer slider's `▲ N` recommended-
 * max marker can share the same thumb-aligned placement math instead of
 * the legacy weight-spacer trick.
 *
 * Why not [Row] with weight spacers? With multiple labels in a single
 * [Row], Compose's weight system divides only the *remaining* width
 * after measuring unweighted children (the Texts themselves), so each
 * label drifts right by the cumulative widths of preceding labels.
 * With four labels under the punctuation slider the drift visibly
 * mismatched the thumb position (#139). Even the single-label
 * [TickMarker] case for the buffer slider's `▲ 64` marker had the same
 * intrinsic-width-leak — by 64-on-a-1500-wide-range the drift was
 * subpixel, but at higher tick fractions or smaller parent widths the
 * label slid right of the thumb just like the punctuation case.
 *
 * We also account for the M3 [Slider]'s internal track padding (half
 * the thumb width = 10dp on each side), which the surrounding Column
 * does not inherit. Fraction 0 in the parent layout maps to
 * track-x = 0 + padding, not to the leftmost pixel of the parent.
 *
 * Each entry is `(label, fraction)` where `fraction ∈ [0, 1]` is the
 * position along the slider track. Labels render in the order given;
 * the last entry is right-aligned so its trailing characters don't
 * overflow the parent edge.
 */
@Composable
internal fun SliderTickLabels(
    ticks: List<Pair<String, Float>>,
    /** Issue #261 — when non-null, tapping a tick label invokes the
     *  callback with the tick's index. The visible '▲' caret pointing
     *  up at the slider track reads as 'tap to snap here', so wiring
     *  the click is what makes the affordance honest. Callers that
     *  don't want tap behaviour (the buffer-size slider's single
     *  preview tick) pass null. */
    onTickTap: ((Int) -> Unit)? = null,
) {
    if (ticks.isEmpty()) return

    // M3 Slider reserves half the thumb diameter as track padding on
    // each side (default thumb is 20dp, so 10dp). Hardcoded here
    // because SliderDefaults doesn't expose this constant publicly. If
    // the thumb size ever changes, the labels will drift by ≤10dp —
    // visible only on the extreme ends — so this stays a load-bearing
    // constant.
    val trackPaddingDp = SLIDER_TRACK_PADDING_DP

    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            ticks.forEachIndexed { index, (label, _) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // a11y (#481): tick labels are tap-to-snap buttons.
                    modifier = if (onTickTap != null) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = "Snap to $label",
                        ) { onTickTap(index) }
                    } else {
                        Modifier
                    },
                )
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val rowWidth = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val trackPaddingPx = trackPaddingDp.dp.toPx().toInt()

        layout(rowWidth, height) {
            placeables.forEachIndexed { i, placeable ->
                val frac = ticks[i].second
                // The right-align rule exists to keep the rightmost-of-
                // many label from running off the parent's right edge
                // when its preceding labels have already consumed track
                // space. With only one tick there's nothing preceding,
                // so we anchor at the thumb-x just like a middle tick —
                // this is what makes the buffer slider's `▲ 64` align
                // with the thumb at value 64 instead of pinning to the
                // right edge of the parent.
                val isLast = ticks.size > 1 && i == ticks.lastIndex
                val x = computeTickLabelX(
                    rowWidthPx = rowWidth,
                    trackPaddingPx = trackPaddingPx,
                    fraction = frac,
                    labelWidthPx = placeable.width,
                    isLast = isLast,
                )
                placeable.place(x, 0)
            }
        }
    }
}

/** Half the M3 default thumb diameter; track padding on each side. */
private const val SLIDER_TRACK_PADDING_DP: Int = 10

/**
 * Pure placement math for [SliderTickLabels], extracted for unit
 * testing. Returns the integer x-offset (in pixels) at which a tick label
 * should be placed inside the parent so its visual ▲ caret sits at the
 * slider thumb position for the given [fraction].
 *
 * Anchoring rule:
 *  - The leftmost label and all middle labels are **left-aligned** at
 *    `trackPaddingPx + fraction × trackWidthPx`. This puts the leading ▲
 *    character at the slider's thumb-x for that value.
 *  - The rightmost label is **right-aligned** to the parent so its full
 *    text stays on screen (the ▲ ends up slightly right of the thumb's
 *    track-x by half-a-thumb, which is the correct visual since the
 *    thumb itself extends right of trackEnd by half its width).
 *
 * All labels are clamped so they never overflow the parent on the right.
 */
internal fun computeTickLabelX(
    rowWidthPx: Int,
    trackPaddingPx: Int,
    fraction: Float,
    labelWidthPx: Int,
    isLast: Boolean,
): Int {
    if (isLast) {
        // Right-align: label's right edge at parent's right edge.
        return (rowWidthPx - labelWidthPx).coerceAtLeast(0)
    }
    val trackWidthPx = (rowWidthPx - 2 * trackPaddingPx).coerceAtLeast(0)
    val anchorX = trackPaddingPx + (fraction.coerceIn(0f, 1f) * trackWidthPx).toInt()
    // Clamp so multi-character middle labels don't overflow the right edge.
    return anchorX.coerceIn(0, (rowWidthPx - labelWidthPx).coerceAtLeast(0))
}

/**
 * Settings → AI section. Provider selector + per-provider config +
 * Test connection + privacy toggle. Issue #81.
 *
 * Inline (not a sub-screen) for v1 — matches the existing Settings
 * structure where every category is a section divider in one
 * scrollable list. We can promote to a sub-screen if the AI
 * controls cross ~5 toggles (matching where the Voices section is
 * heading).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AiSection(
    ai: UiAiSettings,
    probeOutcome: ProbeOutcome?,
    onSetProvider: (UiLlmProvider?) -> Unit,
    onSetClaudeKey: (String?) -> Unit,
    onSetClaudeModel: (String) -> Unit,
    onSetOpenAiKey: (String?) -> Unit,
    onSetOpenAiModel: (String) -> Unit,
    onSetOllamaBaseUrl: (String) -> Unit,
    onSetOllamaModel: (String) -> Unit,
    onSetVertexKey: (String?) -> Unit,
    onSetVertexModel: (String) -> Unit,
    /** Issue #219 — install or clear a Google service-account JSON
     *  for Vertex auth. The caller (SAF picker) reads the picked
     *  document's text and passes it through; `null` clears. The
     *  VM signals parse/validation failures via [vertexSaError]. */
    onSetVertexServiceAccountJson: (String?) -> Unit,
    /** Last SA-JSON failure message, or null when none/cleared. */
    vertexSaError: String?,
    onClearVertexSaError: () -> Unit,
    onSetFoundryKey: (String?) -> Unit,
    onSetFoundryEndpoint: (String) -> Unit,
    onSetFoundryDeployment: (String) -> Unit,
    onSetFoundryServerless: (Boolean) -> Unit,
    onSetBedrockAccessKey: (String?) -> Unit,
    onSetBedrockSecretKey: (String?) -> Unit,
    onSetBedrockRegion: (String) -> Unit,
    onSetBedrockModel: (String) -> Unit,
    onSetSendChapterText: (Boolean) -> Unit,
    onSetChatGroundChapterTitle: (Boolean) -> Unit,
    onSetChatGroundCurrentSentence: (Boolean) -> Unit,
    onSetChatGroundEntireChapter: (Boolean) -> Unit,
    onSetChatGroundEntireBookSoFar: (Boolean) -> Unit,
    /** Issue #217 — Carry memory across fictions. The chat ViewModel
     *  reads [UiAiSettings.carryMemoryAcrossFictions] at send-time and
     *  conditionally appends the cross-fiction context block to the
     *  system prompt. Default ON. */
    onSetCarryMemoryAcrossFictions: (Boolean) -> Unit,
    /** Issue #216 — Allow the AI to take actions. See
     *  [UiAiSettings.actionsEnabled]. Default ON. */
    onSetAiActionsEnabled: (Boolean) -> Unit,
    onTestConnection: (UiLlmProvider) -> Unit,
    onClearProbeOutcome: () -> Unit,
    onResetAi: () -> Unit,
    onOpenTeamsSignIn: () -> Unit,
    onSignOutTeams: () -> Unit,
) {
    val spacing = LocalSpacing.current
    // Header is now emitted by the call site (SettingsScreen). Indent
    // body to match the SettingsGroupCard's row-style padding so we
    // don't break out of the card visually.
    Column(
        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
    Text(
        "Smart features (Recap, character lookup, chat) ask an AI to answer " +
            "questions about what you're reading. Pick a provider, then enable a feature. " +
            "Local providers (Ollama) keep your text on your network; cloud providers " +
            "send it to that company's servers.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Provider chip strip. FlowRow so seven providers wrap on the
    // 800-px tablet rather than wrapping mid-word ("Foundr / y").
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        ProviderChip(label = "Off", selected = ai.provider == null) {
            onSetProvider(null)
        }
        ProviderChip(label = "Claude", selected = ai.provider == UiLlmProvider.Claude) {
            onSetProvider(UiLlmProvider.Claude)
        }
        ProviderChip(label = "OpenAI", selected = ai.provider == UiLlmProvider.OpenAi) {
            onSetProvider(UiLlmProvider.OpenAi)
        }
        ProviderChip(label = "Ollama", selected = ai.provider == UiLlmProvider.Ollama) {
            onSetProvider(UiLlmProvider.Ollama)
        }
        ProviderChip(label = "Vertex", selected = ai.provider == UiLlmProvider.Vertex) {
            onSetProvider(UiLlmProvider.Vertex)
        }
        ProviderChip(label = "Foundry", selected = ai.provider == UiLlmProvider.Foundry) {
            onSetProvider(UiLlmProvider.Foundry)
        }
        ProviderChip(label = "Bedrock", selected = ai.provider == UiLlmProvider.Bedrock) {
            onSetProvider(UiLlmProvider.Bedrock)
        }
        ProviderChip(label = "Teams", selected = ai.provider == UiLlmProvider.Teams) {
            onSetProvider(UiLlmProvider.Teams)
        }
    }

    when (ai.provider) {
        UiLlmProvider.Claude -> ClaudeProviderRows(
            ai = ai,
            onSetClaudeKey = onSetClaudeKey,
            onSetClaudeModel = onSetClaudeModel,
        )
        UiLlmProvider.OpenAi -> OpenAiProviderRows(
            ai = ai,
            onSetOpenAiKey = onSetOpenAiKey,
            onSetOpenAiModel = onSetOpenAiModel,
        )
        UiLlmProvider.Ollama -> OllamaProviderRows(
            ai = ai,
            onSetOllamaBaseUrl = onSetOllamaBaseUrl,
            onSetOllamaModel = onSetOllamaModel,
        )
        UiLlmProvider.Vertex -> VertexProviderRows(
            ai = ai,
            onSetVertexKey = onSetVertexKey,
            onSetVertexModel = onSetVertexModel,
            onSetVertexServiceAccountJson = onSetVertexServiceAccountJson,
            vertexSaError = vertexSaError,
            onClearVertexSaError = onClearVertexSaError,
        )
        UiLlmProvider.Foundry -> AzureFoundryProviderRows(
            ai = ai,
            onSetFoundryKey = onSetFoundryKey,
            onSetFoundryEndpoint = onSetFoundryEndpoint,
            onSetFoundryDeployment = onSetFoundryDeployment,
            onSetFoundryServerless = onSetFoundryServerless,
        )
        UiLlmProvider.Bedrock -> BedrockProviderRows(
            ai = ai,
            onSetAccessKey = onSetBedrockAccessKey,
            onSetSecretKey = onSetBedrockSecretKey,
            onSetRegion = onSetBedrockRegion,
            onSetModel = onSetBedrockModel,
        )
        UiLlmProvider.Teams -> AnthropicTeamsProviderRows(
            ai = ai,
            onOpenSignIn = onOpenTeamsSignIn,
            onSignOut = onSignOutTeams,
        )
        null -> { /* Off — nothing more to show */ }
    }

    if (ai.provider != null && ai.provider.implemented) {
        BrassButton(
            label = "Test connection",
            onClick = { onTestConnection(ai.provider) },
            variant = BrassButtonVariant.Secondary,
        )
        ProbeOutcomeMessage(probeOutcome, onClearProbeOutcome)
    }

    if (ai.provider != null) {
        SettingsSwitchRow(
            title = "Allow chapter text to AI",
            subtitle = if (ai.sendChapterTextEnabled) {
                "Recap, character lookup, and chat can read the current chapter."
            } else {
                "Smart features need this on — turn off to fully disable AI access."
            },
            checked = ai.sendChapterTextEnabled,
            onCheckedChange = onSetSendChapterText,
        )
        // Issue #212 — chat grounding-level toggles. The fiction title
        // is always sent (no toggle); each row below adds a layer of
        // context to the chat ViewModel's system prompt. Token estimates
        // assume the rough chars / 4 ≈ tokens convention.
        //
        // Iona (settings overhaul): wrapped in the existing
        // AdvancedExpander so the four grounding-level switches don't
        // dominate the AI section's vertical real estate. Most users
        // accept the default grounding and never touch these; the few
        // who do tune privacy/cost find them under "Chat grounding".
        var groundingOpen by remember { mutableStateOf(false) }
        AdvancedExpander(
            titlesPreview = listOf(
                "Chapter title",
                "Current sentence",
                "Entire chapter",
                "Entire book so far",
            ),
            expanded = groundingOpen,
            onToggle = { groundingOpen = !groundingOpen },
            title = "Chat grounding",
        ) {
            ChatGroundingSubsection(
                grounding = ai.chatGrounding,
                enabled = ai.sendChapterTextEnabled,
                onSetChapterTitle = onSetChatGroundChapterTitle,
                onSetCurrentSentence = onSetChatGroundCurrentSentence,
                onSetEntireChapter = onSetChatGroundEntireChapter,
                onSetEntireBookSoFar = onSetChatGroundEntireBookSoFar,
            )
        }
        // Issue #217 — Cross-fiction memory toggle. Sits as a top-level
        // switch row (not nested in an expander) because there's only
        // one knob and it materially affects what the AI knows about
        // the reader. Subtitle calls out the per-user-library partition
        // ("everything you've read") and the bounded cost (~500 token
        // cap, oldest dropped first) so the trade-off is legible at a
        // glance. Default ON for fresh installs.
        SettingsSwitchRow(
            title = "Carry memory across fictions",
            subtitle = "Chat assistant remembers characters, places, and " +
                "concepts from other books you've read. Capped at ~500 " +
                "tokens per turn; oldest entries drop first.",
            checked = ai.carryMemoryAcrossFictions,
            onCheckedChange = onSetCarryMemoryAcrossFictions,
        )
        // Issue #216 — actions toggle. Sits below cross-fiction memory
        // because it's the same kind of opt-in capability ("how much
        // agency does the AI have?"). Default ON for fresh installs;
        // subtitle calls out the five v1 tools so the user knows
        // exactly what's gated.
        SettingsSwitchRow(
            title = "Allow the AI to take actions",
            subtitle = "Chat can add books to shelves, queue chapters, " +
                "mark chapters read, set playback speed, and open the " +
                "voice library when you ask. Works on Anthropic and OpenAI.",
            checked = ai.actionsEnabled,
            onCheckedChange = onSetAiActionsEnabled,
        )
        // Issue #262 — destructive AI-reset path. Previously a plain
        // brass-tinted TextButton that looked identical to a 'Save' or
        // 'Show' affordance. Now error-tinted with a leading Delete icon
        // + a confirmation AlertDialog so a single mis-tap can't erase
        // every endpoint URL, API key, and chat history.
        var showResetAiConfirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.material3.TextButton(
            onClick = { showResetAiConfirm = true },
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Outlined.DeleteOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(androidx.compose.ui.Modifier.size(8.dp))
            Text("Forget all AI settings")
        }
        if (showResetAiConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showResetAiConfirm = false },
                title = { Text("Forget all AI settings?") },
                text = {
                    Text(
                        "This erases your endpoint URLs, API keys, model " +
                            "selections, and chat session history. " +
                            "You'll need to re-enter every provider's " +
                            "credentials to use AI features again.",
                    )
                },
                confirmButton = {
                    BrassButton(
                        label = "Forget everything",
                        onClick = {
                            showResetAiConfirm = false
                            onResetAi()
                        },
                        variant = BrassButtonVariant.Primary,
                    )
                },
                dismissButton = {
                    BrassButton(
                        label = "Cancel",
                        onClick = { showResetAiConfirm = false },
                        variant = BrassButtonVariant.Secondary,
                    )
                },
            )
        }
    }
    }
}

/**
 * Issue #212 — chat grounding-level subsection. Four switches that
 * decide what context the chat ViewModel injects into its system
 * prompt. Disabled (greyed out) when the parent "Allow chapter text
 * to AI" toggle is off, since none of these can ship to the model
 * with that gate closed.
 *
 * Subtitles include rough token-cost estimates so a user weighing
 * the privacy / latency / quota trade-off can decide. Estimates
 * assume the standard chars / 4 ≈ tokens English text rule.
 */
@Composable
private fun ChatGroundingSubsection(
    grounding: UiChatGrounding,
    enabled: Boolean,
    onSetChapterTitle: (Boolean) -> Unit,
    onSetCurrentSentence: (Boolean) -> Unit,
    onSetEntireChapter: (Boolean) -> Unit,
    onSetEntireBookSoFar: (Boolean) -> Unit,
) {
    val spacing = LocalSpacing.current
    // Iona (settings overhaul): inline "Chat grounding — what context
    // …" header removed; the wrapping AdvancedExpander now provides
    // that label, so the subsection only ships its descriptor + the
    // four switches. Padding-left matches the SettingsRow horizontal
    // padding so the explainer sits flush with the switch titles.
    Column(
        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            "The fiction title is always included. Each layer below adds " +
                "more text to every chat turn — better answers, more tokens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsSwitchRow(
            title = "Include current chapter title",
            subtitle = "~10 tokens. \"The reader is currently on …\" prefix.",
            checked = grounding.includeChapterTitle,
            onCheckedChange = onSetChapterTitle,
            enabled = enabled,
        )
        SettingsSwitchRow(
            title = "Include current sentence",
            subtitle = "~50 tokens. The exact sentence the listener is on.",
            checked = grounding.includeCurrentSentence,
            onCheckedChange = onSetCurrentSentence,
            enabled = enabled,
        )
        SettingsSwitchRow(
            title = "Include entire current chapter",
            subtitle = "~2 000–5 000 tokens. Full chapter text in every turn.",
            checked = grounding.includeEntireChapter,
            onCheckedChange = onSetEntireChapter,
            enabled = enabled,
        )
        SettingsSwitchRow(
            title = "Include entire book so far",
            subtitle = "50 000+ tokens on long fictions. Chapter 1 → current sentence. " +
                "Cloud providers (Claude, OpenAI) only — local models will run out of context.",
            checked = grounding.includeEntireBookSoFar,
            onCheckedChange = onSetEntireBookSoFar,
            enabled = enabled,
        )
    }
}

@Composable
private fun ProviderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    BrassButton(
        label = label,
        onClick = onClick,
        variant = if (selected) BrassButtonVariant.Primary else BrassButtonVariant.Secondary,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ClaudeProviderRows(
    ai: UiAiSettings,
    onSetClaudeKey: (String?) -> Unit,
    onSetClaudeModel: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var keyDraft by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    // #338 — tri-state local override. EncryptedSharedPreferences changes
    // don't tick the settings StateFlow that ai.*KeyConfigured is derived
    // from, so both Save (→ "set") and Clear (→ "not set") would otherwise
    // lag by ~30s until something else triggered a recomposition. A
    // nullable override takes precedence over the (possibly stale) snapshot
    // value — Save sets it to true, Clear sets it to false, leaving null
    // when nothing has happened yet means we trust the snapshot.
    var keyConfiguredOverride by remember { mutableStateOf<Boolean?>(null) }
    val keyConfigured = keyConfiguredOverride ?: ai.claudeKeyConfigured
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            if (keyConfigured) "Claude API key — set"
            else "Claude API key — not set",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("Paste new Claude key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BrassButton(
                label = if (showKey) "Hide" else "Show",
                onClick = { showKey = !showKey },
                variant = BrassButtonVariant.Text,
            )
            BrassButton(
                label = "Save",
                onClick = {
                    if (keyDraft.isNotBlank()) {
                        onSetClaudeKey(keyDraft)
                        keyDraft = ""
                        showKey = false
                        keyConfiguredOverride = true
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (keyConfigured) {
                BrassButton(
                    label = "Clear",
                    onClick = {
                        onSetClaudeKey(null)
                        keyConfiguredOverride = false
                    },
                    variant = BrassButtonVariant.Text,
                )
            }
        }
        Text(
            "Model: ${ai.claudeModel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Model picker as a row of Brass chips. Hardcoded list for v1.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            listOf("claude-haiku-4.5", "claude-sonnet-4.6", "claude-opus-4.6").forEach { m ->
                BrassButton(
                    label = m.removePrefix("claude-"),
                    onClick = { onSetClaudeModel(m) },
                    variant = if (ai.claudeModel == m) BrassButtonVariant.Primary else BrassButtonVariant.Secondary,
                )
            }
        }
        Text(
            "Estimated cost: ~\$0.005 per recap on Haiku 4.5. Anthropic console is the source of truth for usage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OpenAiProviderRows(
    ai: UiAiSettings,
    onSetOpenAiKey: (String?) -> Unit,
    onSetOpenAiModel: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var keyDraft by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    // #338 — see ClaudeProviderRows for the rationale.
    var keyConfiguredOverride by remember { mutableStateOf<Boolean?>(null) }
    val keyConfigured = keyConfiguredOverride ?: ai.openAiKeyConfigured
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            if (keyConfigured) "OpenAI API key — set"
            else "OpenAI API key — not set",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("Paste new OpenAI key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BrassButton(
                label = if (showKey) "Hide" else "Show",
                onClick = { showKey = !showKey },
                variant = BrassButtonVariant.Text,
            )
            BrassButton(
                label = "Save",
                onClick = {
                    if (keyDraft.isNotBlank()) {
                        onSetOpenAiKey(keyDraft)
                        keyDraft = ""
                        showKey = false
                        keyConfiguredOverride = true
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (keyConfigured) {
                BrassButton(
                    label = "Clear",
                    onClick = {
                        onSetOpenAiKey(null)
                        keyConfiguredOverride = false
                    },
                    variant = BrassButtonVariant.Text,
                )
            }
        }
        Text(
            "Model: ${ai.openAiModel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            listOf("gpt-4o-mini", "gpt-4o").forEach { m ->
                BrassButton(
                    label = m,
                    onClick = { onSetOpenAiModel(m) },
                    variant = if (ai.openAiModel == m) BrassButtonVariant.Primary else BrassButtonVariant.Secondary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OllamaProviderRows(
    ai: UiAiSettings,
    onSetOllamaBaseUrl: (String) -> Unit,
    onSetOllamaModel: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var urlDraft by remember(ai.ollamaBaseUrl) { mutableStateOf(ai.ollamaBaseUrl) }
    var modelDraft by remember(ai.ollamaModel) { mutableStateOf(ai.ollamaModel) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        OutlinedTextField(
            value = urlDraft,
            onValueChange = { urlDraft = it },
            label = { Text("Ollama server URL") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = modelDraft,
            onValueChange = { modelDraft = it },
            label = { Text("Ollama model") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        BrassButton(
            label = "Save",
            onClick = {
                onSetOllamaBaseUrl(urlDraft.trim())
                onSetOllamaModel(modelDraft.trim())
            },
            variant = BrassButtonVariant.Primary,
        )
        Text(
            "Default URL is intentionally a placeholder — fix it to your LAN host (e.g. " +
                "http://10.0.6.50:11434) before testing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun VertexProviderRows(
    ai: UiAiSettings,
    onSetVertexKey: (String?) -> Unit,
    onSetVertexModel: (String) -> Unit,
    onSetVertexServiceAccountJson: (String?) -> Unit,
    vertexSaError: String?,
    onClearVertexSaError: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var keyDraft by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    // #338 — see ClaudeProviderRows for the rationale.
    var keyConfiguredOverride by remember { mutableStateOf<Boolean?>(null) }
    val keyConfigured = keyConfiguredOverride ?: ai.vertexKeyConfigured
    // #219 — local optimistic override for the SA-configured label.
    // Same pattern as keyConfiguredOverride: EncryptedSharedPreferences
    // doesn't notify the settings Flow, so the SA-set status would
    // otherwise lag until the next combine emission.
    var saConfiguredOverride by remember { mutableStateOf<Boolean?>(null) }
    val saConfigured = saConfiguredOverride ?: ai.vertexServiceAccountConfigured

    // SAF file picker — single .json document. The system picker
    // surfaces Drive / Downloads / Files / external-storage by default;
    // mime filter narrows the list to JSON. Persistent permission
    // grant isn't needed here because we copy the JSON content out of
    // the URI immediately and store it encrypted; the URI itself is
    // discarded.
    val context = androidx.compose.ui.platform.LocalContext.current
    val saFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                // Reads can fail when SAF returns a transient URI we
                // can't open (rare; OOM, permission revoke mid-pick).
                // Forward to the VM as a fake error so the user sees
                // why nothing changed.
                onSetVertexServiceAccountJson(null)
            } else {
                onSetVertexServiceAccountJson(text)
                saConfiguredOverride = true
                // Picking SA implicitly drops the API key (mutual
                // exclusion at the repo). Reflect that locally so the
                // "key set" label updates without waiting for a Flow
                // round-trip.
                keyConfiguredOverride = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        // ── API-key mode (legacy/default) ─────────────────────────
        Text(
            if (keyConfigured) "Vertex API key — set"
            else "Vertex API key — not set",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("Paste new Vertex (Gemini) key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BrassButton(
                label = if (showKey) "Hide" else "Show",
                onClick = { showKey = !showKey },
                variant = BrassButtonVariant.Text,
            )
            BrassButton(
                label = "Save",
                onClick = {
                    if (keyDraft.isNotBlank()) {
                        onSetVertexKey(keyDraft)
                        keyDraft = ""
                        showKey = false
                        keyConfiguredOverride = true
                        // API key wins → drop the local SA-configured
                        // sticky bit so the label flips honestly.
                        saConfiguredOverride = false
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (keyConfigured) {
                BrassButton(
                    label = "Clear",
                    onClick = {
                        onSetVertexKey(null)
                        keyConfiguredOverride = false
                    },
                    variant = BrassButtonVariant.Text,
                )
            }
        }

        // ── Service-account JSON mode (#219) ──────────────────────
        // Distinct sub-section under the API-key inputs. The two
        // modes are mutually exclusive at the repo, so configuring
        // either side clears the other; the UI reflects that with the
        // optimistic-override bits above.
        Spacer(modifier = Modifier.padding(top = spacing.xs))
        Text(
            "Service account (advanced)",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = when {
                saConfigured && ai.vertexServiceAccountEmail != null ->
                    "Service account — ${ai.vertexServiceAccountEmail}"
                saConfigured -> "Service account — uploaded"
                else -> "No service account uploaded."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BrassButton(
                label = if (saConfigured) "Replace JSON" else "Upload JSON",
                onClick = {
                    onClearVertexSaError()
                    // The Storage Access Framework's OpenDocument
                    // contract takes an array of mime types. We accept
                    // application/json explicitly; some pickers also
                    // need */* fallback for files that landed without
                    // the right mime (Drive especially), so we list
                    // both.
                    saFilePicker.launch(arrayOf("application/json", "*/*"))
                },
                variant = BrassButtonVariant.Primary,
            )
            if (saConfigured) {
                BrassButton(
                    label = "Clear",
                    onClick = {
                        onSetVertexServiceAccountJson(null)
                        saConfiguredOverride = false
                    },
                    variant = BrassButtonVariant.Text,
                )
            }
        }
        if (vertexSaError != null) {
            // Transient inline error — Compose recomposes when the VM
            // pushes null after a successful save.
            Text(
                text = vertexSaError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ── Model picker ──────────────────────────────────────────
        Text(
            "Model: ${ai.vertexModel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite").forEach { m ->
                BrassButton(
                    label = m.removePrefix("gemini-"),
                    onClick = { onSetVertexModel(m) },
                    variant = if (ai.vertexModel == m) BrassButtonVariant.Primary else BrassButtonVariant.Secondary,
                )
            }
        }
        Text(
            "API key: generate at aistudio.google.com/app/apikey — quick BYOK path.\n" +
                "Service account: GCP console → IAM → Service Accounts → Keys → Create key (JSON). " +
                "Needs the Vertex AI User role on your project.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AzureFoundryProviderRows(
    ai: UiAiSettings,
    onSetFoundryKey: (String?) -> Unit,
    onSetFoundryEndpoint: (String) -> Unit,
    onSetFoundryDeployment: (String) -> Unit,
    onSetFoundryServerless: (Boolean) -> Unit,
) {
    val spacing = LocalSpacing.current
    var keyDraft by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var endpointDraft by remember(ai.foundryEndpoint) { mutableStateOf(ai.foundryEndpoint) }
    var deploymentDraft by remember(ai.foundryDeployment) { mutableStateOf(ai.foundryDeployment) }
    // #338 — see ClaudeProviderRows. Foundry's Save persists endpoint
    // + deployment to DataStore (which the settings flow does observe,
    // so those labels update fine) plus the API key to EncryptedSharedPrefs
    // (which doesn't). Only the API-key label needs the local override.
    var keyConfiguredOverride by remember { mutableStateOf<Boolean?>(null) }
    val keyConfigured = keyConfiguredOverride ?: ai.foundryKeyConfigured

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        // ── Mode toggle ────────────────────────────────────────────
        // Picked first so the deployment-id field's hint copy can
        // adapt. Default is Deployed (the more common Azure path —
        // an Azure OpenAI Service resource with named deployments).
        Text(
            "Mode",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            ProviderChip(
                label = "Deployed",
                selected = !ai.foundryServerless,
            ) { onSetFoundryServerless(false) }
            ProviderChip(
                label = "Serverless",
                selected = ai.foundryServerless,
            ) { onSetFoundryServerless(true) }
        }
        Text(
            if (ai.foundryServerless)
                "Serverless: one /models/chat/completions URL, model id in the body. " +
                    "Use for the Azure model catalog (Llama / Phi / DeepSeek / Grok / …)."
            else
                "Deployed: per-deployment /openai/deployments/{name}/... URL. " +
                    "Use for an Azure OpenAI Service resource — type the deployment name " +
                    "you set in the Azure portal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Endpoint URL ──────────────────────────────────────────
        OutlinedTextField(
            value = endpointDraft,
            onValueChange = { endpointDraft = it },
            label = { Text("Endpoint URL") },
            placeholder = {
                Text(
                    if (ai.foundryServerless) "https://my-project.services.ai.azure.com"
                    else "https://my-resource.openai.azure.com",
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // ── API key (encrypted) ───────────────────────────────────
        Text(
            if (keyConfigured) "Foundry API key — set"
            else "Foundry API key — not set",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("Paste new Foundry api-key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // ── Deployment / model id ─────────────────────────────────
        OutlinedTextField(
            value = deploymentDraft,
            onValueChange = { deploymentDraft = it },
            label = {
                Text(if (ai.foundryServerless) "Model id" else "Deployment name")
            },
            placeholder = {
                Text(if (ai.foundryServerless) "gpt-4o" else "gpt-4o-prod")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (ai.foundryServerless) {
            // Catalog model chips for serverless. Deployed mode shows
            // no chips — the deployment name is entirely the user's
            // (it's whatever they typed in the Azure portal).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                listOf("gpt-4o", "gpt-4o-mini", "Llama-3.3-70B-Instruct").forEach { m ->
                    BrassButton(
                        label = m,
                        onClick = { deploymentDraft = m; onSetFoundryDeployment(m) },
                        variant = if (ai.foundryDeployment == m)
                            BrassButtonVariant.Primary
                        else
                            BrassButtonVariant.Secondary,
                    )
                }
            }
        }

        // ── Save / clear ──────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BrassButton(
                label = if (showKey) "Hide" else "Show",
                onClick = { showKey = !showKey },
                variant = BrassButtonVariant.Text,
            )
            BrassButton(
                label = "Save",
                onClick = {
                    onSetFoundryEndpoint(endpointDraft.trim())
                    onSetFoundryDeployment(deploymentDraft.trim())
                    if (keyDraft.isNotBlank()) {
                        onSetFoundryKey(keyDraft)
                        keyDraft = ""
                        showKey = false
                        keyConfiguredOverride = true
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (keyConfigured) {
                BrassButton(
                    label = "Clear key",
                    onClick = {
                        onSetFoundryKey(null)
                        keyConfiguredOverride = false
                    },
                    variant = BrassButtonVariant.Text,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BedrockProviderRows(
    ai: UiAiSettings,
    onSetAccessKey: (String?) -> Unit,
    onSetSecretKey: (String?) -> Unit,
    onSetRegion: (String) -> Unit,
    onSetModel: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var accessDraft by remember { mutableStateOf("") }
    var secretDraft by remember { mutableStateOf("") }
    var showAccess by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    // #338 — see ClaudeProviderRows. Bedrock has two independent
    // per-field Saves so it needs two tri-state overrides (true on
    // Save, false on Clear, null when nothing has happened yet).
    var accessConfiguredOverride by remember { mutableStateOf<Boolean?>(null) }
    var secretConfiguredOverride by remember { mutableStateOf<Boolean?>(null) }
    val accessConfigured = accessConfiguredOverride ?: ai.bedrockAccessKeyConfigured
    val secretConfigured = secretConfiguredOverride ?: ai.bedrockSecretKeyConfigured
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        // ── Access key ────────────────────────────────────────────
        Text(
            if (accessConfigured) "AWS access key id — set"
            else "AWS access key id — not set",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = accessDraft,
            onValueChange = { accessDraft = it },
            label = { Text("Paste new AWS access key id (AKIA…)") },
            visualTransformation = if (showAccess) VisualTransformation.None
                else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BrassButton(
                label = if (showAccess) "Hide" else "Show",
                onClick = { showAccess = !showAccess },
                variant = BrassButtonVariant.Text,
            )
            BrassButton(
                label = "Save",
                onClick = {
                    if (accessDraft.isNotBlank()) {
                        onSetAccessKey(accessDraft)
                        accessDraft = ""
                        showAccess = false
                        accessConfiguredOverride = true
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (accessConfigured) {
                BrassButton(
                    label = "Clear",
                    onClick = {
                        onSetAccessKey(null)
                        accessConfiguredOverride = false
                    },
                    variant = BrassButtonVariant.Text,
                )
            }
        }
        // ── Secret key ────────────────────────────────────────────
        Text(
            if (secretConfigured) "AWS secret access key — set"
            else "AWS secret access key — not set",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = secretDraft,
            onValueChange = { secretDraft = it },
            label = { Text("Paste new AWS secret access key") },
            visualTransformation = if (showSecret) VisualTransformation.None
                else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            BrassButton(
                label = if (showSecret) "Hide" else "Show",
                onClick = { showSecret = !showSecret },
                variant = BrassButtonVariant.Text,
            )
            BrassButton(
                label = "Save",
                onClick = {
                    if (secretDraft.isNotBlank()) {
                        onSetSecretKey(secretDraft)
                        secretDraft = ""
                        showSecret = false
                        secretConfiguredOverride = true
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (secretConfigured) {
                BrassButton(
                    label = "Clear",
                    onClick = {
                        onSetSecretKey(null)
                        secretConfiguredOverride = false
                    },
                    variant = BrassButtonVariant.Text,
                )
            }
        }
        // ── Region picker ─────────────────────────────────────────
        Text(
            "Region: ${ai.bedrockRegion}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            // Hardcoded Bedrock regions — see BedrockModels.regions in :core-llm.
            listOf("us-east-1", "us-west-2", "eu-central-1", "ap-northeast-1").forEach { r ->
                BrassButton(
                    label = r,
                    onClick = { onSetRegion(r) },
                    variant = if (ai.bedrockRegion == r) BrassButtonVariant.Primary
                        else BrassButtonVariant.Secondary,
                )
            }
        }
        // ── Model picker ──────────────────────────────────────────
        Text(
            "Model: ${ai.bedrockModel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            // Curated subset of cloud-chat-assistant's BEDROCK_MODELS map.
            listOf(
                "claude-haiku-4.5",
                "claude-sonnet-4.6",
                "nova-lite",
                "llama4-maverick-17b",
            ).forEach { m ->
                BrassButton(
                    label = m,
                    onClick = { onSetModel(m) },
                    variant = if (ai.bedrockModel == m) BrassButtonVariant.Primary
                        else BrassButtonVariant.Secondary,
                )
            }
        }
        Text(
            "Bedrock charges per-token; per-region pricing varies. AWS console is " +
                "the source of truth for usage. SigV4 signing happens in-app — " +
                "no AWS SDK is bundled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProbeOutcomeMessage(probe: ProbeOutcome?, onClear: () -> Unit) {
    if (probe == null) return
    LaunchedEffect(probe) {
        // Auto-clear after 8 seconds so the message doesn't linger
        // forever after the user has read it.
        delay(8_000)
        onClear()
    }
    val color = when (probe) {
        is ProbeOutcome.Ok -> MaterialTheme.colorScheme.primary
        is ProbeOutcome.Failure -> MaterialTheme.colorScheme.error
    }
    val text = when (probe) {
        is ProbeOutcome.Ok -> "Connection OK."
        is ProbeOutcome.Failure -> probe.message
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

/**
 * Anthropic Teams (OAuth) provider rows (#181). Replaces the
 * "coming soon" stub with a real sign-in entry. The bearer token
 * round-trips through OAuth — there's nothing to paste, so the
 * row is just a button + a status line that flips between
 * "Sign in to Teams" and "Signed in" once the flow completes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AnthropicTeamsProviderRows(
    ai: UiAiSettings,
    onOpenSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            "Anthropic Teams uses your Claude.ai workspace login — no API key " +
                "to paste. Tap the button below to authorize storyvox in your " +
                "browser; we'll capture the bearer token and refresh it as needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ai.teamsSignedIn) {
            Text(
                "Signed in to Anthropic Teams",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (ai.teamsScopes.isNotBlank()) {
                Text(
                    "Granted scopes: ${ai.teamsScopes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                BrassButton(
                    label = "Sign out",
                    onClick = onSignOut,
                    variant = BrassButtonVariant.Secondary,
                )
                BrassButton(
                    label = "Re-authorize",
                    onClick = onOpenSignIn,
                    variant = BrassButtonVariant.Text,
                )
            }
        } else {
            BrassButton(
                label = "Sign in to Teams",
                onClick = onOpenSignIn,
                variant = BrassButtonVariant.Primary,
            )
        }
        Text(
            "Model: ${ai.claudeModel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Costs are covered by your Teams subscription — Anthropic counts " +
                "tokens against your workspace quota at console.anthropic.com.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}



/**
 * Issue #235 — folder-picker row for the EPUB backend. SAF tree
 * picker via OpenDocumentTree contract; the resolved URI is
 * persistable so we don't have to re-prompt the user across
 * launches.
 */
@Composable
private fun EpubFolderPickerRow(viewModel: SettingsViewModel) {
    val folder by viewModel.epubFolderUri.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val context = androidx.compose.ui.platform.LocalContext.current

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Persist the URI permission so the next launch can still
            // read the folder without re-prompting. SAF grants are
            // session-only by default; takePersistableUriPermission
            // upgrades to a long-lived grant tied to our package.
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            viewModel.setEpubFolderUri(uri.toString())
        }
    }

    Column(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm)) {
        Text(
            "EPUB folder",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.xs),
        )
        Text(
            text = folder?.let { abbreviateSafUri(it) }
                ?: "No folder picked. Tap below to choose where your .epub files live.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.sm),
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            BrassButton(
                label = if (folder == null) "Pick folder" else "Change folder",
                onClick = { launcher.launch(null) },
                variant = BrassButtonVariant.Primary,
            )
            if (folder != null) {
                androidx.compose.material3.TextButton(
                    onClick = viewModel::clearEpubFolder,
                    modifier = Modifier.padding(start = spacing.sm),
                ) { Text("Clear") }
            }
        }
    }
}

/** SAF tree URIs look like `content://com.android.externalstorage.documents/tree/primary%3AAudiobooks`
 *  — useful internally but not friendly to read. Strip the scheme +
 *  authority and percent-decode the path so the user sees something
 *  closer to "primary:Audiobooks". */
private fun abbreviateSafUri(raw: String): String {
    val tree = raw.substringAfterLast("/tree/", missingDelimiterValue = raw)
    return runCatching { java.net.URLDecoder.decode(tree, "UTF-8") }.getOrDefault(tree)
}


/**
 * Issue #245 — host + API-key entry for the Outline self-hosted-wiki
 * backend. Inline section under the toggle in Library & Sync, mirroring
 * the EPUB folder picker / RSS feed manager. Keeps the user inside
 * Library & Sync rather than navigating to a sub-screen.
 */
@Composable
private fun OutlineConfigRow(viewModel: SettingsViewModel) {
    val host by viewModel.outlineHost.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    var hostDraft by remember(host) { mutableStateOf(host) }
    var apiKeyDraft by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm)) {
        Text(
            "Outline instance",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.xs),
        )
        Text(
            text = if (host.isBlank()) "Set your Outline host + API token below."
            else "Currently configured: $host",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        androidx.compose.material3.OutlinedTextField(
            value = hostDraft,
            onValueChange = { hostDraft = it },
            label = { Text("Outline host") },
            placeholder = { Text("wiki.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.material3.OutlinedTextField(
            value = apiKeyDraft,
            onValueChange = { apiKeyDraft = it },
            label = { Text("API token") },
            placeholder = { Text("From Outline → Account → API Tokens") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            BrassButton(
                label = "Save",
                onClick = {
                    val trimmedHost = hostDraft.trim()
                    // Issue #455 — empty required fields used to silently
                    // no-op the Save action with no feedback. JP design
                    // (issue comment): apply defaults silently to the
                    // model but surface a transient toast naming the
                    // skipped field(s) so the user sees that something
                    // was missing. Lighter than Material 3 supported-
                    // error chips, no save-blocking.
                    val skipped = buildList {
                        if (trimmedHost.isEmpty()) add("Outline host")
                        if (apiKeyDraft.isBlank()) add("API token")
                    }
                    if (trimmedHost.isNotEmpty()) viewModel.setOutlineHost(trimmedHost)
                    if (apiKeyDraft.isNotBlank()) {
                        viewModel.setOutlineApiKey(apiKeyDraft)
                        apiKeyDraft = ""
                    }
                    if (skipped.isNotEmpty()) {
                        Toast.makeText(
                            context,
                            "Saved. Skipped (defaults applied): ${skipped.joinToString(", ")}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (host.isNotBlank()) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        viewModel.clearOutlineConfig()
                        hostDraft = ""
                        apiKeyDraft = ""
                    },
                ) { Text("Clear") }
            }
        }
    }
}

/**
 * Issue #377 — Wikipedia language-code picker. Inline row under the
 * Wikipedia toggle in Library & Sync. Single short text field — `en`,
 * `de`, `ja`, `simple`, etc. — resolves to the matching Wikipedia
 * host (`<lang>.wikipedia.org`). Mirrors the inline-config shape of
 * [OutlineConfigRow] / [EpubFolderPickerRow]; nothing to save
 * imperatively because the language code is short and the impl
 * accepts any non-blank value.
 *
 * The Save button keeps the row's interaction visible — users can
 * type without persisting on every keystroke, and a deliberate Save
 * action mirrors what they see on Outline / Memory Palace below.
 */
@Composable
private fun WikipediaLanguageRow(
    languageCode: String,
    onLanguageCodeChange: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var draft by remember(languageCode) { mutableStateOf(languageCode) }
    Column(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm)) {
        Text(
            "Wikipedia language",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.xs),
        )
        Text(
            text = "Pick a Wikipedia. Use the URL prefix: en, de, ja, simple, fr, ...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        androidx.compose.material3.OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.trim().lowercase().take(16) },
            label = { Text("Language code") },
            placeholder = { Text("en") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            BrassButton(
                label = "Save",
                onClick = { onLanguageCodeChange(draft) },
                variant = BrassButtonVariant.Primary,
            )
        }
    }
}

/**
 * Issue #233 — Notion source config row. Two fields:
 *
 *  - **Database ID** — the Notion database the source queries. Defaults
 *    to the techempower.org placeholder from `NotionDefaults`; the user
 *    can override to point at their own Notion DB. Both hyphenated UUID
 *    and 32-hex compact forms accepted by the API.
 *  - **Integration token** — Notion "Internal Integration Token" from
 *    notion.so/my-integrations. Stored encrypted; never re-displayed.
 *    A configured token shows as "Token configured" rather than
 *    surfacing the value.
 *
 * Inline under the Notion toggle in Library & Sync, mirroring the
 * OutlineConfigRow shape. The "Save" button writes both fields at
 * once; the user can change one without retyping the other.
 */
@Composable
private fun NotionConfigRow(
    databaseId: String,
    tokenConfigured: Boolean,
    onDatabaseIdChange: (String) -> Unit,
    onApiTokenChange: (String?) -> Unit,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    var dbDraft by remember(databaseId) { mutableStateOf(databaseId) }
    var tokenDraft by remember { mutableStateOf("") }

    // Issue #447 — the prefilled Database ID ("2a3d70…") read as
    // either a leaked infrastructure id or a confusing placeholder.
    // Detect when the field is still at the TechEmpower default and
    // surface a clear label so the user knows it's a public DB they
    // can replace with their own.
    val techempowerDefaultId = "2a3d706803c649409e74e9ce5ccd4c4b"
    val isDefaultDatabaseId = databaseId == techempowerDefaultId
    Column(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm)) {
        Text(
            "Notion integration",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.xs),
        )
        Text(
            text = when {
                tokenConfigured ->
                    "Token configured. Paste a new token to replace it, " +
                        "or clear it to switch back to the anonymous TechEmpower demo content."
                isDefaultDatabaseId ->
                    "No token configured — Browse → Notion shows TechEmpower's public " +
                        "Notion content as a demo. Paste a Notion Internal Integration " +
                        "Token from notion.so/my-integrations to read your own database; " +
                        "replace the Database ID below with your own."
                else ->
                    "Paste a Notion Internal Integration Token. " +
                        "Create one at notion.so/my-integrations, then share " +
                        "your database with the integration."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        androidx.compose.material3.OutlinedTextField(
            value = dbDraft,
            onValueChange = { dbDraft = it.trim().take(64) },
            label = {
                Text(
                    if (dbDraft == techempowerDefaultId)
                        "Database ID (TechEmpower demo)"
                    else
                        "Database ID",
                )
            },
            placeholder = { Text("32-hex or hyphenated UUID") },
            singleLine = true,
            supportingText = {
                if (dbDraft == techempowerDefaultId) {
                    Text(
                        "Default points at TechEmpower's public Resources DB. " +
                            "Replace with your own DB id to read a personal database.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.material3.OutlinedTextField(
            value = tokenDraft,
            onValueChange = { tokenDraft = it },
            label = { Text("Integration token") },
            placeholder = { Text("ntn_…") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            BrassButton(
                label = "Save",
                onClick = {
                    // Issue #455 — empty required fields produce a toast
                    // so the user sees that something was missing.
                    // Database ID has a baked-in default (TechEmpower),
                    // so an "empty Database ID at save time" already
                    // applies that default silently — the toast still
                    // names it so the user knows the demo content will
                    // be what they see in Browse → Notion.
                    val skipped = buildList {
                        if (dbDraft.isBlank()) add("Database ID")
                        if (tokenDraft.isBlank() && !tokenConfigured) add("Integration token")
                    }
                    if (dbDraft != databaseId) onDatabaseIdChange(dbDraft)
                    if (tokenDraft.isNotBlank()) {
                        onApiTokenChange(tokenDraft)
                        tokenDraft = ""
                    }
                    if (skipped.isNotEmpty()) {
                        Toast.makeText(
                            context,
                            "Saved. Skipped (defaults applied): ${skipped.joinToString(", ")}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (tokenConfigured) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        onApiTokenChange(null)
                        tokenDraft = ""
                    },
                ) { Text("Clear token") }
            }
        }
    }
}

/**
 * Issue #403 — Discord backend config card. Renders inside the
 * Library & Sync section when the Discord toggle is ON.
 *
 * Three knobs:
 *  - **Bot token** — masked text field with a "Save" / "Clear"
 *    affordance. Token is stored encrypted under
 *    `pref_source_discord_token` in `storyvox.secrets`.
 *  - **Server picker** — populated from
 *    `users/@me/guilds` after the token is configured.
 *    Empty list = "no token / fetch failed / bot not invited yet".
 *  - **Coalesce minutes slider** — 1-30 min, default 5. Tunes the
 *    same-author message coalesce window.
 *
 * Parallel shape to [NotionConfigRow]: header + explanatory text +
 * masked token field + extra config + save/clear actions. The
 * Discord-specific piece is the guilds dropdown, which only renders
 * once a token is configured (no point listing servers when the bot
 * isn't authenticated).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DiscordConfigRow(
    tokenConfigured: Boolean,
    serverId: String,
    serverName: String,
    coalesceMinutes: Int,
    guilds: List<Pair<String, String>>,
    onApiTokenChange: (String?) -> Unit,
    onServerSelected: (id: String, name: String) -> Unit,
    onCoalesceMinutesChange: (Int) -> Unit,
    onRefreshGuilds: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var tokenDraft by remember { mutableStateOf("") }
    var serverPickerExpanded by remember { mutableStateOf(false) }

    // Auto-fetch the guild list when the token is configured + nothing
    // has been fetched yet. Without this, opening the Discord card on
    // a fresh paste would render an empty dropdown until the user
    // tapped Refresh manually.
    LaunchedEffect(tokenConfigured) {
        if (tokenConfigured && guilds.isEmpty()) onRefreshGuilds()
    }

    Column(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm)) {
        Text(
            "Discord bot",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.xs),
        )
        Text(
            text = if (tokenConfigured)
                "Token configured. Paste a new token to replace it. " +
                    "Invite the bot to your server with " +
                    "READ_MESSAGE_HISTORY scope, then pick the server below."
            else
                "Create an application at discord.com/developers, " +
                    "generate a bot token, invite the bot to your " +
                    "server with READ_MESSAGE_HISTORY scope, and " +
                    "paste the token here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        androidx.compose.material3.OutlinedTextField(
            value = tokenDraft,
            onValueChange = { tokenDraft = it },
            label = { Text("Bot token") },
            placeholder = { Text("OD…") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            BrassButton(
                label = "Save token",
                onClick = {
                    if (tokenDraft.isNotBlank()) {
                        onApiTokenChange(tokenDraft)
                        tokenDraft = ""
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (tokenConfigured) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        onApiTokenChange(null)
                        tokenDraft = ""
                        // Clearing the token also clears the cached
                        // server so the picker resets to its empty
                        // state on the next render.
                        onServerSelected("", "")
                    },
                ) { Text("Clear token") }
            }
        }
        // ── Server picker — only meaningful when a token is configured.
        if (tokenConfigured) {
            Spacer(Modifier.height(spacing.md))
            Text(
                "Server",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = spacing.xs),
            )
            androidx.compose.material3.ExposedDropdownMenuBox(
                expanded = serverPickerExpanded,
                onExpandedChange = { serverPickerExpanded = !serverPickerExpanded },
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = when {
                        serverName.isNotBlank() -> serverName
                        serverId.isNotBlank() -> serverId
                        else -> "(none selected)"
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Discord server") },
                    trailingIcon = {
                        androidx.compose.material3.ExposedDropdownMenuDefaults
                            .TrailingIcon(expanded = serverPickerExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(
                            androidx.compose.material3.MenuAnchorType.PrimaryNotEditable,
                        ),
                )
                ExposedDropdownMenu(
                    expanded = serverPickerExpanded,
                    onDismissRequest = { serverPickerExpanded = false },
                ) {
                    if (guilds.isEmpty()) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Text(
                                    "(no servers — invite your bot, then tap Refresh)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            onClick = { serverPickerExpanded = false },
                        )
                    } else {
                        for ((id, name) in guilds) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onServerSelected(id, name)
                                    serverPickerExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            androidx.compose.material3.TextButton(
                onClick = onRefreshGuilds,
                modifier = Modifier.padding(top = spacing.xs),
            ) { Text("Refresh server list") }

            // ── Coalesce-window slider.
            Spacer(Modifier.height(spacing.md))
            SettingsSliderBlock(
                title = "Coalesce window",
                valueLabel = "$coalesceMinutes min",
                slider = {
                    Slider(
                        value = coalesceMinutes.toFloat(),
                        onValueChange = { onCoalesceMinutesChange(it.toInt().coerceIn(1, 30)) },
                        valueRange = 1f..30f,
                        steps = 28,
                        modifier = Modifier.semantics {
                            contentDescription = "Message coalesce window"
                            stateDescription = "$coalesceMinutes minutes"
                        },
                    )
                },
            )
            Text(
                "Consecutive messages from the same author within this " +
                    "window collapse into one chapter. Default 5 minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

/**
 * Issue #462 — Telegram backend config card. Renders inside the
 * Library & Sync section right under the Discord card.
 *
 * Two knobs:
 *  - **Bot token** — masked text field with Save / Clear. Token is
 *    stored encrypted under `pref_source_telegram_token` in
 *    `storyvox.secrets`.
 *  - **Probe** — refresh button hits `getMe` to confirm the
 *    bot's identity and `getUpdates` to surface the channels the
 *    bot has been invited to. No persisted channel list — the
 *    probe result is hot per session.
 *
 * Telegram-specific shape: no server picker (Bot API has no
 * "list of channels I'm in" endpoint; we derive from observed
 * `getUpdates` activity), no coalesce slider (channel posts are
 * admin-curated and rarely need coalescing). What we DO have
 * that Discord doesn't is a explicit bot-identity confirmation
 * line ("Authenticated as @bot_name") because the @BotFather
 * onboarding doesn't produce a visible "your bot is" surface
 * elsewhere in the flow.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TelegramConfigRow(
    tokenConfigured: Boolean,
    botUsername: String?,
    channels: List<Pair<String, String>>,
    onApiTokenChange: (String?) -> Unit,
    onRefreshProbe: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var tokenDraft by remember { mutableStateOf("") }

    // Auto-probe when the token is configured + nothing has been
    // fetched yet. Without this, opening the Telegram card on a
    // fresh paste would render the empty "no channels yet" state
    // until the user tapped Refresh manually.
    LaunchedEffect(tokenConfigured) {
        if (tokenConfigured && botUsername == null) onRefreshProbe()
    }

    Column(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm)) {
        Text(
            "Telegram bot",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.xs),
        )
        Text(
            text = if (tokenConfigured)
                "Token configured. Invite your bot to a public channel " +
                    "(channel admins → Add member → search the bot " +
                    "username), then tap Refresh below. Bots only see " +
                    "posts that arrive after they join — older posts " +
                    "can't be replayed via the Bot API."
            else
                "Open Telegram, chat with @BotFather, send /newbot, " +
                    "follow the prompts to create a bot, copy the " +
                    "token, and paste it here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        androidx.compose.material3.OutlinedTextField(
            value = tokenDraft,
            onValueChange = { tokenDraft = it },
            label = { Text("Bot token") },
            placeholder = { Text("123456:ABC-DEF…") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            BrassButton(
                label = "Save token",
                onClick = {
                    if (tokenDraft.isNotBlank()) {
                        onApiTokenChange(tokenDraft)
                        tokenDraft = ""
                    }
                },
                variant = BrassButtonVariant.Primary,
            )
            if (tokenConfigured) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        onApiTokenChange(null)
                        tokenDraft = ""
                    },
                ) { Text("Clear token") }
            }
        }
        // ── Bot identity + observed-channels probe.
        if (tokenConfigured) {
            Spacer(Modifier.height(spacing.md))
            Text(
                text = when {
                    botUsername != null && channels.isEmpty() ->
                        "Authenticated as @$botUsername · " +
                            "your bot has not been added to any " +
                            "channels yet."
                    botUsername != null ->
                        "Authenticated as @$botUsername · " +
                            "sees ${channels.size} channel" +
                            (if (channels.size == 1) "" else "s") +
                            "."
                    else ->
                        "Could not verify the bot token. " +
                            "Check it matches the @BotFather token, " +
                            "then tap Refresh."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = spacing.xs),
            )
            if (channels.isNotEmpty()) {
                for ((chatId, title) in channels) {
                    Text(
                        text = "• $title  ($chatId)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = spacing.sm, top = spacing.xs),
                    )
                }
            }
            androidx.compose.material3.TextButton(
                onClick = onRefreshProbe,
                modifier = Modifier.padding(top = spacing.xs),
            ) { Text("Refresh bot status") }
        }
    }
}

/**
 * Issue #281 — loading skeleton for the Settings tab. Mirrors the
 * structure SettingsScreen renders once `state.settings` hydrates:
 * a section heading shimmer + a grouped-card shimmer per section.
 * Matches the Library Nocturne 1200ms brass pulse from SkeletonBlock.
 *
 * Intentionally not the fancy MagicSkeletonTile — settings rows are
 * short text rows, not artwork. A row-stack of opacity-pulse bars
 * reads as "settings are loading" without the cover-art sigil that
 * would feel out of place on this surface.
 */
@Composable
internal fun SettingsSkeleton(modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        // Five sections worth of placeholder — matches roughly what the
        // real SettingsScreen renders so the visual height doesn't pop
        // when settings hydrate.
        repeat(5) {
            // Section heading skeleton: icon (24dp circle) + a wide
            // title bar + a narrower descriptor bar.
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    SkeletonBlock(
                        modifier = Modifier.size(24.dp),
                        shape = MaterialTheme.shapes.small,
                    )
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.45f)
                            .height(20.dp),
                    )
                }
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(14.dp),
                )
            }
            // Group card skeleton — three row-shaped bars.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                repeat(3) {
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                    )
                }
            }
        }
    }
}

/**
 * Calliope (v0.5.00) — version-string gate for the About-card pill.
 *
 * Mirrors `in.jphe.storyvox.sigil.Milestone.qualifies` from the app
 * module, deliberately re-implemented here so the feature module
 * doesn't need to depend back on `:app`. Parse rule: split on `.`,
 * `-`, or `+`; take the first three integer components; default
 * missing tail components to 0. Any parse failure returns false
 * (fail-closed). Safe for previews and tests — pass `"0.5.00"` for
 * the on-state, anything else for off.
 */
internal fun isV0500MilestoneBuild(versionName: String): Boolean {
    val parts = versionName.split('.', '-', '+')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    // (major, minor, patch) ≥ (0, 5, 0).
    if (major != 0) return major > 0
    if (minor != 5) return minor > 5
    return patch >= 0
}

/**
 * The small "🎉 0.5.00 milestone" pill under the build's sigil
 * line. Brass outline, no fill, small — same visual posture as the
 * StatusPill component but without the colored fill (a quiet
 * achievement marker, not a status warning). Single emoji per the
 * dialog's tone — the brass details celebrate, not the copy.
 */
@Composable
internal fun MilestoneBadgePill() {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier
            .padding(top = spacing.xs)
            .clip(MaterialTheme.shapes.small)
            .background(Color.Transparent)
            .padding(0.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.small,
                )
                .padding(horizontal = spacing.xs, vertical = spacing.xxs),
        ) {
            Text(text = "🎉", style = MaterialTheme.typography.bodySmall)
            Text(
                text = "0.5.00 milestone",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Preview-only About card with the milestone pill. Hand-rolls the
 * column to avoid spinning up the full SettingsViewModel + DI graph
 * in a Preview pane — the pill is the only piece this preview
 * cares about. Confirms the brass-outline pill sits below the
 * sigil-name line and reads as a quiet achievement marker.
 */
@androidx.compose.ui.tooling.preview.Preview(
    name = "About — with v0.5.00 milestone pill (dark)",
    widthDp = 360, heightDp = 240,
)
@Composable
private fun PreviewAboutCardWithMilestonePillDark() =
    `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme(darkTheme = true) {
        val spacing = LocalSpacing.current
        Box(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(spacing.md),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                Text("storyvox v0.5.00", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Spectral Forge · ef6a4cf3",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "main · built 2026-05-10",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MilestoneBadgePill()
            }
        }
    }

@androidx.compose.ui.tooling.preview.Preview(
    name = "About — with v0.5.00 milestone pill (light)",
    widthDp = 360, heightDp = 240,
)
@Composable
private fun PreviewAboutCardWithMilestonePillLight() =
    `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme(darkTheme = false) {
        val spacing = LocalSpacing.current
        Box(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(spacing.md),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                Text("storyvox v0.5.00", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Spectral Forge · ef6a4cf3",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "main · built 2026-05-10",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MilestoneBadgePill()
            }
        }
    }
