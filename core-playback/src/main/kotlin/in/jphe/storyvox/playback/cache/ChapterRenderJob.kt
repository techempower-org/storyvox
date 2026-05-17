package `in`.jphe.storyvox.playback.cache

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.CodeBySonu.VoxSherpa.KittenEngine
import com.CodeBySonu.VoxSherpa.KokoroEngine
import com.CodeBySonu.VoxSherpa.VoiceEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDict
import `in`.jphe.storyvox.playback.EngineSampleRateCache
import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
import `in`.jphe.storyvox.playback.tts.SentenceChunker
import `in`.jphe.storyvox.playback.tts.source.trailingPauseMs
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceManager
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * Background WorkManager job that renders one chapter's PCM into the
 * cache. Triggered by [PcmRenderScheduler.scheduleRender] from
 * [PrerenderTriggers]' lifecycle hooks (library-add, chapter
 * natural-end +2, fullPrerender flow flips).
 *
 * Lifecycle:
 *  1. Re-read chapter text + active voice at run-time. Voice may have
 *     changed between schedule-time and run-time — recompute the cache
 *     key with the CURRENT voice.
 *  2. If the cache is already complete for the recomputed key, skip
 *     (someone else already rendered it; tee-write from foreground
 *     playback or a sibling worker).
 *  3. Skip Azure voices entirely — BYOK rate limits + cloud round-
 *     trips make unsupervised background renders risky. Foreground
 *     play of an Azure voice still tees normally via PR-D.
 *  4. setForeground for the duration — render can take 30+ minutes on
 *     Piper-high + Tab A7 Lite.
 *  5. Acquire [EngineMutex.mutex] around loadModel + every
 *     generateAudioPCM call. EnginePlayer.loadAndPlay also takes this
 *     mutex; serialization is at sentence granularity so foreground
 *     playback can interleave between worker sentences.
 *  6. Loop sentences: generate PCM → [PcmAppender.appendSentence].
 *  7. On completion finalize the appender + run evictToQuota.
 *  8. On worker cancellation (user removed fiction, voice changed and
 *     foreground render of same chapter started), abandon the
 *     appender so partial files don't lie around.
 *
 * Speed/pitch quantization: PR-F's first cut renders at 1.0× / 1.0×
 * regardless of the user's persisted default. A user who plays at
 * 1.5× will hit a cache miss and the streaming path will tee a
 * SECOND cache file at 1.5×. Disk usage grows linearly in distinct
 * speed knobs the user explores; spec accepts this trade-off (open
 * question 1 in the plan).
 *
 * Pronunciation dict snapshot: empty dict at worker time so the key
 * is stable across schedule→run windows. A foreground play with a
 * custom dict will tee a separate cache key.
 *
 * Retry semantics: chapter-body-not-yet-downloaded triggers
 * [Result.retry] (ChapterDownloadWorker may still be running);
 * model-load-failure and OOM trigger retry with the scheduler's
 * exponential backoff (5-minute base). Worker cancellation
 * (isStopped == true) returns [Result.failure] without retry —
 * the cancel was deliberate.
 *
 * PR F of the PCM cache series (#86).
 */
@HiltWorker
class ChapterRenderJob @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val chapterRepo: ChapterRepository,
    private val voiceManager: VoiceManager,
    private val pcmCache: PcmCache,
    private val engineMutex: EngineMutex,
    private val chunker: SentenceChunker,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val fictionId = inputData.getString(KEY_FICTION_ID)
            ?: return Result.failure()
        val chapterId = inputData.getString(KEY_CHAPTER_ID)
            ?: return Result.failure()

        Log.i(LOG_TAG, "pcm-cache PRERENDER-RUNNING chapterId=$chapterId fictionId=$fictionId")

        // 1. Re-read chapter text. If the body isn't downloaded yet,
        // retry — ChapterDownloadWorker may still be running.
        val chapter = chapterRepo.getChapter(chapterId)
        if (chapter == null) {
            Log.i(LOG_TAG, "pcm-cache PRERENDER-SKIP-NOTEXT chapterId=$chapterId")
            return Result.retry()
        }

        // Issue #373 — audio-stream chapters (audioUrl != null) bypass
        // TTS entirely; there's nothing to pre-render.
        if (chapter.audioUrl != null) {
            Log.i(LOG_TAG, "pcm-cache PRERENDER-SKIP-AUDIOURL chapterId=$chapterId")
            return Result.success()
        }
        if (chapter.text.isEmpty()) {
            Log.i(LOG_TAG, "pcm-cache PRERENDER-SKIP-EMPTYTEXT chapterId=$chapterId")
            return Result.success()
        }

        // 2. Re-read active voice at run-time.
        val voice = voiceManager.activeVoice.first()
        if (voice == null) {
            Log.i(LOG_TAG, "pcm-cache PRERENDER-SKIP-NOVOICE chapterId=$chapterId")
            return Result.retry()
        }

        // 3. Skip Azure voices — see kdoc.
        if (voice.engineType is EngineType.Azure) {
            Log.i(
                LOG_TAG,
                "pcm-cache PRERENDER-SKIP-AZURE chapterId=$chapterId voiceId=${voice.id}",
            )
            return Result.success()
        }
        // #676 — Skip System TTS background pre-rendering. The framework
        // TextToSpeech wants the foreground process to drive synthesis
        // (binder ServiceConnection on the OS engine's process) — caching
        // here would require a per-chapter foreground service binding
        // that's heavier than the on-demand synth path saves. Run-time
        // System TTS is fast enough that the perf win from pre-render is
        // small: skipping keeps the worker simple and the cache contract
        // clean.
        if (voice.engineType is EngineType.SystemTts) {
            Log.i(
                LOG_TAG,
                "pcm-cache PRERENDER-SKIP-SYSTEMTTS chapterId=$chapterId voiceId=${voice.id}",
            )
            return Result.success()
        }

        // 4. Build the cache key. Render at the 1.0×/1.0× empty-dict
        // identity — see kdoc on speed/pitch quantization.
        val cacheKey = PcmCacheKey(
            chapterId = chapterId,
            voiceId = voice.id,
            speedHundredths = PcmCacheKey.quantize(1.0f),
            pitchHundredths = PcmCacheKey.quantize(1.0f),
            chunkerVersion = CHUNKER_VERSION,
            pronunciationDictHash = PronunciationDict.EMPTY.contentHash,
        )

        // 5. Skip if already complete.
        if (pcmCache.isComplete(cacheKey)) {
            Log.i(
                LOG_TAG,
                "pcm-cache PRERENDER-SKIP-COMPLETE chapterId=$chapterId " +
                    "base=${cacheKey.fileBaseName().take(12)}",
            )
            return Result.success()
        }

        // 6. Wipe stale partial — same abandon-and-restart policy as
        // the foreground streaming-tee path (PR-D).
        if (pcmCache.metaFileFor(cacheKey).exists()) {
            pcmCache.delete(cacheKey)
        }

        // 7. setForeground for long renders — render can run 30+ min on
        // Piper-high on the Tab A7 Lite. runCatching guards against
        // foreground-service start failures on locked devices /
        // restricted-app states; the worker will run anyway, just at
        // background priority and at risk of doze.
        runCatching { setForeground(buildForegroundInfo(chapter.title)) }

        // 8. Load the model (idempotent if EnginePlayer already loaded
        // the same voice). Holds engineMutex.
        val loadResult = engineMutex.mutex.withLock {
            if (isStopped) return Result.failure()
            loadModel(voice)
        }
        if (loadResult != "Success") {
            Log.w(
                LOG_TAG,
                "pcm-cache PRERENDER-FAIL chapterId=$chapterId loadResult=$loadResult",
            )
            return Result.retry()
        }
        // Issue #582 — populate the @Volatile sample-rate cache off
        // this background load, so any concurrent main-thread reader
        // (e.g. user starts playback mid-prerender) hits the lock-free
        // volatile rather than contending on the engine monitor with
        // this worker's loadModel.
        EngineSampleRateCache.refreshFromEngine()

        // 9. Generate sentences + write to cache.
        val sentences = chunker.chunk(chapter.text)
        val sampleRate = sampleRateFor(voice)
        val appender = pcmCache.appender(cacheKey, sampleRate = sampleRate)
        try {
            for (s in sentences) {
                if (isStopped) {
                    appender.abandon()
                    Log.i(LOG_TAG, "pcm-cache PRERENDER-CANCELLED chapterId=$chapterId")
                    return Result.failure()
                }
                val pcm = engineMutex.mutex.withLock {
                    if (isStopped) return@withLock null
                    generateAudioPCM(voice, s.text)
                } ?: continue
                val pauseMs = trailingPauseMs(s.text)
                runCatching { appender.appendSentence(s, pcm, pauseMs) }
                    .onFailure {
                        appender.abandon()
                        Log.w(LOG_TAG, "pcm-cache PRERENDER-FAIL chapterId=$chapterId", it)
                        return Result.retry()
                    }
            }
            // 10. Complete + evict. evictToQuota pins the just-completed
            // basename so mtime-tie races don't evict the fresh entry.
            // Issue #581 — `complete()` (was `finalize()`) avoids the
            // Object.finalize() shadow that produced uncaught
            // IllegalStateExceptions on the FinalizerDaemon thread.
            runCatching { appender.complete() }
                .onFailure {
                    appender.abandon()
                    Log.w(LOG_TAG, "pcm-cache PRERENDER-FAIL chapterId=$chapterId complete", it)
                    return Result.retry()
                }
            runCatching {
                pcmCache.evictToQuota(pinnedBasenames = setOf(cacheKey.fileBaseName()))
            }
            Log.i(
                LOG_TAG,
                "pcm-cache PRERENDER-SUCCESS chapterId=$chapterId voice=${voice.id} " +
                    "sentences=${sentences.size} base=${cacheKey.fileBaseName().take(12)}",
            )
            return Result.success()
        } catch (t: Throwable) {
            appender.abandon()
            Log.w(LOG_TAG, "pcm-cache PRERENDER-FAIL chapterId=$chapterId threw", t)
            return if (isStopped) Result.failure() else Result.retry()
        }
    }

    // ── engine bridging ─────────────────────────────────────────────────

    private fun loadModel(voice: UiVoiceInfo): String =
        when (val type = voice.engineType) {
            is EngineType.Piper -> {
                val voiceDir = voiceManager.voiceDirFor(voice.id)
                val onnx = File(voiceDir, "model.onnx").absolutePath
                val tokens = File(voiceDir, "tokens.txt").absolutePath
                VoiceEngine.getInstance().loadModel(appContext, onnx, tokens)
                    ?: "Error: load returned null"
            }
            is EngineType.Kokoro -> {
                val sharedDir = voiceManager.kokoroSharedDir()
                val onnx = File(sharedDir, "model.onnx").absolutePath
                val tokens = File(sharedDir, "tokens.txt").absolutePath
                val voicesBin = File(sharedDir, "voices.bin").absolutePath
                KokoroEngine.getInstance().setActiveSpeakerId(type.speakerId)
                KokoroEngine.getInstance().loadModel(appContext, onnx, tokens, voicesBin)
                    ?: "Error: load returned null"
            }
            is EngineType.Kitten -> {
                val sharedDir = voiceManager.kittenSharedDir()
                val onnx = File(sharedDir, "model.onnx").absolutePath
                val tokens = File(sharedDir, "tokens.txt").absolutePath
                val voicesBin = File(sharedDir, "voices.bin").absolutePath
                KittenEngine.getInstance().setActiveSpeakerId(type.speakerId)
                KittenEngine.getInstance().loadModel(appContext, onnx, tokens, voicesBin)
                    ?: "Error: load returned null"
            }
            // Guarded above — defensive fall-through if surface evolves.
            is EngineType.Azure -> "Error: Azure not supported in background pre-render"
            // #676 — also guarded above; mirrors Azure with a typed error
            // so the surface is exhaustive without a fall-through `else`.
            is EngineType.SystemTts ->
                "Error: System TTS not supported in background pre-render"
        }

    private fun sampleRateFor(voice: UiVoiceInfo): Int =
        when (voice.engineType) {
            is EngineType.Kokoro -> KokoroEngine.getInstance().sampleRate
            is EngineType.Kitten -> KittenEngine.getInstance().sampleRate
            else -> VoiceEngine.getInstance().sampleRate
        }.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE_HZ

    private fun generateAudioPCM(voice: UiVoiceInfo, text: String): ByteArray? =
        when (voice.engineType) {
            is EngineType.Kokoro -> KokoroEngine.getInstance()
                .generateAudioPCM(text, 1.0f, 1.0f)
            is EngineType.Kitten -> KittenEngine.getInstance()
                .generateAudioPCM(text, 1.0f, 1.0f)
            else -> VoiceEngine.getInstance()
                .generateAudioPCM(text, 1.0f, 1.0f)
        }

    // ── foreground notification ─────────────────────────────────────────

    private fun buildForegroundInfo(title: String): ForegroundInfo {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Background audiobook caching" }
            nm.createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Caching audiobook")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // FOREGROUND_SERVICE_TYPE_DATA_SYNC is the closest WorkManager
        // type for "render audio in the background" — synthesis is
        // local CPU work, no media playback, no user-visible Media3
        // session attached. Required on API 34+ to avoid SecurityException.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notif)
        }
    }

    companion object {
        private const val LOG_TAG = "ChapterRenderJob"
        const val KEY_FICTION_ID = "fictionId"
        const val KEY_CHAPTER_ID = "chapterId"
        private const val CHANNEL_ID = "pcm-render-channel"
        private const val CHANNEL_NAME = "PCM render"
        private const val NOTIFICATION_ID = 5042
        /** Safe fallback if the engine reports `sampleRate == 0`. Matches
         *  the Piper-high default. */
        private const val DEFAULT_SAMPLE_RATE_HZ = 22_050
    }
}
