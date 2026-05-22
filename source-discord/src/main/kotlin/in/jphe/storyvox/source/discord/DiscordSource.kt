package `in`.jphe.storyvox.source.discord

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.model.map
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.discord.config.DiscordConfig
import `in`.jphe.storyvox.source.discord.net.DiscordApi
import `in`.jphe.storyvox.source.discord.net.DiscordMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #403 — Discord as a fiction backend.
 *
 * **Mapping**
 *
 *  - **Server** (Discord guild) = top-level filter (user picks
 *    which server to expose via Settings).
 *  - **Channel** = one fiction. Channel name → title, channel topic
 *    → description, server name → "author" placeholder.
 *  - **Message** = one chapter, OR consecutive same-author messages
 *    within a tight time window (default 5 min, configurable
 *    1-30 min) get coalesced into one chapter. The coalesce shape
 *    handles the "chat-thread-as-episode" pattern where one author
 *    posts a sequence of short replies that read as a single thought.
 *  - **Attachments** = filename surfaced in the chapter body so TTS
 *    narrates them ("Attachment: dragon-sketch.png").
 *
 * **Auth**: user-supplied bot token (PAT-style). The user creates a
 * Discord application at discord.com/developers, generates a bot
 * token, invites their bot to the server with the
 * `READ_MESSAGE_HISTORY` scope, and pastes the token into Settings
 * → Library & Sync → Discord. No bundled default token, no
 * automatic server-joining, no selfbot/user-token automation
 * (banned by Discord ToS). Default OFF on fresh installs.
 *
 * **Fiction ids**: `discord:<channelId>`. **Chapter ids**:
 * `discord:<channelId>::msg-<headMessageId>` where the head message
 * is the oldest in the coalesced group (i.e. the message whose
 * timestamp anchors the chapter). All coalesced sibling messages
 * are re-fetched at chapter time and rendered as one body so the
 * chapter id stays stable across re-fetches.
 *
 * **`supportsFollow = false`**: Discord channels don't have a
 * "follow" semantic that's distinct from joining the server. The
 * user already chose to be in the server (via the bot invite); a
 * per-channel follow toggle would be a surface inconsistency.
 */
@SourcePlugin(
    id = SourceIds.DISCORD,
    displayName = "Discord",
    // #436 — fresh-install discoverability: chip on by default; the
    // backend stays inert until the user enters a bot token in Settings.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Bot-token-authed channel reader · server = filter, channel = fiction, message = chapter (with same-author coalescing)",
    sourceUrl = "https://discord.com",
)
@Singleton
internal class DiscordSource @Inject constructor(
    private val api: DiscordApi,
    private val config: DiscordConfig,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher {

    override val id: String = SourceIds.DISCORD

    /** Issue #472 — `discord.com/channels/<guild>/<channel>` URL. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val m = Regex(
            """^https?://(?:www\.|ptb\.|canary\.)?discord\.com/channels/(\d+)/(\d+)(?:/.*)?$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(url.trim()) ?: return null
        val guildId = m.groupValues[1]
        val channelId = m.groupValues[2]
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.DISCORD,
            fictionId = "${SourceIds.DISCORD}:$guildId/$channelId",
            confidence = 0.9f,
            label = "Discord channel",
        )
    }
    override val displayName: String = "Discord"
    override val supportsFollow: Boolean = false

    // ─── browse ────────────────────────────────────────────────────────

    /**
     * Front-page = "channels in the configured server". One page, no
     * pagination — guilds rarely have >1000 channels and Discord's
     * `/guilds/{id}/channels` returns them all in one response. We
     * filter to text + announcement types (0 + 5); voice / stage /
     * category channels aren't readable as text fiction.
     */
    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        val state = config.current()
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Discord bot token not configured. " +
                    "Paste a token in Settings → Library & Sync → Discord.",
            )
        }
        if (page > 1) {
            return FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        }

        // Issue #517 — TechEmpower peer-support channel (and any other
        // pinned channels) surface at the top of the Browse list even
        // before the user picks a server. The Discord REST
        // `/channels/{id}/messages` endpoint is channel-scoped, so we
        // don't need guild membership to read these — only that the
        // configured bot has been invited to the channel with the
        // `READ_MESSAGE_HISTORY` scope (documented in
        // scratch/techempower-shared/discord-invite.md as the
        // operational requirement for the seed channel to render).
        //
        // We surface pinned channels as FictionSummary stubs with an
        // explicit `(TechEmpower)` author label so users can tell them
        // apart from their own server's channels. The chapter list +
        // chapter body still resolve through the existing
        // fictionDetail() / chapter() paths — those are channel-id
        // scoped too, so no extra plumbing is required.
        //
        // The Peer-Support channel is also reachable from
        // TechEmpowerHomeScreen via the dedicated Discord card (the
        // paired phone+forum row near the top of the screen). The
        // Notion-served "Featured guides" strip there stays
        // Notion-only by design — it advertises the `notion:guides`
        // PageList fiction specifically, not a cross-source category.
        val pinnedItems: List<FictionSummary> = state.pinnedChannelIds.map { channelId ->
            FictionSummary(
                id = discordFictionId(channelId),
                sourceId = SourceIds.DISCORD,
                title = "#peer-support",
                author = "TechEmpower",
                description = "TechEmpower's peer-support Discord — listen to past messages from the community.",
                status = FictionStatus.ONGOING,
            )
        }

        // Without a selected server we still show pinned channels so
        // the TechEmpower peer-support seed is reachable immediately
        // after the user configures a bot token. The empty-state copy
        // for the server picker stays in Settings.
        if (state.serverId.isBlank()) {
            return FictionResult.Success(
                ListPage(items = pinnedItems, page = 1, hasNext = false),
            )
        }

        return api.listChannels(state.serverId).map { channels ->
            val serverItems = channels
                .filter { it.type == 0 || it.type == 5 }
                .sortedBy { it.position }
                .map { channel ->
                    FictionSummary(
                        id = discordFictionId(channel.id),
                        sourceId = SourceIds.DISCORD,
                        title = "#${channel.name}",
                        // Per the spec mapping: server name acts as the
                        // author placeholder. Falls back to a generic
                        // label if the server-picker step didn't
                        // capture a friendly name.
                        author = state.serverName.ifBlank { "Discord" },
                        description = channel.topic?.ifBlank { null },
                        status = FictionStatus.ONGOING,
                    )
                }
            // Pinned-first ordering — peer-support always above the
            // user's own channels. Dedupe in case a pinned channel
            // happens to live in the user's selected server (the
            // FictionSummary id is `discord:<channelId>` so set-based
            // dedupe is enough).
            val seen = mutableSetOf<String>()
            val combined = (pinnedItems + serverItems).filter { seen.add(it.id) }
            ListPage(items = combined, page = 1, hasNext = false)
        }
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        // Discord doesn't expose a "recently-active channels" ordering
        // distinct from the channel list — the list endpoint returns
        // position-ordered (which the server admin controls). Collapse
        // to popular() for v1; activity-based reordering is a follow-up
        // that'd require N extra round-trips to fetch the last message
        // per channel.
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // Discord has no built-in genre / category faceting. Out of
        // scope for v1; users can search by keyword instead.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    /**
     * Discord's `/guilds/{id}/messages/search` endpoint. Full-text
     * search restricted to the configured server. Each match
     * surfaces as a fiction whose id is the *channel* containing the
     * matched message — opening it lands the user on the channel's
     * full timeline (the search result is a discovery shortcut, not
     * a standalone fiction).
     */
    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val state = config.current()
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Discord bot token not configured.",
            )
        }
        if (state.serverId.isBlank()) {
            return FictionResult.NotFound(
                "No Discord server selected.",
            )
        }
        val term = query.term.trim()
        if (term.isEmpty()) {
            return FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))
        }
        return api.searchMessages(state.serverId, term).map { response ->
            // Dedupe by head message id — Discord's search response
            // doesn't include channel_id on every variant, so the
            // best stable anchor is the head message id itself. Each
            // hit surfaces as a single search result; opening it
            // routes to the channel-level fiction the message
            // belongs to once the chapter resolver finds it (or the
            // user goes to the channel via the Browse popular tab).
            val items = response.messages.mapNotNull { match ->
                val head = match.firstOrNull() ?: return@mapNotNull null
                FictionSummary(
                    id = discordFictionId(head.id),
                    sourceId = SourceIds.DISCORD,
                    title = head.contentPreview(),
                    author = head.author.displayName(),
                    description = head.content.ifBlank { null },
                    status = FictionStatus.ONGOING,
                )
            }
            ListPage(items = items, page = 1, hasNext = false)
        }
    }

    // ─── detail ────────────────────────────────────────────────────────

    /**
     * One channel's recent message history → chapter list. We fetch
     * the most recent 100 messages, reverse to chronological order,
     * filter out system messages (joins, pins, etc.), then walk
     * forward grouping consecutive same-author messages within the
     * coalesce window.
     *
     * The chapter title is `"{author} — {snippet}"` where snippet is
     * the first ~60 chars of the head message's content. Empty
     * snippets (attachment-only messages) fall back to
     * `"{author} — Attachment: {filename}"`.
     */
    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val state = config.current()
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Discord token not configured.",
            )
        }
        val channelId = fictionId.toChannelId()
            ?: return FictionResult.NotFound("Discord fiction id not recognized: $fictionId")

        // Issue #517 — pinned channels (e.g. the TechEmpower peer-
        // support channel) don't necessarily live in the user's
        // selected server. For those, we skip the `/guilds/{id}/channels`
        // metadata lookup entirely and render the channel-level
        // summary from the pinned-channel hardcoded label, then walk
        // the messages directly.
        val isPinned = channelId in state.pinnedChannelIds
        if (!isPinned && state.serverId.isBlank()) {
            return FictionResult.NotFound(
                "No Discord server selected. Pick one in Settings → Library & Sync → Discord.",
            )
        }

        // For non-pinned channels we look up the channel metadata
        // (title/topic) by walking the configured server's channel
        // list. Discord doesn't expose a single-channel-by-id endpoint
        // to bots without the MANAGE_CHANNELS scope, which storyvox
        // doesn't ask for.
        val channelName: String
        val channelTopic: String?
        val channelAuthor: String
        if (isPinned) {
            // Pinned channels: stable label (the peer-support label is
            // the same one rendered in popular()). If we ever expose a
            // pinned channel from a server the user IS in, the dedupe
            // in popular() collapses the duplicate; here we still
            // surface it as the TechEmpower-branded entry so the
            // detail screen matches the Browse tile.
            channelName = "peer-support"
            channelTopic = "TechEmpower's peer-support Discord — listen to past messages from the community."
            channelAuthor = "TechEmpower"
        } else {
            val channels = when (val r = api.listChannels(state.serverId)) {
                is FictionResult.Success -> r.value
                is FictionResult.Failure -> return r
            }
            val channel = channels.firstOrNull { it.id == channelId }
                ?: return FictionResult.NotFound(
                    "Discord channel $channelId not found in server ${state.serverId}",
                )
            channelName = channel.name
            channelTopic = channel.topic?.ifBlank { null }
            channelAuthor = state.serverName.ifBlank { "Discord" }
        }

        val messages = when (val r = api.listMessages(channelId, before = null, limit = 100)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }

        // Discord returns messages newest-first; reverse for
        // chronological reading. Filter out system messages (type
        // != 0) — joins, boosts, pin events, etc. don't belong in
        // an audiobook chapter list.
        val chronological = messages.asReversed().filter { it.type == 0 }
        val groups = coalesceMessages(chronological, state.coalesceMinutes)

        val chapters = groups.mapIndexed { idx, group ->
            ChapterInfo(
                id = chapterIdFor(fictionId, group.headId),
                sourceChapterId = "msg-${group.headId}",
                index = idx,
                title = group.title(),
                publishedAt = group.headTimestampMillis,
            )
        }

        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.DISCORD,
            title = "#$channelName",
            author = channelAuthor,
            description = channelTopic,
            status = FictionStatus.ONGOING,
            chapterCount = chapters.size,
        )
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    /**
     * One chapter body. Re-fetches the channel's recent history,
     * locates the message whose id matches the chapter's head
     * message, collects coalesced siblings, and renders the
     * combined plain-text + attachment-filename body.
     *
     * We re-fetch rather than caching because the repository layer
     * owns caching for storyvox; this source is stateless w.r.t.
     * the caller.
     */
    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val state = config.current()
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Discord token not configured.",
            )
        }
        val channelId = fictionId.toChannelId()
            ?: return FictionResult.NotFound("Discord fiction id not recognized: $fictionId")
        // Issue #517 — pinned channels (e.g. TechEmpower peer-support)
        // can be read without a selected server because the messages
        // endpoint is channel-scoped. Other channel ids still require
        // a selected server (the popular() / fictionDetail() paths
        // wouldn't have surfaced them otherwise).
        val isPinned = channelId in state.pinnedChannelIds
        if (!isPinned && state.serverId.isBlank()) {
            return FictionResult.NotFound(
                "No Discord server selected. Pick one in Settings → Library & Sync → Discord.",
            )
        }
        val headMessageId = chapterId.substringAfterLast("::msg-", "")
            .takeIf { it.isNotBlank() }
            ?: return FictionResult.NotFound("Discord chapter id not recognized: $chapterId")

        return when (val r = api.listMessages(channelId, before = null, limit = 100)) {
            is FictionResult.Success -> {
                val chronological = r.value.asReversed().filter { it.type == 0 }
                val groups = coalesceMessages(chronological, state.coalesceMinutes)
                val group = groups.firstOrNull { it.headId == headMessageId }
                    ?: return FictionResult.NotFound(
                        "Discord chapter (message $headMessageId) not in recent history",
                    )
                val index = groups.indexOf(group)
                val info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = "msg-${group.headId}",
                    index = index,
                    title = group.title(),
                    publishedAt = group.headTimestampMillis,
                )
                FictionResult.Success(
                    ChapterContent(
                        info = info,
                        htmlBody = group.toHtml(),
                        plainBody = group.toPlainText(),
                    ),
                )
            }
            is FictionResult.Failure -> r
        }
    }

    // ─── follow ────────────────────────────────────────────────────────

    /**
     * Discord channels don't have a "follow" concept distinct from
     * "be in the server". The user already chose to be in the server
     * (via the bot invite); a per-channel follow toggle would be UI
     * inconsistency. [supportsFollow] is overridden to false so the
     * FictionDetail Follow button stays hidden for Discord fictions.
     */
    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)
}

// ─── helpers ──────────────────────────────────────────────────────────

/** Stable Discord fiction id from a channel id. */
internal fun discordFictionId(channelId: String): String = "discord:$channelId"

/** Compose a chapter id from a fiction id + head message id. */
internal fun chapterIdFor(fictionId: String, headMessageId: String): String =
    "$fictionId::msg-$headMessageId"

/** Decode the channel id from a Discord fiction id, or null when the
 *  id doesn't carry the `discord:` prefix. */
internal fun String.toChannelId(): String? =
    if (startsWith("discord:")) removePrefix("discord:").substringBefore("::")
    else null

/**
 * One coalesced chapter — a contiguous run of same-author messages
 * within the coalesce window. The head message anchors the chapter
 * id; siblings are rendered into the body.
 */
internal data class DiscordMessageGroup(
    /** Head message id — anchors the chapter id. */
    val headId: String,
    /** Author display name (globalName > username). */
    val authorName: String,
    /** All messages in the group, in chronological order. The head
     *  is `messages.first()`. */
    val messages: List<DiscordMessage>,
    /** Head message timestamp in unix millis, for ChapterInfo.publishedAt. */
    val headTimestampMillis: Long?,
)

/**
 * Build a chapter title from a coalesced group. Format:
 * `"{author} — {snippet}"` where snippet is the first ~60 chars
 * of the head message. Attachment-only messages render as
 * `"{author} — Attachment: {filename}"`.
 */
internal fun DiscordMessageGroup.title(): String {
    val head = messages.first()
    val snippet = head.contentPreview()
    return "$authorName — $snippet"
}

/** Render the group as HTML — one `<p>` per message, attachments
 *  surfaced as `<p>Attachment: …</p>` lines so the reader view + TTS
 *  both mention them. */
internal fun DiscordMessageGroup.toHtml(): String =
    messages.joinToString("\n") { m ->
        val parts = buildList {
            if (m.content.isNotBlank()) add("<p>${htmlEscape(m.content)}</p>")
            for (att in m.attachments) {
                add("<p>Attachment: ${htmlEscape(att.filename)}</p>")
            }
            for (embed in m.embeds) {
                val title = embed.title?.takeIf { it.isNotBlank() }
                val desc = embed.description?.takeIf { it.isNotBlank() }
                if (title != null) add("<p>Link: ${htmlEscape(title)}</p>")
                if (desc != null) add("<p>${htmlEscape(desc)}</p>")
            }
        }
        parts.joinToString("\n")
    }

/** Plain-text projection of the group for TTS. Strips markup;
 *  attachments + embeds become `"Attachment: name"` /
 *  `"Link: title"` lines so they narrate naturally. */
internal fun DiscordMessageGroup.toPlainText(): String =
    messages.joinToString("\n\n") { m ->
        val lines = buildList {
            if (m.content.isNotBlank()) add(m.content)
            for (att in m.attachments) add("Attachment: ${att.filename}")
            for (embed in m.embeds) {
                val title = embed.title?.takeIf { it.isNotBlank() }
                val desc = embed.description?.takeIf { it.isNotBlank() }
                if (title != null) add("Link: $title")
                if (desc != null) add(desc)
            }
        }
        lines.joinToString("\n")
    }.trim()

/**
 * Coalesce a chronological list of messages into groups, where each
 * group is a run of consecutive same-author messages whose adjacent
 * timestamps are within [coalesceMinutes] of each other.
 *
 * Edge cases:
 *  - Empty input → empty output.
 *  - `coalesceMinutes <= 0` → every message is its own group (the
 *    UI slider's minimum is 1, but we defend in case a stored value
 *    drifts).
 *  - Messages with unparseable timestamps fall back to "always
 *    boundary" — they don't merge with neighbours. This is the
 *    safest behavior; merging on unknown time would silently
 *    collapse long runs.
 */
internal fun coalesceMessages(
    chronological: List<DiscordMessage>,
    coalesceMinutes: Int,
): List<DiscordMessageGroup> {
    if (chronological.isEmpty()) return emptyList()
    val windowMs = coalesceMinutes.coerceAtLeast(0).toLong() * 60_000L
    val groups = mutableListOf<DiscordMessageGroup>()
    var currentMessages = mutableListOf<DiscordMessage>()
    var currentAuthor: String? = null
    var currentLastTs: Long? = null

    fun flush() {
        if (currentMessages.isEmpty()) return
        val head = currentMessages.first()
        groups.add(
            DiscordMessageGroup(
                headId = head.id,
                authorName = head.author.displayName(),
                messages = currentMessages.toList(),
                headTimestampMillis = parseDiscordTimestamp(head.timestamp),
            ),
        )
        currentMessages = mutableListOf()
        currentAuthor = null
        currentLastTs = null
    }

    for (m in chronological) {
        val ts = parseDiscordTimestamp(m.timestamp)
        val sameAuthor = currentAuthor == m.author.id
        val withinWindow = windowMs > 0 && ts != null && currentLastTs != null &&
            (ts - currentLastTs!!) <= windowMs
        if (sameAuthor && withinWindow) {
            currentMessages.add(m)
            currentLastTs = ts
        } else {
            flush()
            currentMessages.add(m)
            currentAuthor = m.author.id
            currentLastTs = ts
        }
    }
    flush()
    return groups
}

/**
 * Parse a Discord ISO-8601 timestamp into unix millis, or null if
 * the string isn't parseable. Discord's format is
 * `2026-05-13T19:42:11.123000+00:00` (microsecond precision,
 * explicit offset). We use the JDK's `java.time.OffsetDateTime`
 * which handles the standard ISO-8601 variants without needing to
 * pull in a third-party date library.
 */
internal fun parseDiscordTimestamp(s: String): Long? = runCatching {
    java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
}.getOrNull()

/** Resolve a display name for the author: globalName (when present
 *  and non-blank) → username → "Unknown". The fallback chain matches
 *  what Discord's own client shows in the message header for users
 *  who haven't set a server-scoped nickname. */
internal fun `in`.jphe.storyvox.source.discord.net.DiscordUser.displayName(): String =
    globalName?.takeIf { it.isNotBlank() }
        ?: username.takeIf { it.isNotBlank() }
        ?: "Unknown"

/** First ~60 chars of the message content, with newlines collapsed
 *  to spaces and trailing whitespace trimmed. Falls back to
 *  `"Attachment: filename"` when content is blank but an attachment
 *  exists, then to a generic placeholder. */
internal fun DiscordMessage.contentPreview(): String {
    if (content.isNotBlank()) {
        val flat = content.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= 60) flat else flat.take(57).trimEnd() + "…"
    }
    val firstAttachment = attachments.firstOrNull()
    if (firstAttachment != null) return "Attachment: ${firstAttachment.filename}"
    val firstEmbed = embeds.firstOrNull()
    if (firstEmbed != null) {
        val title = firstEmbed.title?.takeIf { it.isNotBlank() }
        if (title != null) return "Link: $title"
    }
    return "(empty message)"
}

/** Minimal HTML escape — angle brackets, ampersand, double-quote. */
internal fun htmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
