package app.nanotune.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class PcmTest {
    private fun sine(freq: Double, rate: Int, seconds: Double, amplitude: Double = 0.5): ByteArray {
        val n = (rate * seconds).toInt()
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (sin(2 * PI * freq * i / rate) * amplitude * 32767).roundToInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8 and 0xFF).toByte()
        }
        return out
    }

    private fun rms(pcm: ByteArray, skip: Int = 0): Double {
        val n = pcm.size / 2
        var sum = 0.0
        for (i in skip until n - skip) {
            val s = Pcm.sampleAt(pcm, i) / 32768.0
            sum += s * s
        }
        return sqrt(sum / (n - 2 * skip))
    }

    private fun zeroCrossings(pcm: ByteArray): Int {
        var count = 0
        for (i in 1 until pcm.size / 2) {
            if ((Pcm.sampleAt(pcm, i - 1) < 0) != (Pcm.sampleAt(pcm, i) < 0)) count++
        }
        return count
    }

    @Test
    fun wavHeaderIsCanonical() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = Pcm.wav(pcm, sampleRate = 16_000)
        assertEquals(48, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(16_000, (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8))
        assertEquals(4, wav[40].toInt())
        assertArrayEquals(pcm, wav.copyOfRange(44, 48))
    }

    @Test
    fun resamplingKeepsInBandToneAndRemovesAliases() {
        val inBand = Pcm.resample(sine(1_000.0, 48_000, 1.0).let { it }, 96_000, 48_000, 16_000)
        assertEquals(16_000 * 2, inBand.size)
        assertEquals(0.5 / sqrt(2.0), rms(inBand, skip = 100), 0.02)
        // 1 kHz for 1 s -> ~2000 zero crossings.
        assertTrue(abs(zeroCrossings(inBand) - 2_000) < 10)

        // A 12 kHz tone is above the new Nyquist (8 kHz) and must be strongly attenuated.
        val alias = Pcm.resample(sine(12_000.0, 48_000, 1.0), 96_000, 48_000, 16_000)
        assertTrue("alias rms ${rms(alias, 100)}", rms(alias, skip = 100) < 0.01)
    }

    @Test
    fun resamplingNonIntegerRatio() {
        val out = Pcm.resample(sine(440.0, 44_100, 1.0), 88_200, 44_100, 16_000)
        assertEquals(16_000 * 2, out.size)
        assertEquals(0.5 / sqrt(2.0), rms(out, skip = 100), 0.02)
        assertTrue(abs(zeroCrossings(out) - 880) < 6)
    }

    @Test
    fun levelIsZeroForSilenceAndHighForLoudSignal() {
        assertEquals(0f, Pcm.level(ByteArray(2000), 2000))
        assertTrue(Pcm.level(sine(440.0, 16_000, 0.1, 0.9), 3200) > 0.9f)
    }

    @Test
    fun loudestWindowFindsTheMusicAfterSilence() {
        val rate = 16_000
        // 10 s of silence, then 12 s of music, then 8 s of silence.
        val clip = ByteArray(rate * 2 * 10) + sine(440.0, rate, 12.0) + ByteArray(rate * 2 * 8)
        val window = Pcm.loudestWindow(clip, clip.size, rate, windowSeconds = 12)
        assertEquals(10 * rate * 2, window.first)
        assertEquals(12 * rate * 2, window.last - window.first + 1)
    }

    @Test
    fun loudestWindowReturnsEverythingForShortClips() {
        val clip = sine(440.0, 16_000, 5.0)
        assertEquals(0 until clip.size, Pcm.loudestWindow(clip, clip.size, 16_000, windowSeconds = 12))
        // An odd length is rounded down to whole samples.
        assertEquals(0 until clip.size - 2, Pcm.loudestWindow(clip, clip.size - 1, 16_000, windowSeconds = 12))
    }

    @Test
    fun offsetOfAlignsAndClamps() {
        assertEquals(32_000, Pcm.offsetOf(1_000, 16_000, 100_000))
        assertEquals(0, Pcm.offsetOf(-5, 16_000, 100_000))
        assertEquals(100_000, Pcm.offsetOf(60_000, 16_000, 100_000))
        assertEquals(32, Pcm.offsetOf(1, 16_000, 100_000)) // 16 samples
        assertEquals(98, Pcm.offsetOf(60_000, 16_000, 99))  // clamped to an even length
    }
}
