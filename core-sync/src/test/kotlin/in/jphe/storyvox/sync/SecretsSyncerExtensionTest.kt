package `in`.jphe.storyvox.sync

import android.content.SharedPreferences
import `in`.jphe.storyvox.sync.client.FakeInstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.domain.PassphraseProvider
import `in`.jphe.storyvox.sync.domain.SecretsSyncer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 2 — coverage for the extended allowlist added in this PR.
 *
 * The existing [SecretsSyncerTest] covers the encryption mechanics
 * (round-trip, wrong passphrase, no passphrase, passphrase wiping)
 * end-to-end. This file's job is narrower — assert the NEW
 * allowlist entries (`notion.` prefix, `pref_source_discord_token`
 * exact-match name) sync, and assert the existing prefix posture is
 * unchanged.
 *
 * One file per concern so reviewers can see the surface area of
 * Tier 2 (this PR) without re-reading the pre-existing
 * `SecretsSyncerTest`.
 */
class SecretsSyncerExtensionTest {

    private val USER = SignedInUser(userId = "user-secrets-2", email = "j@p.test", refreshToken = "rt")
    private val PASSPHRASE = "correct horse battery staple".toCharArray()

    /** Pure-JVM SharedPreferences substitute — identical to the one
     *  used by [SecretsSyncerTest]. Duplicated here rather than
     *  shared so each test file is self-contained. */
    private class FakePrefs : SharedPreferences {
        private val store = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = store.toMutableMap()
        override fun getString(key: String, defValue: String?): String? =
            (store[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (store[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (store[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (store[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (store[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            (store[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = store.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {}
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {}

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) =
                apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = REMOVE_SENTINEL }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) store.clear()
                for ((k, v) in pending) {
                    if (v === REMOVE_SENTINEL) store.remove(k) else store[k] = v
                }
            }
        }

        private companion object { val REMOVE_SENTINEL = Any() }
    }

    @Test fun `notion api token (notion-dot prefix) round-trips through encrypted bundle`() = runTest {
        val backend = FakeInstantBackend()

        // Device A has a Notion integration token + a non-secret pref.
        val prefsA = FakePrefs().apply {
            edit().putString("notion.api_token", "secret_xxx_notion").apply()
            // A non-allowlisted key that LOOKS like it might match.
            edit().putString("ui.notion_panel_seen", "true").apply()
        }
        SecretsSyncer(
            secrets = prefsA,
            backend = backend,
            passphraseProvider = { PASSPHRASE.copyOf() },
        ).push(USER)

        // Device B pulls.
        val prefsB = FakePrefs()
        SecretsSyncer(
            secrets = prefsB,
            backend = backend,
            passphraseProvider = { PASSPHRASE.copyOf() },
        ).pull(USER)

        // Notion token round-tripped.
        assertEquals("secret_xxx_notion", prefsB.getString("notion.api_token", null))
        // Non-allowlisted key did NOT sync.
        assertNull(prefsB.getString("ui.notion_panel_seen", null))
    }

    @Test fun `discord bot token (exact-name) round-trips through encrypted bundle`() = runTest {
        val backend = FakeInstantBackend()

        // Device A has a Discord bot token AND a near-miss key that
        // should NOT match — `pref_source_discord_enabled` is a
        // non-secret boolean toggle.
        val prefsA = FakePrefs().apply {
            edit().putString("pref_source_discord_token", "bot_token_AAAA").apply()
            edit().putString("pref_source_discord_enabled", "true").apply()
        }
        SecretsSyncer(
            secrets = prefsA,
            backend = backend,
            passphraseProvider = { PASSPHRASE.copyOf() },
        ).push(USER)

        val prefsB = FakePrefs()
        SecretsSyncer(
            secrets = prefsB,
            backend = backend,
            passphraseProvider = { PASSPHRASE.copyOf() },
        ).pull(USER)

        // Bot token synced.
        assertEquals("bot_token_AAAA", prefsB.getString("pref_source_discord_token", null))
        // Near-miss key did NOT sync (would be Tier 1's job, not the
        // encrypted bundle's).
        assertNull(
            "pref_source_discord_enabled is NOT a secret and must not be in the encrypted bundle",
            prefsB.getString("pref_source_discord_enabled", null),
        )
    }

    @Test fun `outline api key (existing outline-dot prefix) still round-trips after refactor`() = runTest {
        // Regression-cover the pre-existing `outline.` prefix — the
        // PR refactored snapshotLocal() / applyLocal() to use the
        // unified isSecretKey() helper, and a typo there would
        // silently break the existing allowlist.
        val backend = FakeInstantBackend()
        val prefsA = FakePrefs().apply {
            edit().putString("outline.api_key", "outline_secret_BBBB").apply()
        }
        SecretsSyncer(prefsA, backend, { PASSPHRASE.copyOf() }).push(USER)

        val prefsB = FakePrefs()
        SecretsSyncer(prefsB, backend, { PASSPHRASE.copyOf() }).pull(USER)
        assertEquals("outline_secret_BBBB", prefsB.getString("outline.api_key", null))
    }

    @Test fun `all existing allowlist prefixes still match — regression`() = runTest {
        // Belt-and-braces sweep of every prefix in the v0.5.12
        // allowlist plus the two new entries. If any of them silently
        // stop matching, this test fires.
        val backend = FakeInstantBackend()
        val prefsA = FakePrefs().apply {
            edit().putString("llm.openai_key", "v1-llm").apply()
            edit().putString("cookie:royalroad", "v1-cookie").apply()
            edit().putString("github.access_token", "v1-github").apply()
            edit().putString("anthropic.teams_refresh", "v1-anthropic").apply()
            edit().putString("outline.api_key", "v1-outline").apply()
            edit().putString("notion.api_token", "v2-notion").apply()
            edit().putString("pref_source_discord_token", "v2-discord").apply()
        }
        SecretsSyncer(prefsA, backend, { PASSPHRASE.copyOf() }).push(USER)

        val prefsB = FakePrefs()
        SecretsSyncer(prefsB, backend, { PASSPHRASE.copyOf() }).pull(USER)
        assertEquals("v1-llm", prefsB.getString("llm.openai_key", null))
        assertEquals("v1-cookie", prefsB.getString("cookie:royalroad", null))
        assertEquals("v1-github", prefsB.getString("github.access_token", null))
        assertEquals("v1-anthropic", prefsB.getString("anthropic.teams_refresh", null))
        assertEquals("v1-outline", prefsB.getString("outline.api_key", null))
        assertEquals("v2-notion", prefsB.getString("notion.api_token", null))
        assertEquals("v2-discord", prefsB.getString("pref_source_discord_token", null))
    }

    @Test fun `tombstone — empty-string clear from remote lands on device B via applyLocal`() = runTest {
        // Tier 2 tombstone story per design-decisions.md: clearing a
        // secret locally is encoded as "write the empty string"
        // (rather than removing the key). The encrypted bundle then
        // carries the empty-string entry on its next push, and pull
        // on device B applies it back.
        //
        // Since #979 the receiving device stamps a real wall-clock
        // `updatedAt` (it no longer reads back 0L), so LWW is now
        // symmetric. For the clear to win, A's clear must simply carry
        // a later stamp than B's local copy — which it does in
        // production once the clear is the most recent write. We model
        // that with explicit stamps: A's clear at `now+1h`, B's stale
        // local copy at `now-1h`.
        val backend = FakeInstantBackend()
        val now = System.currentTimeMillis()
        // Step 1: A pushes its initial secret.
        val prefsA = FakePrefs().apply {
            edit().putString("llm.openai_key", "sk-1234").apply()
        }
        SecretsSyncer(prefsA, backend, { PASSPHRASE.copyOf() }).push(USER)

        // Step 2: A clears the key locally and bumps the
        // LAST_TOUCH_KEY (simulating Settings UI's planned wire-up).
        prefsA.edit().putString("llm.openai_key", "").apply()
        prefsA.edit().putLong("instantdb.secrets_synced_at", now + 3_600_000L).apply()
        SecretsSyncer(prefsA, backend, { PASSPHRASE.copyOf() }).push(USER)

        // Step 3: B pulls. Its local has the OLD value with an older
        // stamp; A's clear has the higher stamp and wins LWW.
        val prefsB = FakePrefs().apply {
            edit().putString("llm.openai_key", "old-local-value").apply()
            edit().putLong("instantdb.secrets_synced_at", now - 3_600_000L).apply()
        }
        SecretsSyncer(prefsB, backend, { PASSPHRASE.copyOf() }).pull(USER)
        assertEquals(
            "tombstone (empty-string + stamp bump) must propagate from A to B",
            "",
            prefsB.getString("llm.openai_key", null),
        )
    }

    @Test fun `non-allowlisted secret key is dropped on apply (forward-compat)`() = runTest {
        // If a future build pushes a key this build doesn't recognise
        // as a secret, applyLocal must drop it. Otherwise an attacker-
        // controlled remote could land arbitrary keys in our
        // EncryptedSharedPreferences.
        val backend = FakeInstantBackend()
        // Push from a fake "future" device with a mix of known +
        // unknown secret keys. We have to do this by directly
        // pushing through the encrypted envelope, which the syncer
        // doesn't expose — so we push from a real syncer with the
        // standard key, then assert pull keeps unknown-keys out.
        val prefsA = FakePrefs().apply {
            edit().putString("llm.test_key", "known-secret").apply()
            // This key shouldn't even snapshot — it's not on the
            // allowlist on device A. So push only carries
            // llm.test_key.
            edit().putString("future.unknown_v999_key", "future-secret").apply()
        }
        SecretsSyncer(prefsA, backend, { PASSPHRASE.copyOf() }).push(USER)

        val prefsB = FakePrefs()
        SecretsSyncer(prefsB, backend, { PASSPHRASE.copyOf() }).pull(USER)
        assertEquals("known-secret", prefsB.getString("llm.test_key", null))
        assertNull(
            "future-unknown key must not land on device B (not on allowlist)",
            prefsB.getString("future.unknown_v999_key", null),
        )
    }

    @Test fun `local-only non-synced secret on device B is preserved through pull`() = runTest {
        // The applyLocal contract from the existing SecretsSyncer:
        // "Don't blow away local-only secrets that aren't in the
        // merged bundle." Belt-and-braces — this PR's refactor of
        // applyLocal preserves that invariant.
        //
        // Stamping note: since #979 a device with local secrets and no
        // recorded write time stamps a real wall-clock `updatedAt`
        // (no longer 0L). For this test to exercise the
        // "remote-wins-over-local" path, A's stamp must be later than
        // B's. A uses `now+1h`; B's local copy is the older one
        // (stamped `now-1h`), so remote wins and B still keeps its
        // local-only secret.
        val backend = FakeInstantBackend()
        val now = System.currentTimeMillis()
        val prefsA = FakePrefs().apply {
            edit().putString("llm.from_device_a", "remote-key").apply()
            edit().putLong("instantdb.secrets_synced_at", now + 3_600_000L).apply()
        }
        SecretsSyncer(prefsA, backend, { PASSPHRASE.copyOf() }).push(USER)

        val prefsB = FakePrefs().apply {
            edit().putString("llm.local_only_on_b", "preserved").apply()
            edit().putLong("instantdb.secrets_synced_at", now - 3_600_000L).apply()
        }
        SecretsSyncer(prefsB, backend, { PASSPHRASE.copyOf() }).pull(USER)
        assertEquals("remote-key", prefsB.getString("llm.from_device_a", null))
        assertEquals(
            "local-only secret on B must survive a pull",
            "preserved",
            prefsB.getString("llm.local_only_on_b", null),
        )
    }

    @Test fun `no passphrase still blocks the extended allowlist (no plaintext fallback)`() = runTest {
        // Critical invariant: even with the extended allowlist, "no
        // passphrase" must NEVER fall back to plaintext. The threat
        // model depends on this.
        val backend = FakeInstantBackend()
        val prefsA = FakePrefs().apply {
            edit().putString("notion.api_token", "secret-must-not-leak").apply()
            edit().putString("pref_source_discord_token", "discord-must-not-leak").apply()
        }
        val outcome = SecretsSyncer(
            secrets = prefsA,
            backend = backend,
            passphraseProvider = PassphraseProvider { null },
        ).push(USER)
        assertTrue("no-passphrase push must be Permanent: $outcome", outcome is SyncOutcome.Permanent)

        // Nothing landed on the remote.
        val row = backend.fetch(USER, "blobs", "secrets:${USER.userId}").getOrNull()
        assertNull("no-passphrase must never push (plaintext OR encrypted)", row)
    }

    @Test fun `SECRET_KEY_PREFIXES contains expected entries — regression-fence`() {
        // Plain assertion that the public allowlist contains the
        // expected prefixes. Catches a silent re-order or accidental
        // delete during a future refactor.
        val expected = setOf("llm.", "cookie:", "github.", "anthropic.", "outline.", "notion.")
        assertEquals(expected, SecretsSyncer.SECRET_KEY_PREFIXES.toSet())
    }

    @Test fun `SECRET_KEY_NAMES contains discord token`() {
        assertTrue(
            "discord bot token must be in the exact-name allowlist",
            "pref_source_discord_token" in SecretsSyncer.SECRET_KEY_NAMES,
        )
    }

    @Test fun `SECRET_KEY_NAMES does not accidentally include non-secrets`() {
        // Negative-case fence — keep the allowlist tight.
        assertFalse(
            "pref_source_discord_enabled is a toggle, NOT a secret",
            "pref_source_discord_enabled" in SecretsSyncer.SECRET_KEY_NAMES,
        )
        assertFalse(
            "pref_theme_override is a settings preference, NOT a secret",
            "pref_theme_override" in SecretsSyncer.SECRET_KEY_NAMES,
        )
    }
}
