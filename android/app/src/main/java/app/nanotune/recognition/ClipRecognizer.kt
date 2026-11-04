package app.nanotune.recognition

import app.nanotune.data.clips.ClipRepository
import app.nanotune.data.repository.HistoryRepository
import app.nanotune.data.repository.SyncRepository
import app.nanotune.domain.model.AudioSample
import app.nanotune.domain.model.ClipAttempt
import app.nanotune.domain.model.ClipStatus
import app.nanotune.domain.model.RecognitionMode
import app.nanotune.domain.model.RecognitionOutcome
import app.nanotune.domain.model.RecognizedTrack
import app.nanotune.recognition.enhanced.EnhancedRecognitionClient

/**
 * Identifies a saved recording later: first with the on-device engine (ShazamKit), then — only if
 * that finds nothing and the user agreed — with the server's engines. ShazamKit results are never
 * sent to or compared with other engines.
 */
class ClipRecognizer(
    private val primary: StreamingRecognitionProvider?,
    private val enhanced: EnhancedRecognitionClient,
    private val gate: EnhancedGate,
    private val clips: ClipRepository,
    private val history: HistoryRepository,
    private val enricher: MetadataEnricher,
    private val sync: SyncRepository,
) {
    sealed interface Result {
        data class Identified(val track: RecognizedTrack, val trackId: String, val engine: String) : Result
        data object NoMatch : Result
        data class Failed(val message: String, val retryable: Boolean) : Result
        data class NoEngine(val message: String) : Result
    }

    suspend fun identify(clipId: String): Result {
        val clip = clips.clips.value.firstOrNull { it.id == clipId }
        if (clip?.status == ClipStatus.IDENTIFIED) return Result.Failed("This recording is already identified.", retryable = false)
        val (pcm, rate) = clips.pcm(clipId) ?: return Result.Failed("The recording file is missing.", retryable = false)
        try {
            var outcome: RecognitionOutcome? = null
            var engine = ""
            if (primary != null && primary.availability() == ProviderAvailability.Ready) {
                engine = RecognitionEngine.SHAZAM_LABEL
                outcome = primary.identify(AudioSample(pcm, rate, durationMs = pcm.size * 1_000L / (rate * 2)))
                clips.recordAttempt(clipId, ClipAttempt(System.currentTimeMillis(), engine, RecognitionEngine.attemptOutcome(outcome)), null)
            }
            if (outcome !is RecognitionOutcome.Match && gate.primaryEligible()) {
                engine = gate.providerNames()
                val upload = minOf(pcm.size, rate * 2 * gate.maxUploadSeconds())
                outcome = enhanced.identify(pcm, upload - upload % 2, rate, EnhancedRecognitionClient.Mode.PRIMARY)
                clips.recordAttempt(clipId, ClipAttempt(System.currentTimeMillis(), engine, RecognitionEngine.attemptOutcome(outcome)), null)
            }
            return when (outcome) {
                null -> Result.NoEngine(
                    if (primary == null) {
                        "No recognition engine is available. Turn on your server's engines in Settings."
                    } else {
                        "The recognition engine isn't reachable right now."
                    },
                )
                is RecognitionOutcome.Match -> {
                    val saved = history.saveRecognition(
                        outcome.track,
                        outcome.providerId,
                        RecognitionMode.MANUAL,
                        sessionId = null,
                        confidence = outcome.confidence,
                        recognizedAt = clip?.createdAt,
                    )
                    clips.recordAttempt(
                        clipId,
                        ClipAttempt(System.currentTimeMillis(), engine, "match"),
                        ClipRepository.Identified(saved.recognitionId, saved.trackId, outcome.track.title, outcome.track.artist),
                    )
                    enricher.enrichLater(saved.trackId)
                    sync.requestSync()
                    Result.Identified(outcome.track, saved.trackId, engine)
                }
                RecognitionOutcome.NoMatch -> Result.NoMatch
                is RecognitionOutcome.Unavailable -> Result.Failed(outcome.reason, retryable = true)
                is RecognitionOutcome.Error -> Result.Failed(outcome.safeMessage, outcome.retryable)
            }
        } finally {
            pcm.fill(0)
        }
    }
}
