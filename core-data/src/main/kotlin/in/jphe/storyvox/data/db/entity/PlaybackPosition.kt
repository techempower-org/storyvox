package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * "Where I was" — one row per (fiction, chapter), holds the latest position
 * for each chapter independently. Issue #965: the PK was `fictionId` alone
 * (one row per fiction), which let a stale future-chapter offset linger in
 * the row when the user backed up to an earlier chapter — any reader of the
 * row in that window (Library Resume tile, Auto/Wear browse, Recap) could
 * resume at the stale offset ("skips forward"). With the composite
 * `(fictionId, chapterId)` PK each chapter remembers its own offset, and
 * "resume the fiction" means "resume its most-recently-played chapter"
 * (`ORDER BY updatedAt DESC`), which is never stale. Cascade-deletes when
 * either the parent fiction or the parent chapter leaves the library.
 */
@Entity(
    tableName = "playback_position",
    primaryKeys = ["fictionId", "chapterId"],
    foreignKeys = [
        ForeignKey(
            entity = Fiction::class,
            parentColumns = ["id"],
            childColumns = ["fictionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // chapterId index backs the chapter FK cascade planner. fictionId is
        // the leading column of the composite PK so its own lookups
        // (observe/get/delete-by-fiction) are already index-served.
        Index(value = ["chapterId"]),
        Index(value = ["updatedAt"]),
    ],
)
data class PlaybackPosition(
    val fictionId: String,
    val chapterId: String,
    val charOffset: Int = 0,
    val paragraphIndex: Int = 0,
    val playbackSpeed: Float = 1.0f,
    /** Player's last-known estimated total duration of this chapter, in millis. */
    val durationEstimateMs: Long = 0L,
    val updatedAt: Long,
)
