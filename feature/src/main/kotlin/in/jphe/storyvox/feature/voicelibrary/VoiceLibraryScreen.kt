package `in`.jphe.storyvox.feature.voicelibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues as ComposePaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.playback.voice.EngineKey
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.QualityLevel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceGender
import `in`.jphe.storyvox.playback.voice.VoiceLibrarySection
import `in`.jphe.storyvox.playback.voice.flagForLanguage
import `in`.jphe.storyvox.ui.a11y.LocalAccessibleTouchTargets
import `in`.jphe.storyvox.ui.a11y.accessibleSize
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.BrassProgressBar
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.component.cascadeReveal
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@Composable
fun VoiceLibraryScreen(
    onOpenSettings: () -> Unit = {},
    viewModel: VoiceLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.dismissError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice library") },
                // Voice Library was promoted to a first-class home tab
                // (issue #264 follow-up), so it no longer has a back
                // arrow — peer of Library/Browse/Follows/Playing. The
                // gear here matches the other home screens' per-screen
                // Settings affordance.
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Issue #264 — search query and language chip selection now
        // live in the ViewModel (StateFlows + 200 ms debounce) so they
        // survive rotation and the filter pipeline runs off the main
        // thread. The screen reads the raw query for the field's
        // two-way bind, and the VM exposes already-filtered favorites
        // / installed / available buckets in [state]. The screen-local
        // gender / tier / multilingual chips stay as `remember` state
        // (still on top of the VM filter — applied as a second
        // [filterBy] pass below) since they aren't part of JP's #264
        // primary-knob spec.
        val rawQuery by viewModel.voiceFilterQuery.collectAsStateWithLifecycle()
        val selectedLanguage by viewModel.voiceFilterLanguage.collectAsStateWithLifecycle()
        var selectedGenders by remember { mutableStateOf<Set<VoiceGender>>(emptySet()) }
        var selectedTiers by remember { mutableStateOf<Set<QualityLevel>>(emptySet()) }
        var multilingualOnly by remember { mutableStateOf(false) }

        // Secondary criteria (the screen-local chips) applied on top of
        // the VM's query+language filter. When all three are at their
        // defaults this is a no-op pass-through.
        val secondaryCriteria = VoiceFilterCriteria(
            genders = selectedGenders,
            tiers = selectedTiers,
            multilingualOnly = multilingualOnly,
        )
        val filteredFavorites = remember(state.favorites, secondaryCriteria) {
            state.favorites.filterBy(secondaryCriteria)
        }
        val filteredInstalled = remember(state.installedByEngine, secondaryCriteria) {
            state.installedByEngine.filterBy(secondaryCriteria)
        }
        val filteredAvailable = remember(state.availableByEngine, secondaryCriteria) {
            state.availableByEngine.filterBy(secondaryCriteria)
        }
        val installedTotal = filteredInstalled.values.sumOf { tiers -> tiers.values.sumOf { it.size } }
        val availableTotal = filteredAvailable.values.sumOf { tiers -> tiers.values.sumOf { it.size } }
        val availableHasKokoro = filteredAvailable.containsKey(VoiceEngine.Kokoro)
        // Unfiltered-empty check has to read the VM unfiltered shape,
        // not [state] — when the user has typed a query that matches
        // nothing, [state] is empty but the catalog isn't. We use the
        // language-code list as a proxy: if the VM has any languages,
        // it has any voices.
        val unfilteredIsEmpty = state.availableLanguageCodes.isEmpty()
        val filteredIsEmpty = filteredFavorites.isEmpty() &&
            installedTotal == 0 && availableTotal == 0

        if (unfilteredIsEmpty) {
            EmptyState(modifier = Modifier.padding(padding).fillMaxSize().padding(spacing.md))
            return@Scaffold
        }

        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Always-visible search bar. Brass outline + neutral text
            // matches the OutlinedTextField look already in Settings →
            // Pronunciation, so it reads as part of the same family.
            // Two-way bound to the VM's [voiceFilterQuery] StateFlow —
            // every keypress hits the VM instantly so [rawQuery] paints
            // immediately; the VM debounces its 200 ms projection
            // (see [VoiceLibraryViewModel.debouncedQuery]) before the
            // filter pipeline runs.
            OutlinedTextField(
                value = rawQuery,
                onValueChange = { viewModel.setQuery(it) },
                placeholder = { Text("Search voices") },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                trailingIcon = if (rawQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                        }
                    }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
            )

            // Issue #264 — language chip strip. Single-select per JP's
            // spec; the codes are derived dynamically from the VM's
            // [availableLanguageCodes] so only languages with at least
            // one voice show a chip. LazyRow so 30+ codes (the live
            // Azure roster regularly spans 60+) don't pre-instantiate
            // every chip on first paint — the 1188-voice catalog has
            // long since taught us not to render the whole world
            // upfront. Brass-pill colors match the Library/Browse chips.
            //
            // Issue #534 — asymmetric end-padding (spacing.xl = 32dp vs
            // start spacing.md = 16dp) via contentPadding so the
            // rightmost chip lands as an obviously-partial chip on
            // narrow Flip3 portrait — clear "scroll for more →"
            // affordance. Mirrors the secondary chip row pattern below
            // (#420). The outer horizontal padding moved INTO
            // contentPadding so the LazyRow's lazy clipping kicks in
            // at the right place — without this, an outer padding clips
            // chips before they can pan into view.
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.xxs),
                contentPadding = ComposePaddingValues(
                    start = spacing.md,
                    end = spacing.xl,
                ),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(state.availableLanguageCodes, key = { "lang-$it" }) { lang ->
                    val isSelected = lang == selectedLanguage
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            // Toggle: tapping the active chip clears the
                            // filter; tapping any other chip swaps to it.
                            viewModel.setLanguage(if (isSelected) null else lang)
                        },
                        label = { Text(lang) },
                        colors = brassFilterChipColors(),
                    )
                }
            }

            // Secondary chip row — gender / tier / multilingual. These
            // pre-date JP's #264 primary-knob spec but stay useful, so
            // they hang below the language strip on their own scroll.
            //
            // Issue #420 — asymmetric end-padding (spacing.xl = 32dp vs
            // start spacing.md = 16dp) so the rightmost chip
            // ("Multilingual") lands as an obviously-partial chip on
            // narrow viewports (e.g. the 800dp tablet portrait) rather
            // than a 9-pixel sliver right at the screen edge. A clearly
            // partial chip reads as "scroll for more →"; a 9-pixel
            // sliver reads as a rendering glitch. Discovery fix only —
            // the scroll itself was already wired up.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(
                        start = spacing.md,
                        end = spacing.xl,
                        top = spacing.xxs,
                        bottom = spacing.xxs,
                    ),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = VoiceGender.Female in selectedGenders,
                    onClick = {
                        selectedGenders = selectedGenders.toggleMember(VoiceGender.Female)
                    },
                    label = { Text("♀ Female") },
                    colors = brassFilterChipColors(),
                )
                FilterChip(
                    selected = VoiceGender.Male in selectedGenders,
                    onClick = {
                        selectedGenders = selectedGenders.toggleMember(VoiceGender.Male)
                    },
                    label = { Text("♂ Male") },
                    colors = brassFilterChipColors(),
                )
                FilterChip(
                    selected = VoiceGender.Unknown in selectedGenders,
                    onClick = {
                        selectedGenders = selectedGenders.toggleMember(VoiceGender.Unknown)
                    },
                    label = { Text("Neutral") },
                    colors = brassFilterChipColors(),
                )
                listOf(
                    QualityLevel.Studio to "Studio",
                    QualityLevel.High to "High",
                    QualityLevel.Medium to "Med",
                    QualityLevel.Low to "Low",
                ).forEach { (tier, label) ->
                    FilterChip(
                        selected = tier in selectedTiers,
                        onClick = { selectedTiers = selectedTiers.toggleMember(tier) },
                        label = { Text(label) },
                        colors = brassFilterChipColors(),
                    )
                }
                FilterChip(
                    selected = multilingualOnly,
                    onClick = { multilingualOnly = !multilingualOnly },
                    label = { Text("Multilingual") },
                    colors = brassFilterChipColors(),
                )
            }

            if (filteredIsEmpty) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    // Empty-state text reflects whichever filter
                    // dimension the user is actually engaging — query
                    // text wins when present (it's the more specific
                    // signal), language label otherwise, then a generic
                    // fallback for the rare case of only secondary
                    // chips being active.
                    val emptyLabel = when {
                        rawQuery.trim().isNotEmpty() ->
                            "No voices match \"${rawQuery.trim()}\"."
                        selectedLanguage != null ->
                            "No voices match \"$selectedLanguage\"."
                        else ->
                            "No voices match the current filters."
                    }
                    Text(
                        emptyLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            val favorites = filteredFavorites
            val installedByEngine = filteredInstalled
            val availableByEngine = filteredAvailable

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = spacing.md),
            ) {
            // STARRED — surfaces the user's pinned voices above
            // everything else. Hidden entirely when empty so the screen
            // doesn't render a "no starred voices" stub for first-time users.
            if (favorites.isNotEmpty()) {
                item { SectionHeader("★ Starred", count = favorites.size) }
                itemsIndexed(favorites, key = { _, item -> "fav-${item.id}" }) { index, voice ->
                    val downloading = state.currentDownload
                    val rowProgress = if (downloading?.voiceId == voice.id) downloading.progress ?: -1f else null
                    val isActive = voice.id == state.activeVoiceId
                    Column {
                        VoiceRow(
                            voice = voice,
                            isActive = isActive,
                            isFavorite = true,
                            downloadingProgress = rowProgress,
                            onTap = { if (downloading == null || voice.isInstalled) viewModel.onRowTapped(voice) },
                            onLongPress = if (voice.isInstalled) ({ viewModel.requestDelete(voice) }) else null,
                            onToggleFavorite = { viewModel.toggleFavorite(voice.id) },
                            // Issue #541 / #548 — surface the most-recent
                            // failure record (if any) so the row tile
                            // renders the diagnostic + retry affordance.
                            failed = state.failedDownloads[voice.id],
                            onRetry = { viewModel.retryDownload(voice.id) },
                            onDismissFailure = { viewModel.dismissFailedDownload(voice.id) },
                            modifier = Modifier
                                .animateItem()
                                .cascadeReveal(index = index, key = "fav-${voice.id}"),
                        )
                        // #197 + #198 — Advanced expander on the starred-section
                        // copy of the active voice. The starred section can
                        // contain the active voice (favorites + activation are
                        // independent), so we surface the expander here too.
                        // Identical wiring to the installed-section copy below.
                        if (isActive && voice.isInstalled) {
                            VoiceAdvancedExpander(
                                voice = voice,
                                lexiconPath = state.voiceLexiconOverrides[voice.id].orEmpty(),
                                phonemizerLang = state.voicePhonemizerLangOverrides[voice.id]
                                    .orEmpty(),
                                onSetLexicon = { path ->
                                    viewModel.setVoiceLexicon(voice.id, path)
                                },
                                onSetPhonemizerLang = { code ->
                                    viewModel.setVoicePhonemizerLang(voice.id, code)
                                },
                            )
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(spacing.md)) }
            }

            // INSTALLED — split into tier sub-sections (Studio → Low). When
            // the user has nothing installed and no favourites, surface
            // a one-line nudge under the empty Installed header rather
            // than skipping it entirely; preserves the screen's existing
            // mental model.
            item { SectionHeader("Installed", count = installedTotal) }
            if (installedTotal == 0) {
                item {
                    Text(
                        "No voices installed yet. Pick one below to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = spacing.sm),
                    )
                }
            } else {
                installedByEngine.forEach { (engine, tiers) ->
                    val engineCount = tiers.values.sumOf { it.size }
                    val engineKey = EngineKey(VoiceLibrarySection.Installed, engine.toCoreId())
                    val isCollapsed = engineKey in state.collapsedEngines
                    item(key = "i-engine-${engine.name}") {
                        EngineSubHeader(
                            engine = engine,
                            count = engineCount,
                            isCollapsed = isCollapsed,
                            onToggle = {
                                viewModel.toggleEngineCollapsed(VoiceLibrarySection.Installed, engine)
                            },
                        )
                    }
                    if (!isCollapsed) {
                        tiers.forEach { (tier, voicesInTier) ->
                            item(key = "i-${engine.name}-tier-${tier.name}") {
                                TierSubHeader(tier = tier, count = voicesInTier.size)
                            }
                            itemsIndexed(
                                voicesInTier,
                                key = { _, item -> "i-${item.id}" },
                            ) { index, voice ->
                                val isActive = voice.id == state.activeVoiceId
                                Column {
                                    VoiceRow(
                                        voice = voice,
                                        isActive = isActive,
                                        isFavorite = voice.id in state.favoriteIds,
                                        downloadingProgress = null,
                                        onTap = { viewModel.onRowTapped(voice) },
                                        onLongPress = { viewModel.requestDelete(voice) },
                                        onToggleFavorite = { viewModel.toggleFavorite(voice.id) },
                                        // Installed rows are unlikely to
                                        // hold a failure record (the row
                                        // is in the Installed bucket
                                        // because the previous download
                                        // succeeded) but pass it through
                                        // anyway — a re-download attempt
                                        // that failed AFTER an old install
                                        // would still want the retry
                                        // affordance.
                                        failed = state.failedDownloads[voice.id],
                                        onRetry = { viewModel.retryDownload(voice.id) },
                                        onDismissFailure = { viewModel.dismissFailedDownload(voice.id) },
                                        modifier = Modifier
                                            .animateItem()
                                            .cascadeReveal(index = index, key = voice.id),
                                    )
                                    // #197 + #198 — per-voice Advanced expander only
                                    // surfaces on the currently active voice. Two
                                    // affordances inside: (1) lexicon file SAF
                                    // picker for IPA pronunciation overrides
                                    // (Piper + Kokoro), (2) Kokoro-only phonemizer
                                    // language dropdown for forcing the voice to
                                    // pronounce embedded foreign-language tokens
                                    // correctly. Lives here rather than on every
                                    // row because applying these knobs requires
                                    // the engine to actually be loaded with the
                                    // target voice — which only happens for the
                                    // active one. Long-press-to-delete plus this
                                    // expander give the active row two distinct
                                    // power-user surfaces.
                                    if (isActive) {
                                        VoiceAdvancedExpander(
                                            voice = voice,
                                            lexiconPath = state.voiceLexiconOverrides[voice.id]
                                                .orEmpty(),
                                            phonemizerLang = state.voicePhonemizerLangOverrides[voice.id]
                                                .orEmpty(),
                                            onSetLexicon = { path ->
                                                viewModel.setVoiceLexicon(voice.id, path)
                                            },
                                            onSetPhonemizerLang = { code ->
                                                viewModel.setVoicePhonemizerLang(voice.id, code)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (availableTotal > 0) {
                item {
                    Spacer(modifier = Modifier.height(spacing.md))
                    SectionHeader("Available", count = availableTotal, dim = true)
                }
                if (availableHasKokoro) {
                    item { KokoroBundleNote() }
                }
                availableByEngine.forEach { (engine, tiers) ->
                    val engineCount = tiers.values.sumOf { it.size }
                    val engineKey = EngineKey(VoiceLibrarySection.Available, engine.toCoreId())
                    val isCollapsed = engineKey in state.collapsedEngines
                    item(key = "a-engine-${engine.name}") {
                        EngineSubHeader(
                            engine = engine,
                            count = engineCount,
                            dim = true,
                            isCollapsed = isCollapsed,
                            onToggle = {
                                viewModel.toggleEngineCollapsed(VoiceLibrarySection.Available, engine)
                            },
                        )
                    }
                    if (!isCollapsed) {
                        tiers.forEach { (tier, voicesInTier) ->
                            item(key = "a-${engine.name}-tier-${tier.name}") {
                                TierSubHeader(tier = tier, count = voicesInTier.size, dim = true)
                            }
                            val downloading = state.currentDownload
                            itemsIndexed(
                                voicesInTier,
                                key = { _, item -> "a-${item.id}" },
                            ) { index, voice ->
                                val rowProgress = if (downloading?.voiceId == voice.id) downloading.progress ?: -1f else null
                                VoiceRow(
                                    voice = voice,
                                    isActive = false,
                                    isFavorite = voice.id in state.favoriteIds,
                                    downloadingProgress = rowProgress,
                                    onTap = { if (downloading == null) viewModel.onRowTapped(voice) },
                                    onLongPress = null,
                                    onToggleFavorite = { viewModel.toggleFavorite(voice.id) },
                                    // Issue #541 / #548 — Available
                                    // section is where the user
                                    // initially fires a download, so
                                    // this is the most common surface
                                    // for the failure tile + retry.
                                    failed = state.failedDownloads[voice.id],
                                    onRetry = { viewModel.retryDownload(voice.id) },
                                    onDismissFailure = { viewModel.dismissFailedDownload(voice.id) },
                                    modifier = Modifier
                                        .animateItem()
                                        .cascadeReveal(index = index, key = voice.id),
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }

    val pending = state.pendingDelete
    if (pending != null) {
        DeleteConfirmDialog(
            voice = pending,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

/** Explains the unusual storage model of Kokoro voices once, inline in
 *  the Available list. The 53 Kokoro speakers all share one ~380 MB
 *  bundled download — picking any of them downloads the model once,
 *  and the remaining 52 then activate instantly. Inference is heavier
 *  than Piper, so on modest hardware a small inter-sentence pause is
 *  expected. Heading off both UX surprises upfront. */
@Composable
private fun KokoroBundleNote() {
    val spacing = LocalSpacing.current
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(spacing.sm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "🌐 Kokoro voices share one bundle",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "All 53 Kokoro speakers (English, Spanish, French, Hindi, Italian, Japanese, Portuguese, Chinese) share one ~380 MB bundle (model + speakers + tokens). The first Kokoro voice you pick downloads it; every Kokoro voice after that activates instantly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Kokoro inference is heavier than Piper — on modest hardware you may notice a small pause between sentences while the next chunk renders.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int, dim: Boolean = false) {
    val color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    // Issue #651 — TalkBack pre-fix focused this row but read nothing
    // (empty default contentDescription). Wrap the Row in a semantics
    // block that announces the section name + count as one phrase, and
    // mark the role as Header so TalkBack's "Headings" navigation
    // shortcut surfaces it. The two child Texts are already announced
    // by Compose's default semantics, so we use [clearAndSetSemantics]
    // to consolidate them into a single TalkBack announcement instead
    // of three separate ones ("AVAILABLE", " · ", "204").
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "$label section, $count voices"
            role = Role.Image // Compose Material lacks Role.Header;
            // Image is the closest neutral role that lets TalkBack
            // read the contentDescription without claiming "Button"
            // (no tap target) or "Tab" (not a tab). Custom roles
            // require a CustomAccessibilityAction — overkill for a
            // static header.
        },
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "  ·  $count",
            style = MaterialTheme.typography.labelMedium,
            color = color.copy(alpha = 0.7f),
        )
    }
}

/** Engine label + count rendered under a [SectionHeader] (Piper /
 *  Kokoro). Visually slightly louder than the per-tier sub-header
 *  beneath it so the read order is Section → Engine → Tier → Row.
 *  Empty engine groups never reach this composable — the ViewModel's
 *  [groupByEngineThenTier] drops them.
 *
 *  Tappable per #130 — the whole row toggles the collapse state and
 *  the trailing chevron flips between [Icons.Outlined.ExpandMore]
 *  (collapsed) and [Icons.Outlined.ExpandLess] (expanded). The
 *  chevron lives at the row's end via a `Spacer(weight = 1f)` so
 *  the label/count stay left-aligned even on wide screens. */
@Composable
private fun EngineSubHeader(
    engine: VoiceEngine,
    count: Int,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
    dim: Boolean = false,
) {
    val baseColor = if (dim) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }
    val label = when (engine) {
        // #676 — System TTS sub-header. "System TTS" is the
        // shortest accurate label — the family card subtitle
        // elsewhere fills in the "uses your device's voice"
        // explanation; here we want a glanceable header.
        VoiceEngine.SystemTts -> "System TTS"
        VoiceEngine.Piper -> "Piper"
        VoiceEngine.Kokoro -> "Kokoro"
        // Issue #119 — third in-process voice family. "Lite" tag set
        // off the engine name communicates the value proposition (this
        // is the smallest tier) without making it sound like a beta.
        VoiceEngine.Kitten -> "Kitten (Lite)"
        // Azure HD voices land in their own sub-header so the cloud
        // round-trip story is one glance away. Catalog labels carry a
        // ☁️ glyph, but the section header restates "Azure" plainly so
        // a user scanning the library doesn't need to decode a single
        // emoji to know what's cloud vs local.
        VoiceEngine.Azure -> "Azure (Cloud)"
    }
    // a11y (#481): engine sub-header expand/collapse — Role.Button with
    // an explicit click label so TalkBack reads "Expand <label>" /
    // "Collapse <label>" rather than the generic "double tap".
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = if (isCollapsed) "Expand $label" else "Collapse $label",
                onClick = onToggle,
            )
            .padding(top = 6.dp, bottom = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = baseColor.copy(alpha = 0.95f),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "  ·  $count",
            style = MaterialTheme.typography.labelMedium,
            color = baseColor.copy(alpha = 0.65f),
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (isCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
            contentDescription = if (isCollapsed) "Expand $label" else "Collapse $label",
            tint = baseColor.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Tier label + count rendered under an [EngineSubHeader] (Studio /
 *  High / Medium / Low). Visually quieter than the engine sub-header so
 *  the Section → Engine grouping reads first; the tier label is a
 *  refinement, not a peer. */
@Composable
private fun TierSubHeader(tier: QualityLevel, count: Int, dim: Boolean = false) {
    val baseColor = if (dim) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val (label, accent) = tierDisplay(tier)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    ) {
        if (accent.isNotEmpty()) {
            Text(
                accent,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.size(4.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = baseColor.copy(alpha = 0.85f),
            fontWeight = FontWeight.Medium,
        )
        Text(
            "  ·  $count",
            style = MaterialTheme.typography.labelSmall,
            color = baseColor.copy(alpha = 0.55f),
        )
    }
}

/** Map a tier to its (display label, optional emoji accent). Studio
 *  earns the trophy; the rest stay text-only so the visual hierarchy
 *  reads top-to-bottom without competing decorations. */
private fun tierDisplay(tier: QualityLevel): Pair<String, String> = when (tier) {
    QualityLevel.Studio -> "Studio" to "🎙️"
    QualityLevel.High -> "High" to ""
    QualityLevel.Medium -> "Medium" to ""
    QualityLevel.Low -> "Low" to ""
}

@Composable
private fun VoiceRow(
    voice: UiVoiceInfo,
    isActive: Boolean,
    isFavorite: Boolean,
    /** null = not downloading; -1f = indeterminate; 0..1 = determinate */
    downloadingProgress: Float?,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
    onToggleFavorite: () -> Unit,
    /** Issue #541 / #548 — non-null when the most recent download
     *  attempt for this voice terminated in Failed. Surfaces a
     *  "Tap to retry · $reason" subtitle and tap routes to [onRetry]
     *  instead of [onTap]. Null on the common path (no failure to
     *  surface). */
    failed: FailedDownload? = null,
    /** Issue #548 — re-arm the download for this voice. Routed when
     *  the row is rendered in the "failed" state. */
    onRetry: () -> Unit = {},
    /** Issue #541 — dismiss the failure record without retrying. The
     *  row reverts to its standard "available, tap to download" shape. */
    onDismissFailure: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val borderColor = if (isActive) brass else outline.copy(alpha = 0.35f)
    val isAvailable = !voice.isInstalled

    // Issue #617 (v1.0 blocker) — pre-fix the row's outer Box used
    // `combinedClickable` with no explicit semantics, so TalkBack
    // would visit the box, the star toggle, every child Text in
    // order, and then the trailing action button. The user heard:
    // "Pick this voice... voice star, off... double tap to
    // activate, Adam, Piper, Medium, English, Download". The
    // intermediate Text nodes are non-interactive and shouldn't be
    // separate stops — TalkBack should hear the row identity in
    // one announcement, then move to the star toggle and the
    // action button as the only other focus stops.
    //
    // Fix: wrap the entire row in `semantics(mergeDescendants = true)`
    // around the body, with `clearAndSetSemantics` on the inner
    // Column that holds the title + subtitle texts. Star toggle and
    // RowAction stay as their own focusable children because they
    // each carry their own `clickable` (which beats a parent
    // `clearAndSetSemantics` because of how Compose merges actions).
    //
    // Focus order outcome on R5CRB0W66MK (TalkBack ON):
    //   1. Star toggle ("Add to starred" / "Remove from starred")
    //   2. The voice row itself ("Adam, Piper Medium English,
    //      double tap to pick this voice", with `selected` when
    //      isActive). The body Column is descendant-cleared so
    //      Text children don't fire their own announcements.
    //   3. RowAction (trailing button — Download / Activate),
    //      when it has its own clickable.
    val rowDescription = buildString {
        append(voice.displayName)
        if (voice.language.isNotBlank()) {
            append(", ")
            append(voice.language)
        }
        if (isActive) append(", currently active")
        if (isAvailable) append(", not yet downloaded")
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
                onClickLabel = if (isActive) "Already active" else "Pick this voice",
            )
            .semantics {
                role = Role.Button
                selected = isActive
                contentDescription = rowDescription
            }
            .padding(horizontal = spacing.md, vertical = spacing.sm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                FavoriteToggle(
                    isFavorite = isFavorite,
                    onToggle = onToggleFavorite,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // Issue #617 — descendant-clear the title +
                        // subtitle Texts. The row's outer
                        // `semantics { contentDescription = ... }`
                        // already exposes the voice identity; these
                        // Texts would otherwise become their own
                        // TalkBack stops (4-5 extra Tab presses per
                        // row). Star toggle + RowAction are siblings
                        // of this Column, not descendants, so they're
                        // unaffected.
                        .clearAndSetSemantics { },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Title: "<flag> <name>". Flag is derived from
                        // language code at render time (see #128) so the
                        // catalog stays render-agnostic and a flag mapping
                        // change reaches every row in one edit.
                        //
                        // #250 — `weight(1f, fill = false) + maxLines + ellipsis`
                        // so long names (live Azure roster e.g.
                        // "☁️ Ava (Dragon HD) · en-US · Dragon HD")
                        // truncate gracefully instead of pushing the
                        // ActiveChip into a "ACTIV/E" wrap. fill = false
                        // means the title takes only the space it needs
                        // when short, leaving room for the chip; with
                        // weight(1f, fill = true) it would always be
                        // full-width and the chip would never sit beside
                        // a short name like "Aria".
                        Text(
                            "${flagForLanguage(voice.language)} ${voice.displayName}",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isAvailable && downloadingProgress == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            // Issue #263 — Azure variant suffixes (Multilingual /
                            // Turbo / Studio / HD) fall exactly where the
                            // 1-line cutoff used to clip on Flip3's 1080px
                            // inner display, so users couldn't distinguish
                            // 'Adam Multilingual' from 'Adam Studio'. Allow
                            // two lines; ellipsis still kicks in past that
                            // for the rare 30+ char name.
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isActive) {
                            Spacer(modifier = Modifier.size(spacing.xs))
                            ActiveChip()
                        }
                    }
                    // Subtitle: "<Engine> · <Tier> · <Gender>". Gender
                    // segment is dropped when [VoiceGender.Unknown] so
                    // the line collapses to "Engine · Tier" rather than
                    // showing an empty trailing dot. Size/language data
                    // moved out of the subtitle in #128 — language is
                    // already encoded in the title flag, and per-voice
                    // size is more useful in the delete-confirm dialog
                    // (the only place it directly drives a decision).
                    Text(
                        text = voiceSubtitle(voice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // PR-H (#86) — per-voice cached-bytes label. Only
                    // rendered for voices the user has meaningfully
                    // used (cache attributed to this voice > 0).
                    // Voices with `cachedBytes == 0L` skip the line so
                    // the row collapses to its pre-PR-H 2-line shape
                    // for the common case (installed voices the user
                    // hasn't played yet). The label uses labelSmall to
                    // sit visually beneath the bodySmall subtitle
                    // without competing for the row's attention.
                    if (voice.cachedBytes > 0L) {
                        Text(
                            text = formatBytes(voice.cachedBytes) + " cached",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                RowAction(
                    voice = voice,
                    isActive = isActive,
                    isDownloading = downloadingProgress != null,
                    onTap = onTap,
                )
            }
            if (downloadingProgress != null) {
                Spacer(modifier = Modifier.size(spacing.xxs))
                // Negative progress = the upstream signal has no
                // Content-Length yet (sherpa-onnx HEAD probe in flight),
                // so we render the indeterminate brass comet. Otherwise
                // the determinate fill smooth-animates as bytes roll in.
                BrassProgressBar(
                    progress = if (downloadingProgress < 0f) null else downloadingProgress,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (failed != null) {
                // Issue #541 / #548 — failure subtitle + retry affordance.
                // Pre-fix the row went silent ("timeout" elsewhere with no
                // context); this surfaces the upstream reason and adds an
                // explicit Tap-to-retry button so the user has an action
                // to take, not just a status.
                Spacer(modifier = Modifier.size(spacing.xs))
                FailedDownloadSubtile(
                    failed = failed,
                    onRetry = onRetry,
                    onDismiss = onDismissFailure,
                )
            }
        }
    }
}

/**
 * Issue #541 / #548 — failure tile rendered under a [VoiceRow] when
 * the most recent download attempt terminated in
 * [VoiceManager.DownloadProgress.Failed]. Shows:
 *   - Error icon + the upstream reason (HTTP code or exception
 *     message, verbatim — so a support thread can pattern-match it).
 *   - Optional "stopped at $pct %" if we got past Resolving before
 *     the failure.
 *   - "Tap to retry" Primary button — re-arms the download via the
 *     viewmodel's retryDownload() path.
 *   - "Dismiss" Text button — clears the failure record without
 *     retrying (e.g. user wants to deal with it later).
 *
 * Color: error container + onErrorContainer per Material3 conventions
 * so the tile reads as "needs attention" without screaming.
 */
@Composable
private fun FailedDownloadSubtile(
    failed: FailedDownload,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val percentSuffix = failed.lastProgress?.let { p ->
        val pct = (p * 100f).toInt().coerceIn(0, 100)
        " · stopped at $pct%"
    }.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
            .padding(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Text(
            // Network timeouts have no HTTP code so the upstream
            // reason often reads "Read timed out" / "java.net…".
            // We prefix the human-friendly description and append the
            // raw reason so both a glance-reader and a debug-reader
            // get what they need from one line.
            text = "Download failed: ${failed.reason}$percentSuffix",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrassButton(
                label = "Tap to retry",
                onClick = onRetry,
                variant = BrassButtonVariant.Primary,
            )
            BrassButton(
                label = "Dismiss",
                onClick = onDismiss,
                variant = BrassButtonVariant.Text,
            )
        }
    }
}

/** Star-toggle leading the row. Filled = starred (pinned to Starred
 *  section), outlined = not. The whole row is wrapped in a parent
 *  `combinedClickable` (tap = activate/download, long-press = delete);
 *  in Compose, `combinedClickable` keeps the pointer event during the
 *  long-press timeout window, which can starve a nested `IconButton`'s
 *  own clickable — that's the regression #106 reports. We sidestep the
 *  arbitration by giving the toggle its own `Box.clickable` directly
 *  (no nested IconButton's gesture detector to compete with) and
 *  letting Compose's standard hit-testing route taps to the deepest
 *  descendant that has a clickable modifier. The bounded ripple inside
 *  the round clip keeps the visual affordance equivalent to a Material
 *  IconButton without the long-press race. */
@Composable
private fun FavoriteToggle(
    isFavorite: Boolean,
    onToggle: () -> Unit,
) {
    val tint = if (isFavorite) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // a11y (#481, #479): favorite star is a toggleable Role.Checkbox —
    // tap toggles starred state; icon's contentDescription doubles as
    // the TalkBack announcement. Target was 36dp (below WCAG 2.5.5
    // minimum); bumped to 48dp baseline via Modifier.accessibleSize,
    // and #486 enlarged-targets opt-in widens further to 64dp under
    // Switch Access / the user toggle.
    //
    // Issue #630 (v1.0 blocker) — pre-fix the star carried
    // `.clickable(role = Role.Checkbox, onClick = onToggle)` which
    // is a *click* node, not a *toggleable* one. TalkBack on
    // R5CRB0W66MK announced the star as "double tap to activate"
    // (button copy) with no "checked" / "not checked" indication;
    // a screen-reader user pressing it heard the activate sound but
    // no state confirmation. Swap to `Modifier.toggleable(value =
    // isFavorite, role = Role.Switch, onValueChange = …)` so the
    // star presents as a binary on/off toggle. TalkBack now reads
    // "Star, off, switch, double tap to toggle" / "Star, on,
    // switch, double tap to toggle" with the state baked into the
    // verb.
    Box(
        modifier = Modifier
            .accessibleSize(
                enlargedFlag = LocalAccessibleTouchTargets.current,
                base = 48.dp,
            )
            .clip(CircleShape)
            .toggleable(
                value = isFavorite,
                role = Role.Switch,
                onValueChange = { onToggle() },
            )
            // The toggleable above carries the state ("on"/"off")
            // via `value`; the contentDescription here just names
            // the thing being toggled so the readout is "Star this
            // voice, on, switch" rather than the more verbose
            // pre-fix copy that baked the action verb into the
            // label and confused the auto-announced state.
            .semantics {
                contentDescription = "Star this voice"
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            // Issue #630 — Icon's contentDescription set to null
            // because the toggleable Box owns the semantics. Two
            // descriptions on a single focusable cluster would
            // surface twice in TalkBack.
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ActiveChip() {
    val brass = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(brass.copy(alpha = 0.18f))
            .border(width = 1.dp, color = brass, shape = RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = null,
            tint = brass,
            modifier = Modifier.size(12.dp),
        )
        Text(
            "ACTIVE",
            style = MaterialTheme.typography.labelSmall,
            color = brass,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Trailing action surfaced on each [VoiceRow]. Three states only:
 *  Downloading (text spinner copy), Installed-but-not-active (Activate
 *  button), and Available (Download button). The active+installed case
 *  used to render an "In use" Text — dropped in #127 because the brass
 *  border + ACTIVE chip in the title row already mark active state, so
 *  the trailing label was redundant clutter. Active+installed rows now
 *  show no trailing action; the row is still tap-targeted via the
 *  enclosing `combinedClickable` (a no-op while active, since
 *  `onRowTapped` only switches if id != active). */
@Composable
private fun RowAction(
    voice: UiVoiceInfo,
    isActive: Boolean,
    isDownloading: Boolean,
    onTap: () -> Unit,
) {
    when {
        isDownloading -> Text(
            "Downloading…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        voice.isInstalled && isActive -> Unit
        voice.isInstalled -> BrassButton(
            label = "Activate",
            onClick = onTap,
            variant = BrassButtonVariant.Secondary,
        )
        else -> BrassButton(
            label = "Download",
            onClick = onTap,
            variant = BrassButtonVariant.Primary,
        )
    }
}

@Composable
private fun DeleteConfirmDialog(
    voice: UiVoiceInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${voice.displayName}?") },
        text = {
            Text(
                "Frees ${formatBytes(voice.sizeBytes)}. You can re-download anytime from this screen.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            BrassButton(
                label = "Delete",
                onClick = onConfirm,
                variant = BrassButtonVariant.Primary,
            )
        },
        dismissButton = {
            BrassButton(
                label = "Cancel",
                onClick = onDismiss,
                variant = BrassButtonVariant.Text,
            )
        },
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MagicSkeletonTile(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape),
            shape = CircleShape,
            glyphSize = 96.dp,
        )
        Spacer(modifier = Modifier.height(spacing.lg))
        Text(
            "Voices loading…",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            "Catalog is being summoned. This shouldn't take more than a moment.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/** Compose the per-row subtitle: `<Engine> · <Tier> · <Gender>`.
 *  Gender is dropped from the line when [VoiceGender.Unknown] (a few
 *  Piper multi-speaker corpora carry no gender metadata) so the
 *  subtitle collapses cleanly to `<Engine> · <Tier>` rather than
 *  showing an empty trailing segment. Pulled out of [VoiceRow] so
 *  the format is unit-testable from a JVM test without spinning up
 *  the screen — see [voicelibrary] tests. */
internal fun voiceSubtitle(voice: UiVoiceInfo): String {
    val engineLabel = when (voice.engineType) {
        is EngineType.Piper -> "Piper"
        is EngineType.Kokoro -> "Kokoro"
        // Issue #119 — third in-process voice family. Surfaces in the
        // Voice Library subtitle as "Kitten · Low · Female" etc.
        is EngineType.Kitten -> "Kitten"
        is EngineType.Azure -> "Azure"
        // #676 — System TTS subtitle uses the engine package label
        // when available ("Google", "Samsung") so users can tell two
        // OS engines apart at a glance. Fallback to "System TTS" when
        // we don't have a labelled name handy.
        is EngineType.SystemTts -> "System TTS"
    }
    val tierLabel = when (voice.qualityLevel) {
        QualityLevel.Studio -> "Studio"
        QualityLevel.High -> "High"
        QualityLevel.Medium -> "Medium"
        QualityLevel.Low -> "Low"
    }
    val genderLabel = when (voice.gender) {
        VoiceGender.Female -> "Female"
        VoiceGender.Male -> "Male"
        VoiceGender.Unknown -> null
    }
    val parts = listOfNotNull(engineLabel, tierLabel, genderLabel)
    return parts.joinToString(separator = "  ·  ")
}

// ─── Issue #264 chip color helpers ─────────────────────────────────
//
// Brass-pill chip colors match the Library / Browse chip strips so the
// Voice Library reads as part of the same family. The filter-criteria
// + matchesCriteria + filterBy helpers used by the screen live in
// VoiceFilter.kt — same package, internal visibility — so JVM tests
// can exercise the contract without bringing up Compose.

@Composable
private fun brassFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/** Toggle-membership helper — adds an element if absent, removes if
 *  present. Used by every chip's onClick to flip the relevant set. */
private fun <T> Set<T>.toggleMember(item: T): Set<T> =
    if (item in this) this - item else this + item

/**
 * Issues #197 + #198 — per-voice Advanced expander, surfaced only on
 * the currently active voice's row. Houses two power-user knobs:
 *
 *  - Lexicon file picker (#197). A SAF-launched OpenDocument call
 *    accepts `.lexicon` files (sherpa-onnx IPA / X-SAMPA dictionaries
 *    for per-token phoneme overrides). The resolved content:// URI is
 *    stored verbatim in DataStore; engine-side, the bridge writes the
 *    same string to `VoiceEngine.voiceLexicon` /
 *    `KokoroEngine.voiceLexicon` and sherpa-onnx parses it via
 *    `OfflineTts*ModelConfig.setLexicon()`. The override takes effect
 *    on the next voice reload — typically the next time the user
 *    taps Play after closing this expander.
 *
 *  - Phonemizer language dropdown (#198). Only rendered on Kokoro
 *    voice rows because Piper voices are per-language and don't go
 *    through a language-aware phonemizer. The list of codes comes
 *    from [`in.jphe.storyvox.playback.KOKORO_PHONEMIZER_LANGS`] —
 *    the documented set sherpa-onnx's Kokoro phonemizer accepts.
 *
 * The expander defaults to collapsed (`expanded = false`) — both
 * knobs are niche power-user surface; the row itself stays clean for
 * everyday picks. Tap the "Advanced" affordance to flip; the state
 * is screen-local (not persisted), matching SettingsScreen's
 * `var perfAdvancedOpen by remember { mutableStateOf(false) }` idiom.
 */
@Composable
internal fun VoiceAdvancedExpander(
    voice: UiVoiceInfo,
    lexiconPath: String,
    phonemizerLang: String,
    onSetLexicon: (String?) -> Unit,
    onSetPhonemizerLang: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    val isKokoro = voice.engineType is EngineType.Kokoro

    // SAF picker for the lexicon file. OpenDocument returns a
    // content:// URI — for v1 we store it verbatim and let
    // sherpa-onnx's loader treat it as an opaque string path through
    // its filesystem-or-resource resolver. If sherpa-onnx rejects the
    // URI (most likely outcome on Android 10+ scoped storage), v2
    // will copy the file into ${filesDir}/lexicons/<voiceId>/ and
    // store that absolute path instead. The map shape is forward-
    // compatible with that migration.
    val context = androidx.compose.ui.platform.LocalContext.current
    val saFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val resolved = resolveLexiconPath(uri.toString())
            if (resolved != null) onSetLexicon(resolved)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = spacing.lg, end = spacing.xs, top = spacing.xxs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
        ) {
            // a11y (#481): expand/collapse — Role.Button + label.
            // Issue #625 — pre-fix the chevron icon swapped between
            // ExpandMore and ExpandLess on expand, but the swap was an
            // instantaneous vector change with no rotation animation,
            // and the icon was tiny (16 dp) with low-contrast tint —
            // visually it read as "not expandable" at first glance.
            //
            // Fix: keep a single ExpandMore icon and rotate it 0° → 180°
            // via animateFloatAsState. The brass primary tint replaces
            // the onSurfaceVariant so the chevron pops, and the size
            // bumps to 20 dp so it reads as a deliberate affordance.
            // The "Advanced" label gets the same brass primary tint so
            // the whole row reads as one cohesive tappable affordance.
            // Honors LocalReducedMotion via a snap-instead-of-tween
            // animation spec (the icon still flips correctly but
            // doesn't sweep through the intermediate angles).
            val rotationReduced = LocalReducedMotion.current
            val chevronRotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = tween(
                    durationMillis = if (rotationReduced) 0 else 200,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
                label = "advanced-chevron-rotation",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "Collapse advanced settings" else "Expand advanced settings",
                    ) { expanded = !expanded }
                    .padding(vertical = spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Advanced",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    // Issue #625 — single icon + rotation; no
                    // ExpandLess/ExpandMore swap. The 180° rotation on
                    // expand produces a smooth visual cue that matches
                    // the established affordance vocabulary (Material 3
                    // expandable cards, gmail thread expanders, etc.).
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse Advanced" else "Expand Advanced",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevronRotation),
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(spacing.xs))
                // #197 — Lexicon file picker. One row, one button.
                // Shows "set" / "not set" so users can tell at a glance.
                Text(
                    "Pronunciation lexicon",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (lexiconPath.isNotEmpty()) {
                        "Custom .lexicon loaded — IPA / X-SAMPA overrides apply on next play."
                    } else {
                        "Override how the voice pronounces specific names and words with a .lexicon file."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    BrassButton(
                        label = if (lexiconPath.isNotEmpty()) "Replace lexicon" else "Pick lexicon",
                        onClick = {
                            // Accept any mime; .lexicon has no canonical
                            // mime type and most pickers return
                            // application/octet-stream or */*.
                            saFilePicker.launch(arrayOf("*/*"))
                        },
                        variant = BrassButtonVariant.Text,
                    )
                    if (lexiconPath.isNotEmpty()) {
                        BrassButton(
                            label = "Clear",
                            onClick = { onSetLexicon(null) },
                            variant = BrassButtonVariant.Text,
                        )
                    }
                }
                if (isKokoro) {
                    Spacer(modifier = Modifier.height(spacing.xs))
                    // #198 — Phonemizer language dropdown, Kokoro only.
                    Text(
                        "Phonemizer language",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (phonemizerLang.isNotEmpty()) {
                            "Forcing pronunciation to: $phonemizerLang. Tap to change or clear."
                        } else {
                            "Force the phonemizer to a specific language (helpful for embedded foreign-language dialogue)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PhonemizerLangDropdown(
                        selected = phonemizerLang,
                        onSelected = onSetPhonemizerLang,
                    )
                }
            }
        }
    }
}

/**
 * #198 — Kokoro phonemizer language dropdown. Renders one chip per
 * documented code in [`in.jphe.storyvox.playback.KOKORO_PHONEMIZER_LANGS`]
 * plus a "Default" chip that clears the override. Chip-strip rather
 * than a Material `DropdownMenu` because (1) the list is short
 * (9 entries today), (2) the rest of VoiceLibraryScreen already uses
 * the same brass FilterChip pattern for language filtering — visual
 * consistency, (3) chips are tappable without an extra
 * tap-to-open step, which suits the active-row inline placement.
 */
@Composable
private fun PhonemizerLangDropdown(
    selected: String,
    onSelected: (String?) -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selected.isEmpty(),
            onClick = { onSelected(null) },
            label = { Text("Default", style = MaterialTheme.typography.labelMedium) },
            colors = brassFilterChipColors(),
        )
        for (code in `in`.jphe.storyvox.playback.KOKORO_PHONEMIZER_LANGS) {
            FilterChip(
                selected = selected == code,
                onClick = {
                    // Tap the active chip → clear; tap a different chip → set.
                    if (selected == code) onSelected(null) else onSelected(code)
                },
                label = { Text(code, style = MaterialTheme.typography.labelMedium) },
                colors = brassFilterChipColors(),
            )
        }
    }
}

/**
 * #197 — normalize a SAF-returned URI string for storage in the
 * per-voice lexicon map. Accepts both `content://` URIs (the common
 * Android SAF return) and raw `file://` / absolute paths (rare; older
 * file pickers, debug builds, instrumentation tests).
 *
 * Returns null on input we can't safely persist — empty string,
 * embedded `;` or `=` (which collide with our flat-string codec in
 * [`in.jphe.storyvox.data.SettingsRepositoryUiImpl`]). The codec
 * silently drops collisions on read, so refusing them at the picker
 * step gives the user immediate feedback (the button no-ops) rather
 * than a delayed "where did my setting go" mystery.
 *
 * Visible (internal) for unit testing — VoxSherpa SAF parse test in
 * `:feature`'s test source set exercises the URI-shape acceptance
 * matrix without touching real ContentResolver state.
 */
internal fun resolveLexiconPath(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.contains(';') || trimmed.contains('=')) return null
    return trimmed
}
