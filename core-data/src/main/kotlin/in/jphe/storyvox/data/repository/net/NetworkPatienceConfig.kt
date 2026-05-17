package `in`.jphe.storyvox.data.repository.net

import kotlinx.coroutines.flow.Flow

/**
 * Issue #597 — user-tunable HTTP timeout preset. Source modules use
 * their own OkHttpClient builders today (each with hardcoded
 * connect/read/write timeouts); this contract lets them consume a
 * shared "patience" multiplier so the user can dial in:
 *
 *  - **Aggressive** (5 s budget) — fast-fail, friendly to flaky
 *    cellular where a long stall hides an "actually broken" path
 *    that the user could route around with a retry.
 *  - **Default** (10 s) — current `:source-rss` default, the
 *    middle-ground for most Wi-Fi paths.
 *  - **Patient** (30 s) — generous timeouts for slow Notion API
 *    walks, large EPUB downloads, hard-pressed cellular.
 *
 * The chip controls a single time value in seconds; each module is
 * responsible for translating that into its own
 * connect/read/write/call-timeout split (some modules want a tighter
 * connect than read; others want them aligned). The shared
 * [NetworkPatience] enum carries the values verbatim so a module can
 * branch on the preset name or use the raw seconds value.
 *
 * Wired into OkHttp builders via:
 *  - `:source-royalroad`'s `provideClient` (the most-touched source)
 *  - `:source-notion`'s `provideClient` (slow API walks)
 *  - `:source-rss`'s `provideClient` (frequent polls)
 *  - other sources stay on their hardcoded defaults for now —
 *    follow-up issues track per-module adoption.
 *
 * Per-device (NOT synced).
 */
interface NetworkPatienceConfig {

    /** Live flow of the active patience preset. */
    val patience: Flow<NetworkPatience>

    /**
     * Snapshot read. Falls back to [NetworkPatience.Default] if the
     * underlying store hasn't emitted yet.
     */
    suspend fun currentPatience(): NetworkPatience
}

/**
 * Tiered network-patience preset (#597). Each tier maps to a "main
 * timeout budget" in seconds. Source modules translate the budget
 * into their per-method timeouts via the helpers below.
 *
 * - [Aggressive] — fast-fail, 5 s budget
 * - [Default] — middle-ground, 10 s budget
 * - [Patient] — generous, 30 s budget
 */
enum class NetworkPatience(val budgetSeconds: Long) {
    Aggressive(5),
    Default(10),
    Patient(30);

    /**
     * Recommended `connectTimeout` value. Half the budget — TCP
     * handshake should fail fast on a flaky link; the read window
     * gets the slack.
     */
    val connectTimeoutSeconds: Long
        get() = (budgetSeconds / 2).coerceAtLeast(2)

    /** Recommended `readTimeout` — full budget. */
    val readTimeoutSeconds: Long
        get() = budgetSeconds

    /** Recommended `writeTimeout` — full budget. */
    val writeTimeoutSeconds: Long
        get() = budgetSeconds

    /**
     * Recommended `callTimeout` — 1.5× budget. Hard cap on the whole
     * call (DNS + connect + TLS + read + retries) that bounds the
     * worst case even under `retryOnConnectionFailure`.
     */
    val callTimeoutSeconds: Long
        get() = (budgetSeconds * 3) / 2
}
