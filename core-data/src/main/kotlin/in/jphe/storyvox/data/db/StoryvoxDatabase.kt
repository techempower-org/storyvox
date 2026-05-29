package `in`.jphe.storyvox.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import `in`.jphe.storyvox.data.db.converter.Converters
import `in`.jphe.storyvox.data.db.dao.AuthDao
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.ChapterHistoryDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.dao.FictionMemoryDao
import `in`.jphe.storyvox.data.db.dao.FictionShelfDao
import `in`.jphe.storyvox.data.db.dao.InboxEventDao
import `in`.jphe.storyvox.data.db.dao.LlmMessageDao
import `in`.jphe.storyvox.data.db.dao.LlmSessionDao
import `in`.jphe.storyvox.data.db.dao.PlaybackDao
import `in`.jphe.storyvox.data.db.entity.AuthCookie
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterHistory
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry
import `in`.jphe.storyvox.data.db.entity.FictionShelf
import `in`.jphe.storyvox.data.db.entity.InboxEvent
import `in`.jphe.storyvox.data.db.entity.LlmSession
import `in`.jphe.storyvox.data.db.entity.LlmStoredMessage
import `in`.jphe.storyvox.data.db.entity.PlaybackPosition

@Database(
    entities = [
        Fiction::class,
        Chapter::class,
        PlaybackPosition::class,
        AuthCookie::class,
        // v3 (#81 AI integration) — multi-session chat tables.
        LlmSession::class,
        LlmStoredMessage::class,
        // v5 (#116 library shelves) — many-to-many junction.
        FictionShelf::class,
        // v6 (#158 reading history) — one row per (fiction, chapter)
        // pair, upserted on every open. Forever retention.
        ChapterHistory::class,
        // v8 (#383 cross-source inbox) — append-only event feed,
        // source-emitted notifications. No FK to fiction/chapter so
        // events survive removal of the underlying rows.
        InboxEvent::class,
        // v9 (#217 cross-fiction memory) — per-(fiction, name) entries
        // the AI extracts from its own chat replies. No FK to fiction
        // so removal of a book doesn't cascade-wipe its memory; the
        // cross-fiction lookup still surfaces "you read this name in
        // a book you no longer have."
        FictionMemoryEntry::class,
    ],
    // v11 (#965 per-chapter playback position) — PlaybackPosition PK changes
    // from `fictionId` to composite `(fictionId, chapterId)` so each chapter
    // remembers its own offset. Table-rebuild migration, zero data loss.
    version = 11,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StoryvoxDatabase : RoomDatabase() {
    abstract fun fictionDao(): FictionDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chapterHistoryDao(): ChapterHistoryDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun authDao(): AuthDao
    abstract fun llmSessionDao(): LlmSessionDao
    abstract fun llmMessageDao(): LlmMessageDao
    abstract fun fictionShelfDao(): FictionShelfDao
    abstract fun inboxEventDao(): InboxEventDao
    abstract fun fictionMemoryDao(): FictionMemoryDao

    companion object {
        const val NAME: String = "storyvox.db"
    }
}
