package `in`.jphe.storyvox.source.radio.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.radio.RadioStation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #417 — minimal Radio Browser API client.
 *
 * Radio Browser (https://www.radio-browser.info/) is a CC0-licensed
 * community-maintained directory of ~30k internet-streamable radio
 * stations. Read-only JSON API; no auth, no rate-limit beyond standard
 * etiquette (they recommend identifying yourself in the User-Agent so
 * abusive clients can be contacted directly).
 *
 * ## Endpoints used
 *
 * - **`GET /json/stations/byname/<q>`** — fuzzy name search. Drives the
 *   Browse → Radio → Search tab. Capped server-side to ~10k results;
 *   we apply a client-side limit of 50 below (anything beyond that
 *   isn't useful in a phone list view).
 *
 * Other Radio Browser endpoints (`bycountry`, `bytag`, `bylanguage`,
 * `byuuid`) are intentionally NOT wired in v1 — the issue calls for
 * search + star, not a multi-facet picker. The shape is here for a
 * follow-up PR to add filter chips without an API client rewrite.
 *
 * ## Server selection
 *
 * Radio Browser exposes per-region DNS mirrors
 * (`de1.api.radio-browser.info`, `nl1.api.radio-browser.info`,
 * `at1.api.radio-browser.info`) plus a round-robin
 * `all.api.radio-browser.info`. We pin to `de1` because (a) it's the
 * one Radio Browser's docs use as the canonical example and (b) the
 * round-robin endpoint resolves to one mirror per DNS lookup, which
 * makes OkHttp's connection pooling less useful. Resiliency on top
 * of the pinned host is a v2 concern (the mirror's been stable for
 * years).
 *
 * ## Failure mapping
 *
 * - 404 → [FictionResult.NotFound] (Radio Browser actually returns
 *   an empty array `[]` for no-results — we surface that as
 *   `Success(emptyList())`, so 404 here means a routing error).
 * - 429 → [FictionResult.RateLimited]. Radio Browser's docs don't
 *   mention 429 but other public JSON APIs return it under load;
 *   handle defensively.
 * - Network / IO errors → [FictionResult.NetworkError] with `cause`.
 * - JSON parse failures → `NetworkError` with a "unexpected JSON
 *   shape" message; preserves the original SerializationException
 *   as `cause` for debug-overlay surfacing.
 */
@Singleton
internal class RadioBrowserApi @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Search stations by name (case-insensitive fuzzy match server-side).
     *
     * Returns at most [limit] hits as [RadioStation] descriptors,
     * already filtered to HTTPS-only entries — Media3's default policy
     * refuses cleartext streams and we don't relax that for the
     * long-tail directory.
     *
     * The Radio Browser `stationuuid` becomes the storyvox station id
     * with a `rb:` prefix so it never collides with a curated slug
     * (curated stations use short ids like `"kvmr"`; imports look like
     * `"rb:4af97ba5-..."`).
     */
    suspend fun byName(
        query: String,
        limit: Int = 50,
    ): FictionResult<List<RadioStation>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return FictionResult.Success(emptyList())
        }
        val url = "$BASE_URL/json/stations/byname/$trimmed"
            .toHttpUrl().newBuilder()
            // `hidebroken=true` filters out stations Radio Browser's
            // own uptime checker has marked as broken — saves the user
            // from tapping "Play" on a dead URL.
            .addQueryParameter("hidebroken", "true")
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .build()
            .toString()
        return getJson<List<RadioBrowserStation>>(url)
            .let { result ->
                when (result) {
                    is FictionResult.Success -> FictionResult.Success(
                        result.value.mapNotNull { it.toRadioStation() },
                    )
                    is FictionResult.Failure -> result
                }
            }
    }

    /**
     * Multi-facet search against `/json/stations/search`. Any of
     * [name], [country], [language], [tag] may be blank/null; the
     * server applies AND semantics across the non-blank params. This
     * is the surface that powers the Browse → Radio filter chips
     * (country / language / tags) declared in [RadioSource.filterDimensions].
     *
     * Same HTTPS-only / lastcheckok / ssl_error filtering as [byName]
     * via the shared [RadioBrowserStation.toRadioStation] mapper.
     */
    suspend fun search(
        name: String? = null,
        country: String? = null,
        language: String? = null,
        tag: String? = null,
        limit: Int = 50,
    ): FictionResult<List<RadioStation>> {
        val url = "$BASE_URL/json/stations/search"
            .toHttpUrl().newBuilder()
            .apply {
                name?.trim()?.takeIf { it.isNotBlank() }?.let {
                    addQueryParameter("name", it)
                }
                country?.trim()?.takeIf { it.isNotBlank() }?.let {
                    addQueryParameter("country", it)
                }
                language?.trim()?.takeIf { it.isNotBlank() }?.let {
                    addQueryParameter("language", it)
                }
                tag?.trim()?.takeIf { it.isNotBlank() }?.let {
                    addQueryParameter("tag", it)
                }
                addQueryParameter("hidebroken", "true")
                addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            }
            .build()
            .toString()
        return getJson<List<RadioBrowserStation>>(url)
            .let { result ->
                when (result) {
                    is FictionResult.Success -> FictionResult.Success(
                        result.value.mapNotNull { it.toRadioStation() },
                    )
                    is FictionResult.Failure -> result
                }
            }
    }

    // ─── transport ────────────────────────────────────────────────────

    private inline fun <reified T> getJson(url: String): FictionResult<T> =
        when (val raw = doRequest(url)) {
            is FictionResult.Success -> try {
                FictionResult.Success(json.decodeFromString<T>(raw.value))
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError(
                    "Radio Browser returned unexpected JSON shape",
                    e,
                )
            }
            is FictionResult.Failure -> raw
        }

    private fun doRequest(url: String): FictionResult<String> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                // Radio Browser asks clients to identify themselves so
                // abusive callers can be contacted directly. The repo
                // URL doubles as the contact channel.
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 404 ->
                        FictionResult.NotFound(
                            "Radio Browser endpoint not found (HTTP 404)",
                        )
                    resp.code == 429 ->
                        FictionResult.RateLimited(
                            retryAfter = resp.header("Retry-After")
                                ?.toDoubleOrNull()
                                ?.let { ceil(it).toLong().seconds },
                            message = "Radio Browser rate-limited the request",
                        )
                    !resp.isSuccessful ->
                        FictionResult.NetworkError(
                            "HTTP ${resp.code} from Radio Browser",
                            IOException("HTTP ${resp.code}"),
                        )
                    else -> FictionResult.Success(text)
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        }
    }

    companion object {
        /**
         * Pinned to `de1`. See class kdoc for the rationale; the
         * round-robin DNS endpoint is a worse fit for our usage shape.
         */
        const val BASE_URL: String = "https://de1.api.radio-browser.info"

        const val USER_AGENT: String =
            "storyvox-radio/0.5.32 (+https://github.com/techempower-org/storyvox)"
    }
}

/**
 * Wire shape of one Radio Browser station record. Only the fields
 * storyvox actually surfaces are decoded — `ignoreUnknownKeys = true`
 * tolerates the ~40 other fields Radio Browser returns per row.
 *
 * `url_resolved` is the post-redirect direct stream URL Radio Browser
 * resolves at index time; we prefer it over `url` because it skips one
 * round of redirects on the client side. Both fields are documented
 * to be HTTPS for stations with `ssl_error=0`; we filter to that
 * subset in [toRadioStation].
 */
@Serializable
internal data class RadioBrowserStation(
    @SerialName("stationuuid") val stationUuid: String,
    val name: String,
    @SerialName("url_resolved") val urlResolved: String = "",
    val url: String = "",
    val homepage: String = "",
    val country: String = "",
    val language: String = "",
    val tags: String = "",
    val codec: String = "",
    @SerialName("ssl_error") val sslError: Int = 0,
    @SerialName("lastcheckok") val lastCheckOk: Int = 1,
) {
    /**
     * Map a Radio Browser hit to a storyvox-shaped [RadioStation], or
     * null if the entry isn't usable (no HTTPS URL, currently broken,
     * blank name). The null-then-mapNotNull pattern keeps the per-hit
     * filtering inline with the parse step so the list-level call site
     * stays readable.
     */
    fun toRadioStation(): RadioStation? {
        if (name.isBlank()) return null
        if (lastCheckOk == 0) return null
        if (sslError != 0) return null
        val stream = urlResolved.ifBlank { url }
        if (!stream.startsWith("https://")) return null
        return RadioStation(
            id = "rb:$stationUuid",
            displayName = name.trim(),
            description = buildList {
                if (country.isNotBlank()) add(country)
                if (language.isNotBlank()) add(language)
                if (codec.isNotBlank()) add(codec)
            }.joinToString(" · ").ifEmpty { "Radio Browser station" },
            streamUrl = stream,
            streamCodec = codec.ifBlank { "UNKNOWN" },
            country = country,
            language = language,
            tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            homepage = homepage,
        )
    }
}
