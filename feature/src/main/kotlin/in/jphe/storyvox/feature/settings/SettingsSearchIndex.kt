package `in`.jphe.storyvox.feature.settings

/**
 * Issue #802 — search index for the legacy long-scroll [SettingsScreen].
 *
 * #773 landed a per-row title/subtitle filter on the hub. The legacy
 * page can't reuse that approach row-for-row: its rows are heterogeneous
 * (sliders, delegated `AiSection`/`AzureSection` blocks, status pills),
 * so there's no uniform title/subtitle to tag each one with. Instead the
 * legacy page filters at the *section* granularity — the unit a user
 * scans for. Each [SettingsSearchSection] mirrors one `SectionHeading`
 * in [SettingsScreen] plus a small keyword list so a query like "TTS" or
 * "kokoro" surfaces the Voice section even though neither word appears in
 * its heading.
 *
 * This list is the source of truth pinned by [SettingsSearchIndexTest];
 * adding or reordering a section in [SettingsScreen] requires editing
 * both, and that drift is the point of pinning.
 */
data class SettingsSearchSection(
    val label: String,
    val descriptor: String,
    val keywords: List<String>,
)

/**
 * Section order matches the `SectionHeading` call order in
 * [SettingsScreen]. Keywords are the *non-obvious* terms a user might
 * search for that don't already appear in the label or descriptor —
 * engine names, synonyms, the specific knob they remember. Terms that
 * are already in label/descriptor aren't repeated; the match function
 * scans all three.
 */
val SettingsSearchSections: List<SettingsSearchSection> = listOf(
    SettingsSearchSection(
        label = "Voice & Playback",
        descriptor = "How storyvox sounds — voice, speed, cadence.",
        keywords = listOf(
            "tts", "engine", "kokoro", "piper", "sonic", "pitch",
            "punctuation", "pause", "pronunciation", "rate", "tempo",
        ),
    ),
    SettingsSearchSection(
        label = "Reading",
        descriptor = "How chapter text and the reader behave.",
        keywords = listOf("theme", "dark", "light", "sleep", "timer", "shake"),
    ),
    SettingsSearchSection(
        label = "Performance & buffering",
        descriptor = "Trade memory and CPU for smoother playback.",
        keywords = listOf(
            "buffer", "cache", "prerender", "parallel synth", "threads",
            "warm-up", "determinism", "catch-up", "underrun", "cpu",
        ),
    ),
    SettingsSearchSection(
        label = "AI",
        descriptor = "Smart features — Recap, character lookup, chat.",
        keywords = listOf(
            "claude", "openai", "gpt", "ollama", "vertex", "bedrock",
            "foundry", "azure", "model", "grounding", "recap", "chat",
            "api key", "sessions", "memory",
        ),
    ),
    SettingsSearchSection(
        label = "Library & Sync",
        descriptor = "Where stories come from and how often we check for updates.",
        keywords = listOf(
            "plugins", "sources", "epub", "outline", "wikipedia", "notion",
            "discord", "telegram", "inbox", "royal road", "kvmr", "wifi",
            "poll", "interval", "download",
        ),
    ),
    SettingsSearchSection(
        label = "Account",
        descriptor = "Sign-in for fiction sources that need it.",
        keywords = listOf("royal road", "github", "sign in", "sign out", "oauth", "login"),
    ),
    SettingsSearchSection(
        label = "Memory Palace",
        descriptor = "Browse your local mempalace as fictions (LAN only).",
        keywords = listOf("mempalace", "daemon", "host", "probe", "lan"),
    ),
    SettingsSearchSection(
        label = "Cloud voices",
        descriptor = "Bring your own Azure key for HD Neural and Dragon HD voices.",
        keywords = listOf("azure", "neural", "dragon", "byok", "key", "region", "fallback"),
    ),
    SettingsSearchSection(
        label = "Developer",
        descriptor = "Live pipeline diagnostics + bug-report export.",
        keywords = listOf("debug", "overlay", "diagnostics", "log", "onboarding", "reset"),
    ),
    SettingsSearchSection(
        label = "About",
        descriptor = "Build identity for bug reports.",
        keywords = listOf("version", "sigil", "build", "license", "open source"),
    ),
)

/**
 * Issue #802 — match for the legacy settings search. Mirrors the #773
 * hub contract: a case-insensitive *substring* hit against the label,
 * the descriptor, or any keyword ("pass" matches "Sync Passphrase").
 *
 * A typo-tolerant subsequence fallback ("kkr" → "kokoro") applies
 * *only* to the short keyword tokens, never to the prose label /
 * descriptor — running subsequence over a long sentence produces wild
 * false positives (a 3-char query like "tts" is an in-order subsequence
 * of almost any sentence). Restricting the fuzzy pass to keywords keeps
 * it useful for engine names without bleeding matches across sections.
 *
 * Blank query matches everything — that's the unfiltered default.
 */
fun matchesSettingsQuery(query: String, section: SettingsSearchSection): Boolean {
    val q = query.trim()
    if (q.isBlank()) return true
    if (section.label.contains(q, ignoreCase = true)) return true
    if (section.descriptor.contains(q, ignoreCase = true)) return true
    return section.keywords.any { keyword ->
        keyword.contains(q, ignoreCase = true) ||
            isSubsequence(q.lowercase(), keyword.lowercase())
    }
}

/**
 * True when every char of [needle] appears in [haystack] in order
 * (not necessarily contiguous). "kkr" ⊆ "kokoro".
 */
private fun isSubsequence(needle: String, haystack: String): Boolean {
    if (needle.isEmpty()) return true
    var i = 0
    for (c in haystack) {
        if (c == needle[i]) {
            i++
            if (i == needle.length) return true
        }
    }
    return false
}
