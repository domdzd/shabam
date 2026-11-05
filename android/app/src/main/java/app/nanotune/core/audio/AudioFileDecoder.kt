package app.nanotune.core.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Decodes the audio of a file the user picked (MP3, AAC/M4A, OGG, FLAC, WAV, or a video's sound
 * track — whatever this phone's codecs support) into mono PCM16 at [TARGET_RATE], using the
 * platform's MediaExtractor and MediaCodec. Nothing is uploaded; the result becomes a recording.
 */
object AudioFileDecoder {
    const val TARGET_RATE = 16_000

    sealed interface Result {
        class Ok(val pcm: ByteArray, val sampleRateHz: Int, val truncated: Boolean) : Result
        data class Failed(val reason: String) : Result
    }

    /** The file's display name without extension, for labelling the recording. */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }

    /** Decodes at most [maxSeconds] of audio. Call off the main thread. */
    fun decode(context: Context, uri: Uri, maxSeconds: Int): Result {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                return Result.Failed("This file can't be read.")
            }
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return Result.Failed("This file has no audio.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return Result.Failed("This file has no audio.")
            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                return Result.Failed("This audio format isn't supported on this phone.")
            }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drain(codec, extractor, format, maxSeconds)
            } catch (e: Exception) {
                return Result.Failed("This file couldn't be decoded.")
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(codec: MediaCodec, extractor: MediaExtractor, format: MediaFormat, maxSeconds: Int): Result {
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val mono = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var truncated = false
        var idle = 0
        while (true) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val input = codec.getInputBuffer(inIndex) ?: return Result.Failed("This file couldn't be decoded.")
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = codec.outputFormat
                    sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
                outIndex >= 0 -> {
                    idle = 0
                    val output = codec.getOutputBuffer(outIndex)
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        appendMono(output, channels.coerceAtLeast(1), encoding, mono)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (mono.size() >= maxSeconds.toLong() * sampleRate * 2) {
                        truncated = true
                        break
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                else -> if (inputDone && ++idle > MAX_IDLE_POLLS) break // codec stalled at the end
            }
        }
        val pcm = mono.toByteArray()
        if (pcm.size < 2 || sampleRate <= 0) return Result.Failed("This file has no audio.")
        val length = minOf(pcm.size - pcm.size % 2, maxSeconds * sampleRate * 2)
        val resampled = Pcm.resample(pcm, length, sampleRate, TARGET_RATE)
        pcm.fill(0)
        return Result.Ok(resampled, TARGET_RATE, truncated)
    }

    /** Appends interleaved PCM (16-bit or float) from [buffer] to [out] as mono PCM16. */
    private fun appendMono(buffer: ByteBuffer, channels: Int, encoding: Int, out: ByteArrayOutputStream) {
        val data = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val frames = data.remaining() / (4 * channels)
            repeat(frames) {
                var sum = 0f
                repeat(channels) { sum += data.getFloat() }
                writeSample(out, (sum / channels * 32767f).roundToInt())
            }
        } else {
            val frames = data.remaining() / (2 * channels)
            repeat(frames) {
                var sum = 0
                repeat(channels) { sum += data.getShort().toInt() }
                writeSample(out, sum / channels)
            }
        }
    }

    private fun writeSample(out: ByteArrayOutputStream, value: Int) {
        val v = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        out.write(v and 0xFF)
        out.write(v shr 8 and 0xFF)
    }

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_POLLS = 300
}
