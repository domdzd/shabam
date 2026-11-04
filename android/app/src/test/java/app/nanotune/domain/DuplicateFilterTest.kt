package app.nanotune.domain

import app.nanotune.domain.dedup.DuplicateFilter
import app.nanotune.domain.model.RecognizedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFilterTest {
    private val minute = 60_000L
    private val songA = RecognizedTrack(providerTrackId = "shazam:1", title = "Song A", artist = "Artist")
    private val songB = RecognizedTrack(providerTrackId = "shazam:2", title = "Song B", artist = "Artist")

    @Test
    fun firstSightingIsRecordedRepeatsAreNot() {
        val filter = DuplicateFilter(4 * minute)
        assertTrue(filter.register(songA, 0))
        assertFalse(filter.register(songA, 10_000))
        assertTrue(filter.register(songB, 20_000))
        assertFalse(filter.register(songB, 30_000))
    }

    @Test
    fun windowSlidesWhileTheSongKeepsPlaying() {
        val filter = DuplicateFilter(4 * minute)
        assertTrue(filter.register(songA, 0))
        // Reported every 30 s for 10 minutes: still one entry.
        var t = 30_000L
        while (t <= 10 * minute) {
            assertFalse("duplicate at $t", filter.register(songA, t))
            t += 30_000L
        }
    }

    @Test
    fun songIsRecordedAgainAfterCooldownOfSilence() {
        val filter = DuplicateFilter(4 * minute)
        assertTrue(filter.register(songA, 0))
        assertTrue(filter.register(songA, 4 * minute))
    }

    @Test
    fun sameSongWithDifferentIdIsStillADuplicate() {
        val filter = DuplicateFilter(4 * minute)
        val remaster = RecognizedTrack(providerTrackId = "shazam:99", title = "song a", artist = "ARTIST")
        assertTrue(filter.register(songA, 0))
        assertFalse(filter.register(remaster, 1_000))
    }

    @Test
    fun clockGoingBackwardsDoesNotSuppressForever() {
        val filter = DuplicateFilter(4 * minute)
        assertTrue(filter.register(songA, 10 * minute))
        assertTrue(filter.register(songA, 0))
    }

    @Test
    fun effectiveCooldownCoversIntervalMode() {
        assertEquals(4 * minute, DuplicateFilter.effectiveCooldownMs(4 * minute, null, 12_000))
        assertEquals(5 * minute + 24_000, DuplicateFilter.effectiveCooldownMs(4 * minute, 5 * minute, 12_000))
    }
}
