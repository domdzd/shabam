package app.nanotune.recognition

import app.nanotune.domain.model.AudioSample
import app.nanotune.domain.model.RecognitionOutcome
import kotlinx.coroutines.flow.Flow

/**
 * A music recognition engine. UI and business logic depend only on this abstraction so engines
 * can be added or replaced without touching screens. Implementations must never persist audio.
 */
interface MusicRecognitionProvider {
    /** Stable identifier stored with each recognition, e.g. `shazamkit`. */
    val id: String

    /** Human readable provenance shown to the user, e.g. "Shazam catalogue (ShazamKit)". */
    val displayName: String

    /** Cheap check run before the microphone is opened, so we never record for nothing. */
    suspend fun availability(): ProviderAvailability

    /** Identifies a complete, pre-recorded sample. */
    suspend fun identify(sample: AudioSample): RecognitionOutcome
}

/** Engines that can match a live stream of microphone audio (e.g. ShazamKit's StreamingSession). */
interface StreamingRecognitionProvider : MusicRecognitionProvider {
    /** Sample rates the engine accepts for 16-bit mono PCM, best first. */
    val supportedSampleRates: List<Int>

    suspend fun openStream(sampleRateHz: Int, readBufferSizeBytes: Int): StreamOpenResult
}

sealed interface StreamOpenResult {
    data class Opened(val stream: RecognitionStream) : StreamOpenResult
    data class Failed(val outcome: RecognitionOutcome) : StreamOpenResult
}

/** A live recognition stream. Results may repeat the same match many times. */
interface RecognitionStream {
    val outcomes: Flow<RecognitionOutcome>

    /** Feeds contiguous PCM16 mono audio. Must be called from a single thread. */
    fun feed(audio: ByteArray, length: Int, timestampMs: Long)

    fun close()
}

sealed interface ProviderAvailability {
    data object Ready : ProviderAvailability

    /** Needs owner/user action before it can work. */
    data class NotConfigured(val reason: String, val hint: SetupHint) : ProviderAvailability

    /** Configured but temporarily unusable (offline, server error, rate limited...). */
    data class Unavailable(val reason: String, val retryable: Boolean) : ProviderAvailability
}

enum class SetupHint {
    /** The ShazamKit AAR was not present when this APK was built. */
    ENGINE_NOT_BUNDLED,

    /** No NanoTune server URL is configured in Settings. */
    SET_SERVER_URL,

    /** The server is reachable but has no Apple key configured for token signing. */
    SERVER_NOT_CONFIGURED,
}
