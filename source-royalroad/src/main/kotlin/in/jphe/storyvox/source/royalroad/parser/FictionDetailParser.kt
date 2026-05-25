package `in`.jphe.storyvox.source.royalroad.parser

import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import `in`.jphe.storyvox.source.royalroad.model.extractChapterIdFromHref
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Parses a Royal Road fiction page (`/fiction/{id}` or `/fiction/{id}/{slug}`)
 * into [FictionDetail].
 *
 * Strategy: prefer JSON-LD (every fiction page ships a `<script type="application/ld+json">`
 * block with cleanly-parsed fields), fall back to HTML selectors for whatever
 * JSON-LD doesn't carry (status pill, tags, full chapter table).
 */
internal object FictionDetailParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun parse(html: String, fictionId: String): FictionDetail {
        val doc = Jsoup.parse(html, RoyalRoadIds.BASE_URL)
        HoneypotFilter.strip(doc)

        val ld = readJsonLd(doc)

        val title = ld?.string("name")?.trim()
            ?: doc.selectFirst("div.fic-header h1.font-white")?.text()?.trim()
            ?: ""

        val author = ld?.author()?.trim()
            ?: doc.selectFirst("div.fic-header h4 a")?.text()?.trim()
            ?: ""

        val authorId = ld?.authorProfileId()
            ?: doc.selectFirst("div.fic-header h4 a")?.attr("href")
                ?.let { Regex("""/profile/(\d+)""").find(it)?.groupValues?.get(1) }

        val cover = ld?.string("image")
            ?: doc.selectFirst("div.cover-art-container img")?.let { absUrl(it.attr("src")) }

        val description = ld?.string("description")?.let { stripHtmlTags(it) }
            ?: doc.selectFirst("div.description div.hidden-content")?.text()?.trim()

        val rating = ld?.aggregateRating()
        val ratingCount = ld?.ratingCount()
        val wordCount = ld?.long("numberOfPages")
        val publishedAt = ld?.string("datePublished")?.let { parseIso8601(it) }
        val updatedAt = ld?.string("dateModified")?.let { parseIso8601(it) }

        val tags = doc.select("a.fiction-tag").mapNotNull { tag ->
            tag.attr("href").substringAfter("tagsAdd=", "").substringBefore("&").trim().ifEmpty { null }
        }

        val statusLabels = doc.select("span.label.bg-blue-hoki").map { it.text().trim() }
        val status = statusLabels.firstNotNullOfOrNull { mapStatus(it) } ?: FictionStatus.ONGOING

        val chapters = parseChapterRows(doc)

        val summary = FictionSummary(
            id = fictionId,
            sourceId = RoyalRoadIds.SOURCE_ID,
            title = title,
            author = author,
            coverUrl = cover,
            description = description,
            tags = tags,
            status = status,
            chapterCount = chapters.size,
            rating = rating,
        )

        return FictionDetail(
            summary = summary,
            chapters = chapters,
            genres = ld?.genreSlugs() ?: emptyList(),
            wordCount = wordCount,
            views = ld?.long("interactionStatistic.userInteractionCount") ?: ld?.viewsFromStat(),
            followers = ratingCount,
            lastUpdatedAt = updatedAt,
            authorId = authorId,
        )
    }

    // -- JSON-LD --------------------------------------------------------------

    private fun readJsonLd(doc: Document): JsonObject? {
        val raw = doc.select("script[type=application/ld+json]")
            .firstOrNull()?.data() ?: return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? {
        // Supports dot-paths like "interactionStatistic.userInteractionCount".
        val parts = key.split(".")
        var cur: JsonElement? = this
        for (p in parts) {
            cur = (cur as? JsonObject)?.get(p) ?: return null
        }
        return cur?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
    }

    private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()

    private fun JsonObject.author(): String? =
        (this["author"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull

    private fun JsonObject.authorProfileId(): String? =
        (this["author"] as? JsonObject)?.get("@id")?.jsonPrimitive?.contentOrNull
            ?.let { Regex("""/profile/(\d+)""").find(it)?.groupValues?.get(1) }

    private fun JsonObject.aggregateRating(): Float? =
        (this["aggregateRating"] as? JsonObject)?.get("ratingValue")?.jsonPrimitive?.floatOrNull

    private fun JsonObject.ratingCount(): Int? =
        (this["aggregateRating"] as? JsonObject)?.get("ratingCount")?.jsonPrimitive?.intOrNull

    private fun JsonObject.genreSlugs(): List<String> =
        (this["genre"] as? JsonElement)?.let { el ->
            (el as? kotlinx.serialization.json.JsonArray)?.jsonArray?.mapNotNull { item ->
                val url = item.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                Regex("""[?&]genre=([^&]+)""").find(url)?.groupValues?.get(1)
            }
        } ?: emptyList()

    private fun JsonObject.viewsFromStat(): Long? =
        (this["interactionStatistic"] as? JsonObject)
            ?.get("userInteractionCount")?.jsonPrimitive?.longOrNull

    // -- Chapter table --------------------------------------------------------

    private fun parseChapterRows(doc: Document): List<ChapterInfo> {
        return doc.select("tr.chapter-row").mapIndexedNotNull { idx, row ->
            val href = row.attr("data-url").ifEmpty {
                row.selectFirst("td a")?.attr("href").orEmpty()
            }
            if (href.isEmpty()) return@mapIndexedNotNull null
            val sourceChapterId = extractChapterIdFromHref(href) ?: return@mapIndexedNotNull null
            val title = row.selectFirst("td a")?.text()?.trim().orEmpty()
            val publishedAt = row.selectFirst("time[unixtime]")?.attr("unixtime")
                ?.toLongOrNull()?.let { it * 1000L }
            ChapterInfo(
                // Use the bare sourceChapterId as the primary key — the playback
                // and download paths feed this back to the source layer to build
                // the chapter URL, so anything compound here (e.g., a parent DOM
                // id) breaks the URL and produces a 404.
                id = sourceChapterId,
                sourceChapterId = sourceChapterId,
                index = idx,
                title = title,
                publishedAt = publishedAt,
                wordCount = null,
            )
        }
    }

    // -- helpers --------------------------------------------------------------

    private fun mapStatus(label: String): FictionStatus? = when (label.uppercase()) {
        "ONGOING" -> FictionStatus.ONGOING
        "COMPLETED" -> FictionStatus.COMPLETED
        "HIATUS" -> FictionStatus.HIATUS
        "STUB" -> FictionStatus.STUB
        "DROPPED" -> FictionStatus.DROPPED
        else -> null
    }

    private fun absUrl(src: String): String? {
        if (src.isEmpty() || src.endsWith("/dist/img/nocover-new-min.png")) return null
        return when {
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            src.startsWith("/") -> "${RoyalRoadIds.BASE_URL}$src"
            else -> src
        }
    }

    private val TAG_RE = Regex("""<[^>]+>""")
    private fun stripHtmlTags(s: String): String = s.replace(TAG_RE, "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#x2013;", "–")
        .replace("&quot;", "\"").replace("&#x27;", "'").trim()

    private fun parseIso8601(s: String): Long? = runCatching {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    }.getOrNull()
}
