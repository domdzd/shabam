package app.nanotune.recognition.shazamkit

import app.nanotune.domain.model.AudioSample
import app.nanotune.domain.model.ErrorKind
import app.nanotune.domain.model.ExternalLink
import app.nanotune.domain.model.RecognitionOutcome
import app.nanotune.domain.model.RecognizedTrack
import app.nanotune.recognition.ProviderAvailability
import app.nanotune.recognition.RecognitionStream
import app.nanotune.recognition.StreamOpenResult
import app.nanotune.recognition.StreamingRecognitionProvider
import com.shazam.shazamkit.AudioSampleRateInHz
import com.shazam.shazamkit.DeveloperToken
import com.shazam.shazamkit.DeveloperTokenProvider
import com.shazam.shazamkit.InternalError
import com.shazam.shazamkit.MatchError
import com.shazam.shazamkit.MatchResult
import com.shazam.shazamkit.ShazamCatalog
import com.shazam.shazamkit.ShazamKit
import com.shazam.shazamkit.ShazamKitException
import com.shazam.shazamkit.ShazamKitResult
import com.shazam.shazamkit.StreamingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Compiled only when Apple's ShazamKit Android AAR (2.1.x) is present in app/libs.
 *
 * Written strictly against the official API reference at
 * https://developer.apple.com/shazamkit/android/ — only documented types and calls are used:
 * ShazamKit.createShazamCatalog / createSignatureGenerator / createSession /
 * createStreamingSession, Session.match, StreamingSession.matchStream / recognitionResults,
 * MatchResult.{Match, NoMatch, Error} and MatchedMediaItem properties.
 */
object ShazamKitBridge {
    const val INCLUDED: Boolean = true
    const val SDK_DESCRIPTION: String = "Apple ShazamKit for Android"

    fun createProvider(tokenSource: ShazamTokenSource): StreamingRecognitionProvider? = ShazamKitProvider(tokenSource)
}

internal class ShazamKitProvider(private val tokenSource: ShazamTokenSource) : StreamingRecognitionProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "Shazam catalogue (ShazamKit)"
    override val supportedSampleRates: List<Int> = listOf(48_000, 44_100, 32_000, 16_000)

    private val catalog: ShazamCatalog by lazy {
        ShazamKit.createShazamCatalog(DeveloperTokenProvider { DeveloperToken(tokenSource.blockingToken()) })
    }

    override suspend fun availability(): ProviderAvailability =
        when (val token = tokenSource.get()) {
            is ShazamTokenSource.TokenResult.Ok -> ProviderAvailability.Ready
            is ShazamTokenSource.TokenResult.Failed -> token.availability
        }

    override suspend fun identify(sample: AudioSample): RecognitionOutcome {
        val rate = sampleRateOf(sample.sampleRateHz)
            ?: return RecognitionOutcome.Error("Unsupported sample rate ${sample.sampleRateHz} Hz.", retryable = false, kind = ErrorKind.AUDIO)
        val generator = when (val result = ShazamKit.createSignatureGenerator(rate)) {
            is ShazamKitResult.Success -> result.data
            is ShazamKitResult.Failure -> return internalFailure(result.reason)
        }
        generator.append(sample.pcmData, sample.pcmData.size, System.currentTimeMillis())
        val signature = generator.generateSignature()
        val session = when (val result = ShazamKit.createSession(catalog)) {
            is ShazamKitResult.Success -> result.data
            is ShazamKitResult.Failure -> return internalFailure(result.reason)
        }
        return toOutcome(session.match(signature))
    }

    override suspend fun openStream(sampleRateHz: Int, readBufferSizeBytes: Int): StreamOpenResult {
        val rate = sampleRateOf(sampleRateHz)
            ?: return StreamOpenResult.Failed(
                RecognitionOutcome.Error("Unsupported sample rate $sampleRateHz Hz.", retryable = false, kind = ErrorKind.AUDIO),
            )
        return when (val result = ShazamKit.createStreamingSession(catalog, rate, readBufferSizeBytes)) {
            is ShazamKitResult.Success -> StreamOpenResult.Opened(Stream(result.data))
            is ShazamKitResult.Failure -> StreamOpenResult.Failed(internalFailure(result.reason))
        }
    }

    private inner class Stream(private val session: StreamingSession) : RecognitionStream {
        override val outcomes: Flow<RecognitionOutcome> = session.recognitionResults().map { toOutcome(it) }

        override fun feed(audio: ByteArray, length: Int, timestampMs: Long) {
            session.matchStream(audio, length, timestampMs)
        }

        override fun close() {
            // The documented StreamingSession API has no close(); we stop feeding audio and the
            // caller cancels its collector, which releases the session for garbage collection.
        }
    }

    private fun toOutcome(result: MatchResult): RecognitionOutcome = when (result) {
        is MatchResult.Match -> {
            val item = result.matchedMediaItems.firstOrNull()
            val title = item?.title?.takeIf { it.isNotBlank() }
            val artist = (item?.artist ?: item?.subtitle)?.takeIf { it.isNotBlank() }
            if (item == null || title == null || artist == null) {
                RecognitionOutcome.NoMatch
            } else {
                val links = buildMap {
                    item.appleMusicURL?.let { put(ExternalLink.APPLE_MUSIC, it.toString()) }
                    item.webURL?.let { put(ExternalLink.SHAZAM_WEB, it.toString()) }
                    item.videoURL?.let { put(ExternalLink.VIDEO, it.toString()) }
                }
                RecognitionOutcome.Match(
                    track = RecognizedTrack(
                        providerTrackId = item.shazamID?.let { "shazam:$it" },
                        title = title,
                        artist = artist,
                        artworkUrl = item.artworkURL?.toString(),
                        isrc = item.isrc,
                        genres = item.genres,
                        explicit = item.explicitContent,
                        externalUrls = links,
                    ),
                    providerId = PROVIDER_ID,
                    confidence = null, // ShazamKit does not expose a confidence score.
                    providerLabel = displayName,
                )
            }
        }
        is MatchResult.NoMatch -> RecognitionOutcome.NoMatch
        is MatchResult.Error -> matchFailure(result.exception.matchError)
        else -> RecognitionOutcome.Error("Unexpected ShazamKit result.", retryable = true, kind = ErrorKind.PROVIDER)
    }

    private fun matchFailure(error: MatchError): RecognitionOutcome.Error = when (error) {
        MatchError.MATCH_ATTEMPT_FAILED ->
            RecognitionOutcome.Error("Couldn't reach the Shazam service.", retryable = true, kind = ErrorKind.NETWORK)
        MatchError.UNAUTHORIZED ->
            RecognitionOutcome.Error("The Shazam service rejected the developer token.", retryable = true, kind = ErrorKind.AUTH)
        MatchError.PROVIDED_EMPTY_AUDIO_DATA ->
            RecognitionOutcome.Error("No audio reached the recogniser.", retryable = true, kind = ErrorKind.AUDIO)
        MatchError.INVALID_SIGNATURE, MatchError.INVALID_SIGNATURE_DURATION ->
            RecognitionOutcome.Error("The recorded audio could not be processed.", retryable = true, kind = ErrorKind.AUDIO)
        else -> RecognitionOutcome.Error("ShazamKit reported an error.", retryable = true, kind = ErrorKind.PROVIDER)
    }

    private fun internalFailure(exception: ShazamKitException): RecognitionOutcome.Error {
        val message = when (exception.internalError) {
            InternalError.OUT_OF_MEMORY -> "Not enough memory to start ShazamKit."
            InternalError.DEVICE_IO_ERROR -> "ShazamKit could not access device storage."
            InternalError.ERROR_LOADING_SHAZAM_CORE -> "ShazamKit failed to load on this device."
            else -> "ShazamKit failed to start."
        }
        return RecognitionOutcome.Error(message, retryable = false, kind = ErrorKind.PROVIDER)
    }

    private fun sampleRateOf(hz: Int): AudioSampleRateInHz? = when (hz) {
        48_000 -> AudioSampleRateInHz.SAMPLE_RATE_48000
        44_100 -> AudioSampleRateInHz.SAMPLE_RATE_44100
        32_000 -> AudioSampleRateInHz.SAMPLE_RATE_32000
        16_000 -> AudioSampleRateInHz.SAMPLE_RATE_16000
        else -> null
    }

    companion object {
        const val PROVIDER_ID = "shazamkit"
    }
}
