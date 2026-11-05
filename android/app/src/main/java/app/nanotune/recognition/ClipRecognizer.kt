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
                // Query signatures are kept short (as when listening live); the engine gets its own copy.
                val length = evenPrefix(pcm.size, rate, MAX_SIGNATURE_SECONDS)
                val sample = AudioSample(pcm.copyOf(length), rate, durationMs = length * 1_000L / (rate * 2))
                outcome = try {
                    primary.identify(sample)
                } finally {
                    sample.clear()
                }
                if (outcome !is RecognitionOutcome.Match) recordAttempt(clipId, engine, outcome)
            }
            if (outcome !is RecognitionOutcome.Match && gate.primaryEligible()) {
                engine = gate.providerNames()
                val upload = evenPrefix(pcm.size, rate, gate.maxUploadSeconds())
                outcome = enhanced.identify(pcm, upload, rate, EnhancedRecognitionClient.Mode.PRIMARY)
                if (outcome !is RecognitionOutcome.Match) recordAttempt(clipId, engine, outcome)
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

    private suspend fun recordAttempt(clipId: String, engine: String, outcome: RecognitionOutcome) {
        clips.recordAttempt(clipId, ClipAttempt(System.currentTimeMillis(), engine, RecognitionEngine.attemptOutcome(outcome)), null)
    }

    /** Byte length of at most [seconds] of 16-bit mono audio, rounded down to whole samples. */
    private fun evenPrefix(size: Int, rate: Int, seconds: Int): Int {
        val length = minOf(size, rate * 2 * seconds)
        return length - length % 2
    }

    private companion object {
        const val MAX_SIGNATURE_SECONDS = 12
    }
}
