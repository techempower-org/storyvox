package `in`.jphe.storyvox.playback.tts

import java.text.BreakIterator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

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
 */
const val CHUNKER_VERSION: Int = 2

data class Sentence(
    val index: Int,
    val startChar: Int,
    val endChar: Int,
    val text: String,
)

/**
 * Splits chapter text into sentence-sized utterances using ICU's [BreakIterator].
 * Sentences exceeding [maxUtteranceChars] (engine limit ~4000 by default) are
 * subdivided at clause boundaries; the [SentenceTracker] reassembles parent
 * ranges from sub-utterance ids.
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
                    endChar = trimmedStart + sentenceText.length,
                    text = sentenceText,
                )
            }
            start = end
        }
        return out
    }

    fun utteranceId(sentenceIndex: Int, subIndex: Int = 0): String =
        "s${sentenceIndex}_p$subIndex"

    fun parseSentenceIndex(utteranceId: String): Int? =
        utteranceId.removePrefix("s").substringBefore("_").toIntOrNull()
}
