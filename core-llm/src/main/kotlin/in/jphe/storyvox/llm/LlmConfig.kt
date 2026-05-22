package `in`.jphe.storyvox.llm

/**
 * Non-secret AI configuration, persisted in DataStore alongside theme
 * / buffer / voice prefs. API keys are NOT here — those go through
 * [LlmCredentialsStore] backed by EncryptedSharedPreferences.
 *
 * Per-provider model + auxiliary fields live on this single record
 * rather than splitting per-provider records, because (a) the config
 * is small (a dozen scalars) and (b) Settings UI reads them all at
 * once. Spec-only providers (Bedrock / Vertex / Foundry) have their
 * fields here so DataStore migrations are additive when those
 * providers ship.
 */
data class LlmConfig(
    /** null means AI is disabled — Recap button greys out, no
     *  provider is contacted. Settings → AI's "Off" three-stop
     *  selector maps to this. */
    val provider: ProviderId? = null,

    // Claude direct
    val claudeModel: String = "claude-haiku-4.5",

    // OpenAI direct
    val openAiModel: String = "gpt-4o-mini",

    // Ollama (local LAN)
    /** Sentinel default — clearly wrong on most setups, prompts the
     *  user to fix it before they can test the connection. We don't
     *  default to localhost because storyvox runs on phones/tablets
     *  where localhost is the device, almost never the Ollama host. */
    val ollamaBaseUrl: String = "http://10.0.0.1:11434",
    val ollamaModel: String = "llama3.3",

    // ── Spec-only fields. Persisted ahead of implementation so a
    // future provider PR doesn't need a DataStore migration. ───
    val bedrockRegion: String = "us-east-1",
    val bedrockModel: String = "claude-haiku-4.5",
    val vertexModel: String = "gemini-2.5-flash",
    val foundryEndpoint: String = "",
    val foundryDeployment: String = "",
    /** Foundry has two deployment models: per-model "deployed" URLs
     *  (`/openai/deployments/{name}/...`, default) and a single
     *  "serverless" catalog URL (`/models/chat/completions`). The
     *  toggle is required because the URL templates differ AND the
     *  request bodies differ — deployed omits `model`, serverless
     *  includes it. See [provider.AzureFoundryProvider.buildUrl]. */
    val foundryServerless: Boolean = false,

    // Cross-provider toggles
    /** First-time-activation modal acknowledged. Reset when the user
     *  clears all AI settings, so the disclosure fires again on next
     *  activation. */
    val privacyAcknowledged: Boolean = false,
    /** Hard kill switch — when false, the Settings AI section still
     *  works (key entry, Test connection) but no chapter text is
     *  ever sent. Recap button greys out with "Enable in Settings". */
    val sendChapterTextEnabled: Boolean = true,
    /**
     * Issue #216 — global toggle for AI-initiated actions
     * ("Allow the AI to take actions" in Settings → AI). When false,
     * the chat layer passes an empty [ToolRegistry] to the provider —
     * function calling is silently disabled, the model behaves as a
     * plain Q&A bot. When true, the v1 tool catalog from
     * `StoryvoxToolSpecs` is advertised on every chat turn and the
     * model can invoke handlers wired by `ChatToolHandlers`.
     *
     * Default ON for fresh installs — the spec calls actions out as
     * the headline behaviour of the feature; gating them behind an
     * opt-in toggle would hide the new capability from most users.
     * The toggle exists as an escape hatch for users who want to
     * disable agency for any reason (privacy, accident-avoidance).
     */
    val aiActionsEnabled: Boolean = true,
)

/**
 * The set of canonical model IDs we surface in Settings dropdowns.
 * Provider-specific names (e.g. "claude-haiku-4-5-20251001" for
 * Anthropic vs "claude-haiku-4.5" canonical) are resolved inside the
 * provider class — same pattern cloud-chat-assistant's `MODEL_MAP`
 * uses. Hardcoded list for v1; "fetch from `/v1/models`" is a
 * follow-up if users want it.
 */
object LlmModels {
    val claude = listOf(
        "claude-haiku-4.5",
        "claude-sonnet-4.6",
        "claude-opus-4.6",
        "claude-sonnet-4.7",
        "claude-opus-4.7",
    )
    val openAi = listOf(
        "gpt-4o-mini",
        "gpt-4o",
        "gpt-5.3",
    )
    /** Ollama models depend entirely on what's pulled on the user's
     *  box, so this is a starter list that "Test connection" will
     *  augment with /api/tags output. */
    val ollamaStarter = listOf(
        "llama3.3",
        "llama3.2",
        "mistral",
        "phi3",
    )

    /** Mirrors cloud-chat-assistant's Gemini list. The `-lite` tier is
     *  the cheapest; `-pro` is the smartest; `-flash` is the default
     *  middle ground. */
    val vertex = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-pro",
        "gemini-2.5-flash-lite",
    )

    /** Azure Foundry **serverless** catalog short-list — surfaces a
     *  small grid in Settings when the user picks the Serverless
     *  toggle. Deployed mode shows no chips because the deployment
     *  name is whatever the user typed in the Azure portal. */
    val foundryServerless: List<String> = listOf(
        "gpt-4o",
        "gpt-4o-mini",
        "Llama-3.3-70B-Instruct",
        "DeepSeek-R1",
        "Phi-4",
        "grok-3",
    )
}
