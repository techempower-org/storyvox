package `in`.jphe.storyvox.data.db.converter

import androidx.room.TypeConverter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.db.entity.DownloadMode
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.NotePosition
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room TypeConverters. Enums round-trip via their `name` (stable so long as we
 * never rename a constant); list-of-string columns round-trip via JSON.
 */
class Converters {

    // ── lists ─────────────────────────────────────────────────────────────

    @TypeConverter
    fun fromStringList(value: List<String>?): String =
        if (value.isNullOrEmpty()) "[]" else json.encodeToString(stringListSerializer, value)

    @TypeConverter
    fun toStringList(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString(stringListSerializer, raw)

    // ── enums ─────────────────────────────────────────────────────────────

    @TypeConverter
    fun fromFictionStatus(value: FictionStatus?): String? = value?.name

    @TypeConverter
    fun toFictionStatus(raw: String?): FictionStatus? =
        raw?.let { runCatching { FictionStatus.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromDownloadMode(value: DownloadMode?): String? = value?.name

    @TypeConverter
    fun toDownloadMode(raw: String?): DownloadMode? =
        raw?.let { runCatching { DownloadMode.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromChapterDownloadState(value: ChapterDownloadState?): String? = value?.name

    @TypeConverter
    fun toChapterDownloadState(raw: String?): ChapterDownloadState? =
        raw?.let { runCatching { ChapterDownloadState.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromNotePosition(value: NotePosition?): String? = value?.name

    @TypeConverter
    fun toNotePosition(raw: String?): NotePosition? =
        raw?.let { runCatching { NotePosition.valueOf(it) }.getOrNull() }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
        val stringListSerializer = ListSerializer(String.serializer())
    }
}
