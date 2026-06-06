package `in`.jphe.storyvox.sync.domain

import android.content.SharedPreferences
import androidx.core.content.edit
import `in`.jphe.storyvox.sync.SyncIds
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.Stamped
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.Syncer
import `in`.jphe.storyvox.sync.crypto.UserDerivedKey
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Source of the user's sync passphrase. Returns null when the user hasn't
 * configured one yet — secrets sync is then a no-op (returns
 * [SyncOutcome.Permanent]; the coordinator surfaces that to the UI so the
 * user can set the passphrase and retry).
 *
 * Modeled as an interface (not `() -> CharArray?`) so Hilt can resolve a
 * `@Named` binding without the function-type quirks. The default binding
 * in `:core-sync` returns null; `:app` (or `:feature` once the Settings UI
 * lands) overrides with a real lookup against EncryptedSharedPreferences
 * or an in-memory unlocked-session cache.
 */
fun interface PassphraseProvider {
    fun get(): CharArray?
}

/**
 * Write-side extension of [PassphraseProvider] — used by the settings UI
 * to let the user set, clear, and check the passphrase. The syncer only
 * needs [PassphraseProvider.get]; this interface is for the Account screen.
 */
interface PassphraseManager : PassphraseProvider {
    fun set(passphrase: CharArray)
    fun clear()
    fun isSet(): Boolean
}

/**
 * Syncs the user's encrypted secrets — API keys (Claude, OpenAI, etc.),
 * Royal Road session cookies, GitHub PAT, Outline tokens, Notion
 * integration token, Discord bot token.
 *
 * NEVER pushes plaintext. The flow is:
 *  1. Snapshot the relevant keys out of [EncryptedSharedPreferences].
 *  2. Serialize to a single JSON map.
 *  3. Encrypt with a [UserDerivedKey] derived from the user's passphrase.
 *  4. Push the AES-GCM envelope (which contains its own salt + iv) as
 *     the blob to InstantDB.
 *
 * If the user hasn't set a passphrase, this syncer is a no-op — secrets
 * stay local-only and the user is warned in Settings that their secrets
 * will need to be re-entered on reinstall. We don't fall back to
 * "push plaintext" — that would silently break the threat model.
 *
 * Conflict resolution: LWW on the envelope. There is no per-secret
 * merging because we can only decrypt the whole bag at once.
 *
 * Key scope (which prefs get synced): everything matching one of
 * [SECRET_KEY_PREFIXES], plus the explicit-name entries in
 * [SECRET_KEY_NAMES]. Hardcoded to avoid accidentally syncing
 * device-local secrets (Tink master-key wrappers shouldn't sync —
 * they're already KeyStore-bound).
 *
 * **Why both a prefix list and a name list?** Most secrets follow a
 * dotted-prefix convention (`llm.openai_key`, `cookie:royalroad`,
 * `github.access_token`) so a prefix match handles them cheaply.
 * But the `:source-notion` and `:source-discord` configs predate
 * that convention — they pushed their secret to
 * EncryptedSharedPreferences under flat keys
 * (`pref_source_notion_token`, `pref_source_discord_token`) that
 * don't share a useful prefix without also catching unrelated
 * preferences. A prefix that's broad enough to match would also
 * match the non-secret toggles like `pref_source_notion_enabled`
 * — so we use an explicit name list for those two.
 */
@Singleton
class SecretsSyncer @Inject constructor(
    private val secrets: SharedPreferences,
    private val backend: InstantBackend,
    @Named(PASSPHRASE_PROVIDER) private val passphraseProvider: PassphraseProvider,
) : Syncer {

    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    override val name: String get() = DOMAIN

    @Serializable
    data class Bundle(val entries: Map<String, String>)

    override suspend fun push(user: SignedInUser): SyncOutcome = reconcile(user)
    override suspend fun pull(user: SignedInUser): SyncOutcome = reconcile(user)

    private suspend fun reconcile(user: SignedInUser): SyncOutcome {
        val passphrase = passphraseProvider.get()
            ?: return SyncOutcome.Permanent(
                "secrets sync requires a sync passphrase — set one in Settings → Account",
            )

        val key = try {
            // Salt is consistent for the user's secrets — we use a
            // deterministic salt derived from the userId so the same
            // user on a new device can decrypt. The InstantDB user id
            // is opaque and stable across sessions.
            UserDerivedKey.deriveKey(passphrase, deterministicSaltFor(user))
        } finally {
            passphrase.fill(' ')
        }

        val local = snapshotLocal()

        val remote = backend.fetch(user, ENTITY, rowId(user)).getOrElse {
            return SyncOutcome.Transient("remote fetch: ${it.message}")
        }

        // Issue #1027 (data loss): a remote row that EXISTS but cannot be
        // decrypted/parsed is NOT the same as "no remote row." The old
        // code collapsed both into `null`, picked local, and then pushed
        // unconditionally — re-encrypting local secrets under the wrong
        // key and silently destroying a perfectly good remote blob that a
        // sibling device (holding the correct passphrase) still needed.
        //
        // We now distinguish the two. A present-but-opaque row means the
        // passphrase doesn't match what the blob was encrypted with (wrong
        // passphrase typed, or rotated on another device) — or, far more
        // rarely, a garbled fetch. Either way we MUST NOT push: bail with a
        // Permanent outcome so the coordinator stops retrying and the UI
        // can prompt for the right passphrase, and leave both the remote
        // blob and local prefs untouched.
        val remoteDecoded: Stamped<Map<String, String>>? = when (val d = remote?.let { decode(it.payload, it.updatedAt, key) }) {
            null -> null // no remote row at all — safe to originate from local
            is DecodeResult.Undecryptable -> return SyncOutcome.Permanent(PASSPHRASE_MISMATCH_MESSAGE)
            is DecodeResult.Decoded -> d.stamped
        }
        val localStamp = Stamped(local, localUpdatedAt(local))

        val merged = when {
            remoteDecoded == null -> localStamp
            local.isEmpty() && remoteDecoded.value.isNotEmpty() -> remoteDecoded
            remoteDecoded.updatedAt > localStamp.updatedAt -> remoteDecoded
            else -> localStamp
        }

        // Apply merged to local if it's newer-than-or-equal-to and differs.
        if (merged !== localStamp && merged.value != local) {
            applyLocal(merged.value)
            secrets.edit { putLong(LAST_TOUCH_KEY, merged.updatedAt) }
        }

        // Push merged back. Reaching here guarantees either there was no
        // remote row, or the remote row decoded cleanly under this key —
        // so this push never clobbers an opaque-but-present remote blob
        // (the #1027 invariant). `merged` was therefore encrypted under the
        // same key the existing remote uses (or originates a fresh blob).
        val envelope = encode(merged.value, key)
        val pushed = backend.upsert(
            user = user,
            entity = ENTITY,
            id = rowId(user),
            payload = envelope,
            updatedAt = merged.updatedAt,
        )
        return if (pushed.isSuccess) {
            SyncOutcome.Ok(merged.value.size)
        } else {
            SyncOutcome.Transient("remote push: ${pushed.exceptionOrNull()?.message}")
        }
    }

    /**
     * The `updatedAt` to stamp on the local secrets bundle.
     *
     * Issue #979: secrets were the one LWW domain that pushed
     * `updatedAt=0`. [LAST_TOUCH_KEY] is only ever written from a
     * *resolved* sync (line: `putLong(LAST_TOUCH_KEY, merged.updatedAt)`)
     * — nothing observes the user typing an API key, so on a device
     * that originated its secrets the key stays unset (`0L`). A blob
     * pushed at `updatedAt=0` can NEVER win LWW ("higher updatedAt
     * wins"), so the remote secrets blob stays pinned at 0 and never
     * pulls down to a second device — even with the right passphrase.
     *
     * Fix: mirror [SettingsSyncer.readLocal] — when we have local
     * secrets but no recorded write time, stamp `System.currentTimeMillis()`
     * and **persist it**. Persisting is what keeps LWW symmetric: the
     * stamp is materialised once and then stable across reconciles, so
     * a genuinely newer remote (real timestamp from another device) can
     * still out-stamp it on the next round. A fresh `?: now()` recomputed
     * every reconcile would make local perpetually "newest" and silently
     * re-break the pull side.
     *
     * Empty local → 0L: a device with no secrets must not out-stamp a
     * real remote blob (the `local.isEmpty()` branch in [reconcile]
     * already prefers remote, but 0L keeps the LWW comparison honest).
     */
    private fun localUpdatedAt(local: Map<String, String>): Long {
        val existing = secrets.getLong(LAST_TOUCH_KEY, 0L)
        if (existing > 0L) return existing
        if (local.isEmpty()) return 0L
        val now = System.currentTimeMillis()
        secrets.edit { putLong(LAST_TOUCH_KEY, now) }
        return now
    }

    private fun snapshotLocal(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val all = secrets.all
        for ((k, v) in all) {
            if (!isSecretKey(k)) continue
            if (v is String) out[k] = v
        }
        return out
    }

    private fun applyLocal(entries: Map<String, String>) {
        secrets.edit {
            // Don't blow away local-only secrets that aren't in the merged
            // bundle (the user might have a key set on this device that
            // they intentionally haven't synced — corner case but cheap
            // to support). We only overwrite keys present in [entries] AND
            // that pass the allowlist check (a forward-compat clause — if
            // a future build pushes a key this build doesn't recognise as
            // a secret, we drop it on the floor rather than blindly
            // writing arbitrary attacker-controlled keys to
            // EncryptedSharedPreferences).
            for ((k, v) in entries) {
                if (isSecretKey(k)) putString(k, v)
            }
        }
    }

    private fun isSecretKey(key: String): Boolean =
        key in SECRET_KEY_NAMES || SECRET_KEY_PREFIXES.any { key.startsWith(it) }

    private fun encode(values: Map<String, String>, key: SecretKey): String {
        // Issue #360 finding 5 (argus): the v1 envelope stored a fresh
        // per-encrypt salt, but the AES key was actually derived from
        // [deterministicSaltFor] — the envelope salt was unused on
        // decrypt. Cross-device decrypt has always worked by both
        // devices independently recomputing the deterministic salt; the
        // envelope's random salt was dead bytes. v2 envelope drops the
        // salt slot — see [UserDerivedKey.envelope] for the design.
        val plaintext = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()), values,
        ).encodeToByteArray()
        val blob = UserDerivedKey.encrypt(key, plaintext)
        return UserDerivedKey.envelope(blob)
    }

    /**
     * Outcome of decoding a remote secrets row that we KNOW exists.
     *
     * Issue #1027: the caller must be able to tell "decoded cleanly" from
     * "row is present but we can't read it" — the latter is the
     * data-loss-causing case and must never lead to a push. ([reconcile]
     * handles "no remote row at all" separately, before calling [decode],
     * via the nullable `remote?.let { … }`.)
     */
    private sealed interface DecodeResult {
        data class Decoded(val stamped: Stamped<Map<String, String>>) : DecodeResult

        /**
         * The envelope failed to parse, failed to decrypt (AES-GCM tag
         * mismatch → wrong passphrase), or decrypted to non-JSON. The row
         * exists on the server and belongs to someone's correct
         * passphrase — overwriting it would be data loss.
         */
        data object Undecryptable : DecodeResult
    }

    private fun decode(envelope: String, updatedAt: Long, key: SecretKey): DecodeResult {
        val parsed = UserDerivedKey.parseEnvelope(envelope) ?: return DecodeResult.Undecryptable
        // AES-GCM authentication failure (wrong key) surfaces as an
        // AEADBadTagException out of cipher.doFinal; any decrypt throw is
        // treated as "can't read this row," never as "no row."
        val plain = runCatching { UserDerivedKey.decrypt(key, parsed.blob) }.getOrNull()
            ?: return DecodeResult.Undecryptable
        val map = runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                plain.decodeToString(),
            )
        }.getOrNull() ?: return DecodeResult.Undecryptable
        return DecodeResult.Decoded(Stamped(value = map, updatedAt = updatedAt))
    }

    /** A 16-byte salt derived from the userId. Stable across devices for
     *  the same user, so a passphrase + userId combo always derives the
     *  same key. SHA-256 → first 16 bytes. */
    private fun deterministicSaltFor(user: SignedInUser): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(("storyvox-secrets:${user.userId}").toByteArray(Charsets.UTF_8)).copyOf(16)
    }

    private fun rowId(user: SignedInUser) = SyncIds.rowUuid(DOMAIN, user.userId)

    companion object {
        const val DOMAIN: String = "secrets"
        private const val ENTITY = "blobs"
        private const val LAST_TOUCH_KEY = "instantdb.secrets_synced_at"

        /**
         * Issue #1027 — user-facing outcome when the remote secrets blob
         * exists but can't be decrypted with this device's passphrase.
         * Surfaced via [SyncOutcome.Permanent] so the coordinator stops
         * retrying and the Account screen can prompt the user to re-enter
         * the passphrase used on their other device — instead of silently
         * overwriting the remote blob (the data-loss bug this fixes).
         */
        const val PASSPHRASE_MISMATCH_MESSAGE: String =
            "secrets: passphrase does not match the synced data — re-enter the passphrase used on your other device"

        /**
         * Hilt @Named qualifier for the passphrase provider binding.
         *
         * Why a named binding and not a plain `() -> CharArray?`: Hilt can't
         * resolve raw function types out of the box, and we want the
         * provider to be swappable per build (a default "no passphrase
         * configured" binding in `:core-sync`, overridable by `:app` /
         * Settings once the UI surface lands).
         */
        const val PASSPHRASE_PROVIDER: String = "secretsSyncerPassphraseProvider"

        /**
         * Allowlist of EncryptedSharedPreferences key prefixes that get
         * pushed to the cloud. Anything not in here AND not in
         * [SECRET_KEY_NAMES] stays local. Prefixes match where the rest
         * of the app stores secrets today:
         *  - `llm.*` for AI provider API keys (OpenAI, Anthropic
         *    direct, Vertex SA JSON, Azure key, Bedrock)
         *  - `cookie:*` for the per-source session cookies (RR,
         *    Outline, etc.)
         *  - `github.*` for GitHub OAuth state (access token, refresh
         *    token)
         *  - `anthropic.*` for Anthropic Teams refresh token
         *  - `outline.*` for Outline API key + base URL bits
         *  - `notion.*` for the Notion integration token
         *
         * Tink master-key wrappers (which are KeyStore-bound and can't
         * decrypt on another device anyway) live under `__androidx_security_*`
         * and are deliberately not listed.
         */
        val SECRET_KEY_PREFIXES: List<String> = listOf(
            "llm.",
            "cookie:",
            "github.",
            "anthropic.",
            "outline.",
            "notion.",
        )

        /**
         * Exact-match allowlist for secrets that don't follow the
         * dotted-prefix convention. Added as a Tier 2 extension in this
         * PR — the Discord bot token uses the legacy `pref_source_*`
         * naming, and broadening the prefix to `pref_source_` would
         * sweep in dozens of non-secret per-source toggles like
         * `pref_source_discord_enabled`.
         *
         * Each name here must be a high-sensitivity token whose leak
         * would be at least as serious as the contents of
         * [SECRET_KEY_PREFIXES]. Don't add non-secrets here — Tier 1
         * settings go through the plaintext [SettingsSyncer] blob.
         */
        val SECRET_KEY_NAMES: Set<String> = setOf(
            "pref_source_discord_token",
            // Issue #454 — Slack Bot Token (xoxb-…). Same posture as
            // the Discord token: high-sensitivity workspace-scoped
            // credential whose leak would expose the user's channel
            // history. Synced through InstantDB to the user's other
            // devices so a token paste on one device propagates.
            "pref_source_slack_token",
            // Issue #457 — Matrix backend access token. Same
            // `pref_source_*` naming convention as Discord; lives in
            // EncryptedSharedPreferences (`storyvox.secrets`) and
            // syncs cross-device so a user who configures Matrix on
            // their phone gets the same homeserver-token pair on
            // their tablet without re-running the access-token
            // create flow on their homeserver.
            "pref_source_matrix_token",
        )
    }
}
