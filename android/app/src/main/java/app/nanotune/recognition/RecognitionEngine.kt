package app.nanotune.recognition

import app.nanotune.core.audio.AudioCapture
import app.nanotune.core.audio.AudioDispatcher
import app.nanotune.core.audio.Pcm
import app.nanotune.data.repository.HistoryRepository
import app.nanotune.data.repository.SavedRecognition
import app.nanotune.data.repository.SyncRepository
import app.nanotune.data.settings.SettingsRepository
import app.nanotune.domain.model.ErrorKind
import app.nanotune.domain.model.RecognitionMode
import app.nanotune.domain.model.RecognitionOutcome
import app.nanotune.recognition.enhanced.EnhancedRecognitionClient
import app.nanotune.recognition.shazamkit.ShazamTokenSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one "tap to identify" attempt end to end: checks the engine is usable *before* opening
 * the microphone, streams audio to it until the first match or the deadline, optionally falls
 * back to the enhanced server route, saves a match locally, and wipes the audio buffer.
 */
class RecognitionEngine(
    private val primary: StreamingRecognitionProvider?,
    private val tokenSource: ShazamTokenSource,
    private val enhanced: EnhancedRecognitionClient,
    private val enhancedGate: EnhancedGate,
    private val audio: AudioCapture,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val enricher: MetadataEnricher,
    private val sync: SyncRepository,
) {
    sealed interface Progress {
        data object Preparing : Progress
        data class Listening(val elapsedMs: Long, val maxMs: Long, val level: Float, val micSilenced: Boolean) : Progress
        data object SearchingEnhanced : Progress
    }

    data class Result(
        val outcome: RecognitionOutcome,
        val saved: SavedRecognition? = null,
        val setupHint: SetupHint? = null,
        val usedEnhanced: Boolean = false,
    )

    val engineBundled: Boolean get() = primary != null

    suspend fun primaryAvailability(): ProviderAvailability = primary?.availability() ?: NOT_BUNDLED

    suspend fun identifyOnce(onProgress: (Progress) -> Unit): Result {
        onProgress(Progress.Preparing)
        val prefs = settings.current()
        val maxMs = prefs.manualListenSeconds * 1_000L
        val enhancedEligible = enhancedGate.isEligible()
        val availability = primaryAvailability()
        val primaryReady = availability is ProviderAvailability.Ready
        if (!primaryReady && !enhancedEligible) {
            return Result(availability.asOutcome(), setupHint = (availability as? ProviderAvailability.NotConfigured)?.hint)
        }

        val rates = primary?.supportedSampleRates ?: listOf(48_000, 44_100, 16_000)
        val recorder = when (val opened = audio.open(rates)) {
            is AudioCapture.OpenResult.Opened -> opened.recorder
            AudioCapture.OpenResult.PermissionMissing ->
                return Result(RecognitionOutcome.Error("Microphone permission is needed to identify music.", retryable = false, kind = ErrorKind.AUDIO))
            is AudioCapture.OpenResult.Failed ->
                return Result(RecognitionOutcome.Error(opened.reason, retryable = true, kind = ErrorKind.AUDIO))
        }

        // Keep a copy of the audio in memory only if the enhanced fallback may need it.
        val clip = if (enhancedEligible) ByteArray(recorder.bytesPerSecond * enhancedGate.maxUploadSeconds()) else null
        var clipLength = 0
        val sink: (ByteArray, Int) -> Unit = { buffer, length ->
            if (clip != null && clipLength < clip.size) {
                val copy = minOf(length, clip.size - clipLength)
                System.arraycopy(buffer, 0, clip, clipLength, copy)
                clipLength += copy
            }
        }

        var outcome: RecognitionOutcome
        var usedEnhanced = false
        try {
            if (!recorder.start()) {
                return Result(RecognitionOutcome.Error("The microphone is busy. Close other recording apps and try again.", retryable = true, kind = ErrorKind.AUDIO))
            }
            outcome = if (primaryReady && primary != null) {
                listenWithStream(primary, recorder, maxMs, onProgress, sink)
            } else {
                recordClip(recorder, minOf(maxMs, enhancedGate.maxUploadSeconds() * 1_000L), onProgress, sink)
                RecognitionOutcome.NoMatch
            }
        } finally {
            recorder.release()
        }

        val worthFallback = outcome is RecognitionOutcome.NoMatch ||
            outcome is RecognitionOutcome.Unavailable ||
            (outcome is RecognitionOutcome.Error && outcome.retryable)
        if (clip != null && worthFallback && clipLength >= recorder.bytesPerSecond * MIN_FALLBACK_SECONDS) {
            onProgress(Progress.SearchingEnhanced)
            outcome = enhanced.identify(clip, clipLength, recorder.sampleRateHz)
            usedEnhanced = true
        }
        clip?.fill(0)

        val match = outcome as? RecognitionOutcome.Match ?: return Result(outcome, usedEnhanced = usedEnhanced)
        val saved = history.saveRecognition(match.track, match.providerId, RecognitionMode.MANUAL, sessionId = null, confidence = match.confidence)
        enricher.enrichLater(saved.trackId)
        sync.requestSync()
        return Result(match, saved, usedEnhanced = usedEnhanced)
    }

    private suspend fun listenWithStream(
        provider: StreamingRecognitionProvider,
        recorder: AudioCapture.Recorder,
        maxMs: Long,
        onProgress: (Progress) -> Unit,
        sink: (ByteArray, Int) -> Unit,
    ): RecognitionOutcome = coroutineScope {
        val stream = when (val opened = provider.openStream(recorder.sampleRateHz, recorder.chunkSizeBytes)) {
            is StreamOpenResult.Opened -> opened.stream
            is StreamOpenResult.Failed -> return@coroutineScope opened.outcome
        }
        val session = IdentifySession()
        val finished = CompletableDeferred<RecognitionOutcome>()
        val deadlinePassed = AtomicBoolean(false)
        val collector = launch {
            stream.outcomes.collect { outcome ->
                when (val decision = session.onOutcome(outcome)) {
                    IdentifySession.Decision.Continue ->
                        // After the deadline, the engine's verdict on the final audio settles it.
                        if (deadlinePassed.get()) finished.complete(session.onDeadline())
                    IdentifySession.Decision.RefreshTokenAndContinue -> tokenSource.get(forceRefresh = true)
                    is IdentifySession.Decision.Finish -> finished.complete(decision.outcome)
                }
            }
        }
        val buffer = ByteArray(recorder.chunkSizeBytes)
        val reader = launch(AudioDispatcher.dispatcher) {
            val streamStart = System.currentTimeMillis()
            var bytes = 0L
            var lastProgressAt = -PROGRESS_INTERVAL_MS
            while (isActive && !finished.isCompleted) {
                val read = recorder.read(buffer)
                if (read < 0) {
                    finished.complete(RecognitionOutcome.Error("The microphone stopped delivering audio.", retryable = true, kind = ErrorKind.AUDIO))
                    break
                }
                if (read == 0) continue
                val positionMs = bytes * 1_000 / recorder.bytesPerSecond
                stream.feed(buffer, read, streamStart + positionMs)
                sink(buffer, read)
                bytes += read
                val elapsed = bytes * 1_000 / recorder.bytesPerSecond
                if (elapsed - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                    onProgress(Progress.Listening(elapsed, maxMs, Pcm.level(buffer, read), recorder.isSilencedByOtherApp()))
                    lastProgressAt = elapsed
                }
                if (elapsed >= maxMs) break
            }
        }
        reader.join()
        deadlinePassed.set(true)
        buffer.fill(0)
        // Give the engine a moment to answer for the final seconds of audio.
        val result = withTimeoutOrNull(FINAL_ANSWER_GRACE_MS) { finished.await() }
        collector.cancelAndJoin()
        stream.close()
        result ?: session.onDeadline()
    }

    private suspend fun recordClip(
        recorder: AudioCapture.Recorder,
        maxMs: Long,
        onProgress: (Progress) -> Unit,
        sink: (ByteArray, Int) -> Unit,
    ) = coroutineScope {
        launch(AudioDispatcher.dispatcher) {
            val buffer = ByteArray(recorder.chunkSizeBytes)
            var bytes = 0L
            while (isActive) {
                val read = recorder.read(buffer)
                if (read <= 0) {
                    if (read < 0) break else continue
                }
                sink(buffer, read)
                bytes += read
                val elapsed = bytes * 1_000 / recorder.bytesPerSecond
                onProgress(Progress.Listening(elapsed, maxMs, Pcm.level(buffer, read), recorder.isSilencedByOtherApp()))
                if (elapsed >= maxMs) break
            }
            buffer.fill(0)
        }.join()
    }

    private fun ProviderAvailability.asOutcome(): RecognitionOutcome = when (this) {
        ProviderAvailability.Ready -> RecognitionOutcome.NoMatch
        is ProviderAvailability.NotConfigured -> RecognitionOutcome.Unavailable(reason)
        is ProviderAvailability.Unavailable -> RecognitionOutcome.Error(reason, retryable, ErrorKind.SERVER)
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 100L
        private const val FINAL_ANSWER_GRACE_MS = 2_500L
        private const val MIN_FALLBACK_SECONDS = 3

        val NOT_BUNDLED = ProviderAvailability.NotConfigured(
            "This build of NanoTune does not include Apple's ShazamKit engine.",
            SetupHint.ENGINE_NOT_BUNDLED,
        )
    }
}
