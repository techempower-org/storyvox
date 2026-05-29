package `in`.jphe.storyvox.sync

import android.content.SharedPreferences
import `in`.jphe.storyvox.sync.client.FakeInstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.domain.PassphraseProvider
import `in`.jphe.storyvox.sync.domain.SecretsSyncer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #360 finding 1 + 5 (argus): the most-advertised privacy feature
 * (encrypted secrets sync) shipped without a single test. This file is
 * the cross-device round-trip that would have caught:
 *
 *  - The DI binding being missing (finding 1): impossible to construct
 *    the syncer in a `:core-sync` unit test if its deps aren't
 *    injectable. Caught at compile time of this test.
 *  - The envelope salt being cosmetic (finding 5): without an actual
 *    "device A encrypts, device B decrypts via the same passphrase +
 *    fake backend" test, the bug that decrypt only worked because both
 *    devices recompute the deterministic per-user salt was invisible.
 *    This test now exercises the cross-device path end-to-end.
 *
 * `FakeInstantBackend` plays the role of the shared cloud — device A
 * pushes into it, device B pulls out, asserts every secret round-trips
 * with the right plaintext.
 */
class SecretsSyncerTest {

    private val USER = SignedInUser(userId = "user-abc-123", email = "a@b.test", refreshToken = "rt")
    private val PASSPHRASE = "correct horse battery staple".toCharArray()

    /** Pure-JVM SharedPreferences substitute. The production
     *  [SharedPreferences] is the Tink-backed EncryptedSharedPreferences
     *  from `:core-data`; tests don't need Robolectric to exercise the
     *  syncer's map<key,value> contract. */
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

    @Test fun `device A encrypts secrets, device B pulls and decrypts the same secrets`() = runTest {
        val backend = FakeInstantBackend()

        // Device A: has the user's LLM keys + an RR cookie in local prefs.
        val prefsA = FakePrefs().apply {
            edit().putString("llm.openai_key", "sk-test-aaaa").apply()
            edit().putString("llm.anthropic_key", "sk-ant-bbbb").apply()
            edit().putString("cookie:royalroad", "session=cccc").apply()
            edit().putString("github.token", "ghp_dddd").apply()
            // A non-secret key (no SECRET_KEY_PREFIXES match) — must NOT
            // be pushed; this asserts the prefix allowlist works.
            edit().putString("ui.theme", "dark").apply()
        }
        val deviceA = SecretsSyncer(
            secrets = prefsA,
            backend = backend,
            passphraseProvider = { PASSPHRASE.copyOf() },
        )
        val pushOutcome = deviceA.push(USER)
        assertTrue("device A push succeeds: $pushOutcome", pushOutcome is SyncOutcome.Ok)

        // Device B: fresh install, empty prefs, same user + same passphrase.
        val prefsB = FakePrefs()
        val deviceB = SecretsSyncer(
            secrets = prefsB,
            backend = backend,
            passphraseProvider = { PASSPHRASE.copyOf() },
        )
        val pullOutcome = deviceB.pull(USER)
        assertTrue("device B pull succeeds: $pullOutcome", pullOutcome is SyncOutcome.Ok)

        // Every secret round-tripped with the right plaintext.
        assertEquals("sk-test-aaaa", prefsB.getString("llm.openai_key", null))
        assertEquals("sk-ant-bbbb", prefsB.getString("llm.anthropic_key", null))
        assertEquals("session=cccc", prefsB.getString("cookie:royalroad", null))
        assertEquals("ghp_dddd", prefsB.getString("github.token", null))
        // The non-secret key was not pushed (and therefore not pulled).
        assertNull(prefsB.getString("ui.theme", null))
    }

    @Test fun `pushing local secrets stamps a non-zero updatedAt (regression #979)`() = runTest {
        // #979: secrets pushed with updatedAt=0 — a blob at 0 can never
        // win LWW, so the remote secrets blob stays pinned and never
        // pulls down to a second device. After the fix, a push from a
        // device whose LAST_TOUCH_KEY was never set must still carry a
        // real (>0) timestamp.
        val backend = FakeInstantBackend()
        val before = System.currentTimeMillis()
        val prefs = FakePrefs().apply {
            edit().putString("llm.openai_key", "sk-test").apply()
        }
        val device = SecretsSyncer(
            secrets = prefs,
            backend = backend,
            passphraseProvider = { PASSPHRASE.copyOf() },
        )
        assertTrue(device.push(USER) is SyncOutcome.Ok)

        val rowId = SyncIds.rowUuid(SecretsSyncer.DOMAIN, USER.userId)
        val onRemote = backend.fetch(USER, "blobs", rowId).getOrNull()
        assertNotNull("secrets blob must exist on remote", onRemote)
        assertTrue(
            "remote secrets blob must carry a non-zero updatedAt (was ${onRemote!!.updatedAt})",
            onRemote.updatedAt > 0L,
        )
        assertTrue(
            "updatedAt must be a real wall-clock stamp, not a sentinel",
            onRemote.updatedAt >= before,
        )
    }

    @Test fun `device B with its own older local secrets still pulls device A's secrets (regression #979)`() = runTest {
        // The user-facing #979 symptom: a *receiving* device that already
        // has its own local secret never pulled the originating device's
        // secrets, because both sides were pinned at updatedAt=0 and
        // `remote.updatedAt > local.updatedAt` (0 > 0) was always false.
        // With real timestamps, the later push wins symmetrically.
        val backend = FakeInstantBackend()

        // Device B sets a secret FIRST (earlier wall-clock time) and syncs
        // it up so it has a recorded local stamp.
        val prefsB = FakePrefs().apply {
            edit().putString("llm.openai_key", "B-older-key").apply()
        }
        val deviceB = SecretsSyncer(
            secrets = prefsB,
            backend = backend,
            passphraseProvider = { PASSPHRASE.copyOf() },
        )
        assertTrue(deviceB.push(USER) is SyncOutcome.Ok)

        // Device A then pushes a newer value for the same key. Its stamp
        // is later, so it must win on the remote.
        Thread.sleep(2)
        val prefsA = FakePrefs().apply {
            edit().putString("llm.openai_key", "A-newer-key").apply()
        }
        val deviceA = SecretsSyncer(
            secrets = prefsA,
            backend = backend,
            passphraseProvider = { PASSPHRASE.copyOf() },
        )
        assertTrue(deviceA.push(USER) is SyncOutcome.Ok)

        // Now device B pulls again. Remote (A's value, newer stamp) must
        // win over B's older local secret — the pull that #979 broke.
        assertTrue(deviceB.pull(USER) is SyncOutcome.Ok)
        assertEquals(
            "device B must pull device A's newer secret",
            "A-newer-key",
            prefsB.getString("llm.openai_key", null),
        )
    }

    @Test fun `wrong passphrase on device B fails decrypt and does not clobber local`() = runTest {
        val backend = FakeInstantBackend()
        val prefsA = FakePrefs().apply {
            edit().putString("llm.openai_key", "sk-test").apply()
        }
        SecretsSyncer(
            secrets = prefsA,
            backend = backend,
            passphraseProvider = { PASSPHRASE.copyOf() },
        ).push(USER)

        // Device B has a different passphrase. The decrypt must fail
        // silently (AES-GCM auth failure) and local data must not be
        // touched. We pre-seed a local secret so we can assert it
        // survives.
        val prefsB = FakePrefs().apply {
            edit().putString("llm.openai_key", "local-only-key").apply()
        }
        val deviceB = SecretsSyncer(
            secrets = prefsB,
            backend = backend,
            passphraseProvider = { "wrong-passphrase".toCharArray() },
        )
        deviceB.pull(USER)
        // Local prefs must still hold the local-only value — a wrong
        // passphrase should not corrupt local state.
        assertEquals("local-only-key", prefsB.getString("llm.openai_key", null))
    }

    @Test fun `no passphrase configured returns permanent and does not touch local or remote`() = runTest {
        val backend = FakeInstantBackend()
        val prefs = FakePrefs().apply {
            edit().putString("llm.openai_key", "sk-test").apply()
        }
        val syncer = SecretsSyncer(
            secrets = prefs,
            backend = backend,
            passphraseProvider = PassphraseProvider { null },
        )
        val outcome = syncer.push(USER)
        assertTrue("no-passphrase push must be Permanent: $outcome", outcome is SyncOutcome.Permanent)
        // Local prefs untouched.
        assertEquals("sk-test", prefs.getString("llm.openai_key", null))
        // Nothing on the backend either.
        val onRemote = backend.fetch(USER, "blobs", "secrets:${USER.userId}").getOrNull()
        assertNull(onRemote)
    }

    @Test fun `passphrase array is wiped after use`() = runTest {
        // The kdoc on UserDerivedKey.deriveKey says we zero the
        // passphrase after key derivation. This test asserts the
        // syncer-side contract: we pass a `CharArray`, the syncer is
        // responsible for wiping it before returning so the caller can
        // hand us a sensitive char array safely.
        val backend = FakeInstantBackend()
        val prefs = FakePrefs().apply {
            edit().putString("llm.openai_key", "sk-test").apply()
        }
        val handed = PASSPHRASE.copyOf()
        val handedRef = handed
        val syncer = SecretsSyncer(
            secrets = prefs,
            backend = backend,
            passphraseProvider = { handedRef },
        )
        syncer.push(USER)
        // After the syncer runs, the original passphrase array should be
        // zeroed/space-filled so a heap dump doesn't leak it.
        assertTrue(
            "passphrase chars must be wiped after the syncer runs (got ${handed.concatToString()})",
            handed.all { it == ' ' || it == ' ' },
        )
    }
}
