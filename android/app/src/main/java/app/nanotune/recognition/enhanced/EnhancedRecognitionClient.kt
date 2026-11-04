package app.nanotune.recognition.enhanced

import app.nanotune.core.audio.Pcm
import app.nanotune.data.remote.ApiResult
import app.nanotune.data.remote.dto.EnhancedResultDto
import app.nanotune.data.repository.AccountRepository
import app.nanotune.domain.model.ErrorKind
import app.nanotune.domain.model.RecognitionOutcome
import app.nanotune.domain.model.RecognizedTrack
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Optional "Enhanced recognition": uploads one short clip to the user's own NanoTune server,
 * which routes it to additional engines the server owner configured. Only used when the user is
 * signed in, the server advertises it, and the user explicitly consented to the upload.
 */
class EnhancedRecognitionClient(private val account: AccountRepository) {

    suspend fun identify(pcm: ByteArray, length: Int, sampleRateHz: Int): RecognitionOutcome {
        // 16 kHz mono keeps the upload small (~32 kB/s) and is plenty for fingerprinting.
        val resampled = Pcm.resample(pcm, length, sampleRateHz, UPLOAD_SAMPLE_RATE)
        val wav = Pcm.wav(resampled, sampleRate = UPLOAD_SAMPLE_RATE)
        resampled.fill(0)
        val part = MultipartBody.Part.createFormData(
            "audio",
            "clip.wav",
            wav.toRequestBody("audio/wav".toMediaType()),
        )
        val result = account.authorized { api, auth -> api.enhanced(auth, part) }
        wav.fill(0)
        return when (result) {
            is ApiResult.Ok -> map(result.value)
            is ApiResult.NetworkError -> RecognitionOutcome.Error(result.message, retryable = true, kind = ErrorKind.NETWORK)
            ApiResult.NotConfigured -> RecognitionOutcome.Unavailable("No NanoTune server is configured.")
            is ApiResult.HttpError -> when (result.status) {
                401 -> RecognitionOutcome.Error("Sign in again to use enhanced recognition.", retryable = false, kind = ErrorKind.AUTH)
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
                    append(best.providers.joinToString(" + ").ifEmpty { "additional engine" })
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
                    providerId = "enhanced:" + best.providers.joinToString(",").ifEmpty { "server" },
                    confidence = best.score,
                    providerLabel = label,
                    agreeingProviders = best.providers,
                )
            }
            "no_match" -> RecognitionOutcome.NoMatch
            "unavailable" -> RecognitionOutcome.Unavailable(dto.message ?: "Enhanced recognition is not available on this server.")
            else -> RecognitionOutcome.Error(dto.message ?: "Enhanced recognition failed.", retryable = true, kind = ErrorKind.SERVER)
        }
    }

    companion object {
        const val UPLOAD_SAMPLE_RATE = 16_000
    }
}
