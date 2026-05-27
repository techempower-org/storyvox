package `in`.jphe.storyvox.source.github.auth

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the GitHub OAuth access token + identity metadata.
 *
 * Issue #91. Spec: docs/superpowers/specs/2026-05-08-github-oauth-design.md
 *
 * Persistence: keys live in the same `EncryptedSharedPreferences`
 * instance (`storyvox.secrets`) that holds the Royal Road cookie. Tink-
 * backed AES-256-GCM value encryption keyed by the Android keystore
 * master key. No new prefs file, no new MasterKey — same threat model as
 * the existing RR cookie.
 *
 * Single token per app instance (no multi-account in v1). Hydration on
 * construction mirrors `AuthRepositoryImpl`'s pattern: read disk in the
 * `init` block so the in-memory [StateFlow] is hot before the first
 * outbound API call.
 *
 * **Why a parallel impl** (vs folding into `:core-data`'s
 * `AuthRepository`): the spec calls out a "PR Auth-A — multi-source
 * `AuthRepository` refactor" as a prerequisite. Sky's brief is "single
 * PR, P0 only," so the cross-source refactor is deferred. GitHub auth
 * stands beside the RR auth here; a future PR can fold both into the
 * `SourceAuth` interface from the spec.
 */
interface GitHubAuthRepository {

    /** Hot stream of the session state. Hydrated from disk on construction. */
    val sessionState: StateFlow<GitHubSession>

    /** Persist a freshly-issued token + identity. Transitions → [GitHubSession.Authenticated]. */
    suspend fun captureSession(
        token: String,
        login: String?,
        scopes: String,
    )

    /** Forget everything. Transitions → [GitHubSession.Anonymous]. */
    suspend fun clearSession()

    /**
     * Token at github.com is gone (revoked, rotated, deleted OAuth app).
     * Disk copy is left intact so Settings can show "Session expired —
     * sign in again." The interceptor calls this on a 401 response.
     */
    fun markExpired()
}

@Singleton
internal class GitHubAuthRepositoryImpl @Inject constructor(
    private val prefs: SharedPreferences,
) : GitHubAuthRepository {

    private val state = MutableStateFlow<GitHubSession>(GitHubSession.Anonymous)
    override val sessionState: StateFlow<GitHubSession> = state.asStateFlow()

    init {
        // Hydrate from disk before any caller can touch the StateFlow. Hilt
        // creates this on the singleton component, and this is the single
        // place where blocking IO on the prefs file is acceptable — the
        // construction path runs before any coroutine consumes the flow.
        val token = prefs.getString(KEY_TOKEN, null)
        val login = prefs.getString(KEY_LOGIN, null)
        val scopes = prefs.getString(KEY_SCOPES, null) ?: GitHubAuthConfig.DEFAULT_SCOPES
        val grantedAt = prefs.getString(KEY_GRANTED_AT, null)?.toLongOrNull() ?: 0L
        if (token != null) {
            state.value = GitHubSession.Authenticated(
                token = token,
                login = login,
                scopes = scopes,
                grantedAt = grantedAt,
            )
        }
    }

    override suspend fun captureSession(
        token: String,
        login: String?,
        scopes: String,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        prefs.edit {
            putString(KEY_TOKEN, token)
            // Allow null/blank login to coexist with re-captures that happen
            // before the GET /user lookup completes. Settings UI tolerates
            // a null login by falling back to "Signed in".
            if (login != null) putString(KEY_LOGIN, login) else remove(KEY_LOGIN)
            putString(KEY_SCOPES, scopes)
            putString(KEY_GRANTED_AT, now.toString())
        }
        state.value = GitHubSession.Authenticated(
            token = token,
            login = login,
            scopes = scopes,
            grantedAt = now,
        )
    }

    override suspend fun clearSession() = withContext(Dispatchers.IO) {
        prefs.edit {
            remove(KEY_TOKEN)
            remove(KEY_LOGIN)
            remove(KEY_SCOPES)
            remove(KEY_GRANTED_AT)
        }
        state.value = GitHubSession.Anonymous
    }

    override fun markExpired() {
        // Called from the interceptor on a 401 — synchronous on the OkHttp
        // dispatcher thread, so we can't suspend here. The on-disk copy
        // stays intact so the next captureSession() restores cleanly. The
        // in-memory state flips so the interceptor stops attaching the
        // dead token to subsequent requests.
        //
        // #871 — MutableStateFlow.value= is thread-safe and notifies
        // subscribers immediately; the previous CoroutineScope(IO).launch
        // was redundant and leaked an unscoped coroutine.
        if (state.value is GitHubSession.Expired) return
        state.value = GitHubSession.Expired
    }

    companion object {
        // Per-source key namespace: `<kind>:<sourceId>`. Mirrors the
        // existing `cookie:royalroad` shape so future multi-source auth
        // refactors don't need a data migration.
        const val KEY_TOKEN = "token:github"
        const val KEY_LOGIN = "token:github:login"
        const val KEY_SCOPES = "token:github:scopes"
        const val KEY_GRANTED_AT = "token:github:granted_at"
    }
}
