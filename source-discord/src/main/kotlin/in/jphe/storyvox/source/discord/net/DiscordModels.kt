package `in`.jphe.storyvox.source.discord.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Issue #403 — minimal Discord REST API response shapes.
 *
 * Only the fields storyvox actually reads are declared. Discord's
 * API returns dozens of fields per object (boost counts, role
 * settings, member flags, etc.); declaring them all would just be
 * future-fragile noise. `Json.ignoreUnknownKeys = true` drops what
 * we don't ask for, and Discord's API guarantees additive evolution
 * within a major version so unknown new fields don't break us.
 */

/**
 * One element of `GET /api/v10/users/@me/guilds` — a guild (server)
 * the bot has been invited to. The bot must accept the invite
 * out-of-band before this surface lists anything; storyvox doesn't
 * auto-join.
 */
@Serializable
internal data class DiscordGuild(
    /** Snowflake guild id (18-19 digit numeric string). */
    val id: String,
    /** Server name as the owner set it. */
    val name: String,
    /** Optional icon hash — Discord exposes the image at
     *  `cdn.discordapp.com/icons/{guildId}/{iconHash}.png`. Null when
     *  the server has no icon (free-tier with default placeholder). */
    val icon: String? = null,
    /** True iff this guild's bot membership is the owner's account
     *  (rare; storyvox doesn't surface this in v1 but the field is
     *  cheap to deserialize). */
    val owner: Boolean = false,
)

/**
 * One element of `GET /api/v10/guilds/{id}/channels` — a channel
 * within a guild. Each text channel becomes one storyvox fiction in
 * the channels-as-fictions mapping.
 */
@Serializable
internal data class DiscordChannel(
    /** Snowflake channel id. */
    val id: String,
    /** Channel name (without the `#` prefix). */
    val name: String,
    /** Discord channel type. 0 = GUILD_TEXT, 5 = GUILD_ANNOUNCEMENT,
     *  15 = GUILD_FORUM, etc. storyvox surfaces 0 and 5 in v1; other
     *  types (voice, category, stage) are filtered out at the source
     *  layer. */
    val type: Int = 0,
    /** Owner-set topic / description (the "channel topic" you see in
     *  the channel header). Null when unset. Becomes the
     *  [FictionSummary.description]. */
    val topic: String? = null,
    /** Parent category id, if the channel is grouped under one.
     *  Unused in v1 — surface ordering doesn't yet honor categories. */
    @SerialName("parent_id")
    val parentId: String? = null,
    /** Position within the guild's channel list. Discord orders by
     *  this value within each category; we use it as the
     *  presentation order in Browse. */
    val position: Int = 0,
)

/**
 * One element of `GET /api/v10/channels/{id}/messages` — a single
 * message in a text channel. Each message becomes one chapter, or
 * coalesces with consecutive same-author messages into one chapter
 * per the [DiscordConfigState.coalesceMinutes] window.
 */
@Serializable
internal data class DiscordMessage(
    val id: String,
    /** ISO-8601 timestamp ("2026-05-13T19:42:11.123000+00:00"). The
     *  source layer parses this for the coalesce-window comparison. */
    val timestamp: String,
    /** Plain-text message content. May be empty for attachment-only
     *  or embed-only messages. */
    val content: String = "",
    /** Message author. Coalesce check compares author ids.
     *
     *  Nullable + defaulted (#1064): webhook / system / integration
     *  message shapes can arrive with `author` explicitly null or the
     *  key omitted entirely. Discord's `coerceInputValues` does NOT
     *  rescue a non-nullable field, so a single author-less element in
     *  a page would throw and fail the WHOLE `List<DiscordMessage>`
     *  decode — bricking the entire channel rather than one message.
     *  Author-less messages are system/webhook noise the `type == 0`
     *  filter drops downstream; consumers fall back via
     *  `author?.displayName() ?: "Unknown"`. Matches Slack's
     *  `user: SlackUser? = null` defense. */
    val author: DiscordUser? = null,
    /** Attachments uploaded with the message. Filenames + URLs are
     *  surfaced in the chapter body so TTS can mention them. */
    val attachments: List<DiscordAttachment> = emptyList(),
    /** Embeds (link previews, rich content). v1 surfaces the title +
     *  description text; full embed rendering is a follow-up. */
    val embeds: List<DiscordEmbed> = emptyList(),
    /** True for system messages (joins, pins, etc.). Filtered out at
     *  the source layer — system messages don't belong in an
     *  audiobook. */
    val type: Int = 0,
)

@Serializable
internal data class DiscordUser(
    val id: String,
    val username: String,
    /** Global display name on Discord (the "@handle" → "Display Name"
     *  v2 split). Falls back to [username] when null. */
    @SerialName("global_name")
    val globalName: String? = null,
)

@Serializable
internal data class DiscordAttachment(
    val id: String,
    val filename: String,
    /** Discord CDN URL — included in the chapter body so the reader
     *  view can render an image preview alongside the TTS narration
     *  of "Attachment: {filename}". v1 doesn't fetch the bytes. */
    val url: String,
    @SerialName("content_type")
    val contentType: String? = null,
    /** File size in bytes. */
    val size: Long = 0,
)

@Serializable
internal data class DiscordEmbed(
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
)

/**
 * Response shape from
 * `GET /api/v10/guilds/{id}/messages/search`. Discord wraps results
 * in a `messages: List<List<DiscordMessage>>` envelope (each inner
 * list is one match with up to 2 context messages on either side);
 * we project the head match only for v1.
 */
@Serializable
internal data class DiscordSearchResponse(
    /** Total hit count across the whole match set. Used for the
     *  Browse "X results" affordance; pagination is via Discord's
     *  `offset` parameter which v1 doesn't yet pass. */
    @SerialName("total_results")
    val totalResults: Int = 0,
    /** Each top-level list element is one match. The match is the
     *  middle item in the inner list (Discord surrounds it with up
     *  to 2 context messages); for storyvox's v1 surfacing we just
     *  take the first inner element of each match. */
    val messages: List<List<DiscordMessage>> = emptyList(),
)

/**
 * Structured error envelope returned by Discord on 4xx/5xx —
 * `{ "code": 50001, "message": "Missing Access" }`. Decoded for
 * human-readable surfacing in the [FictionResult.Failure] message
 * field; runtime guards a non-JSON body (gateway HTML errors, empty
 * bodies under the worst transient failures).
 */
@Serializable
internal data class DiscordError(
    val code: Int = 0,
    val message: String = "",
)
