package app.nanotune.recognition

import app.nanotune.core.audio.AudioCapture
import app.nanotune.core.audio.AudioDispatcher
import app.nanotune.core.audio.Pcm
import app.nanotune.data.clips.ClipPolicy
import app.nanotune.data.clips.ClipRepository
import app.nanotune.data.repository.HistoryRepository
import app.nanotune.data.repository.SavedRecognition
import app.nanotune.data.repository.SyncRepository
import app.nanotune.data.settings.SettingsRepository
import app.nanotune.domain.model.CapturedClip
import app.nanotune.domain.model.ClipAttempt
import app.nanotune.domain.model.ClipReason
import app.nanotune.domain.model.ErrorKind
import app.nanotune.domain.model.RecognitionMode
import app.nanotune.domain.model.RecognitionOutcome
import app.nanotune.domain.model.SavedClip
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
 * Runs one "tap to identify" attempt end to end:
 *
 * 1. Picks the route **before** opening the microphone: the on-device engine (ShazamKit), the
 *    server's engines (if the user agreed), or — when offline and the user keeps recordings — a
 *    capture to identify later. If nothing can work, it explains why without recording.
 * 2. Streams audio to the engine until the first match or the deadline.
 * 3. Optionally falls back to the server engines after a ShazamKit "no match".
 * 4. Saves a match, then keeps the recording only if the user opted in (or offers to keep it).
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
    private val clips: ClipRepository,
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
        /** Server engines exist but the user hasn't agreed to send them audio yet. */
        val consentNeeded: Boolean = false,
        /** Recording kept automatically (user opted in). */
        val savedClip: SavedClip? = null,
        /** Recording held in memory that the user may still choose to keep. Wipe when done. */
        val pendingClip: CapturedClip? = null,
        /** The audio was captured while offline and will be identified later. */
        val savedForLater: Boolean = false,
    )

    val engineBundled: Boolean get() = primary != null

    suspend fun primaryAvailability(): ProviderAvailability = primary?.availability() ?: NOT_BUNDLED

    suspend fun identifyOnce(onProgress: (Progress) -> Unit): Result {
        onProgress(Progress.Preparing)
        val prefs = settings.current()
        val maxMs = prefs.manualListenSeconds * 1_000L
        val availability = primaryAvailability()
        val primaryReady = availability is ProviderAvailability.Ready && primary != null
        val serverPrimary = !primaryReady && enhancedGate.primaryEligible()
        val fallback = primaryReady && enhancedGate.fallbackEligible()
        val couldIdentifyLater = primary != null || (prefs.enhancedEnabled && prefs.enhancedConsentAt != null)
        val offlineCapture = !primaryReady && !serverPrimary && prefs.saveRecordings && prefs.retryRecordingsOnline &&
            availability is ProviderAvailability.Unavailable && availability.retryable && couldIdentifyLater

        if (!primaryReady && !serverPrimary && !offlineCapture) {
            return Result(
                availability.asOutcome(),
                setupHint = (availability as? ProviderAvailability.NotConfigured)?.hint,
                consentNeeded = enhancedGate.consentNeeded(),
            )
        }

        val rates = primary?.supportedSampleRates ?: listOf(48_000, 44_100, 16_000)
        val recorder = when (val opened = audio.open(rates)) {
            is AudioCapture.OpenResult.Opened -> opened.recorder
            AudioCapture.OpenResult.PermissionMissing ->
                return Result(RecognitionOutcome.Error("Microphone permission is needed to identify music.", retryable = false, kind = ErrorKind.AUDIO))
            is AudioCapture.OpenResult.Failed ->
                return Result(RecognitionOutcome.Error(opened.reason, retryable = true, kind = ErrorKind.AUDIO))
        }

        val uploadMs = enhancedGate.maxUploadSeconds() * 1_000L
        val listenMs = if (serverPrimary) minOf(maxMs, uploadMs) else maxMs
        // Keep the audio in memory only when something may need it afterwards.
        val keepAudio = serverPrimary || fallback || offlineCapture || prefs.saveRecordings
        val clip = if (keepAudio) ByteArray((recorder.bytesPerSecond * listenMs / 1_000).toInt()) else null
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
        var engineLabel = if (primaryReady) SHAZAM_LABEL else enhancedGate.providerNames()
        try {
            if (!recorder.start()) {
                clip?.fill(0)
                return Result(RecognitionOutcome.Error("The microphone is busy. Close other recording apps and try again.", retryable = true, kind = ErrorKind.AUDIO))
            }
            outcome = if (primaryReady && primary != null) {
                listenWithStream(primary, recorder, listenMs, onProgress, sink)
            } else {
                recordClip(recorder, listenMs, onProgress, sink)
                RecognitionOutcome.NoMatch
            }
        } finally {
            recorder.release()
        }
        val sampleRate = recorder.sampleRateHz
        val uploadBytes = minOf(clipLength, (recorder.bytesPerSecond * uploadMs / 1_000).toInt())
        val enoughAudio = clipLength >= recorder.bytesPerSecond * MIN_UPLOAD_SECONDS

        if (serverPrimary && clip != null && enoughAudio) {
            onProgress(Progress.SearchingEnhanced)
            outcome = enhanced.identify(clip, uploadBytes, sampleRate, EnhancedRecognitionClient.Mode.PRIMARY)
            usedEnhanced = true
        } else if (fallback && clip != null && enoughAudio && worthFallback(outcome)) {
            onProgress(Progress.SearchingEnhanced)
            outcome = enhanced.identify(clip, uploadBytes, sampleRate, EnhancedRecognitionClient.Mode.FALLBACK)
            usedEnhanced = true
            engineLabel = "$SHAZAM_LABEL, then ${enhancedGate.providerNames()}"
        } else if (offlineCapture) {
            outcome = RecognitionOutcome.Error(
                "You're offline, so NanoTune kept the recording and will identify it when you're back online.",
                retryable = true,
                kind = ErrorKind.NETWORK,
            )
        }

        val match = outcome as? RecognitionOutcome.Match
        val saved = match?.let {
            history.saveRecognition(it.track, it.providerId, RecognitionMode.MANUAL, sessionId = null, confidence = it.confidence)
        }
        if (saved != null) {
            enricher.enrichLater(saved.trackId)
            sync.requestSync()
        }

        var savedClip: SavedClip? = null
        var pendingClip: CapturedClip? = null
        if (clip != null && prefs.saveRecordings && clipLength > 0) {
            val captured = CapturedClip(clip, clipLength, sampleRate)
            val reason = ClipPolicy.autoSaveReason(outcome, prefs.recordingsPolicy, offlineCapture)
            if (reason != null) {
                val networkFailure = outcome is RecognitionOutcome.Error && outcome.kind == ErrorKind.NETWORK
                savedClip = runCatching {
                    clips.save(
                        captured,
                        reason,
                        retryWhenOnline = offlineCapture || (networkFailure && prefs.retryRecordingsOnline),
                        recognitionId = saved?.recognitionId,
                        trackId = saved?.trackId,
                        title = match?.track?.title,
                        artist = match?.track?.artist,
                        firstAttempt = if (offlineCapture) null else ClipAttempt(System.currentTimeMillis(), engineLabel, attemptOutcome(outcome)),
                    )
                }.getOrNull()
                captured.wipe()
            } else {
                pendingClip = captured
            }
        } else {
            clip?.fill(0)
        }

        return Result(
            outcome = outcome,
            saved = saved,
            usedEnhanced = usedEnhanced,
            savedClip = savedClip,
            pendingClip = pendingClip,
            savedForLater = offlineCapture && savedClip != null,
        )
    }

    /** Keeps a recording the user chose to save after the fact. */
    suspend fun keepPendingClip(clip: CapturedClip, result: Result): SavedClip? {
        val match = result.outcome as? RecognitionOutcome.Match
        val saved = clips.save(
            clip,
            if (match != null) ClipReason.MATCHED else ClipReason.MANUAL,
            recognitionId = result.saved?.recognitionId,
            trackId = result.saved?.trackId,
            title = match?.track?.title,
            artist = match?.track?.artist,
        )
        clip.wipe()
        return saved
    }

    private fun worthFallback(outcome: RecognitionOutcome) = outcome is RecognitionOutcome.NoMatch ||
        outcome is RecognitionOutcome.Unavailable ||
        (outcome is RecognitionOutcome.Error && outcome.retryable)

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
        private const val MIN_UPLOAD_SECONDS = 3
        const val SHAZAM_LABEL = "Shazam"

        val NOT_BUNDLED = ProviderAvailability.NotConfigured(
            "This build of NanoTune does not include Apple's ShazamKit engine.",
            SetupHint.ENGINE_NOT_BUNDLED,
        )

        fun attemptOutcome(outcome: RecognitionOutcome): String = when (outcome) {
            is RecognitionOutcome.Match -> "match"
            RecognitionOutcome.NoMatch -> "no_match"
            else -> "error"
        }
    }
}
