package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.ocr.config.OcrConfig
import `in`.jphe.storyvox.source.ocr.config.OcrDocument
import `in`.jphe.storyvox.source.ocr.config.OcrDocumentStore
import `in`.jphe.storyvox.source.ocr.config.OcrPage
import `in`.jphe.storyvox.source.ocr.config.fictionIdForOcrCapture
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.ocrDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "storyvox_ocr_documents",
)

private object OcrKeys {
    /**
     * JSON-encoded `List<OcrDocumentDto>` of the user's scanned
     * documents. Single key because the set is small (a user scans a
     * handful of letters / pages), and a scanned document is the
     * canonical content — there's no upstream file to re-read, so the
     * recognized text lives here. Same JSON-blob shape as
     * [RadioConfigImpl]'s starred-stations store.
     */
    val DOCUMENTS = stringPreferencesKey("pref_ocr_documents")
}

/**
 * Issue #995 — production [OcrConfig] (read) + [OcrDocumentStore]
 * (write) backed by a dedicated DataStore (`storyvox_ocr_documents`).
 *
 * The OCR source persists its own content because a camera capture is
 * transient — unlike `:source-epub` (re-reads SAF bytes) or
 * `:source-rss` (re-fetches the feed), the recognized text IS the
 * artifact. Documents are stored newest-first.
 *
 * Forward-compat: `ignoreUnknownKeys = true` tolerates a future schema
 * extension (per-page source-image thumbnail, OCR confidence, etc.); a
 * corrupt blob drops back to an empty list rather than failing the
 * source load.
 */
@Singleton
class OcrConfigImpl internal constructor(
    private val store: DataStore<Preferences>,
    private val now: () -> Long,
    private val nonce: () -> String,
) : OcrConfig, OcrDocumentStore {

    @Inject constructor(@ApplicationContext context: Context) : this(
        store = context.ocrDataStore,
        now = { System.currentTimeMillis() },
        nonce = { UUID.randomUUID().toString().take(8) },
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val documents: Flow<List<OcrDocument>> = store.data
        .map { prefs -> decode(prefs[OcrKeys.DOCUMENTS]) }
        .distinctUntilChanged()

    override suspend fun documents(): List<OcrDocument> =
        decode(store.data.first()[OcrKeys.DOCUMENTS])

    override suspend fun document(fictionId: String): OcrDocument? =
        documents().firstOrNull { it.fictionId == fictionId }

    override suspend fun save(title: String, pages: List<OcrPage>): String {
        val createdAt = now()
        val fictionId = fictionIdForOcrCapture(createdAt, nonce())
        val doc = OcrDocument(
            fictionId = fictionId,
            title = title,
            createdAt = createdAt,
            pages = pages,
        )
        store.edit { prefs ->
            val current = decode(prefs[OcrKeys.DOCUMENTS])
            // Newest first.
            prefs[OcrKeys.DOCUMENTS] = encode(listOf(doc) + current)
        }
        return fictionId
    }

    override suspend fun delete(fictionId: String) {
        store.edit { prefs ->
            val current = decode(prefs[OcrKeys.DOCUMENTS])
            prefs[OcrKeys.DOCUMENTS] = encode(current.filterNot { it.fictionId == fictionId })
        }
    }

    // ─── (de)serialization ──────────────────────────────────────────────

    private fun decode(blob: String?): List<OcrDocument> {
        if (blob.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(OcrDocumentDto.serializer()), blob)
                .map { it.toModel() }
        } catch (_: SerializationException) {
            emptyList()
        }
    }

    private fun encode(docs: List<OcrDocument>): String =
        json.encodeToString(
            ListSerializer(OcrDocumentDto.serializer()),
            docs.map { OcrDocumentDto.fromModel(it) },
        )

    companion object {
        /** Test factory — deterministic clock + nonce so persistence
         *  round-trips are assertable without Robolectric. */
        internal fun forTesting(
            store: DataStore<Preferences>,
            now: () -> Long = { 0L },
            nonce: () -> String = { "test" },
        ): OcrConfigImpl = OcrConfigImpl(store, now, nonce)
    }
}

@Serializable
private data class OcrDocumentDto(
    val fictionId: String,
    val title: String,
    val createdAt: Long,
    val pages: List<OcrPageDto>,
) {
    fun toModel() = OcrDocument(
        fictionId = fictionId,
        title = title,
        createdAt = createdAt,
        pages = pages.map { it.toModel() },
    )

    companion object {
        fun fromModel(d: OcrDocument) = OcrDocumentDto(
            fictionId = d.fictionId,
            title = d.title,
            createdAt = d.createdAt,
            pages = d.pages.map { OcrPageDto.fromModel(it) },
        )
    }
}

@Serializable
private data class OcrPageDto(
    val index: Int,
    val title: String,
    val text: String,
) {
    fun toModel() = OcrPage(index = index, title = title, text = text)

    companion object {
        fun fromModel(p: OcrPage) = OcrPageDto(index = p.index, title = p.title, text = p.text)
    }
}
