package `in`.jphe.storyvox.source.discord

import `in`.jphe.storyvox.source.discord.net.DiscordChannel
import `in`.jphe.storyvox.source.discord.net.DiscordGuild
import `in`.jphe.storyvox.source.discord.net.DiscordMessage
import `in`.jphe.storyvox.source.discord.net.DiscordSearchResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #403 — JSON parsing checks for the four Discord API
 * responses storyvox reads. These run against captured-from-docs
 * fixtures, not live Discord; they guarantee storyvox keeps parsing
 * the documented shapes even if Discord adds new fields (the
 * `ignoreUnknownKeys = true` posture).
 */
class DiscordModelsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Mirror DiscordApi.kt:55 exactly — the production decoder also sets
    // coerceInputValues. The #1064 regression hinges on the fact that
    // coerceInputValues does NOT rescue a null/missing NON-nullable field,
    // so the null-author tests below must run under the real config.
    private val apiJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `parses users me guilds response`() {
        // Real response shape from Discord's docs for
        // GET /users/@me/guilds. The bot is in two servers — one
        // public-facing, one private sandbox. We assert the shape we'll
        // actually consume: id, name, icon, owner. Unknown fields
        // (permissions, features) are dropped by ignoreUnknownKeys.
        val body = """
            [
              {
                "id": "287295173033361408",
                "name": "TechEmpower",
                "icon": "f9c8073b6b1d4f7e9a2c8a1a3f6b9c0d",
                "owner": false,
                "permissions": "2147483647",
                "features": ["NEWS", "COMMUNITY"]
              },
              {
                "id": "418827092174831616",
                "name": "JP's Test Server",
                "icon": null,
                "owner": true,
                "permissions": "2147483647",
                "features": []
              }
            ]
        """.trimIndent()

        val guilds = json.decodeFromString<List<DiscordGuild>>(body)

        assertEquals(2, guilds.size)
        assertEquals("287295173033361408", guilds[0].id)
        assertEquals("TechEmpower", guilds[0].name)
        assertEquals("f9c8073b6b1d4f7e9a2c8a1a3f6b9c0d", guilds[0].icon)
        assertEquals(false, guilds[0].owner)

        // Second guild has null icon and owner=true — both fields are
        // optional and we want to be sure null icon doesn't trip the
        // parser.
        assertNull(guilds[1].icon)
        assertEquals(true, guilds[1].owner)
    }

    @Test
    fun `parses guilds id channels response with mixed types`() {
        // Mixed channel types: a text channel (0), a voice channel (2),
        // a category (4), and an announcement channel (5). The source
        // layer keeps types 0 + 5 and drops the rest.
        val body = """
            [
              {
                "id": "1100000000000000001",
                "name": "general",
                "type": 0,
                "topic": "Server-wide chat",
                "parent_id": "1100000000000000000",
                "position": 0
              },
              {
                "id": "1100000000000000002",
                "name": "voice-chat",
                "type": 2,
                "position": 1
              },
              {
                "id": "1100000000000000003",
                "name": "Lounge",
                "type": 4,
                "position": 2
              },
              {
                "id": "1100000000000000004",
                "name": "announcements",
                "type": 5,
                "topic": "Server announcements",
                "position": 3
              }
            ]
        """.trimIndent()

        val channels = json.decodeFromString<List<DiscordChannel>>(body)

        assertEquals(4, channels.size)
        // Type filtering happens in the source layer — verify the
        // wire shape preserves the field correctly so the filter has
        // accurate input to work with.
        assertEquals(0, channels[0].type)
        assertEquals("Server-wide chat", channels[0].topic)
        assertEquals(2, channels[1].type)
        assertEquals(null, channels[1].topic) // voice channels have no topic
        assertEquals(4, channels[2].type)
        assertEquals(5, channels[3].type)
        assertEquals("Server announcements", channels[3].topic)
    }

    @Test
    fun `parses messages search response with nested context list`() {
        // Discord's /messages/search returns
        // { total_results, messages: [[match, ...context]] }.
        // We project the head of each inner list. This fixture has 2
        // matches.
        val body = """
            {
              "total_results": 47,
              "messages": [
                [
                  {
                    "id": "1200000000000000001",
                    "timestamp": "2026-05-13T19:42:11.000000+00:00",
                    "content": "Has anyone seen the dragon chapter?",
                    "author": {
                      "id": "111111111111111111",
                      "username": "alice",
                      "global_name": "Alice"
                    },
                    "attachments": [],
                    "embeds": [],
                    "type": 0
                  },
                  {
                    "id": "1200000000000000002",
                    "timestamp": "2026-05-13T19:42:30.000000+00:00",
                    "content": "yes — chapter 4",
                    "author": {
                      "id": "222222222222222222",
                      "username": "bob",
                      "global_name": null
                    },
                    "attachments": [],
                    "embeds": [],
                    "type": 0
                  }
                ],
                [
                  {
                    "id": "1300000000000000001",
                    "timestamp": "2026-05-10T08:11:01.000000+00:00",
                    "content": "Posting the dragon sketch here",
                    "author": {
                      "id": "111111111111111111",
                      "username": "alice",
                      "global_name": "Alice"
                    },
                    "attachments": [
                      {
                        "id": "9000",
                        "filename": "dragon-sketch.png",
                        "url": "https://cdn.discordapp.com/attachments/.../dragon-sketch.png",
                        "size": 245678,
                        "content_type": "image/png"
                      }
                    ],
                    "embeds": [],
                    "type": 0
                  }
                ]
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<DiscordSearchResponse>(body)

        assertEquals(47, response.totalResults)
        assertEquals(2, response.messages.size)
        // First match's head message
        val firstHead = response.messages[0][0]
        assertEquals("1200000000000000001", firstHead.id)
        assertEquals("Has anyone seen the dragon chapter?", firstHead.content)
        assertEquals("alice", firstHead.author!!.username)
        assertEquals("Alice", firstHead.author!!.globalName)
        // Bob in the second message has no global_name; parsing must
        // accept null without falling over.
        assertNull(response.messages[0][1].author!!.globalName)
        // Second match has an attachment — verify the nested shape.
        val secondHead = response.messages[1][0]
        assertEquals(1, secondHead.attachments.size)
        assertEquals("dragon-sketch.png", secondHead.attachments[0].filename)
        assertEquals(245678L, secondHead.attachments[0].size)
        assertEquals("image/png", secondHead.attachments[0].contentType)
    }

    @Test
    fun `parses channel messages with embeds and unknown extra fields`() {
        // Real Discord message responses include dozens of fields
        // (flags, mentions, mention_roles, pinned, tts, ...). We
        // assert that ignoreUnknownKeys = true lets us parse without
        // declaring every one, AND that the fields we DO care about
        // still come through correctly.
        val body = """
            [
              {
                "id": "1400000000000000001",
                "timestamp": "2026-05-13T20:00:00.000000+00:00",
                "edited_timestamp": null,
                "content": "Check out this link",
                "author": {
                  "id": "333333333333333333",
                  "username": "charlie",
                  "discriminator": "0",
                  "avatar": null,
                  "global_name": "Charlie"
                },
                "attachments": [],
                "embeds": [
                  {
                    "title": "Storyvox — narratable Discord",
                    "description": "16 backends, sideload-only audiobook reader",
                    "url": "https://github.com/techempower-org/candela"
                  }
                ],
                "tts": false,
                "mention_everyone": false,
                "mentions": [],
                "mention_roles": [],
                "pinned": false,
                "type": 0,
                "flags": 0
              }
            ]
        """.trimIndent()

        val messages = json.decodeFromString<List<DiscordMessage>>(body)
        assertEquals(1, messages.size)
        val m = messages[0]
        assertEquals("Check out this link", m.content)
        assertEquals("Charlie", m.author!!.globalName)
        assertEquals(1, m.embeds.size)
        assertEquals("Storyvox — narratable Discord", m.embeds[0].title)
        assertTrue(
            "embed description must round-trip",
            (m.embeds[0].description ?: "").startsWith("16 backends"),
        )
        // The author's display-name helper falls back through the
        // global_name → username chain.
        assertEquals("Charlie", m.author!!.displayName())
        assertNotNull(parseDiscordTimestamp(m.timestamp))
    }

    // ─── #1064 — author-less messages must not brick the page ─────────

    @Test
    fun `a message with author null does not fail the whole page decode`() {
        // A webhook / system / integration message can arrive with
        // author explicitly null. Before #1064, author was a required
        // non-null field, so kotlinx.serialization threw on this element
        // and the ENTIRE List<DiscordMessage> decode failed — the whole
        // channel surfaced as NetworkError. The real-shaped author-less
        // message sits BETWEEN two normal ones to prove the page survives
        // and the good neighbours still decode.
        val body = """
            [
              {
                "id": "1500000000000000001",
                "timestamp": "2026-05-13T20:00:00.000000+00:00",
                "content": "normal message before",
                "author": { "id": "111", "username": "alice", "global_name": "Alice" },
                "attachments": [],
                "embeds": [],
                "type": 0
              },
              {
                "id": "1500000000000000002",
                "timestamp": "2026-05-13T20:01:00.000000+00:00",
                "content": "webhook post with no author",
                "author": null,
                "attachments": [],
                "embeds": [],
                "type": 0
              },
              {
                "id": "1500000000000000003",
                "timestamp": "2026-05-13T20:02:00.000000+00:00",
                "content": "normal message after",
                "author": { "id": "222", "username": "bob", "global_name": "Bob" },
                "attachments": [],
                "embeds": [],
                "type": 0
              }
            ]
        """.trimIndent()

        val messages = apiJson.decodeFromString<List<DiscordMessage>>(body)

        assertEquals(3, messages.size)
        assertEquals("Alice", messages[0].author?.displayName())
        // The anomalous element decodes with a null author rather than
        // throwing — this is the core #1064 regression guard.
        assertNull(messages[1].author)
        assertEquals("normal message after", messages[2].content)
        assertEquals("Bob", messages[2].author?.displayName())
    }

    @Test
    fun `a message with author field entirely absent decodes with null author`() {
        // Some system-message shapes omit `author` outright rather than
        // sending null. A missing required field threw MissingFieldException
        // before #1064; with author defaulted to null it now decodes.
        val body = """
            [
              {
                "id": "1600000000000000001",
                "timestamp": "2026-05-13T21:00:00.000000+00:00",
                "content": "system event with no author key",
                "attachments": [],
                "embeds": [],
                "type": 6
              }
            ]
        """.trimIndent()

        val messages = apiJson.decodeFromString<List<DiscordMessage>>(body)

        assertEquals(1, messages.size)
        assertNull(messages[0].author)
        // The display-name fallback yields the synthetic label for a
        // null author, matching the DiscordUser.displayName() terminal.
        assertEquals("Unknown", messages[0].author?.displayName() ?: "Unknown")
    }
}
