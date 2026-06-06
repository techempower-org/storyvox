package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Issue #999 — a text highlight (optionally with a note) inside a chapter.
 *
 * Goes beyond the single per-chapter bookmark ([Chapter.bookmarkCharOffset],
 * #121): a fiction can hold many annotations, each spanning a character range
 * of the chapter body, optionally carrying a freeform [note].
 *
 * ## Range model
 * `[startOffset, endOffset)` are character offsets into the chapter's body
 * text — the *same* coordinate the existing bookmark ([Chapter.bookmarkCharOffset]
 * → `plainBody`) and the reader's sentence-highlight use. No new coordinate
 * concept is introduced: the reader already maps offsets ↔ `TextLayoutResult`.
 *
 * Offsets are best-effort for scroll-to / overlay rendering. A chapter
 * re-fetch can shift the body (different whitespace, a fixed typo), so the
 * offsets may no longer line up. [quotedText] is the durable record — it is
 * denormalized into the row so the annotation list and the export survive a
 * body that no longer matches, and so the overlay can be skipped (rather than
 * drawn at the wrong place) when the range falls outside the current body.
 *
 * ## Identity
 * [id] is a client-generated UUID (see `SyncIds`), NOT a derived key. A stable
 * id is what lets [AnnotationsSyncer] (#999, mirroring `BookmarksSyncer`)
 * address one annotation across devices and express an explicit delete
 * tombstone for it without a derived-key collision when two devices highlight
 * overlapping ranges.
 *
 * ## Cascade
 * Both FKs are `ON DELETE CASCADE`: purging a fiction (or a chapter) drops its
 * annotations, so removing a book can't leave orphaned highlight rows that
 * would surface as ghost entries in the per-fiction list. Mirrors the cascade
 * reasoning in [FictionShelf] and [Chapter].
 */
@Entity(
    tableName = "annotation",
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
        // Per-fiction list surface (FictionDetail highlights/notes view).
        Index(value = ["fictionId"]),
        // Per-chapter overlay query (reader renders this chapter's spans) and
        // the FK-cascade planner for the chapter foreign key.
        Index(value = ["chapterId"]),
    ],
)
data class Annotation(
    /** Client-generated UUID — the stable cross-device sync key. */
    @PrimaryKey val id: String,
    val fictionId: String,
    val chapterId: String,
    /** Inclusive start char offset into the chapter body. */
    val startOffset: Int,
    /** Exclusive end char offset into the chapter body. */
    val endOffset: Int,
    /**
     * Highlight color, persisted as a palette enum *name* string (not an
     * ordinal) so a new swatch can be appended without a schema migration —
     * the same string-not-ordinal pattern as [FictionShelf.shelf].
     */
    val color: String,
    /** Optional freeform note. Null = a highlight with no attached note. */
    val note: String? = null,
    /**
     * Denormalized snapshot of the highlighted text at creation time. The
     * durable record of *what* was highlighted, independent of whether
     * [startOffset]/[endOffset] still line up after a body re-fetch.
     */
    val quotedText: String,
    /** Wall-clock millis the annotation was created. */
    val createdAt: Long,
    /**
     * Wall-clock millis of the last edit (note change). Equal to
     * [createdAt] on first write. Drives last-writer-wins in
     * [AnnotationsSyncer] and orders the per-fiction list within a chapter
     * group as a tiebreak after [startOffset].
     */
    val updatedAt: Long,
)
