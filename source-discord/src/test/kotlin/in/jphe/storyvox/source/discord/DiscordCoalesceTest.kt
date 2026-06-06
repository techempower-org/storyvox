package `in`.jphe.storyvox.source.discord

import `in`.jphe.storyvox.source.discord.net.DiscordMessage
import `in`.jphe.storyvox.source.discord.net.DiscordUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #403 — coalescing rules for Discord messages →
 * storyvox chapters. The subtle part: two messages from the same
 * author within the window merge; same-author crossing the window
 * splits; different-author always splits regardless of timing.
 *
 * The window is closed-interval on both sides for symmetry — exactly
 * window-minutes apart is "still inside the window" and merges. The
 * UI slider exposes integer minutes; the [coalesceMessages] helper
 * accepts any non-negative int.
 */
class DiscordCoalesceTest {

    private fun msg(
        id: String,
        authorId: String,
        timestamp: String,
        content: String = "msg-$id",
    ): DiscordMessage = DiscordMessage(
        id = id,
        timestamp = timestamp,
        content = content,
        author = DiscordUser(id = authorId, username = "user-$authorId", globalName = null),
        attachments = emptyList(),
        embeds = emptyList(),
        type = 0,
    )

    @Test
    fun `same author within window coalesces into one group`() {
        // Three messages from alice, each 1 minute apart. With a 5
        // minute window they all collapse into one chapter.
        val messages = listOf(
            msg("1", "alice", "2026-05-13T19:00:00.000000+00:00", "First thought"),
            msg("2", "alice", "2026-05-13T19:01:00.000000+00:00", "Adding to that"),
            msg("3", "alice", "2026-05-13T19:02:00.000000+00:00", "And one more thing"),
        )

        val groups = coalesceMessages(messages, coalesceMinutes = 5)

        assertEquals(1, groups.size)
        assertEquals("1", groups[0].headId)
        assertEquals(3, groups[0].messages.size)
        // The chapter title is built from the head message preview.
        assertTrue(
            "chapter title must lead with author name",
            groups[0].title().startsWith("user-alice"),
        )
        // The head publishedAt timestamp must round-trip from the head
        // message's ISO timestamp.
        assertEquals(
            parseDiscordTimestamp("2026-05-13T19:00:00.000000+00:00"),
            groups[0].headTimestampMillis,
        )
    }

    @Test
    fun `same author crossing window splits into two groups`() {
        // Three messages from alice: first two within 1 minute, third
        // 10 minutes after the second. With a 5-min window the third
        // should start a new chapter — the "story paused, then alice
        // returned with a new thought" shape.
        val messages = listOf(
            msg("1", "alice", "2026-05-13T19:00:00.000000+00:00"),
            msg("2", "alice", "2026-05-13T19:01:00.000000+00:00"),
            msg("3", "alice", "2026-05-13T19:11:00.000000+00:00"),
        )

        val groups = coalesceMessages(messages, coalesceMinutes = 5)

        assertEquals(2, groups.size)
        assertEquals(listOf("1", "2"), groups[0].messages.map { it.id })
        assertEquals(listOf("3"), groups[1].messages.map { it.id })
    }

    @Test
    fun `different authors always split regardless of window`() {
        // Alice + bob alternating, all within seconds of each other.
        // Even with a huge 30-minute window, every transition is a
        // chapter boundary.
        val messages = listOf(
            msg("1", "alice", "2026-05-13T19:00:00.000000+00:00"),
            msg("2", "bob", "2026-05-13T19:00:10.000000+00:00"),
            msg("3", "alice", "2026-05-13T19:00:20.000000+00:00"),
            msg("4", "bob", "2026-05-13T19:00:30.000000+00:00"),
        )

        val groups = coalesceMessages(messages, coalesceMinutes = 30)

        assertEquals(4, groups.size)
        assertEquals("alice", groups[0].messages.first().author!!.id)
        assertEquals("bob", groups[1].messages.first().author!!.id)
        assertEquals("alice", groups[2].messages.first().author!!.id)
        assertEquals("bob", groups[3].messages.first().author!!.id)
    }

    @Test
    fun `alice cluster then bob interrupt then alice continues = three groups`() {
        // alice-A-A-A within window, bob breaks, alice resumes A-A
        // within window. Three groups total: {1,2,3}, {4}, {5,6}.
        val messages = listOf(
            msg("1", "alice", "2026-05-13T19:00:00.000000+00:00"),
            msg("2", "alice", "2026-05-13T19:01:00.000000+00:00"),
            msg("3", "alice", "2026-05-13T19:02:00.000000+00:00"),
            msg("4", "bob", "2026-05-13T19:03:00.000000+00:00"),
            msg("5", "alice", "2026-05-13T19:04:00.000000+00:00"),
            msg("6", "alice", "2026-05-13T19:05:00.000000+00:00"),
        )

        val groups = coalesceMessages(messages, coalesceMinutes = 5)

        assertEquals(3, groups.size)
        assertEquals(listOf("1", "2", "3"), groups[0].messages.map { it.id })
        assertEquals(listOf("4"), groups[1].messages.map { it.id })
        assertEquals(listOf("5", "6"), groups[2].messages.map { it.id })
    }

    @Test
    fun `zero window disables coalescing — each message becomes its own group`() {
        // Defensive guard: the UI slider's minimum is 1 minute, but
        // if a stored value somehow drifts to 0 we don't want a
        // crash. coalesceMinutes <= 0 means "no coalescing — each
        // message is its own chapter".
        val messages = listOf(
            msg("1", "alice", "2026-05-13T19:00:00.000000+00:00"),
            msg("2", "alice", "2026-05-13T19:00:01.000000+00:00"),
            msg("3", "alice", "2026-05-13T19:00:02.000000+00:00"),
        )

        val groups = coalesceMessages(messages, coalesceMinutes = 0)

        assertEquals(3, groups.size)
        groups.forEach { assertEquals(1, it.messages.size) }
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals(emptyList<Any>(), coalesceMessages(emptyList(), coalesceMinutes = 5))
    }
}
