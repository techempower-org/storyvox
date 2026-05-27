package `in`.jphe.storyvox.playback.tts

import java.text.BreakIterator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Samples up to [SCRIPT_SAMPLE_LIMIT] non-whitespace characters from [text]
 * and returns a [Locale] whose [BreakIterator] rules match the dominant
 * Unicode script. Falls back to [fallback] (default [Locale.getDefault])
 * when the text is Latin, Common, or too short to classify.
 *
 * The mapping covers scripts where ICU's sentence-break rules differ
 * materially from the Latin/Common defaults:
 *  - CJK (Han / Hiragana / Katakana → [Locale.JAPANESE], Hangul → [Locale.KOREAN])
 *  - Thai (no inter-word spaces; BreakIterator needs Thai locale)
 *  - Arabic / Hebrew (RTL + different punctuation conventions)
 *
 * Issue #899 — sentence boundaries were always computed with the device
 * locale, producing wrong splits for non-Latin chapter text.
 */
internal fun detectLocale(text: String, fallback: Locale = Locale.getDefault()): Locale {
    val counts = mutableMapOf<Character.UnicodeScript, Int>()
    var sampled = 0
    for (ch in text) {
        if (ch.isWhitespace()) continue
        val script = Character.UnicodeScript.of(ch.code)
        // COMMON covers digits, punctuation, emoji — skip.
        if (script == Character.UnicodeScript.COMMON ||
            script == Character.UnicodeScript.INHERITED
        ) continue
        counts[script] = (counts[script] ?: 0) + 1
        if (++sampled >= SCRIPT_SAMPLE_LIMIT) break
    }
    val dominant = counts.maxByOrNull { it.value }?.key ?: return fallback
    return SCRIPT_LOCALE_MAP[dominant] ?: fallback
}

/** Max non-whitespace characters to sample for script detection. */
private const val SCRIPT_SAMPLE_LIMIT: Int = 200

/** Maps a dominant Unicode script to the most appropriate [Locale] for
 *  [BreakIterator.getSentenceInstance]. Only scripts where ICU's break
 *  rules diverge from the Latin default need entries here. */
private val SCRIPT_LOCALE_MAP: Map<Character.UnicodeScript, Locale> = mapOf(
    Character.UnicodeScript.HAN to Locale.CHINESE,
    Character.UnicodeScript.HIRAGANA to Locale.JAPANESE,
    Character.UnicodeScript.KATAKANA to Locale.JAPANESE,
    Character.UnicodeScript.HANGUL to Locale.KOREAN,
    Character.UnicodeScript.THAI to Locale("th"),
    Character.UnicodeScript.ARABIC to Locale("ar"),
    Character.UnicodeScript.HEBREW to Locale("he"),
)

/**
 * Bumped when [SentenceChunker.chunk] changes its boundary semantics in a way
 * that would shift `(startChar, endChar)` for the same input. Included in
 * [`in`.jphe.storyvox.playback.cache.PcmCacheKey] so a chunker change
 * self-evicts stale on-disk PCM caches without a DB migration. Bumping
 * leaves old caches orphaned on disk; LRU eviction reclaims them.
 *
 * Bump policy: increment by 1 in the same commit that changes [SentenceChunker.chunk]'s
 * output. Do NOT bump for cosmetic refactors that don't move any
 * `(startChar, endChar)` pair on real input. If unsure, write a quick
 * test with a representative chapter and diff the resulting [Sentence]
 * lists.
 *
 * Version history:
 *  - v1: initial release.
 *  - v2 (#859): invalidate caches accumulated before the silence-detection
 *    and chunking algorithm evolved past the original v1 boundaries. The
 *    constant had been stuck at 1 across multiple semantic changes, so
 *    bumping once here forces a one-time cache rebuild on first launch.
 *  - v3 (#900): endChar now extends to the next sentence's startChar (text
 *    length for the last sentence) instead of stopping at the trimmed text
 *    length, so sentence ranges partition the chapter contiguously. This
 *    moves endChar for any sentence followed by inter-sentence whitespace.
 *  - v4 (#899): call sites now pass a text-detected locale instead of
 *    Locale.getDefault(). For non-Latin text (CJK, Thai, Arabic, Hebrew)
 *    the BreakIterator uses locale-appropriate rules, shifting boundaries.
 *    Bump invalidates pre-cached PCM that was chunked under the wrong locale.
 */
const val CHUNKER_VERSION: Int = 4

data class Sentence(
    val index: Int,
    val startChar: Int,
    val endChar: Int,
    val text: String,
)

/**
 * Splits chapter text into sentence-sized utterances using ICU's [BreakIterator].
 * Sentences exceeding [maxUtteranceChars] (engine limit ~4000 by default) are
 * subdivided at clause boundaries; the EnginePlayer consumer thread
 * reassembles parent ranges and emits `currentSentenceRange` via
 * `_observableState.update` as each utterance plays.
 */
@Singleton
class SentenceChunker @Inject constructor() {

    fun chunk(text: String, locale: Locale = Locale.getDefault()): List<Sentence> {
        if (text.isEmpty()) return emptyList()
        val iter = BreakIterator.getSentenceInstance(locale)
        iter.setText(text)

        val out = mutableListOf<Sentence>()
        var start = iter.first()
        var idx = 0
        while (true) {
            val end = iter.next()
            if (end == BreakIterator.DONE) break
            val raw = text.substring(start, end)
            val trimmedStart = start + raw.indexOfFirst { !it.isWhitespace() }
                .takeIf { it >= 0 }.let { it ?: 0 }
            val sentenceText = raw.trim()
            if (sentenceText.isNotEmpty()) {
                out += Sentence(
                    index = idx++,
                    startChar = trimmedStart,
                    // endChar is rewritten below to the next sentence's startChar
                    // (or text length for the last) so boundaries partition the
                    // chapter contiguously. See #900.
                    endChar = end,
                    text = sentenceText,
                )
            }
            start = end
        }
        // Close the gaps the trimmed startChars leave between sentences: each
        // sentence's endChar becomes the next sentence's startChar so a charOffset
        // landing in inter-sentence whitespace maps to the sentence that owns it,
        // not the previous one (which replayed it). The final sentence extends to
        // the end of the text. #900.
        return out.mapIndexed { i, s ->
            val end = if (i + 1 < out.size) out[i + 1].startChar else text.length
            if (end == s.endChar) s else s.copy(endChar = end)
        }
    }

    fun utteranceId(sentenceIndex: Int, subIndex: Int = 0): String =
        "s${sentenceIndex}_p$subIndex"

    fun parseSentenceIndex(utteranceId: String): Int? =
        utteranceId.removePrefix("s").substringBefore("_").toIntOrNull()
}
