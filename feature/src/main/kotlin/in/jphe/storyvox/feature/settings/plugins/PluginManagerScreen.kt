package `in`.jphe.storyvox.feature.settings.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.playback.voice.VoiceEngineFamily
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptor
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Plugin manager screen (#404, #501) — Settings → Plugins.
 *
 * Registry-driven brass-edged card list. Each card has:
 *  - Brass monogram icon (`fictionMonogram(displayName)`).
 *  - Display name + plugin description (subtitle).
 *  - Capability chips (Follow, Search, Audio, Anonymous/PAT for
 *    fiction sources; Local/Cloud + size hint for voice families).
 *  - Brass-edged switch.
 *  - Tap to open details sheet.
 *
 * Three category sections: Fiction sources, Audio streams, Voice
 * bundles. As of v0.5.49 (#501) the Voice bundles section is fully
 * iterated — Piper / Kokoro / KittenTTS / Azure HD cards plus the
 * VoxSherpa upstreams placeholder, all sourced from
 * `VoiceFamilyRegistry`.
 *
 * The top of the screen has a search input + 3 filter chips
 * (On / Off / All). Search is substring on displayName/description/id.
 *
 * The Voice bundle cards delegate to a `onOpenVoiceLibrary` callback
 * (wired by the NavHost) for the "Manage voices →" link, and to
 * `onOpenAzureSettings` for the Azure family's BYOK configure CTA.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagerScreen(
    onNavigateBack: () -> Unit,
    onOpenVoiceLibrary: () -> Unit = {},
    onOpenAzureSettings: () -> Unit = {},
    viewModel: PluginManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    var detailsForId by remember { mutableStateOf<String?>(null) }
    var voiceFamilyDetailsForId by remember { mutableStateOf<String?>(null) }

    val sections = remember(state.plugins) { groupPluginsForManager(state.plugins) }
    val fictionVisible = remember(sections.fiction, state.searchQuery, state.filterChip) {
        filterPlugins(sections.fiction, state.searchQuery, state.filterChip)
    }
    val audioVisible = remember(sections.audio, state.searchQuery, state.filterChip) {
        filterPlugins(sections.audio, state.searchQuery, state.filterChip)
    }
    val voiceFamiliesVisible = remember(state.voiceFamilies, state.searchQuery, state.filterChip) {
        filterVoiceFamilies(state.voiceFamilies, state.searchQuery, state.filterChip)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Plugins",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Search input
            item("search") {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    label = { Text("Search plugins") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Filter chips: On / Off / All
            item("chips") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    FilterChip(
                        selected = state.filterChip == PluginFilterChip.On,
                        onClick = { viewModel.setFilterChip(PluginFilterChip.On) },
                        label = { Text("On") },
                    )
                    FilterChip(
                        selected = state.filterChip == PluginFilterChip.Off,
                        onClick = { viewModel.setFilterChip(PluginFilterChip.Off) },
                        label = { Text("Off") },
                    )
                    FilterChip(
                        selected = state.filterChip == PluginFilterChip.All,
                        onClick = { viewModel.setFilterChip(PluginFilterChip.All) },
                        label = { Text("All") },
                    )
                }
            }

            // Fiction sources section
            item("fiction-header") {
                CategoryHeader(
                    title = "Fiction sources",
                    count = fictionVisible.size,
                )
            }
            items(fictionVisible) { row ->
                PluginCard(
                    row = row,
                    onToggle = { enabled -> viewModel.togglePlugin(row.descriptor.id, enabled) },
                    onTap = { detailsForId = row.descriptor.id },
                )
            }

            // Audio streams section
            item("audio-header") {
                CategoryHeader(
                    title = "Audio streams",
                    count = audioVisible.size,
                )
            }
            items(audioVisible) { row ->
                PluginCard(
                    row = row,
                    onToggle = { enabled -> viewModel.togglePlugin(row.descriptor.id, enabled) },
                    onTap = { detailsForId = row.descriptor.id },
                )
            }

            // Voice bundles section (#501) — Piper / Kokoro / Kitten /
            // Azure HD cards + VoxSherpa upstreams placeholder, all
            // sourced from VoiceFamilyRegistry. Each card has the
            // same brass-edged shape as the fiction cards.
            item("voice-header") {
                CategoryHeader(
                    title = "Voice bundles",
                    count = voiceFamiliesVisible.count { !it.descriptor.isPlaceholder },
                )
            }
            items(voiceFamiliesVisible) { row ->
                VoiceFamilyCard(
                    row = row,
                    onToggle = { enabled -> viewModel.toggleVoiceFamily(row.descriptor.id, enabled) },
                    onTap = { voiceFamilyDetailsForId = row.descriptor.id },
                    onManageVoices = onOpenVoiceLibrary,
                    onConfigure = onOpenAzureSettings,
                )
            }
        }
    }

    // Fiction / Audio source details sheet
    val detailRow = state.plugins.firstOrNull { it.descriptor.id == detailsForId }
    if (detailRow != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { detailsForId = null },
            sheetState = sheetState,
        ) {
            PluginDetailsContent(row = detailRow)
        }
    }

    // Voice family details sheet (#501)
    val voiceFamilyDetailRow = state.voiceFamilies.firstOrNull { it.descriptor.id == voiceFamilyDetailsForId }
    if (voiceFamilyDetailRow != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { voiceFamilyDetailsForId = null },
            sheetState = sheetState,
        ) {
            VoiceFamilyDetailsContent(
                row = voiceFamilyDetailRow,
                onManageVoices = {
                    voiceFamilyDetailsForId = null
                    onOpenVoiceLibrary()
                },
                onConfigure = {
                    voiceFamilyDetailsForId = null
                    onOpenAzureSettings()
                },
            )
        }
    }
}

@Composable
private fun CategoryHeader(title: String, count: Int) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.padding(vertical = spacing.sm)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (count == 1) "1 plugin" else "$count plugins",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Plugin manager card (#404). Brass-edged outline, brass monogram on
 * the left, name + description in the middle, switch on the right,
 * capability chips below, "tap for details" hint at the bottom.
 *
 * The whole card is clickable for the details sheet; the switch
 * intercepts taps so toggling doesn't open the details sheet.
 */
@Composable
private fun PluginCard(
    row: PluginManagerRow,
    onToggle: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    val brassColors = SwitchDefaults.colors(
        checkedThumbColor = brass,
        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
        checkedBorderColor = brass,
        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (row.enabled) brass else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .background(MaterialTheme.colorScheme.surface)
            // a11y (#481): Role.Button for the card's open-details tap.
            .clickable(role = Role.Button, onClickLabel = "Open ${row.descriptor.displayName} details", onClick = onTap)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Brass monogram icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = fictionMonogram(row.descriptor.displayName, row.descriptor.displayName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.descriptor.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (row.descriptor.description.isNotBlank()) {
                    Text(
                        row.descriptor.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // a11y (#478): the outer Column has its own tap (open
            // details) so we can't make the whole card toggleable. Add
            // a `contentDescription` to the Switch so TalkBack announces
            // "Enable <plugin>, switch, on" instead of just "switch, on".
            val toggleLabel = "Enable ${row.descriptor.displayName}"
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggle,
                colors = brassColors,
                modifier = Modifier.semantics { contentDescription = toggleLabel },
            )
        }
        // Capability chips
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            if (row.descriptor.supportsSearch) {
                CapabilityChip("Search")
            }
            if (row.descriptor.supportsFollow) {
                CapabilityChip("Follow")
            }
            if (row.descriptor.category == `in`.jphe.storyvox.data.source.plugin.SourceCategory.AudioStream) {
                CapabilityChip("Audio")
            } else {
                CapabilityChip("Text")
            }
        }
        Text(
            "Status: ${if (row.enabled) "enabled" else "disabled"} · Tap for details",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CapabilityChip(label: String) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    )
}

@Composable
private fun PluginDetailsContent(row: PluginManagerRow) {
    val spacing = LocalSpacing.current
    val descriptor: SourcePluginDescriptor = row.descriptor
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            descriptor.displayName,
            style = MaterialTheme.typography.headlineSmall,
        )
        if (descriptor.description.isNotBlank()) {
            Text(
                descriptor.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
        Text("Capabilities", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            CapabilityChip(if (descriptor.supportsSearch) "Search ✓" else "Search ✗")
            CapabilityChip(if (descriptor.supportsFollow) "Follow ✓" else "Follow ✗")
            CapabilityChip(
                if (descriptor.category == `in`.jphe.storyvox.data.source.plugin.SourceCategory.AudioStream) "Audio" else "Text",
            )
        }
        if (descriptor.sourceUrl.isNotBlank()) {
            Text(
                "Source: ${descriptor.sourceUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Plugin id: ${descriptor.id}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(spacing.md))
    }
}

/**
 * Plugin-seam Phase 4 (#501) — twin of [PluginCard] for voice family
 * rows. Same brass-edged shape; the differences are:
 *
 * - Subtitle is the family's declared `description` (e.g.
 *   "Local neural voices · per-voice ONNX download").
 * - Capability chips show **Local / Cloud / BYOK / Shared model** and
 *   a live voice count when known.
 * - Placeholder rows (VoxSherpa upstreams) skip the toggle and use a
 *   muted outline so they read as "future shape preview".
 * - The status line at the bottom carries a "Manage voices →" or
 *   "Configure Azure key →" affordance.
 */
@Composable
private fun VoiceFamilyCard(
    row: VoiceFamilyRow,
    onToggle: (Boolean) -> Unit,
    onTap: () -> Unit,
    onManageVoices: () -> Unit,
    onConfigure: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    val brassColors = SwitchDefaults.colors(
        checkedThumbColor = brass,
        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
        checkedBorderColor = brass,
        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    )
    val descriptor = row.descriptor
    val borderColor = when {
        descriptor.isPlaceholder -> MaterialTheme.colorScheme.outlineVariant
        row.enabled -> brass
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                role = Role.Button,
                onClickLabel = "Open ${descriptor.displayName} details",
                onClick = onTap,
            )
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = fictionMonogram(descriptor.displayName, descriptor.displayName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    descriptor.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (descriptor.description.isNotBlank()) {
                    Text(
                        descriptor.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Placeholder rows have no toggle — they're for visibility,
            // not user control.
            if (!descriptor.isPlaceholder) {
                val toggleLabel = "Enable ${descriptor.displayName}"
                Switch(
                    checked = row.enabled,
                    onCheckedChange = onToggle,
                    colors = brassColors,
                    modifier = Modifier.semantics { contentDescription = toggleLabel },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            CapabilityChip(
                when (descriptor.engineFamily) {
                    VoiceEngineFamily.Local -> "Local"
                    VoiceEngineFamily.Cloud -> "Cloud"
                },
            )
            if (descriptor.requiresConfiguration) {
                CapabilityChip(if (row.isConfigured) "BYOK ✓" else "BYOK")
            }
            // Voice count chip — surfaced for non-Azure families (Azure
            // count is roster-dependent and shown in the details modal
            // as the size hint string instead).
            if (!descriptor.requiresConfiguration && !descriptor.isPlaceholder) {
                CapabilityChip(
                    if (row.voiceCount == 1) "1 voice" else "${row.voiceCount} voices",
                )
            }
            if (descriptor.isPlaceholder) {
                CapabilityChip("Coming soon")
            }
        }
        // Bottom row: status + manage-voices link.
        // - For placeholder: just a status line.
        // - For Azure unconfigured: "Configure Azure key →".
        // - For everything else: "Manage voices →".
        if (descriptor.isPlaceholder) {
            Text(
                "Future engine-lib families will surface here.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Status: ${if (row.enabled) "enabled" else "disabled"} · Tap for details",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (descriptor.requiresConfiguration && !row.isConfigured) {
                    TextButton(onClick = onConfigure) {
                        Text("Configure →", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    TextButton(onClick = onManageVoices) {
                        Text("Manage voices →", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceFamilyDetailsContent(
    row: VoiceFamilyRow,
    onManageVoices: () -> Unit,
    onConfigure: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val descriptor: VoiceFamilyDescriptor = row.descriptor
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            descriptor.displayName,
            style = MaterialTheme.typography.headlineSmall,
        )
        if (descriptor.description.isNotBlank()) {
            Text(
                descriptor.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
        DetailRow(label = "Engine", value = when (descriptor.engineFamily) {
            VoiceEngineFamily.Local -> "Local (on-device)"
            VoiceEngineFamily.Cloud -> "Cloud (network)"
        })
        // Voice count: -1 sentinel = "roster not yet loaded" for Azure
        // when configured. 0 = no voices (Azure not configured, or
        // placeholder). Otherwise show the static-catalog count.
        DetailRow(label = "Voices", value = when {
            descriptor.isPlaceholder -> "—"
            row.voiceCount == -1 -> "Live from Azure roster"
            row.voiceCount == 0 && descriptor.requiresConfiguration -> "Configure to load roster"
            else -> "${row.voiceCount}"
        })
        if (descriptor.sizeHint.isNotBlank()) {
            DetailRow(label = "Size", value = descriptor.sizeHint)
        }
        if (descriptor.license.isNotBlank()) {
            DetailRow(label = "License", value = descriptor.license)
        }
        if (descriptor.sourceUrl.isNotBlank()) {
            DetailRow(label = "Source", value = descriptor.sourceUrl)
        }
        if (descriptor.requiresConfiguration) {
            DetailRow(
                label = "Status",
                value = if (row.isConfigured) "Configured" else "Not configured",
            )
        }
        if (row.activeVoiceId != null) {
            DetailRow(label = "Current voice", value = row.activeVoiceId)
        }
        Text(
            "Family id: ${descriptor.id}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(spacing.sm))
        if (!descriptor.isPlaceholder) {
            if (descriptor.requiresConfiguration && !row.isConfigured) {
                TextButton(onClick = onConfigure) {
                    Text("Configure ${descriptor.displayName} →")
                }
            } else {
                TextButton(onClick = onManageVoices) {
                    Text("Manage voices →")
                }
            }
        }
        Spacer(Modifier.height(spacing.md))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.65f),
        )
    }
}
