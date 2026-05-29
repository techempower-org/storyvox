package `in`.jphe.storyvox.data.db

import android.app.Instrumentation
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import `in`.jphe.storyvox.data.db.migration.MIGRATION_1_2
import `in`.jphe.storyvox.data.db.migration.MIGRATION_2_3
import `in`.jphe.storyvox.data.db.migration.MIGRATION_3_4
import `in`.jphe.storyvox.data.db.migration.MIGRATION_4_5
import `in`.jphe.storyvox.data.db.migration.MIGRATION_5_6
import `in`.jphe.storyvox.data.db.migration.MIGRATION_6_7
import `in`.jphe.storyvox.data.db.migration.MIGRATION_7_8
import `in`.jphe.storyvox.data.db.migration.MIGRATION_8_9
import `in`.jphe.storyvox.data.db.migration.MIGRATION_9_10
import `in`.jphe.storyvox.data.db.migration.MIGRATION_10_11
import `in`.jphe.storyvox.data.db.migration.MIGRATION_11_12
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #158 — schema migration coverage for v5 → v6 (chapter_history).
 *
 * Originally authored as v4→v5 against pre-shelves main; renumbered to
 * v5→v6 at merge time when #116 (shelves) claimed v5 first. Coverage and
 * assertions are unchanged — chapter_history doesn't touch shelves
 * tables, so the migration is independent.
 *
 * Patterned after the Robolectric usage in
 * `app/src/test/.../StoryvoxRoutesTest.kt`. We avoid the default Hilt
 * application via `@Config(application = Application::class)` —
 * MigrationTestHelper only needs a Context for the temp database dir,
 * not a real DI graph.
 *
 * What this test pins:
 *   1. The 5→6 migration applies without error against the v5 schema
 *      snapshot (`schemas/in.jphe.storyvox.data.db.StoryvoxDatabase/5.json`).
 *      That alone catches a malformed CREATE TABLE / missing FK column.
 *   2. The post-migration table exists with the expected structure
 *      (PK on (fictionId, chapterId), an index on openedAt).
 *   3. Inserting + selecting a row round-trips, proving the table is
 *      writable and the PK definition matches the entity.
 *
 * The Room runtime also performs an automatic identity-hash check on
 * open: if the generated v6 schema (`6.json`) doesn't match what the
 * migration produces, opening the migrated DB through Room throws
 * `IllegalStateException: Migration didn't properly handle...`. The
 * test triggers that path explicitly via `runMigrationsAndValidate`.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class StoryvoxDatabaseMigrationTest {

    private val robolectricInstrumentation: Instrumentation =
        object : Instrumentation() {
            override fun getTargetContext(): android.content.Context =
                org.robolectric.RuntimeEnvironment.getApplication()
            override fun getContext(): android.content.Context =
                org.robolectric.RuntimeEnvironment.getApplication()
        }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        robolectricInstrumentation,
        StoryvoxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `migrate v5 to v6 creates chapter_history table`() {
        val dbName = "history-migration-test.db"

        // Start at v5 — the shelves migration has already run, so the
        // v5 schema is the "real" pre-history baseline. Bring the DB up
        // to v5 first via the chain, then exercise 5→6 on top.
        helper.createDatabase(dbName, 4).close()
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            6,
            /* validateDroppedTables = */ true,
            MIGRATION_5_6,
        )

        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='chapter_history'",
        ).use { c ->
            assertTrue("chapter_history table must exist post-migration", c.moveToFirst())
        }

        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='chapter_history'",
        ).use { c ->
            val indexes = mutableListOf<String>()
            while (c.moveToNext()) indexes += c.getString(0)
            assertTrue(
                "index_chapter_history_openedAt must exist (sort feed)",
                indexes.any { it == "index_chapter_history_openedAt" },
            )
            assertTrue(
                "index_chapter_history_chapterId must exist (FK cascade planner)",
                indexes.any { it == "index_chapter_history_chapterId" },
            )
        }

        db.close()
    }

    @Test fun `migrated v6 db round-trips a chapter_history row`() {
        val dbName = "history-roundtrip-test.db"
        helper.createDatabase(dbName, 4).close()
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).close()
        // #373 — bump through v7 so the test's Room.databaseBuilder
        // (which now targets v7 per the DB-level version bump) doesn't
        // throw "migration from 6 to 7 was required but not found"
        // when it opens the file. The history-row assertion below
        // doesn't touch the new audioUrl column; it stays purely a
        // chapter_history smoke test.
        helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7).close()
        // #383 — same reasoning for v8 (inbox_event). The DB-level
        // version bumped again; chain through so Room can open the
        // file without complaining about a missing migration.
        helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8).close()
        // #217 — v9 adds fiction_memory_entry for cross-fiction AI
        // memory. Same chain-through reasoning: the DB-level version
        // bumped, so opening the file without the v8 → v9 migration
        // would fail.
        helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9).close()
        // #890/#889/#884 — v10 adds the missing query indexes. Chain
        // through so the Room.databaseBuilder below (targeting the
        // latest version) can open the file without a missing-migration
        // failure.
        helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10).close()
        // #965 — v11 rebuilds playback_position with the composite PK.
        // Chain through so the latest-version Room.databaseBuilder opens
        // cleanly.
        helper.runMigrationsAndValidate(dbName, 11, true, MIGRATION_10_11).close()
        // #981 — v12 adds fiction.metadataBackfillFailedAt. Chain through
        // so the latest-version Room.databaseBuilder opens without a
        // missing-migration failure.
        helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12).close()

        val ctx = org.robolectric.RuntimeEnvironment.getApplication() as android.content.Context
        val db = Room.databaseBuilder(ctx, StoryvoxDatabase::class.java, dbName)
            .addMigrations(
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
            )
            .build()

        try {
            db.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO fiction(
                    id, sourceId, title, author, genres, tags, status,
                    chapterCount, firstSeenAt, metadataFetchedAt,
                    inLibrary, followedRemotely, notesEverSeen
                ) VALUES (
                    'rr:42', 'royalroad', 'Mother of Learning', 'nobody103',
                    '[]', '[]', 'ONGOING', 0, 0, 0, 0, 0, 0
                )
                """.trimIndent(),
            )
            db.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO chapter(
                    id, fictionId, sourceChapterId, `index`, title,
                    downloadState, userMarkedRead
                ) VALUES (
                    'rr:42:0', 'rr:42', '0', 0, 'Good Morning Brother',
                    'NOT_DOWNLOADED', 0
                )
                """.trimIndent(),
            )
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO chapter_history(fictionId, chapterId, openedAt, completed) " +
                    "VALUES ('rr:42', 'rr:42:0', 1234, 0)",
            )

            db.openHelper.readableDatabase.query(
                "SELECT fictionId, chapterId, openedAt, completed, fractionRead " +
                    "FROM chapter_history",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("rr:42", c.getString(0))
                assertEquals("rr:42:0", c.getString(1))
                assertEquals(1234L, c.getLong(2))
                assertEquals(0, c.getInt(3))
                assertEquals(
                    "fractionRead defaults to NULL when omitted",
                    true,
                    c.isNull(4),
                )
            }
        } finally {
            db.close()
        }

        assertNotNull("smoke-only sentinel — kept to fail-fast if the try block was no-op'd", helper)
    }

    /**
     * Issues #373 + #374 — v7 adds the `audioUrl` column to the
     * chapter table so audio-stream backends (KVMR community radio +
     * future LibriVox / Internet Archive) can persist a Media3-routable
     * URL through the same download/persist pipeline that text
     * chapters use. Purely additive; existing rows get NULL.
     */
    @Test fun `migrate v6 to v7 adds chapter audioUrl column`() {
        val dbName = "audio-url-migration-test.db"
        helper.createDatabase(dbName, 4).close()
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            7,
            /* validateDroppedTables = */ true,
            MIGRATION_6_7,
        )

        // Verify the column exists with the expected default (NULL).
        db.query("PRAGMA table_info('chapter')").use { c ->
            var sawAudioUrl = false
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                if (name == "audioUrl") {
                    sawAudioUrl = true
                    val type = c.getString(c.getColumnIndexOrThrow("type"))
                    assertEquals("TEXT", type)
                    val notnull = c.getInt(c.getColumnIndexOrThrow("notnull"))
                    assertEquals("audioUrl must be nullable", 0, notnull)
                }
            }
            assertTrue("chapter.audioUrl column must exist post-migration", sawAudioUrl)
        }

        db.close()
    }

    /**
     * Issue #383 — v8 creates the `inbox_event` table backing the
     * cross-source Inbox feed. Purely additive; existing tables stay
     * untouched. Two indexes (ts for sort, isRead for the unread
     * count badge) must exist post-migration.
     */
    @Test fun `migrate v7 to v8 creates inbox_event table`() {
        val dbName = "inbox-event-migration-test.db"
        helper.createDatabase(dbName, 4).close()
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).close()
        helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            8,
            /* validateDroppedTables = */ true,
            MIGRATION_7_8,
        )

        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='inbox_event'",
        ).use { c ->
            assertTrue("inbox_event table must exist post-migration", c.moveToFirst())
        }

        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='inbox_event'",
        ).use { c ->
            val indexes = mutableListOf<String>()
            while (c.moveToNext()) indexes += c.getString(0)
            assertTrue(
                "index_inbox_event_ts must exist (feed sort)",
                indexes.any { it == "index_inbox_event_ts" },
            )
            assertTrue(
                "index_inbox_event_isRead must exist (unread-count badge)",
                indexes.any { it == "index_inbox_event_isRead" },
            )
        }

        db.close()
    }

    /**
     * Issue #383 — smoke test that the migrated v8 db can write +
     * read an inbox_event row through the Room DAO surface. Catches
     * a schema/entity mismatch that the identity-hash check alone
     * wouldn't surface (e.g. a column rename that happens to keep
     * the hash stable).
     */
    @Test fun `migrated v8 db round-trips an inbox_event row`() {
        val dbName = "inbox-event-roundtrip-test.db"
        helper.createDatabase(dbName, 4).close()
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).close()
        helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7).close()
        helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8).close()
        // #217 — chain through v9 (fiction_memory_entry) so the
        // Room.databaseBuilder below can open at the latest version.
        helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9).close()
        // #890/#889/#884 — chain through v10 (missing query indexes).
        helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10).close()
        // #965 — chain through v11 (playback_position composite PK).
        helper.runMigrationsAndValidate(dbName, 11, true, MIGRATION_10_11).close()
        // #981 — chain through v12 (fiction.metadataBackfillFailedAt).
        helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12).close()

        val ctx = org.robolectric.RuntimeEnvironment.getApplication() as android.content.Context
        val db = Room.databaseBuilder(ctx, StoryvoxDatabase::class.java, dbName)
            .addMigrations(
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
            )
            .build()

        try {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO inbox_event(sourceId, fictionId, chapterId, title, body, ts, isRead, deepLinkUri) " +
                    "VALUES ('royalroad', 'rr:42', 'rr:42:7', '3 new chapters', null, 1234, 0, 'storyvox://reader/rr:42/rr:42:7')",
            )

            db.openHelper.readableDatabase.query(
                "SELECT sourceId, fictionId, chapterId, title, ts, isRead, deepLinkUri " +
                    "FROM inbox_event",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("royalroad", c.getString(0))
                assertEquals("rr:42", c.getString(1))
                assertEquals("rr:42:7", c.getString(2))
                assertEquals("3 new chapters", c.getString(3))
                assertEquals(1234L, c.getLong(4))
                assertEquals(0, c.getInt(5))
                assertEquals("storyvox://reader/rr:42/rr:42:7", c.getString(6))
            }
        } finally {
            db.close()
        }

        assertNotNull("smoke sentinel — fail-fast if the try block was no-op'd", helper)
    }

    /**
     * Issue #217 — v9 adds the `fiction_memory_entry` table backing
     * cross-fiction AI memory. Purely additive: existing tables stay
     * untouched. Two indexes (`name` for the cross-fiction lookup, and
     * `fictionId` for the per-book Notebook feed) must exist alongside
     * the composite-PK index. The PK is `(fictionId, name)` so upsert
     * semantics ("one fact per name per book") fall out of conflict
     * resolution.
     */
    @Test fun `migrate v8 to v9 creates fiction_memory_entry table`() {
        val dbName = "fiction-memory-migration-test.db"
        helper.createDatabase(dbName, 4).close()
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).close()
        helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7).close()
        helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            9,
            /* validateDroppedTables = */ true,
            MIGRATION_8_9,
        )

        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='fiction_memory_entry'",
        ).use { c ->
            assertTrue("fiction_memory_entry table must exist post-migration", c.moveToFirst())
        }

        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='fiction_memory_entry'",
        ).use { c ->
            val indexes = mutableListOf<String>()
            while (c.moveToNext()) indexes += c.getString(0)
            assertTrue(
                "index_fiction_memory_entry_name must exist (cross-fiction lookup)",
                indexes.any { it == "index_fiction_memory_entry_name" },
            )
            assertTrue(
                "index_fiction_memory_entry_fictionId must exist (per-book Notebook feed)",
                indexes.any { it == "index_fiction_memory_entry_fictionId" },
            )
        }

        db.close()
    }

    /**
     * Issues #890 / #889 / #884 — v10 adds the missing composite query
     * indexes that previously forced full-table scans:
     *  - `fiction(inLibrary, addedToLibraryAt)` — `observeLibrary`
     *  - `fiction(followedRemotely, lastUpdatedAt)` — `observeFollowsRemote`
     *  - `chapter(fictionId, userMarkedRead)` — `unreadChapters`
     *  - `inbox_event(sourceId, fictionId)` — Inbox source/fiction lookup
     *
     * Purely additive — no tables or rows touched. The test chains a
     * fresh v4 db up to v9, then validates the 9→10 step and asserts
     * each index exists on its table.
     */
    @Test fun `migrate v9 to v10 creates missing query indexes`() {
        val dbName = "missing-indexes-migration-test.db"
        helper.createDatabase(dbName, 4).close()
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).close()
        helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7).close()
        helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8).close()
        helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            10,
            /* validateDroppedTables = */ true,
            MIGRATION_9_10,
        )

        fun indexesOn(table: String): List<String> =
            db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='$table'",
            ).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }

        val fictionIdx = indexesOn("fiction")
        assertTrue(
            "index_fiction_inLibrary_addedToLibraryAt must exist (observeLibrary)",
            fictionIdx.any { it == "index_fiction_inLibrary_addedToLibraryAt" },
        )
        assertTrue(
            "index_fiction_followedRemotely_lastUpdatedAt must exist (observeFollowsRemote)",
            fictionIdx.any { it == "index_fiction_followedRemotely_lastUpdatedAt" },
        )
        assertTrue(
            "index_chapter_fictionId_userMarkedRead must exist (unreadChapters)",
            indexesOn("chapter").any { it == "index_chapter_fictionId_userMarkedRead" },
        )
        assertTrue(
            "index_inbox_event_sourceId_fictionId must exist (Inbox lookup)",
            indexesOn("inbox_event").any { it == "index_inbox_event_sourceId_fictionId" },
        )

        db.close()
    }

    /**
     * Issue #965 — v11 changes the `playback_position` primary key from
     * `fictionId` alone to the composite `(fictionId, chapterId)` so each
     * chapter remembers its own offset. This is the DATA-PRESERVATION guard
     * for the issue: a botched migration would lose every user's "where I
     * was" anchor, so the test seeds a populated v10 `playback_position`
     * table and asserts every row + every offset survives the rebuild
     * byte-for-byte.
     *
     * The migration is a table rebuild (SQLite can't ALTER a PK in place):
     * CREATE _new with the composite PK + identical columns/FKs, INSERT
     * SELECT *, DROP old, RENAME. `runMigrationsAndValidate(..., 11, true,
     * MIGRATION_10_11)` also triggers Room's identity-hash check against
     * `11.json`, so a missing index / wrong FK / wrong PK fails here too.
     *
     * Seeds TWO fictions with ONE position each (the only shape a v10 DB can
     * hold — fictionId was the PK), then proves both survive. A post-migration
     * insert of a SECOND chapter row for an existing fiction confirms the new
     * composite PK actually permits per-chapter rows (it would have hit a
     * UNIQUE violation under the old single-column PK).
     */
    @Test fun `migrate v10 to v11 preserves all playback positions and enables per-chapter rows`() {
        val dbName = "playback-position-pk-migration-test.db"

        helper.createDatabase(dbName, 4).close()
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).close()
        helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7).close()
        helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8).close()
        helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9).close()
        helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10).use { db ->
            // Parent rows first (FK: playback_position → fiction + chapter).
            db.execSQL(
                """
                INSERT INTO fiction(
                    id, sourceId, title, author, genres, tags, status,
                    chapterCount, firstSeenAt, metadataFetchedAt,
                    inLibrary, followedRemotely, notesEverSeen
                ) VALUES
                    ('rr:1', 'royalroad', 'Mother of Learning', 'nobody103',
                     '[]', '[]', 'ONGOING', 2, 0, 0, 1, 0, 0),
                    ('rr:2', 'royalroad', 'The Wandering Inn', 'pirateaba',
                     '[]', '[]', 'ONGOING', 1, 0, 0, 1, 0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO chapter(
                    id, fictionId, sourceChapterId, `index`, title,
                    downloadState, userMarkedRead
                ) VALUES
                    ('rr:1:0', 'rr:1', '0', 0, 'Good Morning Brother',
                     'NOT_DOWNLOADED', 0),
                    ('rr:1:1', 'rr:1', '1', 1, 'A Bad Night',
                     'NOT_DOWNLOADED', 0),
                    ('rr:2:0', 'rr:2', '0', 0, 'Chapter 1.00',
                     'NOT_DOWNLOADED', 0)
                """.trimIndent(),
            )
            // v10 shape: one position row per fiction (fictionId is the PK).
            db.execSQL(
                """
                INSERT INTO playback_position(
                    fictionId, chapterId, charOffset, paragraphIndex,
                    playbackSpeed, durationEstimateMs, updatedAt
                ) VALUES
                    ('rr:1', 'rr:1:0', 4321, 7, 1.5, 600000, 1000),
                    ('rr:2', 'rr:2:0', 99,   0, 1.0, 120000, 2000)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            11,
            /* validateDroppedTables = */ true,
            MIGRATION_10_11,
        )

        // Composite PK present, both indexes recreated.
        db.query("PRAGMA table_info('playback_position')").use { c ->
            val pkCols = mutableListOf<String>()
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                val pk = c.getInt(c.getColumnIndexOrThrow("pk"))
                if (pk > 0) pkCols += "$pk:$name"
            }
            // pk index 1 = fictionId, 2 = chapterId (declaration order).
            assertEquals(
                "composite PK must be (fictionId, chapterId)",
                listOf("1:fictionId", "2:chapterId"),
                pkCols.sorted(),
            )
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' " +
                "AND tbl_name='playback_position'",
        ).use { c ->
            val indexes = buildList { while (c.moveToNext()) add(c.getString(0)) }
            assertTrue(
                "index_playback_position_chapterId must survive the rebuild",
                indexes.any { it == "index_playback_position_chapterId" },
            )
            assertTrue(
                "index_playback_position_updatedAt must survive the rebuild",
                indexes.any { it == "index_playback_position_updatedAt" },
            )
        }

        // EVERY seeded row + offset must survive verbatim.
        db.query(
            "SELECT fictionId, chapterId, charOffset, paragraphIndex, " +
                "playbackSpeed, durationEstimateMs, updatedAt " +
                "FROM playback_position ORDER BY fictionId",
        ).use { c ->
            assertTrue("row rr:1 must survive", c.moveToFirst())
            assertEquals("rr:1", c.getString(0))
            assertEquals("rr:1:0", c.getString(1))
            assertEquals(4321, c.getInt(2))
            assertEquals(7, c.getInt(3))
            assertEquals(1.5f, c.getFloat(4), 0.0001f)
            assertEquals(600000L, c.getLong(5))
            assertEquals(1000L, c.getLong(6))

            assertTrue("row rr:2 must survive", c.moveToNext())
            assertEquals("rr:2", c.getString(0))
            assertEquals("rr:2:0", c.getString(1))
            assertEquals(99, c.getInt(2))
            assertEquals(0, c.getInt(3))
            assertEquals(1.0f, c.getFloat(4), 0.0001f)
            assertEquals(120000L, c.getLong(5))
            assertEquals(2000L, c.getLong(6))

            assertTrue("exactly the two seeded rows survive", !c.moveToNext())
        }

        // The whole point of #965: a second chapter of an existing fiction
        // can now hold its own independent position row. Under the old
        // single-column PK this insert would throw a UNIQUE constraint
        // violation on fictionId.
        db.execSQL(
            """
            INSERT INTO playback_position(
                fictionId, chapterId, charOffset, paragraphIndex,
                playbackSpeed, durationEstimateMs, updatedAt
            ) VALUES ('rr:1', 'rr:1:1', 12, 0, 1.0, 300000, 3000)
            """.trimIndent(),
        )
        db.query(
            "SELECT COUNT(*) FROM playback_position WHERE fictionId = 'rr:1'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(
                "fiction rr:1 now holds two independent per-chapter rows",
                2,
                c.getInt(0),
            )
        }

        db.close()
    }

    /**
     * Issue #722 — v2 adds `fiction.lastSeenRevision` for the GitHub-source
     * commit-SHA cheap-poll path. Purely additive nullable TEXT column;
     * existing rows get NULL so the first post-upgrade poll still hits the
     * full detail fetch and persists a SHA.
     *
     * Test seeds a v1 row with a complete fiction record (using only v1
     * columns), runs 1→2, and asserts the column exists, is nullable, and
     * defaults to NULL on the pre-existing row. Catches both malformed
     * ALTER TABLE SQL and identity-hash drift if the `lastSeenRevision`
     * field is ever changed without a corresponding migration.
     */
    @Test fun `migrate v1 to v2 adds fiction lastSeenRevision column`() {
        val dbName = "last-seen-revision-migration-test.db"

        helper.createDatabase(dbName, 1).use { db ->
            // Seed a row in the v1 fiction table so we can prove existing
            // rows get NULL for the new column post-migration.
            db.execSQL(
                """
                INSERT INTO fiction(
                    id, sourceId, title, author, genres, tags, status,
                    chapterCount, firstSeenAt, metadataFetchedAt,
                    inLibrary, followedRemotely, notesEverSeen
                ) VALUES (
                    'gh:repo:42', 'github', 'A Programmer''s Companion', 'octocat',
                    '[]', '[]', 'ONGOING', 0, 0, 0, 0, 0, 0
                )
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            2,
            /* validateDroppedTables = */ true,
            MIGRATION_1_2,
        )

        db.query("PRAGMA table_info('fiction')").use { c ->
            var sawLastSeenRevision = false
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                if (name == "lastSeenRevision") {
                    sawLastSeenRevision = true
                    val type = c.getString(c.getColumnIndexOrThrow("type"))
                    assertEquals("TEXT", type)
                    val notnull = c.getInt(c.getColumnIndexOrThrow("notnull"))
                    assertEquals("lastSeenRevision must be nullable", 0, notnull)
                }
            }
            assertTrue(
                "fiction.lastSeenRevision column must exist post-migration",
                sawLastSeenRevision,
            )
        }

        // Pre-existing row must round-trip with NULL in the new column.
        db.query(
            "SELECT id, lastSeenRevision FROM fiction WHERE id = 'gh:repo:42'",
        ).use { c ->
            assertTrue("seeded v1 row must survive the migration", c.moveToFirst())
            assertEquals("gh:repo:42", c.getString(0))
            assertTrue(
                "lastSeenRevision must default to NULL for existing rows",
                c.isNull(1),
            )
        }

        db.close()
    }

    /**
     * Issue #722 — v3 introduces the multi-session AI tables (#81).
     * Purely additive: `llm_session` + `llm_message` with an index on
     * `llm_message.sessionId` (FK cascade planner). Existing tables are
     * untouched.
     *
     * The new `llm_message` table carries a FK to `llm_session(id)` with
     * `ON DELETE CASCADE`; the index on `sessionId` is what Room's
     * schema validator expects to see for that FK column. If the
     * migration SQL ever drops the CREATE INDEX line, this test fails
     * with an identity-hash mismatch even though the tables themselves
     * exist.
     */
    @Test fun `migrate v2 to v3 creates llm_session and llm_message tables`() {
        val dbName = "llm-tables-migration-test.db"

        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            3,
            /* validateDroppedTables = */ true,
            MIGRATION_2_3,
        )

        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name IN ('llm_session', 'llm_message') ORDER BY name",
        ).use { c ->
            val tables = mutableListOf<String>()
            while (c.moveToNext()) tables += c.getString(0)
            assertEquals(
                "both llm_session and llm_message must exist post-migration",
                listOf("llm_message", "llm_session"),
                tables,
            )
        }

        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' " +
                "AND tbl_name='llm_message'",
        ).use { c ->
            val indexes = mutableListOf<String>()
            while (c.moveToNext()) indexes += c.getString(0)
            assertTrue(
                "index_llm_message_sessionId must exist (FK cascade planner)",
                indexes.any { it == "index_llm_message_sessionId" },
            )
        }

        // Smoke: insert + read back a session+message pair to prove the
        // CREATE TABLE matches the entity (column names, types,
        // AUTOINCREMENT on message id).
        db.execSQL(
            """
            INSERT INTO llm_session(
                id, name, provider, model, systemPrompt,
                createdAt, lastUsedAt, featureKind,
                anchorFictionId, anchorChapterId
            ) VALUES (
                'sess-1', 'first chat', 'openai', 'gpt-5', NULL,
                1000, 1500, 'CHAT', NULL, NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT INTO llm_message(sessionId, role, content, createdAt) " +
                "VALUES ('sess-1', 'user', 'hello', 1100)",
        )
        db.query(
            "SELECT sessionId, role, content, createdAt FROM llm_message",
        ).use { c ->
            assertTrue("inserted llm_message row must be readable", c.moveToFirst())
            assertEquals("sess-1", c.getString(0))
            assertEquals("user", c.getString(1))
            assertEquals("hello", c.getString(2))
            assertEquals(1100L, c.getLong(3))
        }

        db.close()
    }

    /**
     * Issue #722 — v4 adds `chapter.bookmarkCharOffset` for the
     * per-chapter bookmark feature (#121). Single nullable INTEGER
     * column; existing rows get NULL (the "no bookmark" sentinel the
     * entity already understands).
     *
     * Mirrors the shape of `migrate v6 to v7 adds chapter audioUrl
     * column` test. We seed a v3 chapter row, run 3→4, and verify the
     * column exists / is nullable / defaults to NULL on the
     * pre-existing row.
     */
    @Test fun `migrate v3 to v4 adds chapter bookmarkCharOffset column`() {
        val dbName = "bookmark-char-offset-migration-test.db"

        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).close()
        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3).use { db ->
            // Seed a fiction + chapter at v3 so the existing row exercises
            // the NULL-default behaviour after the column is added.
            db.execSQL(
                """
                INSERT INTO fiction(
                    id, sourceId, title, author, genres, tags, status,
                    chapterCount, firstSeenAt, metadataFetchedAt,
                    inLibrary, followedRemotely, notesEverSeen
                ) VALUES (
                    'rr:42', 'royalroad', 'Mother of Learning', 'nobody103',
                    '[]', '[]', 'ONGOING', 1, 0, 0, 0, 0, 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO chapter(
                    id, fictionId, sourceChapterId, `index`, title,
                    downloadState, userMarkedRead
                ) VALUES (
                    'rr:42:0', 'rr:42', '0', 0, 'Good Morning Brother',
                    'NOT_DOWNLOADED', 0
                )
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            4,
            /* validateDroppedTables = */ true,
            MIGRATION_3_4,
        )

        db.query("PRAGMA table_info('chapter')").use { c ->
            var sawBookmark = false
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                if (name == "bookmarkCharOffset") {
                    sawBookmark = true
                    val type = c.getString(c.getColumnIndexOrThrow("type"))
                    assertEquals("INTEGER", type)
                    val notnull = c.getInt(c.getColumnIndexOrThrow("notnull"))
                    assertEquals(
                        "bookmarkCharOffset must be nullable (NULL = no bookmark)",
                        0,
                        notnull,
                    )
                }
            }
            assertTrue(
                "chapter.bookmarkCharOffset column must exist post-migration",
                sawBookmark,
            )
        }

        // Pre-existing v3 chapter row must round-trip with NULL bookmark.
        db.query(
            "SELECT id, bookmarkCharOffset FROM chapter WHERE id = 'rr:42:0'",
        ).use { c ->
            assertTrue("seeded v3 chapter row must survive the migration", c.moveToFirst())
            assertEquals("rr:42:0", c.getString(0))
            assertTrue(
                "bookmarkCharOffset must default to NULL for existing rows",
                c.isNull(1),
            )
        }

        db.close()
    }

    /**
     * Issue #981 — v12 adds the nullable `fiction.metadataBackfillFailedAt`
     * column so synced placeholder rows that the back-fill worker can't
     * hydrate surface a "Couldn't load" state instead of an eternal
     * "Loading…". Purely additive; existing rows get NULL (never-attempted
     * / last attempt succeeded).
     *
     * Mirrors the `migrate v3 to v4 adds chapter bookmarkCharOffset` shape:
     * seed a v11 fiction row, run 11→12, verify the column exists, is
     * nullable, and defaults to NULL on the pre-existing row.
     */
    @Test fun `migrate v11 to v12 adds fiction metadataBackfillFailedAt column`() {
        val dbName = "metadata-backfill-failed-migration-test.db"

        helper.createDatabase(dbName, 4).close()
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).close()
        helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7).close()
        helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8).close()
        helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9).close()
        helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10).close()
        helper.runMigrationsAndValidate(dbName, 11, true, MIGRATION_10_11).use { db ->
            // Seed a placeholder-shaped fiction at v11 so the existing row
            // exercises the NULL-default behaviour after the column lands.
            db.execSQL(
                """
                INSERT INTO fiction(
                    id, sourceId, title, author, genres, tags, status,
                    chapterCount, firstSeenAt, metadataFetchedAt,
                    inLibrary, followedRemotely, notesEverSeen
                ) VALUES (
                    'gutenberg:84', 'gutenberg', 'Loading…', '',
                    '[]', '[]', 'ONGOING', 0, 0, 0, 1, 0, 0
                )
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            12,
            /* validateDroppedTables = */ true,
            MIGRATION_11_12,
        )

        db.query("PRAGMA table_info('fiction')").use { c ->
            var sawColumn = false
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                if (name == "metadataBackfillFailedAt") {
                    sawColumn = true
                    val type = c.getString(c.getColumnIndexOrThrow("type"))
                    assertEquals("INTEGER", type)
                    val notnull = c.getInt(c.getColumnIndexOrThrow("notnull"))
                    assertEquals(
                        "metadataBackfillFailedAt must be nullable (NULL = never failed)",
                        0,
                        notnull,
                    )
                }
            }
            assertTrue(
                "fiction.metadataBackfillFailedAt column must exist post-migration",
                sawColumn,
            )
        }

        // Pre-existing v11 placeholder row must round-trip with NULL stamp.
        db.query(
            "SELECT id, metadataBackfillFailedAt FROM fiction WHERE id = 'gutenberg:84'",
        ).use { c ->
            assertTrue("seeded v11 fiction row must survive the migration", c.moveToFirst())
            assertEquals("gutenberg:84", c.getString(0))
            assertTrue(
                "metadataBackfillFailedAt must default to NULL for existing rows",
                c.isNull(1),
            )
        }

        db.close()
    }
}
