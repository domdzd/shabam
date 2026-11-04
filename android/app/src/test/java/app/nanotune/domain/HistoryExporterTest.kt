package app.nanotune.domain

import app.nanotune.domain.export.HistoryExporter
import app.nanotune.domain.model.ExternalLink
import app.nanotune.domain.model.HistoryEntry
import app.nanotune.domain.model.RecognitionMode
import app.nanotune.domain.model.RecognizedTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class HistoryExporterTest {
    private val entry = HistoryEntry(
        recognitionId = "r1", trackId = "t1",
        track = RecognizedTrack(
            providerTrackId = "shazam:42", title = "Hello, \"World\"", artist = "=cmd()",
            album = "Album\nTwo", isrc = "GBAYE0601498", genres = listOf("Pop", "Rock"),
            externalUrls = mapOf(ExternalLink.APPLE_MUSIC to "https://music.apple.com/x"),
        ),
        recognizedAt = 1_762_214_400_000, providerId = "shazamkit", mode = RecognitionMode.SESSION,
        sessionId = "s1", confidence = null, favourite = true,
    )

    @Test
    fun csvEscapesQuotesCommasNewlinesAndFormulas() {
        val csv = HistoryExporter.toCsv(listOf(entry), ZoneOffset.UTC)
        val lines = csv.split("\r\n")
        assertTrue(lines[0].startsWith("recognized_at,title,artist,album"))
        val row = lines[1] + "\r\n" + lines[2]
        assertTrue(row, row.startsWith("2025-11-04T00:00:00Z,\"Hello, \"\"World\"\"\",'=cmd(),\"Album"))
        assertTrue(row.contains("Pop; Rock"))
        assertTrue(row.contains(",yes,session,s1,shazamkit,https://music.apple.com/x,"))
    }

    @Test
    fun escapeLeavesPlainValuesAlone() {
        assertEquals("plain", HistoryExporter.escapeCsv("plain"))
        assertEquals("\" padded \"", HistoryExporter.escapeCsv(" padded "))
        assertEquals("'+1", HistoryExporter.escapeCsv("+1"))
    }

    @Test
    fun jsonContainsAllFields() {
        val text = HistoryExporter.toJson(listOf(entry), ZoneOffset.UTC, "0.1.0", 1_762_214_400_000)
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, root["count"]!!.jsonPrimitive.int)
        val item = root["items"]!!.jsonArray[0].jsonObject
        assertEquals("Hello, \"World\"", item["title"]!!.jsonPrimitive.content)
        assertEquals("GBAYE0601498", item["isrc"]!!.jsonPrimitive.content)
        assertEquals("https://music.apple.com/x", item["links"]!!.jsonObject[ExternalLink.APPLE_MUSIC]!!.jsonPrimitive.content)
        assertEquals("session", item["mode"]!!.jsonPrimitive.content)
    }
}
