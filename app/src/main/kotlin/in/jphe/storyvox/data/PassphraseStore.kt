package `in`.jphe.storyvox.data

import android.content.SharedPreferences
import `in`.jphe.storyvox.sync.domain.PassphraseManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EncryptedSharedPreferences-backed passphrase store for secrets sync.
 *
 * The passphrase is stored under [PREF_KEY] in the `storyvox.secrets`
 * bag (AES-GCM at rest via Android KeyStore). This key deliberately
 * falls outside [SecretsSyncer.SECRET_KEY_PREFIXES] and
 * [SecretsSyncer.SECRET_KEY_NAMES] — the passphrase itself must NOT
 * be synced to InstantDB (that would be circular: the passphrase
 * encrypts the bag that would contain the passphrase).
 */
@Singleton
class PassphraseStore @Inject constructor(
    private val secrets: SharedPreferences,
) : PassphraseManager {

    override fun get(): CharArray? {
        val stored = secrets.getString(PREF_KEY, null)
        return if (stored.isNullOrEmpty()) null else stored.toCharArray()
    }

    override fun set(passphrase: CharArray) {
        secrets.edit().putString(PREF_KEY, String(passphrase)).apply()
    }

    override fun clear() {
        secrets.edit().remove(PREF_KEY).apply()
    }

    override fun isSet(): Boolean = secrets.contains(PREF_KEY)

    companion object {
        internal const val PREF_KEY = "sync.passphrase"
    }
}
