package app.nanotune.recognition

import app.nanotune.core.network.Connectivity
import app.nanotune.data.remote.MusicBrainzClient
import app.nanotune.data.repository.Enrichment
import app.nanotune.data.repository.HistoryRepository
import app.nanotune.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Fills in album, release date, duration and (if missing) cover art from MusicBrainz using the
 * ISRC the recognition engine returned. Optional, cached per track, and honours "Wi-Fi only".
 */
class MetadataEnricher(
    private val history: HistoryRepository,
    private val musicBrainz: MusicBrainzClient,
    private val settings: SettingsRepository,
    private val connectivity: Connectivity,
    private val scope: CoroutineScope,
) {
    fun enrichLater(trackId: String) {
        scope.launch { runCatching { enrich(trackId) } }
    }

    suspend fun enrich(trackId: String) {
        val prefs = settings.current()
        if (!prefs.enrichmentEnabled) return
        val network = connectivity.current()
        if (!network.connected || (prefs.enrichmentWifiOnly && network.metered)) return
        val track = history.track(trackId) ?: return
        if (track.enrichedAt != null) return
        val isrc = track.isrc ?: return
        val complete = track.album != null && track.releaseDate != null && track.durationMs != null && track.artworkUrl != null
        if (complete) return
        val match = musicBrainz.lookupIsrc(isrc, track.title)
        if (match == null) {
            history.markEnrichmentAttempted(trackId)
            return
        }
        history.applyEnrichment(
            trackId,
            Enrichment(
                album = match.album,
                releaseDate = match.releaseDate,
                durationMs = match.durationMs,
                artworkUrl = match.coverArtUrl,
                source = SOURCE,
            ),
        )
    }

    companion object {
        const val SOURCE = "musicbrainz"
    }
}
