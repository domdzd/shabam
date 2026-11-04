package app.nanotune.data.remote

import app.nanotune.domain.model.TrackKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Album / release date / duration found for an ISRC. */
data class MusicBrainzMatch(
    val recordingId: String,
    val album: String?,
    val releaseDate: String?,
    val durationMs: Long?,
    val coverArtUrl: String?,
)

/**
 * Optional metadata enrichment from the MusicBrainz web service (non-commercial use is free).
 * Respects MusicBrainz etiquette: a meaningful User-Agent with contact information and at most
 * one request per second from this app. Only the ISRC of an already recognised song is sent.
 */
class MusicBrainzClient(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val userAgent: String,
) {
    private val gate = Mutex()
    private var lastRequestAt = 0L

    suspend fun lookupIsrc(isrc: String, expectedTitle: String): MusicBrainzMatch? {
        val clean = isrc.trim().uppercase()
        if (!ISRC_PATTERN.matches(clean)) return null
        val body = rateLimited {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("https://musicbrainz.org/ws/2/isrc/$clean?inc=releases&fmt=json")
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .build()
                runCatching {
                    okHttp.newCall(request).execute().use { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    }
                }.getOrNull()
            }
        } ?: return null
        val parsed = runCatching { json.decodeFromString(IsrcResponse.serializer(), body) }.getOrNull() ?: return null
        val wanted = TrackKeys.normalize(expectedTitle)
        val recording = parsed.recordings.firstOrNull { TrackKeys.normalize(it.title.orEmpty()) == wanted }
            ?: parsed.recordings.firstOrNull()
            ?: return null
        val release = recording.releases
            .filter { !it.date.isNullOrBlank() }
            .sortedWith(compareBy<Release> { if (it.status == "Official") 0 else 1 }.thenBy { it.date })
            .firstOrNull()
            ?: recording.releases.firstOrNull()
        return MusicBrainzMatch(
            recordingId = recording.id,
            album = release?.title,
            releaseDate = release?.date?.takeIf { it.isNotBlank() } ?: recording.firstReleaseDate,
            durationMs = recording.length,
            coverArtUrl = release?.id?.let { "https://coverartarchive.org/release/$it/front-250" },
        )
    }

    private suspend fun <T> rateLimited(block: suspend () -> T): T = gate.withLock {
        val wait = lastRequestAt + MIN_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    @Serializable
    private data class IsrcResponse(val recordings: List<Recording> = emptyList())

    @Serializable
    private data class Recording(
        val id: String,
        val title: String? = null,
        val length: Long? = null,
        @SerialName("first-release-date") val firstReleaseDate: String? = null,
        val releases: List<Release> = emptyList(),
    )

    @Serializable
    private data class Release(
        val id: String,
        val title: String? = null,
        val date: String? = null,
        val status: String? = null,
    )

    private companion object {
        const val MIN_INTERVAL_MS = 1_100L
        val ISRC_PATTERN = Regex("^[A-Z]{2}[A-Z0-9]{3}\\d{7}$")
    }
}
