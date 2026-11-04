package app.nanotune.domain.export

import app.nanotune.domain.model.ExternalLink
import app.nanotune.domain.model.HistoryEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Serialises local history to CSV (RFC 4180) or JSON for the user's own export. */
object HistoryExporter {
    private val csvHeader = listOf(
        "recognized_at", "title", "artist", "album", "release_date", "isrc", "genres",
        "favourite", "mode", "session_id", "provider", "apple_music_url", "shazam_url", "artwork_url",
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun toCsv(entries: List<HistoryEntry>, zone: ZoneId): String {
        val builder = StringBuilder()
        builder.append(csvHeader.joinToString(",")).append("\r\n")
        for (entry in entries.sortedByDescending { it.recognizedAt }) {
            val track = entry.track
            val fields = listOf(
                isoTime(entry.recognizedAt, zone),
                track.title,
                track.artist,
                track.album.orEmpty(),
                track.releaseDate.orEmpty(),
                track.isrc.orEmpty(),
                track.genres.joinToString("; "),
                if (entry.favourite) "yes" else "no",
                entry.mode.name.lowercase(),
                entry.sessionId.orEmpty(),
                entry.providerId,
                track.externalUrls[ExternalLink.APPLE_MUSIC].orEmpty(),
                track.externalUrls[ExternalLink.SHAZAM_WEB].orEmpty(),
                track.artworkUrl.orEmpty(),
            )
            builder.append(fields.joinToString(",") { escapeCsv(it) }).append("\r\n")
        }
        return builder.toString()
    }

    fun toJson(entries: List<HistoryEntry>, zone: ZoneId, appVersion: String, exportedAtMs: Long): String {
        val document = ExportDocument(
            app = "NanoTune",
            appVersion = appVersion,
            exportedAt = isoTime(exportedAtMs, zone),
            count = entries.size,
            items = entries.sortedByDescending { it.recognizedAt }.map { entry ->
                ExportItem(
                    id = entry.recognitionId,
                    recognizedAt = isoTime(entry.recognizedAt, zone),
                    title = entry.track.title,
                    artist = entry.track.artist,
                    album = entry.track.album,
                    releaseDate = entry.track.releaseDate,
                    isrc = entry.track.isrc,
                    genres = entry.track.genres,
                    favourite = entry.favourite,
                    mode = entry.mode.name.lowercase(),
                    sessionId = entry.sessionId,
                    provider = entry.providerId,
                    providerTrackId = entry.track.providerTrackId,
                    artworkUrl = entry.track.artworkUrl,
                    links = entry.track.externalUrls.toSortedMap(),
                    metadataEnrichedBy = entry.enrichmentSource,
                )
            },
        )
        return json.encodeToString(ExportDocument.serializer(), document)
    }

    /** Quotes a CSV field when needed and neutralises spreadsheet formula injection. */
    fun escapeCsv(raw: String): String {
        val value = if (raw.isNotEmpty() && raw[0] in "=+-@\t\r") "'$raw" else raw
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || value != value.trim()
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    private fun isoTime(epochMs: Long, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    @Serializable
    private data class ExportDocument(
        val app: String,
        @SerialName("app_version") val appVersion: String,
        @SerialName("exported_at") val exportedAt: String,
        val count: Int,
        val items: List<ExportItem>,
    )

    @Serializable
    private data class ExportItem(
        val id: String,
        @SerialName("recognized_at") val recognizedAt: String,
        val title: String,
        val artist: String,
        val album: String?,
        @SerialName("release_date") val releaseDate: String?,
        val isrc: String?,
        val genres: List<String>,
        val favourite: Boolean,
        val mode: String,
        @SerialName("session_id") val sessionId: String?,
        val provider: String,
        @SerialName("provider_track_id") val providerTrackId: String?,
        @SerialName("artwork_url") val artworkUrl: String?,
        val links: Map<String, String>,
        @SerialName("metadata_enriched_by") val metadataEnrichedBy: String?,
    )
}
