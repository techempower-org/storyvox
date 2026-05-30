package `in`.jphe.storyvox.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 ships with no migrations. When the schema bumps, declare each step here
 * and pass them via `Room.databaseBuilder(...).addMigrations(...)` in
 * `DataModule.provideDb`.
 *
 * Schemas are exported to `core-data/schemas/` (see KSP `room.schemaLocation`)
 * so diffs land in PRs.
 */

/**
 * v2 — adds `fiction.lastSeenRevision` for the GitHub-source commit-SHA
 * cheap-poll path (step 9 in the GitHub-source spec). Existing rows get
 * NULL so the first poll after upgrade still hits the full detail fetch
 * and persists a SHA, then subsequent polls take the short-circuit.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE fiction ADD COLUMN lastSeenRevision TEXT")
    }
}

/**
 * v3 — adds the multi-session AI tables (#81 AI integration). Pure
 * additive: two new tables and one index, no existing data touched.
 *
 * Schema mirrors the entity definitions in
 * `in.jphe.storyvox.data.db.entity.LlmSession` and
 * `in.jphe.storyvox.data.db.entity.LlmStoredMessage`. The `provider`
 * + `featureKind` columns are TEXT (storing enum names) for
 * forward-compatibility — adding a new enum value doesn't require a
 * migration.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `llm_session` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `name` TEXT NOT NULL,
                `provider` TEXT NOT NULL,
                `model` TEXT NOT NULL,
                `systemPrompt` TEXT,
                `createdAt` INTEGER NOT NULL,
                `lastUsedAt` INTEGER NOT NULL,
                `featureKind` TEXT,
                `anchorFictionId` TEXT,
                `anchorChapterId` TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `llm_message` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`sessionId`) REFERENCES `llm_session`(`id`)
                  ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_llm_message_sessionId` " +
                "ON `llm_message` (`sessionId`)",
        )
    }
}

/**
 * v4 — issue #121: per-chapter bookmark. One nullable column on the
 * chapter table; null on existing rows is the "no bookmark" sentinel
 * the entity already understands. Pure additive, no data backfill.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapter ADD COLUMN bookmarkCharOffset INTEGER")
    }
}

/**
 * v5 — issue #116: predefined library shelves (Reading / Read /
 * Wishlist) with many-to-many membership. New junction table only; no
 * existing data touched, no backfill needed (existing library rows
 * simply have zero shelf memberships until the user pins them).
 *
 * `shelf` is TEXT to match the enum-name-as-string pattern from
 * [MIGRATION_2_3] — adding a fourth shelf value in v2 requires no
 * further migration. Index name matches Room's default
 * `index_<table>_<col>` convention so the schema diff stays clean.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `fiction_shelf` (
                `fictionId` TEXT NOT NULL,
                `shelf` TEXT NOT NULL,
                `addedAt` INTEGER NOT NULL,
                PRIMARY KEY(`fictionId`, `shelf`),
                FOREIGN KEY(`fictionId`) REFERENCES `fiction`(`id`)
                  ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_fiction_shelf_shelf` " +
                "ON `fiction_shelf` (`shelf`)",
        )
    }
}

/**
 * v6 — issue #158: chapter_history table backing the Library "History"
 * sub-tab. Single CREATE TABLE + two indexes (openedAt for the ordering
 * the History feed uses, chapterId for the FK cascade-delete planner).
 * Purely additive — no existing data touched.
 *
 * The composite PK + UPSERT semantics mean re-opening the same chapter
 * twenty times produces one row, not twenty audit entries — see the
 * kdoc on [`in`.jphe.storyvox.data.db.entity.ChapterHistory] for the
 * product call.
 *
 * Originally authored as MIGRATION_4_5 against pre-shelves main;
 * renumbered to 5→6 at merge time because #116 (shelves) landed first
 * and claimed the v5 slot. SQL is unchanged — chapter_history doesn't
 * touch any shelves columns, so ordering is independent.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chapter_history` (
                `fictionId` TEXT NOT NULL,
                `chapterId` TEXT NOT NULL,
                `openedAt` INTEGER NOT NULL,
                `completed` INTEGER NOT NULL DEFAULT 0,
                `fractionRead` REAL,
                PRIMARY KEY(`fictionId`, `chapterId`),
                FOREIGN KEY(`fictionId`) REFERENCES `fiction`(`id`)
                  ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`chapterId`) REFERENCES `chapter`(`id`)
                  ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapter_history_openedAt` " +
                "ON `chapter_history` (`openedAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapter_history_chapterId` " +
                "ON `chapter_history` (`chapterId`)",
        )
    }
}

/**
 * v7 — issues #373 + #374: audio-stream backend category. Adds
 * `audioUrl` column to the chapter table so chapters returned by
 * audio-source backends (KVMR community radio, future LibriVox /
 * Internet Archive) can carry a Media3-routable URL through the same
 * download/persist pipeline that text chapters use.
 *
 * Purely additive — existing chapter rows keep their `htmlBody` /
 * `plainBody` and the new column defaults to NULL, preserving the
 * TTS pipeline for every backend that doesn't surface audio. The
 * playback layer reads `audioUrl IS NOT NULL` to decide whether to
 * route through TTS or hand the URL to a Media3 ExoPlayer instance.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapter ADD COLUMN audioUrl TEXT")
    }
}

/**
 * v8 — issue #383: cross-source Inbox. New `inbox_event` table backing the
 * Library "Inbox" sub-tab — one row per source-emitted notification (new
 * chapters, live programs, article updates). Two indexes:
 *  - `ts` for the `ORDER BY ts DESC` feed query
 *  - `isRead` for the unread-count badge (`COUNT(*) WHERE isRead = 0`)
 *
 * No foreign keys to `fiction` / `chapter` — Inbox events deliberately
 * survive removal of the underlying rows so the user can still see
 * "Wikipedia: X updated" after they unfollow the article. See the
 * [`in`.jphe.storyvox.data.db.entity.InboxEvent] kdoc for the reasoning.
 *
 * Purely additive — no existing data touched.
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `inbox_event` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sourceId` TEXT NOT NULL,
                `fictionId` TEXT,
                `chapterId` TEXT,
                `title` TEXT NOT NULL,
                `body` TEXT,
                `ts` INTEGER NOT NULL,
                `isRead` INTEGER NOT NULL DEFAULT 0,
                `deepLinkUri` TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_inbox_event_ts` " +
                "ON `inbox_event` (`ts`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_inbox_event_isRead` " +
                "ON `inbox_event` (`isRead`)",
        )
    }
}

/**
 * v9 — issue #217: cross-fiction AI memory. New `fiction_memory_entry`
 * table holding per-fiction character/place/concept notes the AI chat
 * post-processor extracts from its own replies. Two indexes:
 *  - `name` for the cross-fiction lookup (`WHERE name = ?`) the
 *    prompt-builder runs once per turn.
 *  - `fictionId` for the per-book Notebook UI feed
 *    (`WHERE fictionId = ? ORDER BY ...`).
 *
 * Composite PK `(fictionId, name)` enforces upsert semantics ("one
 * fact per name per book"). No FK to `fiction` because removing a
 * book shouldn't cascade-wipe what the AI remembers — surfacing
 * "you also read this character in book X" still works after the
 * underlying fiction row is gone. Purely additive — no existing
 * data touched.
 */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `fiction_memory_entry` (
                `fictionId` TEXT NOT NULL,
                `entityType` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `firstSeenChapterIndex` INTEGER,
                `lastUpdated` INTEGER NOT NULL,
                `userEdited` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`fictionId`, `name`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_fiction_memory_entry_name` " +
                "ON `fiction_memory_entry` (`name`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_fiction_memory_entry_fictionId` " +
                "ON `fiction_memory_entry` (`fictionId`)",
        )
    }
}

/**
 * v10 — issues #890, #889, #884: missing query indexes causing full-table
 * scans. Purely additive — three composite indexes, no data touched:
 *  - `fiction(inLibrary, addedToLibraryAt)` for `observeLibrary` (#890),
 *    which filters `inLibrary = 1` then orders by `addedToLibraryAt`.
 *  - `fiction(followedRemotely, lastUpdatedAt)` for `observeFollowsRemote`
 *    (#890), the same shape over the remote-follow flag.
 *  - `chapter(fictionId, userMarkedRead)` for `unreadChapters` (#889),
 *    which scopes to a fiction then counts/filters on the read flag.
 *  - `inbox_event(sourceId, fictionId)` for the per-source/-fiction Inbox
 *    coalescing + lookup (#884).
 *
 * Index names match Room's default `index_<table>_<col>_<col>` convention
 * so the exported-schema diff stays clean against the generated schema.
 */
val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_fiction_inLibrary_addedToLibraryAt` " +
                "ON `fiction` (`inLibrary`, `addedToLibraryAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_fiction_followedRemotely_lastUpdatedAt` " +
                "ON `fiction` (`followedRemotely`, `lastUpdatedAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapter_fictionId_userMarkedRead` " +
                "ON `chapter` (`fictionId`, `userMarkedRead`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_inbox_event_sourceId_fictionId` " +
                "ON `inbox_event` (`sourceId`, `fictionId`)",
        )
    }
}

/**
 * v11 — issue #965: per-chapter playback position. Changes the
 * `playback_position` primary key from `fictionId` alone to the composite
 * `(fictionId, chapterId)` so each chapter remembers its own offset. A
 * stale future-chapter offset can no longer linger in "the fiction's row"
 * after the user backs up to an earlier chapter ("skips forward").
 *
 * SQLite can't ALTER a primary key in place, so this is the standard Room
 * table-rebuild: create `playback_position_new` with the composite PK and
 * the IDENTICAL column set / FKs, copy every row across, drop the old
 * table, rename the new one into place, then recreate both indexes.
 *
 * DATA PRESERVATION — the crux of this issue. Every existing v10 row
 * already carries its `chapterId` as a regular NOT-NULL column; only the PK
 * definition changes. `INSERT ... SELECT *` copies all seven columns
 * verbatim, so each old per-fiction row maps 1:1 to a `(fictionId,
 * chapterId)` row with its stored offset/paragraph/speed/duration/timestamp
 * intact. No row is dropped, merged, or transformed. The composite PK only
 * *permits* additional rows going forward; it doesn't disturb the ones
 * already there (no two v10 rows can collide, since they had distinct
 * fictionIds to begin with).
 *
 * The DDL below is byte-for-byte the v10 `playback_position` createSql with
 * `PRIMARY KEY(fictionId)` swapped for `PRIMARY KEY(fictionId, chapterId)`,
 * so Room's post-migration identity-hash validation against `11.json`
 * passes. Both FKs (fiction + chapter, ON DELETE CASCADE) and both indexes
 * (chapterId, updatedAt) are recreated exactly as the entity declares them.
 */
val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playback_position_new` (
                `fictionId` TEXT NOT NULL,
                `chapterId` TEXT NOT NULL,
                `charOffset` INTEGER NOT NULL,
                `paragraphIndex` INTEGER NOT NULL,
                `playbackSpeed` REAL NOT NULL,
                `durationEstimateMs` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`fictionId`, `chapterId`),
                FOREIGN KEY(`fictionId`) REFERENCES `fiction`(`id`)
                  ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`chapterId`) REFERENCES `chapter`(`id`)
                  ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `playback_position_new` (
                `fictionId`, `chapterId`, `charOffset`, `paragraphIndex`,
                `playbackSpeed`, `durationEstimateMs`, `updatedAt`
            )
            SELECT
                `fictionId`, `chapterId`, `charOffset`, `paragraphIndex`,
                `playbackSpeed`, `durationEstimateMs`, `updatedAt`
            FROM `playback_position`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `playback_position`")
        db.execSQL("ALTER TABLE `playback_position_new` RENAME TO `playback_position`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_playback_position_chapterId` " +
                "ON `playback_position` (`chapterId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_playback_position_updatedAt` " +
                "ON `playback_position` (`updatedAt`)",
        )
    }
}

/**
 * v12 — issue #981: metadata back-fill failure stamp. Adds the nullable
 * `fiction.metadataBackfillFailedAt` column. `MetadataBackfillWorker`
 * writes the wall-clock of a failed hydrate here so the library UI can
 * render a "Couldn't load" state (rather than an eternal "Loading…") for
 * synced placeholder rows whose source can't be reached (auth-gated,
 * removed upstream, network), and so the worker applies a cool-down
 * before retrying that row.
 *
 * Purely additive — existing rows get NULL (never-attempted / last
 * attempt succeeded), which is the correct default. SQLite `ALTER TABLE
 * ADD COLUMN` with a nullable type is a metadata-only change; no row is
 * rewritten.
 */
val MIGRATION_11_12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `fiction` ADD COLUMN `metadataBackfillFailedAt` INTEGER")
    }
}

/**
 * Issue #989 — add `fiction.sourceUrl`, the rebuild-essential original
 * URL for sources whose id is an opaque hash of the URL (Readability,
 * RSS, EPUB direct-download). Nullable: the column stays NULL for the
 * many sources whose id already encodes everything needed to re-fetch.
 */
val MIGRATION_12_13: Migration = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `fiction` ADD COLUMN `sourceUrl` TEXT")
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
    MIGRATION_10_11,
    MIGRATION_11_12,
    MIGRATION_12_13,
)
