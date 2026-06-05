package `in`.jphe.storyvox.source.discord.config

/**
 * Issue #403 — Discord backend defaults. The values here are
 * intentionally minimal: there is no bundled bot token (ToS posture —
 * storyvox doesn't ship credentials and won't auto-join anyone's
 * server), and no default server id (the user picks one after the
 * `users/@me/guilds` lookup populates the picker).
 *
 * The chapter-coalesce window defaults to 5 minutes which matches the
 * "chat thread = episode" shape: a single author's burst of messages
 * within a 5-minute window reads as one chapter, with separations
 * crossing that threshold rendering as chapter boundaries. Users can
 * tune the window in Settings → Library & Sync → Discord (1-30 min
 * slider) per the issue spec.
 */
object DiscordDefaults {
    /** Discord REST API base URL — v10 is the current stable as of
     *  2026. Overridable for test fakes (the test classes inject a
     *  copy-of-DiscordConfigState with their own base URL). */
    const val BASE_URL: String = "https://discord.com"

    /** API version segment. Pinned in the source so storyvox + Discord
     *  don't drift apart silently. v10 is current; v9 still works in
     *  practice but Discord's docs treat v10 as the recommended floor. */
    const val API_VERSION: String = "v10"

    /** Default message-coalesce window (minutes). Within this window,
     *  consecutive messages from the same author collapse into one
     *  chapter — matches the "chat-thread-as-episode" shape from
     *  #403. Slider range in Settings is 1-30. */
    const val DEFAULT_COALESCE_MINUTES: Int = 5

    /** Minimum coalesce-window value the slider exposes. Below 1 min,
     *  coalescing is effectively disabled (every message becomes its
     *  own chapter); the toggle for that is "set to 1 min" rather than
     *  a separate boolean to keep the UI simple. */
    const val MIN_COALESCE_MINUTES: Int = 1

    /** Maximum coalesce-window value the slider exposes. Above 30 min,
     *  a single chapter could span many hours of unrelated thoughts;
     *  Discord channels rarely have a continuous-monologue cadence
     *  past this. */
    const val MAX_COALESCE_MINUTES: Int = 30

    /** User-Agent header per Discord's API guidelines — should
     *  identify storyvox and the version so Discord's abuse team can
     *  reach out before banning a misbehaving bot. */
    const val USER_AGENT: String =
        "Storyvox-Discord/1.0 (+https://github.com/techempower-org/candela)"

    /**
     * Issue #517 — Discord channel id for the TechEmpower peer-support
     * channel. When the user enables Discord AND keeps the TechEmpower-
     * defaults toggle on (default ON for fresh installs, see
     * [DEFAULT_TECHEMPOWER_DEFAULTS_ENABLED]), the channel surfaces as
     * a pinned fiction in the Discord browse list — even before the
     * user picks a server, because the Discord REST endpoint
     * `/channels/{id}/messages` is channel-scoped and works as long
     * as the configured bot has been invited with
     * `READ_MESSAGE_HISTORY` on the channel.
     *
     * Captured from JP 2026-05-15 (see
     * `scratch/techempower-shared/discord-invite.md`). The channel
     * lives in TechEmpower's peer-support Discord server reachable via
     * the [DISCORD_INVITE_URL][in.jphe.storyvox.data.TechEmpowerLinks.DISCORD_INVITE_URL]
     * invite.
     */
    const val TECHEMPOWER_PEER_SUPPORT_CHANNEL_ID: String = "1504888494206484531"

    /**
     * Issue #517 — default pinned channel ids surfaced as fictions
     * regardless of which Discord server the user has picked. Today
     * this is the single TechEmpower peer-support channel; the shape
     * is a List so future TechEmpower-default-pinning expansions
     * (multiple peer-support rooms, region-specific channels, etc.)
     * don't require a config-shape migration.
     *
     * Pinned channels appear at the top of [DiscordSource.popular]'s
     * returned list, marked with a `(TechEmpower)` author label so
     * users can tell them apart from their own server's channels.
     */
    val DEFAULT_PINNED_CHANNEL_IDS: List<String> =
        listOf(TECHEMPOWER_PEER_SUPPORT_CHANNEL_ID)

    /**
     * Issue #517 — default value of the per-user "surface TechEmpower
     * default channels as pinned fictions" toggle. ON for fresh
     * installs so the peer-support thread is discoverable the moment
     * a user configures Discord; users can flip it off in
     * Settings → Library & Sync → Discord (UI surface is a follow-up;
     * the value is persisted today through
     * [`in`.jphe.storyvox.data.DiscordConfigImpl.setTechEmpowerDefaultsEnabled]).
     */
    const val DEFAULT_TECHEMPOWER_DEFAULTS_ENABLED: Boolean = true
}
