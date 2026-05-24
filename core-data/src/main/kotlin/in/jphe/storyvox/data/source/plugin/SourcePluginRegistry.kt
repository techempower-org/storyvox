package `in`.jphe.storyvox.data.source.plugin

import `in`.jphe.storyvox.data.source.SourceIds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plugin-seam Phase 1 (#384) — runtime registry of every
 * `@SourcePlugin`-annotated `FictionSource`.
 *
 * Singleton. Hilt injects the multibinding `Set<SourcePluginDescriptor>`
 * populated by the KSP-generated `@Provides @IntoSet` factories (one
 * per `@SourcePlugin`-annotated class). The registry exposes a stable
 * sort order — alphabetical by [SourcePluginDescriptor.displayName]
 * within each [SourceCategory], categories in their enum-declared
 * order — so the Browse chip row and Settings auto-section render
 * deterministically across builds.
 *
 * ## Phase 1 invariants
 *
 * - Phase 1 ships with `:source-kvmr` as the only `@SourcePlugin`-
 *   annotated backend, so the set has exactly one descriptor. The
 *   other 11 backends are still resolved through the legacy
 *   `Map<String, FictionSource>` and not yet visible here. That's
 *   intentional — Phase 2 migrates them one-by-one and each
 *   migration is a small, isolated diff.
 * - The legacy `SourceIds` constants still apply: an `@SourcePlugin`
 *   class's `id` and the legacy `SourceIds.KVMR`-style constant are
 *   the same string by convention. Don't drift them apart.
 *
 * ## Test hook
 *
 * Tests construct an instance directly with a `Set<SourcePluginDescriptor>`
 * built from fake `FictionSource` instances — the constructor is
 * `@Inject` but not `internal`, so a unit test in `:core-data` can
 * `SourcePluginRegistry(setOf(descriptor))` without Hilt.
 *
 * @property all Every registered plugin, in the deterministic display
 *  order described above. Empty when no `@SourcePlugin`-annotated
 *  classes exist on the classpath (e.g. instrumentation tests that
 *  link only `:core-data`).
 */
@Singleton
class SourcePluginRegistry @Inject constructor(
    descriptors: Set<@JvmSuppressWildcards SourcePluginDescriptor>,
) {

    /** All registered plugins, sorted: Notion pinned first (TechEmpower's
     *  Notion content is the default landing surface — JP design call,
     *  2026-05-14), then by category ordinal, then by displayName
     *  alphabetically. Stable across builds — the chip row / Settings
     *  list order doesn't churn. */
    val all: List<SourcePluginDescriptor> = descriptors
        .sortedWith(
            compareBy<SourcePluginDescriptor> { if (it.id == SourceIds.NOTION_TECHEMPOWER) 0 else 1 }
                .thenBy { it.category.ordinal }
                .thenBy { it.displayName.lowercase() },
        )

    /** Alias for [all] used by Phase 3 (#384) call sites that iterate
     *  the registry rather than the deleted `BrowseSourceKey` enum.
     *  The shape (sorted list of descriptors) is the same; the name
     *  reads better at iteration sites that don't otherwise mention
     *  "registry". */
    val descriptors: List<SourcePluginDescriptor> get() = all

    init {
        // Plugin-seam Phase 2 (#384) — fail fast at app startup if two
        // `@SourcePlugin` annotations declared the same id. Hilt would
        // otherwise happily wire both factories into the multibinding
        // set and consumers would see a non-deterministic which-one-
        // wins lookup. The check runs in the Singleton-scoped graph
        // build, so a duplicate is surfaced once at process start.
        val byId = all.groupBy { it.id }
        val duplicates = byId.filterValues { it.size > 1 }
        if (duplicates.isNotEmpty()) {
            val detail = duplicates.entries.joinToString("; ") { (id, descriptors) ->
                "$id × ${descriptors.size} (${descriptors.joinToString { it.displayName }})"
            }
            error(
                "SourcePluginRegistry: duplicate @SourcePlugin ids detected — $detail. " +
                    "Each plugin's id must be unique; consult SourceIds for the canonical list.",
            )
        }
    }

    /** Plugins registered under [category], preserving the
     *  display-order sort. Returns an empty list when no plugins in
     *  the category are registered. */
    fun byCategory(category: SourceCategory): List<SourcePluginDescriptor> =
        all.filter { it.category == category }

    /** Plugin descriptor for [id], or null when no plugin with that
     *  id is registered. */
    fun byId(id: String): SourcePluginDescriptor? = all.firstOrNull { it.id == id }

    /** True when at least one plugin is registered. Phase 1 always
     *  returns true (KVMR is the worked example); Phase 2's migrations
     *  monotonically grow the set. */
    val isNotEmpty: Boolean get() = all.isNotEmpty()

    /** All registered plugin ids, in display order. Convenience for
     *  the settings migration shim that seeds
     *  `UiSettings.sourcePluginsEnabled` from each plugin's
     *  [SourcePluginDescriptor.defaultEnabled]. */
    val ids: List<String> get() = all.map { it.id }
}
