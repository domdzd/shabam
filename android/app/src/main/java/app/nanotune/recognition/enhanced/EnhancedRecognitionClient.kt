package app.nanotune.recognition.enhanced

import app.nanotune.core.audio.Pcm
import app.nanotune.data.remote.ApiResult
import app.nanotune.data.remote.BackendClient
import app.nanotune.data.remote.dto.EnhancedResultDto
import app.nanotune.data.repository.AccountRepository
import app.nanotune.data.settings.CredentialStore
import app.nanotune.domain.model.ErrorKind
import app.nanotune.domain.model.RecognitionOutcome
import app.nanotune.domain.model.RecognizedTrack
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sends one short clip to the user's own NanoTune server, which routes it to the engines the owner
 * configured (e.g. AudD, ACRCloud). Only called after [app.nanotune.recognition.EnhancedGate] said so.
 */
class EnhancedRecognitionClient(
    private val backend: BackendClient,
    private val account: AccountRepository,
    private val credentials: CredentialStore,
    private val installId: suspend () -> String,
) {
    enum class Mode {
        /** The server engines are the recognition engine (or retrying a saved recording). */
        PRIMARY,

        /** "Enhanced" fallback after ShazamKit found no match. */
        FALLBACK,
    }

    suspend fun identify(pcm: ByteArray, length: Int, sampleRateHz: Int, mode: Mode): RecognitionOutcome {
        // 16 kHz mono keeps the upload small (~32 kB/s) and is plenty for fingerprinting.
        val resampled = Pcm.resample(pcm, length, sampleRateHz, UPLOAD_SAMPLE_RATE)
        val wav = Pcm.wav(resampled, sampleRate = UPLOAD_SAMPLE_RATE)
        resampled.fill(0)
        val part = MultipartBody.Part.createFormData("audio", "clip.wav", wav.toRequestBody("audio/wav".toMediaType()))
        val install = installId()
        val result = when {
            mode == Mode.FALLBACK -> account.authorized { api, auth -> api.enhanced(auth, part) }
            credentials.currentAccount() != null -> account.authorized { api, auth -> api.identifyClip(auth, install, part) }
            else -> backend.call { identifyClip(null, install, part) }
        }
        wav.fill(0)
        return when (result) {
            is ApiResult.Ok -> map(result.value)
            is ApiResult.NetworkError -> RecognitionOutcome.Error(result.message, retryable = true, kind = ErrorKind.NETWORK)
            ApiResult.NotConfigured -> RecognitionOutcome.Unavailable("No NanoTune server is configured.")
            is ApiResult.HttpError -> when (result.status) {
                401 -> RecognitionOutcome.Error("Sign in to use your server's recognition engines.", retryable = false, kind = ErrorKind.AUTH)
                429 -> RecognitionOutcome.Error(result.message, retryable = true, kind = ErrorKind.RATE_LIMITED)
                503 -> RecognitionOutcome.Unavailable(result.message)
                else -> RecognitionOutcome.Error(result.message, retryable = result.status >= 500, kind = ErrorKind.SERVER)
            }
        }
    }

    private fun map(dto: EnhancedResultDto): RecognitionOutcome {
        val best = dto.best
        return when (dto.outcome) {
            "match" -> if (best == null) {
                RecognitionOutcome.NoMatch
            } else {
                val label = buildString {
                    append(best.providers.joinToString(" + ").ifEmpty { "your server's engine" })
                    append(" via your NanoTune server")
                    if (dto.agreement == "conflict") append(" (engines disagreed)")
                }
                RecognitionOutcome.Match(
                    track = RecognizedTrack(
                        providerTrackId = best.providerTrackId,
                        title = best.title,
                        artist = best.artist,
                        album = best.album,
                        releaseDate = best.releaseDate,
                        artworkUrl = best.artworkUrl,
                        isrc = best.isrc,
                        externalUrls = best.externalUrls,
                    ),
                    providerId = "server:" + best.providers.joinToString(",").ifEmpty { "unknown" },
                    confidence = best.score,
                    providerLabel = label,
                    agreeingProviders = best.providers,
                )
            }
            "no_match" -> RecognitionOutcome.NoMatch
            "unavailable" -> RecognitionOutcome.Unavailable(dto.message ?: "Your server's engines are not available right now.")
            else -> RecognitionOutcome.Error(dto.message ?: "Your server's engines failed.", retryable = true, kind = ErrorKind.SERVER)
        }
    }

    companion object {
        const val UPLOAD_SAMPLE_RATE = 16_000
    }
}
