package app.nanotune.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nanotune.data.local.NanoTuneDatabase
import app.nanotune.data.remote.dto.SyncRecognitionDto
import app.nanotune.data.repository.HistoryRepository
import app.nanotune.domain.model.DateFilter
import app.nanotune.domain.model.ExternalLink
import app.nanotune.domain.model.RecognitionMode
import app.nanotune.domain.model.RecognizedTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class HistoryRepositoryTest {
    private lateinit var db: NanoTuneDatabase
    private var now = 1_700_000_000_000L
    private var syncActive = false
    private lateinit var repository: HistoryRepository

    private val song = RecognizedTrack(
        providerTrackId = "shazam:1",
        title = "Song Title",
        artist = "Some Artist",
        artworkUrl = "https://example.com/a.jpg",
        isrc = "USRC17607839",
        genres = listOf("Pop"),
        externalUrls = mapOf(ExternalLink.APPLE_MUSIC to "https://music.apple.com/song"),
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, NanoTuneDatabase::class.java).allowMainThreadQueries().build()
        repository = HistoryRepository(db, Json { ignoreUnknownKeys = true }, clock = { now }, syncActive = { syncActive })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun savingTheSameSongTwiceReusesTheTrack() = runTest {
        val first = repository.saveRecognition(song, "shazamkit", RecognitionMode.MANUAL, null, null)
        now += 60_000
        val second = repository.saveRecognition(song.copy(album = "Album"), "shazamkit", RecognitionMode.MANUAL, null, null)
        assertTrue(first.newTrack)
        assertFalse(second.newTrack)
        assertEquals(first.trackId, second.trackId)
        assertNotEquals(first.recognitionId, second.recognitionId)

        val history = repository.observeHistory("", DateFilter.ALL, favouritesOnly = false).first()
        assertEquals(2, history.size)
        // Newest first, and the album learned later is merged into the stored track.
        assertEquals(second.recognitionId, history[0].recognitionId)
        assertEquals("Album", history[0].track.album)
        assertEquals(listOf("Pop"), history[0].track.genres)
        assertEquals("https://music.apple.com/song", history[0].track.externalUrls[ExternalLink.APPLE_MUSIC])
    }

    @Test
    fun searchFavouritesAndDateFilters() = runTest {
        val a = repository.saveRecognition(song, "shazamkit", RecognitionMode.MANUAL, null, null)
        repository.saveRecognition(RecognizedTrack(title = "100% Pure_Love", artist = "Crystal Waters"), "shazamkit", RecognitionMode.MANUAL, null, null)

        assertEquals(1, repository.observeHistory("crystal", DateFilter.ALL, false).first().size)
        // LIKE wildcards in the query are treated literally.
        assertEquals(1, repository.observeHistory("100%", DateFilter.ALL, false).first().size)
        assertEquals(0, repository.observeHistory("_x", DateFilter.ALL, false).first().size)

        repository.setFavourite(a.trackId, true)
        val favourites = repository.observeHistory("", DateFilter.ALL, favouritesOnly = true).first()
        assertEquals(listOf(a.recognitionId), favourites.map { it.recognitionId })
        assertTrue(favourites[0].favourite)

        now += 3 * 86_400_000L
        assertEquals(0, repository.observeHistory("", DateFilter.TODAY, false).first().size)
        assertEquals(2, repository.observeHistory("", DateFilter.WEEK, false).first().size)
    }

    @Test
    fun deletingWithoutSyncRemovesRowsAndOrphanTracks() = runTest {
        val saved = repository.saveRecognition(song, "shazamkit", RecognitionMode.MANUAL, null, null)
        repository.deleteRecognition(saved.recognitionId)
        assertTrue(repository.observeHistory("", DateFilter.ALL, false).first().isEmpty())
        assertEquals(null, repository.track(saved.trackId))
    }

    @Test
    fun deletingWithSyncLeavesATombstoneToPush() = runTest {
        syncActive = true
        val saved = repository.saveRecognition(song, "shazamkit", RecognitionMode.MANUAL, null, null)
        now += 1_000
        repository.deleteRecognition(saved.recognitionId)
        assertTrue(repository.observeHistory("", DateFilter.ALL, false).first().isEmpty())
        val pending = repository.pendingRecognitions(10)
        assertEquals(1, pending.size)
        assertTrue(pending[0].deleted)
        assertEquals(now, pending[0].updatedAt)
    }

    @Test
    fun sessionCountsAndDanglingSessionsAreClosed() = runTest {
        val sessionId = repository.startSession()
        repository.saveRecognition(song, "shazamkit", RecognitionMode.SESSION, sessionId, null)
        assertEquals(1, repository.observeSession(sessionId).first()?.trackCount)
        assertEquals(1, repository.observeSessionEntries(sessionId).first().size)
        repository.closeDanglingSessions()
        val summary = repository.observeSession(sessionId).first()!!
        assertEquals(now, summary.endedAt)
        assertFalse(summary.userStopped)
    }

    @Test
    fun remoteRecognitionsAreMergedLastWriterWins() = runTest {
        val remote = SyncRecognitionDto(
            id = "remote-1", trackKey = "id:shazam:9", title = "Remote Song", artist = "Remote Artist",
            providerTrackId = "shazam:9", providerId = "shazamkit", recognizedAt = now, mode = "manual", updatedAt = now,
        )
        repository.applyRemoteRecognition(remote)
        assertEquals(listOf("Remote Song"), repository.observeHistory("", DateFilter.ALL, false).first().map { it.track.title })

        // An older tombstone must not delete the newer local copy...
        repository.applyRemoteRecognition(remote.copy(updatedAt = now - 10, deleted = true))
        assertEquals(1, repository.observeHistory("", DateFilter.ALL, false).first().size)
        // ...but a newer one does.
        repository.applyRemoteRecognition(remote.copy(updatedAt = now + 10, deleted = true))
        assertTrue(repository.observeHistory("", DateFilter.ALL, false).first().isEmpty())
        // Remote changes are not echoed back as pending local changes.
        assertTrue(repository.pendingRecognitions(10).isEmpty())
    }
}
