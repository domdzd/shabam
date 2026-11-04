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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClipStoreTest {
    private lateinit var dir: File
    private var now = 1_000_000L
    private var nextId = 0
    private lateinit var store: ClipStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("clips").toFile()
        store = ClipStore(File(dir, "clips"), Json { ignoreUnknownKeys = true }, clock = { now }, newId = { "id-${nextId++}" })
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun pcm(seconds: Int, rate: Int = 16_000): ByteArray = ByteArray(seconds * rate * 2) { (it % 251).toByte() }

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
        assertTrue(store.audioFile(saved.id).length() == 44L + audio.size)
    }

    @Test
    fun listIsNewestFirstAndIgnoresOrphans() {
        val a = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.NO_MATCH)
        now += 10
        val b = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.MANUAL)
        assertEquals(listOf(b.id, a.id), store.list().map { it.id })
        store.audioFile(a.id).delete() // metadata without audio is not listed
        assertEquals(listOf(b.id), store.list().map { it.id })
    }

    @Test
    fun updateRecordsAttemptsAndIdentification() {
        val saved = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.OFFLINE, retryWhenOnline = true)
        store.update(saved.id) {
            it.copy(
                attempts = it.attempts + ClipAttempt(5, "Shazam", "match"),
                status = ClipStatus.IDENTIFIED,
                title = "Song",
                artist = "Artist",
                retryWhenOnline = false,
                createdAt = 0, // ignored: creation time is immutable
            )
        }
        val reloaded = store.get(saved.id)!!
        assertEquals(ClipStatus.IDENTIFIED, reloaded.status)
        assertEquals("Song", reloaded.title)
        assertEquals(1, reloaded.attempts.size)
        assertFalse(reloaded.retryWhenOnline)
        assertEquals(saved.createdAt, reloaded.createdAt)
        assertNull(store.update("missing") { it })
    }

    @Test
    fun purgeByAgeThenBySize() {
        val old = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.NO_MATCH)
        now += 10 * 86_400_000L
        val middle = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.NO_MATCH)
        now += 1
        val newest = store.save(pcm(3), pcm(3).size, 16_000, ClipReason.NO_MATCH)
        assertEquals(1, store.purge(maxAgeMs = 7 * 86_400_000L, maxTotalBytes = Long.MAX_VALUE))
        assertEquals(listOf(newest.id, middle.id), store.list().map { it.id })
        // Size cap removes the oldest remaining clip first.
        assertEquals(1, store.purge(maxAgeMs = 0, maxTotalBytes = newest.sizeBytes))
        assertEquals(listOf(newest.id), store.list().map { it.id })
        assertFalse(store.audioFile(old.id).exists())
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
        val evil = ClipStore(File(dir, "clips"), Json, clock = { now }, newId = { "../../etc/passwd" })
        val saved = evil.save(pcm(3), pcm(3).size, 16_000, ClipReason.MANUAL)
        assertEquals("etcpasswd", saved.id)
        assertEquals(File(dir, "clips"), evil.audioFile("../x").parentFile)
    }

    @Test
    fun policyDecidesWhatIsKept() {
        val match = RecognitionOutcome.Match(RecognizedTrack(title = "T", artist = "A"), providerId = "shazamkit")
        assertNull(ClipPolicy.autoSaveReason(match, RecordingsPolicy.NOT_IDENTIFIED, offlineCapture = false))
        assertEquals(ClipReason.MATCHED, ClipPolicy.autoSaveReason(match, RecordingsPolicy.ALL, offlineCapture = false))
        assertEquals(ClipReason.NO_MATCH, ClipPolicy.autoSaveReason(RecognitionOutcome.NoMatch, RecordingsPolicy.NOT_IDENTIFIED, false))
        val network = RecognitionOutcome.Error("x", retryable = true, kind = ErrorKind.NETWORK)
        assertEquals(ClipReason.ERROR, ClipPolicy.autoSaveReason(network, RecordingsPolicy.NOT_IDENTIFIED, false))
        val fatal = RecognitionOutcome.Error("x", retryable = false, kind = ErrorKind.AUDIO)
        assertNull(ClipPolicy.autoSaveReason(fatal, RecordingsPolicy.ALL, false))
        assertEquals(ClipReason.OFFLINE, ClipPolicy.autoSaveReason(network, RecordingsPolicy.NOT_IDENTIFIED, offlineCapture = true))
        assertEquals(0L, ClipPolicy.retentionMs(0))
        assertEquals(30 * 86_400_000L, ClipPolicy.retentionMs(30))
    }
}
