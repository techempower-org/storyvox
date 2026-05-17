package `in`.jphe.storyvox.feature.settings.plugins

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.VoiceCatalog
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptor
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds
import `in`.jphe.storyvox.playback.voice.VoiceFamilyRegistry
import `in`.jphe.storyvox.playback.voice.voiceFamilyId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Plugin manager (#404) — filter chips for the on/off/all view state.
 *
 * - [On]: only plugins the user has enabled (or default-enabled and
 *   not explicitly disabled).
 * - [Off]: only plugins the user has explicitly disabled (or that
 *   default to off and haven't been flipped on).
 * - [All]: every registered plugin.
 */
enum class PluginFilterChip { On, Off, All }

/**
 * Plugin manager (#404) — UI row for a single plugin.
 *
 * Wraps a [SourcePluginDescriptor] with the resolved enabled state so
 * the screen doesn't have to re-look-up the per-id boolean during
 * rendering. The descriptor lives in `:core-data`; this is the
 * `:feature/settings/plugins` projection.
 */
@Immutable
data class PluginManagerRow(
    val descriptor: SourcePluginDescriptor,
    val enabled: Boolean,
)

/**
 * Plugin manager (#501) — UI row for a single voice family card.
 *
 * Twin of [PluginManagerRow] for the Voice bundles section. Wraps a
 * [VoiceFamilyDescriptor] with:
 * - the resolved enabled state (from `voiceFamiliesEnabled` settings
 *   with `defaultEnabled` as fallback),
 * - the live voice count from [VoiceCatalog] (Azure count reflects the
 *   roster fetched at runtime; 0 when not yet loaded),
 * - the configuration status (for BYOK families like Azure).
 *
 * The current-selection field is left as an id-only string so the
 * details modal can render "Currently using: Lessac (en_US, High)"
 * without making the row depend on the picker's rich `UiVoiceInfo`.
 */
@Immutable
data class VoiceFamilyRow(
    val descriptor: VoiceFamilyDescriptor,
    val enabled: Boolean,
    val voiceCount: Int,
    val isConfigured: Boolean,
    /** Active voice id for this family, or null when the user's
     *  current default voice belongs to a different family. */
    val activeVoiceId: String?,
)

/** Plugin manager (#404) — Compose-facing UI state. */
@Immutable
data class PluginManagerUiState(
    /** All plugins, regardless of filter/search — the section
     *  renderer slices this by category and then filters by the
     *  search/chip state. */
    val plugins: List<PluginManagerRow> = emptyList(),
    /** Plugin-seam Phase 4 (#501) — all voice families, regardless
     *  of filter/search. The screen filters via [filterVoiceFamilies]. */
    val voiceFamilies: List<VoiceFamilyRow> = emptyList(),
    val searchQuery: String = "",
    val filterChip: PluginFilterChip = PluginFilterChip.All,
)

/**
 * Plugin manager (#404, #501) — ViewModel.
 *
 * Iterates `SourcePluginRegistry.descriptors` and
 * `VoiceFamilyRegistry.descriptors`, joining each with the user's
 * `sourcePluginsEnabled` / `voiceFamiliesEnabled` maps (with each
 * descriptor's `defaultEnabled` as fallback for unseeded ids). The
 * Compose tree filters by category + search + chip state at render
 * time; the state object stays a single source of truth and the
 * screen handles the three filter axes without needing further
 * ViewModel state.
 *
 * Voice family rows additionally project:
 * - voice count: `VoiceCatalog.voices` filtered by engine family, plus
 *   the live Azure roster count when the family is Azure.
 * - configuration status: derived from `UiSettings.azure.isConfigured`
 *   for the Azure family; always true for local families.
 * - active voice id: resolved by mapping the user's `defaultVoiceId`
 *   to its `EngineType` via `VoiceCatalog.byId`, then matching the
 *   family id.
 */
@HiltViewModel
class PluginManagerViewModel @Inject constructor(
    private val registry: SourcePluginRegistry,
    private val voiceFamilyRegistry: VoiceFamilyRegistry,
    private val settings: SettingsRepositoryUi,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _filterChip = MutableStateFlow(PluginFilterChip.All)

    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    val filterChip: StateFlow<PluginFilterChip> = _filterChip.asStateFlow()

    private val pluginsFlow: kotlinx.coroutines.flow.Flow<List<PluginManagerRow>> =
        settings.settings.map { s ->
            registry.descriptors.map { descriptor ->
                val explicit = s.sourcePluginsEnabled[descriptor.id]
                val effective = explicit ?: descriptor.defaultEnabled
                PluginManagerRow(descriptor = descriptor, enabled = effective)
            }
        }

    private val voiceFamiliesFlow: kotlinx.coroutines.flow.Flow<List<VoiceFamilyRow>> =
        settings.settings.map { s ->
            // Live voice count from the static catalog, grouped by
            // family id. Azure entries from the static catalog are
            // empty (live roster lives in AzureVoiceRoster), so the
            // Azure count is derived separately below.
            val staticCountsByFamily = VoiceCatalog.voices
                .groupBy { it.engineType.voiceFamilyId() }
                .mapValues { it.value.size }

            val activeId = s.defaultVoiceId
            val activeFamily = activeId
                ?.let { VoiceCatalog.byId(it) }
                ?.engineType
                ?.voiceFamilyId()

            voiceFamilyRegistry.descriptors.map { descriptor ->
                val explicit = s.voiceFamiliesEnabled[descriptor.id]
                val effective = explicit ?: descriptor.defaultEnabled
                val count = when (descriptor.id) {
                    // Azure voices live in the runtime roster, not in
                    // the static VoiceCatalog. Surface the configured
                    // BYOK state as a count proxy: 0 = not yet
                    // configured, ~330 = Dragon HD eastus rendered
                    // count. The Plugin Manager details modal shows
                    // the size hint string regardless, so a "—" count
                    // for "unknown until first roster fetch" is fine.
                    VoiceFamilyIds.AZURE -> if (s.azure.isConfigured) -1 else 0
                    // #676 — System TTS voices live in the OS-driven
                    // SystemTtsVoiceRoster (runtime enumeration), not
                    // VoiceCatalog.voices. Surface -1 as the "unknown
                    // until first roster snapshot" sentinel so the
                    // Plugin Manager renders an em-dash rather than
                    // "0 voices" before enumeration completes. The
                    // details modal's size hint string handles the rest.
                    VoiceFamilyIds.SYSTEM_TTS -> -1
                    else -> staticCountsByFamily[descriptor.id] ?: 0
                }
                val isConfigured = when (descriptor.id) {
                    VoiceFamilyIds.AZURE -> s.azure.isConfigured
                    else -> true
                }
                VoiceFamilyRow(
                    descriptor = descriptor,
                    enabled = effective,
                    voiceCount = count,
                    isConfigured = isConfigured,
                    activeVoiceId = if (activeFamily == descriptor.id) activeId else null,
                )
            }
        }

    val uiState: StateFlow<PluginManagerUiState> = combine(
        pluginsFlow, voiceFamiliesFlow, _searchQuery, _filterChip,
    ) { plugins, families, query, chip ->
        PluginManagerUiState(
            plugins = plugins,
            voiceFamilies = families,
            searchQuery = query,
            filterChip = chip,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PluginManagerUiState())

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setFilterChip(chip: PluginFilterChip) { _filterChip.value = chip }

    fun togglePlugin(id: String, enabled: Boolean) {
        viewModelScope.launch { settings.setSourcePluginEnabled(id, enabled) }
    }

    /** Plugin-seam Phase 4 (#501) — twin of [togglePlugin] for the
     *  Voice bundles section. */
    fun toggleVoiceFamily(id: String, enabled: Boolean) {
        viewModelScope.launch { settings.setVoiceFamilyEnabled(id, enabled) }
    }
}

/**
 * Plugin manager (#404) — applies the search + chip filter to a list
 * of plugins. Pulled out into a free function so the screen can call
 * it once per section.
 *
 * Search is a substring match on `displayName`, `description`, and
 * `id` (so a user looking for "wiki" matches Wikipedia and Wikisource
 * but not "Wikimedia" in another field; the substring matches at
 * least one of the three). Case-insensitive.
 *
 * Filter chip:
 * - [PluginFilterChip.On] keeps enabled plugins.
 * - [PluginFilterChip.Off] keeps disabled plugins.
 * - [PluginFilterChip.All] keeps everything.
 */
fun filterPlugins(
    plugins: List<PluginManagerRow>,
    query: String,
    chip: PluginFilterChip,
): List<PluginManagerRow> {
    val byChip = when (chip) {
        PluginFilterChip.On -> plugins.filter { it.enabled }
        PluginFilterChip.Off -> plugins.filterNot { it.enabled }
        PluginFilterChip.All -> plugins
    }
    if (query.isBlank()) return byChip
    val needle = query.trim().lowercase()
    return byChip.filter { row ->
        row.descriptor.displayName.lowercase().contains(needle) ||
            row.descriptor.description.lowercase().contains(needle) ||
            row.descriptor.id.lowercase().contains(needle)
    }
}

/**
 * Plugin manager (#501) — twin of [filterPlugins] for voice family
 * rows. Same search/chip semantics; matches on displayName,
 * description, and id.
 *
 * Placeholder rows (e.g. "VoxSherpa upstreams") are excluded from
 * the On / Off filters since they have no toggle; they only surface
 * under [PluginFilterChip.All].
 */
fun filterVoiceFamilies(
    families: List<VoiceFamilyRow>,
    query: String,
    chip: PluginFilterChip,
): List<VoiceFamilyRow> {
    val byChip = when (chip) {
        PluginFilterChip.On -> families.filter { it.enabled && !it.descriptor.isPlaceholder }
        PluginFilterChip.Off -> families.filter { !it.enabled && !it.descriptor.isPlaceholder }
        PluginFilterChip.All -> families
    }
    if (query.isBlank()) return byChip
    val needle = query.trim().lowercase()
    return byChip.filter { row ->
        row.descriptor.displayName.lowercase().contains(needle) ||
            row.descriptor.description.lowercase().contains(needle) ||
            row.descriptor.id.lowercase().contains(needle)
    }
}

/**
 * Plugin manager (#404) — group [plugins] into the manager's three
 * section buckets: Fiction sources (every Text + Ebook + Database +
 * Other category), Audio streams (AudioStream category), and the
 * Voice bundles section.
 *
 * As of #501 the Voice bundles bucket is populated by
 * [VoiceFamilyRegistry] independently — voice families are not
 * `@SourcePlugin`-annotated. The `voiceBundles` field here stays
 * empty and the screen renders [PluginManagerUiState.voiceFamilies]
 * directly. The field is preserved on [PluginManagerSections] for
 * test stability.
 */
fun groupPluginsForManager(plugins: List<PluginManagerRow>): PluginManagerSections {
    val fiction = plugins.filter { it.descriptor.category != SourceCategory.AudioStream }
    val audio = plugins.filter { it.descriptor.category == SourceCategory.AudioStream }
    return PluginManagerSections(fiction = fiction, audio = audio, voiceBundles = emptyList())
}

/** Plugin manager (#404) — three category sections for rendering. */
@Immutable
data class PluginManagerSections(
    val fiction: List<PluginManagerRow>,
    val audio: List<PluginManagerRow>,
    val voiceBundles: List<PluginManagerRow>,
)
