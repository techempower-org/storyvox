package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.BuildConfig
import `in`.jphe.storyvox.data.log.DebugLog
import `in`.jphe.storyvox.playback.cache.PcmCache
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Issue #860 — wipe the PCM cache directory on first launch after an
 * app version upgrade.
 *
 * The PCM cache key ([PcmCacheKey][in.jphe.storyvox.playback.cache.PcmCacheKey])
 * does not include `versionCode` or any app-version discriminator, so
 * entries written by v0.5.97 are served to v0.5.98 even when silence
 * trimming, sample-rate handling, or chunker behaviour changed across
 * the boundary. Combined with [CHUNKER_VERSION] discipline being
 * fallible (#859), stale entries can otherwise persist across releases
 * until a user manually clears cache.
 *
 * Strategy: on every cold start, compare the persisted
 * `pref_last_seen_version_code` to [BuildConfig.VERSION_CODE]. If they
 * differ (fresh install, upgrade, or downgrade — any version change),
 * call [PcmCache.clearAll] and then write the new versionCode. The
 * wipe is fast — just `File.delete()` over the cache root — and only
 * happens once per upgrade.
 *
 * Fresh installs (no stored versionCode) also trigger a wipe; this is
 * a no-op when the cache directory is empty, so it costs nothing.
 *
 * Failure mode: if the cache wipe throws (e.g. file held open by the
 * OS), the versionCode is still recorded — the next upgrade will try
 * again, but a stuck wipe shouldn't pin the user at the old
 * versionCode forever (which would re-attempt the wipe on every cold
 * start, slowing launch).
 */
@Singleton
class VersionUpgradeHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pcmCache: PcmCache,
) {

    suspend fun runIfUpgraded() {
        val store = context.settingsDataStore
        val stored = store.data.map { it[LAST_SEEN_VERSION_CODE_KEY] }.first()
        val current = BuildConfig.VERSION_CODE
        if (stored == current) return

        DebugLog.i(TAG) {
            "version upgrade detected stored=$stored current=$current — wiping PCM cache"
        }
        runCatching { pcmCache.clearAll() }
            .onFailure { e -> DebugLog.i(TAG) { "PCM cache wipe failed: $e" } }

        store.edit { prefs -> prefs[LAST_SEEN_VERSION_CODE_KEY] = current }
    }

    private companion object {
        const val TAG = "VersionUpgradeHandler"
        val LAST_SEEN_VERSION_CODE_KEY = intPreferencesKey("pref_last_seen_version_code")
    }
}
