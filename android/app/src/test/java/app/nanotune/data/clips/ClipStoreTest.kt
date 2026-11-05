package app.nanotune.data.clips

import app.nanotune.domain.model.ClipAttempt
import app.nanotune.domain.model.ClipReason
import app.nanotune.domain.model.ClipStatus
import app.nanotune.domain.model.ErrorKind
import app.nanotune.domain.model.RecognitionOutcome
import app.nanotune.domain.model.RecognizedTrack
import app.nanotune.domain.model.RecordingsPolicy
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClipStoreTest {
    private lateinit var dir: File
    private lateinit var clipsDir: File
    private var now = 1_000_000L
    private var nextId = 0
    private lateinit var store: ClipStore

    /** Stand-in for the Keystore codec: reversible, and visibly not a WAV file. */
    private object XorCodec : ClipCodec {
        override val extension = ".nta"
        override fun seal(plain: ByteArray) = byteArrayOf(9, 9) + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun open(sealed: ByteArray): ByteArray {
            require(sealed[0] == 9.toByte() && sealed[1] == 9.toByte())
            return sealed.copyOfRange(2, sealed.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private object BrokenCodec : ClipCodec {
        override val extension = ".nta"
        override fun seal(plain: ByteArray): ByteArray = throw IllegalStateException("keystore unavailable")
        override fun open(sealed: ByteArray): ByteArray = throw IllegalStateException("keystore unavailable")
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("clips").toFile()
        clipsDir = File(dir, "clips")
        store = storeWith(ClipCodec.Plain)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun storeWith(codec: ClipCodec) =
        ClipStore(clipsDir, Json { ignoreUnknownKeys = true }, clock = { now }, newId = { "id-${nextId++}" }, codec = codec)

    private fun pcm(seconds: Int, rate: Int = 16_000): ByteArray = ByteArray(seconds * rate * 2) { (it % 251).toByte() }

    private fun file(name: String) = File(clipsDir, name)

    @Test
    fun saveListAndReadBackPcm() {
        val audio = pcm(4)
        val saved = store.save(audio, audio.size, 16_000, ClipReason.NO_MATCH, retryWhenOnline = true)
        assertEquals("id-0", saved.id)
        assertEquals(4_000L, saved.durationMs)
        assertEquals(ClipStatus.UNIDENTIFIED, saved.status)
        assertEquals(44L + audio.size, saved.sizeBytes)
        assertEquals(listOf(saved), store.list())
        val (back, rate) = store.readPcm(saved.id)!!
        assertEquals(16_000, rate)
        assertArrayEquals(audio, back)
        assertEquals(44L + audio.size, file("id-0.wav").length())
        assertEquals("RIFF", String(store.readWav(saved.id)!!, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun listIsNewestFirstAndIgnoresOrphans() {
        val a = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.NO_MATCH)
        now += 10
        val b = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.MANUAL)
        assertEquals(listOf(b.id, a.id), store.list().map { it.id })
        file("${a.id}.wav").delete() // metadata without audio is not listed
        assertEquals(listOf(b.id), store.list().map { it.id })
        assertNull(store.get(a.id))
    }

    @Test
    fun updateRecordsAttemptsIdentificationAndLabel() {
        val saved = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.OFFLINE, retryWhenOnline = true)
        store.update(saved.id) {
            it.copy(
                attempts = it.attempts + ClipAttempt(5, "Shazam", "match"),
                status = ClipStatus.IDENTIFIED,
                title = "Song",
                artist = "Artist",
                retryWhenOnline = false,
                label = "  Café   in   Lisbon  ",
                createdAt = 0, // ignored: creation time is immutable
                durationMs = 1, // ignored: only replaceAudio changes the audio facts
            )
        }
        val reloaded = store.get(saved.id)!!
        assertEquals(ClipStatus.IDENTIFIED, reloaded.status)
        assertEquals("Song", reloaded.title)
        assertEquals(1, reloaded.attempts.size)
        assertFalse(reloaded.retryWhenOnline)
        assertEquals(saved.createdAt, reloaded.createdAt)
        assertEquals(saved.durationMs, reloaded.durationMs)
        assertEquals("Café in Lisbon", reloaded.label)
        assertEquals("Café in Lisbon", reloaded.displayName)
        assertNull(store.update("missing") { it })
        // A blank name falls back to the song.
        assertEquals("Song — Artist", store.update(saved.id) { it.copy(label = "   ") }!!.displayName)
    }

    @Test
    fun replaceAudioTrimsAndKeepsMetadata() {
        val saved = store.save(pcm(10), pcm(10).size, 16_000, ClipReason.NO_MATCH, label = "Radio")
        val part = pcm(10).copyOfRange(32_000 * 2, 32_000 * 6)
        val trimmed = store.replaceAudio(saved.id, part, part.size, 16_000)!!
        assertEquals(4_000L, trimmed.durationMs)
        assertEquals(44L + part.size, trimmed.sizeBytes)
        assertEquals("Radio", trimmed.label)
        assertEquals(saved.createdAt, trimmed.createdAt)
        assertArrayEquals(part, store.readPcm(saved.id)!!.first)
        assertNull(store.replaceAudio("missing", part, part.size, 16_000))
    }

    @Test
    fun purgeByAgeThenBySizeAndCleansOrphans() {
        val old = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.NO_MATCH)
        now += 10 * 86_400_000L
        val middle = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.NO_MATCH)
        now += 1
        val newest = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.NO_MATCH)
        file("stray.wav").writeBytes(ByteArray(10)) // audio without metadata
        file("x.json.tmp").writeBytes(ByteArray(10)) // interrupted write
        assertEquals(1, store.purge(maxAgeMs = 7 * 86_400_000L, maxTotalBytes = Long.MAX_VALUE))
        assertEquals(listOf(newest.id, middle.id), store.list().map { it.id })
        assertFalse(file("stray.wav").exists())
        assertFalse(file("x.json.tmp").exists())
        // Size cap removes the oldest remaining clip first.
        assertEquals(1, store.purge(maxAgeMs = 0, maxTotalBytes = newest.sizeBytes))
        assertEquals(listOf(newest.id), store.list().map { it.id })
        assertFalse(file("${old.id}.wav").exists())
    }

    @Test
    fun deleteAndDeleteAll() {
        val a = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.NO_MATCH)
        store.save(pcm(3), pcm(3).size, 16_000, ClipReason.NO_MATCH)
        assertTrue(store.delete(a.id))
        assertEquals(1, store.list().size)
        assertEquals(1, store.deleteAll())
        assertTrue(store.list().isEmpty())
        assertEquals(0L, store.totalBytes())
    }

    @Test
    fun idsCannotEscapeTheDirectory() {
        val evil = ClipStore(clipsDir, Json, clock = { now }, newId = { "../../etc/passwd" })
        val saved = evil.save(pcm(3), pcm(3).size, 16_000, ClipReason.MANUAL)
        assertEquals("etcpasswd", saved.id)
        assertTrue(file("etcpasswd.wav").isFile)
        assertNull(evil.readWav("../x"))
        assertFalse(File(dir, "x.wav").exists())
    }

    @Test
    fun encryptedRecordingsAreNotPlainWavOnDisk() {
        val secure = storeWith(XorCodec)
        val audio = pcm(3)
        val saved = secure.save(audio, audio.size, 16_000, ClipReason.NO_MATCH)
        assertFalse(file("${saved.id}.wav").exists())
        val onDisk = file("${saved.id}.nta").readBytes()
        assertFalse(String(onDisk, 2, 4, Charsets.US_ASCII) == "RIFF")
        assertArrayEquals(audio, secure.readPcm(saved.id)!!.first)
        assertEquals(listOf(saved.id), secure.list().map { it.id })
        assertTrue(secure.delete(saved.id))
        assertFalse(file("${saved.id}.nta").exists())
    }

    @Test
    fun legacyPlainRecordingsStayReadableAndCanBeSealed() {
        val legacy = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.NO_MATCH) // written by 0.2 (plain)
        val secure = storeWith(XorCodec)
        assertArrayEquals(pcm(3), secure.readPcm(legacy.id)!!.first)
        assertEquals(1, secure.sealLegacy())
        assertFalse(file("${legacy.id}.wav").exists())
        assertTrue(file("${legacy.id}.nta").isFile)
        assertArrayEquals(pcm(3), secure.readPcm(legacy.id)!!.first)
        assertEquals(0, secure.sealLegacy())
    }

    @Test
    fun brokenEncryptionFallsBackToPlainInsteadOfLosingTheRecording() {
        val fragile = storeWith(BrokenCodec)
        val saved = fragile.save(pcm(3), pcm(3).size, 16_000, ClipReason.NO_ENGINE)
        assertTrue(file("${saved.id}.wav").isFile)
        assertNotNull(fragile.readPcm(saved.id))
        assertEquals(ClipReason.NO_ENGINE, fragile.get(saved.id)!!.reason)
    }

    @Test
    fun policyKeepsEverythingThatWasNotIdentified() {
        val match = RecognitionOutcome.Match(RecognizedTrack(title = "T", artist = "A"), providerId = "shazamkit")
        assertNull(ClipPolicy.autoSaveReason(match, RecordingsPolicy.NOT_IDENTIFIED, offlineCapture = false))
        assertEquals(ClipReason.MATCHED, ClipPolicy.autoSaveReason(match, RecordingsPolicy.ALL, offlineCapture = false))
        assertEquals(ClipReason.NO_MATCH, ClipPolicy.autoSaveReason(RecognitionOutcome.NoMatch, RecordingsPolicy.NOT_IDENTIFIED, false))
        val network = RecognitionOutcome.Error("x", retryable = true, kind = ErrorKind.NETWORK)
        assertEquals(ClipReason.ERROR, ClipPolicy.autoSaveReason(network, RecordingsPolicy.NOT_IDENTIFIED, false))
        // Even a non-retryable failure (e.g. sign-in needed) keeps the audio.
        val fatal = RecognitionOutcome.Error("x", retryable = false, kind = ErrorKind.AUTH)
        assertEquals(ClipReason.ERROR, ClipPolicy.autoSaveReason(fatal, RecordingsPolicy.NOT_IDENTIFIED, false))
        assertEquals(ClipReason.OFFLINE, ClipPolicy.autoSaveReason(network, RecordingsPolicy.NOT_IDENTIFIED, offlineCapture = true))
        val notBundled = RecognitionOutcome.Unavailable("no engine in this build")
        assertEquals(ClipReason.NO_ENGINE, ClipPolicy.autoSaveReason(notBundled, RecordingsPolicy.ALL, false, noEngine = true))
        assertEquals(0L, ClipPolicy.retentionMs(0))
        assertEquals(30 * 86_400_000L, ClipPolicy.retentionMs(30))
    }
}
