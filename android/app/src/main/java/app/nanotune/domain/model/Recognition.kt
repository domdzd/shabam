package app.nanotune.domain.model

/**
 * A short, in-memory PCM sample. Raw audio is never persisted: callers must [clear] the sample
 * as soon as recognition finishes so the bytes do not linger on the heap longer than needed.
 */
class AudioSample(
    val pcmData: ByteArray,
    val sampleRateHz: Int,
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
    val durationMs: Long,
) {
    /** Overwrites the audio bytes with zeros. */
    fun clear() = pcmData.fill(0)

    override fun toString(): String =
        "AudioSample(${pcmData.size} bytes, ${sampleRateHz}Hz, ${channels}ch, ${bitsPerSample}bit, ${durationMs}ms)"
}

/** Normalised metadata for a recognised recording, independent of the provider that found it. */
data class RecognizedTrack(
    /** Provider-scoped identifier, e.g. `shazam:1234567`. */
    val providerTrackId: String? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    val releaseDate: String? = null,
    val artworkUrl: String? = null,
    val isrc: String? = null,
    val genres: List<String> = emptyList(),
    val explicit: Boolean? = null,
    val durationMs: Long? = null,
    /** Links supplied by the provider, keyed by [ExternalLink] constants. */
    val externalUrls: Map<String, String> = emptyMap(),
)

/** Keys used in [RecognizedTrack.externalUrls]. */
object ExternalLink {
    const val APPLE_MUSIC = "apple_music"
    const val SHAZAM_WEB = "shazam_web"
    const val VIDEO = "video"
    const val MUSICBRAINZ_RECORDING = "musicbrainz_recording"
}

/** Why a recognition attempt failed, so the UI can offer the right next step. */
enum class ErrorKind { NETWORK, AUTH, RATE_LIMITED, AUDIO, PROVIDER, SERVER, OTHER }

sealed interface RecognitionOutcome {
    data class Match(
        val track: RecognizedTrack,
        val providerId: String,
        /** Only set when the provider exposes a real score. ShazamKit does not. */
        val confidence: Float? = null,
        /** Human readable provenance such as "Shazam catalogue" or "AudD via your server". */
        val providerLabel: String = providerId,
        val agreeingProviders: List<String> = emptyList(),
    ) : RecognitionOutcome

    data object NoMatch : RecognitionOutcome

    /** The engine cannot be used at all right now (not configured, server down, ...). */
    data class Unavailable(val reason: String) : RecognitionOutcome

    data class Error(
        val safeMessage: String,
        val retryable: Boolean,
        val kind: ErrorKind = ErrorKind.OTHER,
    ) : RecognitionOutcome
}

enum class RecognitionMode { MANUAL, SESSION }
