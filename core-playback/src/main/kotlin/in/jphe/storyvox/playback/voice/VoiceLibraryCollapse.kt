package `in`.jphe.storyvox.playback.voice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Per-engine collapse memory for the Voice Library (#130).
 *
 * The library renders engine sub-headers (Piper / Kokoro) under the
 * Installed and Available section headers. Tapping an engine sub-header
 * collapses the tier sub-headers and voice rows beneath it; tapping
 * again expands them. This store persists those toggles across app
 * restarts.
 *
 * **Default policy** — drives the meaning of "absent from the stored set":
 * - Installed engines default to **expanded** (a user with installed
 *   voices wants to see them at a glance — the section is small and
 *   already-curated).
 * - Available engines default to **collapsed** (the long tail of 50+
 *   voices is what makes scrolling painful; first-paint should hide
 *   them by default and let the user opt in per engine).
 *
 * The on-disk schema stores **flipped-from-default** keys only — i.e.
 * an entry is present in the set iff its current state differs from
 * the default for its section. This keeps the persisted set tiny in
 * the common case (most users won't toggle anything) and means a
 * fresh-install user with an empty set sees exactly the default
 * layout. See [isCollapsed].
 *
 * Schema-evolution note: the DataStore name is suffixed `_v1`. If the
 * default policy ever flips, mint a `_v2` rather than reinterpreting
 * existing `_v1` entries — the meaning of the stored set is tied to
 * the defaults at write time.
 */
private val Context.voiceLibraryCollapseStore: DataStore<Preferences> by preferencesDataStore(
    name = "voice_library_collapsed_engines_v1",
)

private object CollapseKeys {
    /** String set of engine keys whose state is **flipped** from the
     *  default for their section. Each entry is `"<section>:<engine>"`,
     *  e.g. `"installed:Piper"` or `"available:Kokoro"`. */
    val FLIPPED = stringSetPreferencesKey("flipped_engine_keys")
}

/** Section discriminator for the collapse store. Starred has no engine
 *  sub-headers (one flat list), so it isn't represented here. */
enum class VoiceLibrarySection { Installed, Available }

/** Composite key for an engine sub-header within a section. The
 *  collapse store keys on the rendered string `"<section>:<engine>"`
 *  so the on-disk format stays human-readable in case anyone ever
 *  needs to inspect the prefs file. */
data class EngineKey(val section: VoiceLibrarySection, val engine: VoiceEngineId) {
    /** On-disk key — keep stable across refactors. */
    fun storeKey(): String = "${section.name.lowercase()}:${engine.name}"
}

/** Mirror of the feature-module `VoiceEngine` enum, lifted into core
 *  so the collapse store can key on it without a feature dependency.
 *  Keep in lockstep with [VoiceEngine] in
 *  `feature/.../voicelibrary/VoiceLibraryViewModel.kt`. */
enum class VoiceEngineId { Piper, Kokoro, Kitten, Azure, SystemTts }

@Singleton
class VoiceLibraryCollapse private constructor(
    private val store: DataStore<Preferences>,
) {
    /** Hilt entry point. Uses [Context.voiceLibraryCollapseStore]. */
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.voiceLibraryCollapseStore)

    /** Reactive set of [EngineKey] entries currently flipped from
     *  their section default. Empty on first launch. */
    val flippedKeys: Flow<Set<String>> = store.data.map { prefs ->
        prefs[CollapseKeys.FLIPPED].orEmpty()
    }

    /** Idempotent toggle — flips the (section, engine) pair's state
     *  away from / back to its default. Use [isCollapsed] downstream
     *  to derive the rendered state from the flipped set. */
    suspend fun toggle(key: EngineKey) {
        store.edit { prefs ->
            val current = prefs[CollapseKeys.FLIPPED].orEmpty()
            val storeKey = key.storeKey()
            prefs[CollapseKeys.FLIPPED] = if (storeKey in current) {
                current - storeKey
            } else {
                current + storeKey
            }
        }
    }

    companion object {
        /** Derive the rendered "collapsed" state for an engine sub-header
         *  given the persisted flipped-keys set. Encapsulates the
         *  default policy described in the class kdoc:
         *  Installed → default expanded → flipped means collapsed.
         *  Available → default collapsed → flipped means expanded.
         *  Returning a Boolean keeps the call site clean — composables
         *  can `if (isCollapsed) skip rows`. */
        fun isCollapsed(key: EngineKey, flipped: Set<String>): Boolean {
            val isFlipped = key.storeKey() in flipped
            val defaultExpanded = when (key.section) {
                VoiceLibrarySection.Installed -> true
                VoiceLibrarySection.Available -> false
            }
            // Collapsed = default-expanded XOR flipped.
            //   Installed: expanded by default → flipped means collapsed.
            //   Available: collapsed by default → flipped means expanded;
            //     i.e. NOT flipped → collapsed.
            return defaultExpanded == isFlipped
        }

        /** Test-only factory. */
        fun forTesting(store: DataStore<Preferences>): VoiceLibraryCollapse =
            VoiceLibraryCollapse(store)
    }
}
