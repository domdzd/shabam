package app.nanotune.core.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Small, allocation-conscious helpers for 16-bit little-endian mono PCM. */
object Pcm {
    const val BYTES_PER_SAMPLE = 2

    /** Wraps PCM bytes in a canonical 44-byte RIFF/WAVE header. */
    fun wav(pcm: ByteArray, length: Int = pcm.size, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        require(length in 0..pcm.size) { "length out of range" }
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArray(44 + length)
        fun putAscii(offset: Int, text: String) = text.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() }
        fun putInt(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = (value ushr 8 and 0xFF).toByte()
            out[offset + 2] = (value ushr 16 and 0xFF).toByte()
            out[offset + 3] = (value ushr 24 and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = (value ushr 8 and 0xFF).toByte()
        }
        putAscii(0, "RIFF")
        putInt(4, 36 + length)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, channels)
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, blockAlign)
        putShort(34, bitsPerSample)
        putAscii(36, "data")
        putInt(40, length)
        System.arraycopy(pcm, 0, out, 44, length)
        return out
    }

    fun sampleAt(pcm: ByteArray, sampleIndex: Int): Int {
        val lo = pcm[sampleIndex * 2].toInt() and 0xFF
        val hi = pcm[sampleIndex * 2 + 1].toInt()
        return (hi shl 8) or lo
    }

    /** Root-mean-square level mapped to 0..1 on a 60 dB scale, for a microphone level meter. */
    fun level(buffer: ByteArray, length: Int): Float {
        val samples = length / BYTES_PER_SAMPLE
        if (samples == 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val s = sampleAt(buffer, i) / 32768.0
            sum += s * s
        }
        val rms = sqrt(sum / samples)
        if (rms <= 1e-6) return 0f
        val db = 20 * log10(rms)
        return ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Band-limited resampling of mono PCM16 using a Hann-windowed sinc and a precomputed
     * polyphase table, e.g. 48 kHz → 16 kHz before uploading a short clip.
     */
    fun resample(input: ByteArray, length: Int, inRate: Int, outRate: Int): ByteArray {
        require(inRate > 0 && outRate > 0) { "sample rates must be positive" }
        require(length % BYTES_PER_SAMPLE == 0 && length <= input.size) { "invalid PCM length" }
        if (inRate == outRate) return input.copyOf(length)
        val inSamples = length / BYTES_PER_SAMPLE
        val step = inRate.toDouble() / outRate
        val outSamples = floor(inSamples / step).toInt()
        if (outSamples <= 0) return ByteArray(0)

        val cutoff = 0.5 * min(1.0, outRate.toDouble() / inRate) * 0.92 // cycles per input sample
        val radius = ZERO_CROSSINGS / (2 * cutoff)
        val tapCount = ceil(radius).toInt() * 2 + 2
        val table = Array(PHASES) { phase ->
            val fraction = phase.toDouble() / PHASES
            DoubleArray(tapCount) { tap ->
                val t = (tap - tapCount / 2 + 1) - fraction // distance from the output instant
                windowedSinc(t, cutoff, radius)
            }
        }
        val sums = DoubleArray(PHASES) { table[it].sum() }

        val samples = IntArray(inSamples) { sampleAt(input, it) }
        val out = ByteArray(outSamples * BYTES_PER_SAMPLE)
        for (n in 0 until outSamples) {
            val center = n * step
            val base = floor(center).toInt()
            val phase = ((center - base) * PHASES).toInt().coerceIn(0, PHASES - 1)
            val taps = table[phase]
            var acc = 0.0
            var norm = 0.0
            val first = base - tapCount / 2 + 1
            for (tap in 0 until tapCount) {
                val k = first + tap
                if (k < 0 || k >= inSamples) continue
                acc += samples[k] * taps[tap]
                norm += taps[tap]
            }
            // Normalise by the taps actually used so edges keep unity gain.
            val value = if (norm != 0.0) acc / norm else acc / sums[phase]
            val clamped = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[n * 2] = (clamped and 0xFF).toByte()
            out[n * 2 + 1] = (clamped shr 8 and 0xFF).toByte()
        }
        return out
    }

    private const val PHASES = 64
    private const val ZERO_CROSSINGS = 8

    private fun windowedSinc(t: Double, cutoff: Double, radius: Double): Double {
        if (t <= -radius || t >= radius) return 0.0
        val x = 2 * PI * cutoff * t
        val sinc = if (t == 0.0) 1.0 else sin(x) / x
        val window = 0.5 * (1 + cos(PI * t / radius))
        return 2 * cutoff * sinc * window
    }
}
