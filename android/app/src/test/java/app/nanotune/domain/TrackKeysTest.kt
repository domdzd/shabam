package app.nanotune.domain

import app.nanotune.domain.model.RecognizedTrack
import app.nanotune.domain.model.TrackKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TrackKeysTest {
    @Test
    fun normalizeStripsAccentsCaseAndPunctuation() {
        assertEquals("beyonce halo", TrackKeys.normalize("  Beyoncé — HALO!! "))
        assertEquals("sigur ros hoppipolla", TrackKeys.normalize("Sigur Rós: Hoppípolla"))
    }

    @Test
    fun identityKeyPrefersProviderId() {
        val a = RecognizedTrack(providerTrackId = "shazam:1", title = "Song", artist = "Band")
        val b = RecognizedTrack(providerTrackId = "shazam:2", title = "Song", artist = "Band")
        assertEquals("id:shazam:1", TrackKeys.identityKey(a))
        assertNotEquals(TrackKeys.identityKey(a), TrackKeys.identityKey(b))
        assertEquals(TrackKeys.similarityKey(a), TrackKeys.similarityKey(b))
    }

    @Test
    fun identityKeyFallsBackToMetadata() {
        val track = RecognizedTrack(providerTrackId = "  ", title = "Hello", artist = "Adele")
        assertEquals("meta:adele|hello", TrackKeys.identityKey(track))
    }
}
