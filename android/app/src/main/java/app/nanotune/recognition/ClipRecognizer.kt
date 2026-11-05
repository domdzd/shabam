package app.nanotune.recognition

import app.nanotune.core.audio.Pcm
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
 * sent to or compared with other engines. Engines get the loudest 12 seconds of the recording (or
 * of the part the user selected), which is where the music usually is.
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

    /** Whether any engine could be asked right now (ShazamKit in this build, or the server's engines). */
    suspend fun canIdentify(): Boolean = primary != null || gate.primaryEligible()

    /** Identifies [clipId]; [fromMs]/[toMs] limit it to a part of the recording. */
    suspend fun identify(clipId: String, fromMs: Long? = null, toMs: Long? = null): Result {
        val clip = clips.clips.value.firstOrNull { it.id == clipId }
        if (clip?.status == ClipStatus.IDENTIFIED && fromMs == null) {
            return Result.Failed("This recording is already identified.", retryable = false)
        }
        val (pcm, rate) = clips.pcm(clipId) ?: return Result.Failed("The recording file is missing.", retryable = false)
        try {
            val from = fromMs?.let { Pcm.offsetOf(it, rate, pcm.size) } ?: 0
            val to = toMs?.let { Pcm.offsetOf(it, rate, pcm.size) } ?: pcm.size
            if (to - from < rate * 2 * MIN_SECONDS) return Result.Failed("Select at least $MIN_SECONDS seconds.", retryable = false)
            gate.ensureFresh()
            var outcome: RecognitionOutcome? = null
            var engine = ""
            if (primary != null && primary.availability() == ProviderAvailability.Ready) {
                engine = RecognitionEngine.SHAZAM_LABEL
                val sample = window(pcm, from, to, rate, MAX_SIGNATURE_SECONDS)
                outcome = try {
                    primary.identify(AudioSample(sample, rate, durationMs = sample.size * 1_000L / (rate * 2)))
                } finally {
                    sample.fill(0)
                }
                if (outcome !is RecognitionOutcome.Match) recordAttempt(clipId, engine, outcome)
            }
            if (outcome !is RecognitionOutcome.Match && gate.primaryEligible()) {
                engine = gate.providerNames()
                val upload = window(pcm, from, to, rate, gate.maxUploadSeconds())
                outcome = try {
                    enhanced.identify(upload, upload.size, rate, EnhancedRecognitionClient.Mode.PRIMARY)
                } finally {
                    upload.fill(0)
                }
                if (outcome !is RecognitionOutcome.Match) recordAttempt(clipId, engine, outcome)
            }
            return when (outcome) {
                null -> Result.NoEngine(
                    if (primary == null) {
                        "No recognition engine is available. Allow your server's engines in Settings (or on the Identify screen)."
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

    /** A copy of the loudest [seconds] between byte offsets [from] and [to]. */
    private fun window(pcm: ByteArray, from: Int, to: Int, rate: Int, seconds: Int): ByteArray {
        val part = pcm.copyOfRange(from, to)
        return try {
            val range = Pcm.loudestWindow(part, part.size, rate, seconds)
            part.copyOfRange(range.first, range.last + 1)
        } finally {
            part.fill(0)
        }
    }

    private companion object {
        const val MAX_SIGNATURE_SECONDS = 12
        const val MIN_SECONDS = 3
    }
}
