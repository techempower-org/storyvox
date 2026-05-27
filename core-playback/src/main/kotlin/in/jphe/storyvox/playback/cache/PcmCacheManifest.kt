package `in`.jphe.storyvox.playback.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `<sha>.idx.json` sidecar. Written by [PcmAppender.complete] —
 * its presence on disk = "cache is complete". `PcmCache.isComplete`
 * is just `indexFor(key).exists()`.
 *
 * `start`/`end` are character offsets into the chapter's plaintext, so
 * the EnginePlayer consumer thread can emit `currentSentenceRange`
 * unchanged; `byteOffset`/`byteLen` locate the sentence's PCM in the
 * `.pcm` file.
 *
 * `trailingSilenceMs` is the punctuation-driven cadence pause the
 * streaming source splices in — preserved here so PR-E's
 * `CacheFileSource` can replay the same cadence.
 */
@Serializable
data class PcmIndex(
    val sampleRate: Int,
    val sentenceCount: Int,
    val totalBytes: Long,
    val sentences: List<PcmIndexEntry>,
)

@Serializable
data class PcmIndexEntry(
    val i: Int,
    val start: Int,
    val end: Int,
    val byteOffset: Long,
    val byteLen: Int,
    val trailingSilenceMs: Int,
)

/**
 * The `<sha>.meta.json` sidecar. Written at the START of a render so an
 * in-progress (uncompleted) render is identifiable: meta exists, .pcm
 * exists, but .idx.json doesn't yet. Lets PR-D's tee writer + PR-F's
 * worker tell "I'm resuming someone else's incomplete render" from
 * "the cache is complete and ready to read".
 *
 * [chapterId] is denormalized into meta (vs. derived from the cache
 * key SHA) so [PcmCache.deleteAllForChapter] can sweep every voice
 * variant for one chapter without a key-back-translation table.
 *
 * `createdEpochMs` is informational; LRU eviction uses file mtime on
 * the `.pcm` itself, which gets touched on every play.
 */
@Serializable
data class PcmMeta(
    val chapterId: String,
    val voiceId: String,
    val sampleRate: Int,
    val createdEpochMs: Long,
    val chunkerVersion: Int,
    val speedHundredths: Int,
    val pitchHundredths: Int,
)

/** Shared JSON instance. `ignoreUnknownKeys` keeps PR-D/E/F additions
 *  forward-compatible: a future field added to the manifest in v0.5.x
 *  doesn't blow up an older PR-C-only client trying to read it. */
internal val pcmCacheJson: Json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    explicitNulls = false
}
