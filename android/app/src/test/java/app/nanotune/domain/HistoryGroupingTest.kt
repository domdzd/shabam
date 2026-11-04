package app.nanotune.domain

import app.nanotune.domain.history.HistoryGrouping
import app.nanotune.domain.history.HistoryRow
import app.nanotune.domain.model.HistoryEntry
import app.nanotune.domain.model.RecognitionMode
import app.nanotune.domain.model.RecognizedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryGroupingTest {
    private val zone = ZoneOffset.UTC
    private fun at(day: Int, hour: Int) = LocalDate.of(2026, 11, day).atTime(hour, 0).toInstant(zone).toEpochMilli()
    private fun entry(id: String, time: Long, session: String? = null) = HistoryEntry(
        recognitionId = id, trackId = "t$id",
        track = RecognizedTrack(title = "Title $id", artist = "Artist"),
        recognizedAt = time, providerId = "shazamkit",
        mode = if (session == null) RecognitionMode.MANUAL else RecognitionMode.SESSION,
        sessionId = session, confidence = null, favourite = false,
    )

    @Test
    fun groupsByDayNewestFirstWithSessionHeaders() {
        val rows = HistoryGrouping.group(
            listOf(
                entry("1", at(3, 9)),
                entry("2", at(4, 10), "s1"),
                entry("3", at(4, 11), "s1"),
                entry("4", at(4, 12)),
            ),
            zone,
        )
        val kinds = rows.map {
            when (it) {
                is HistoryRow.DayHeader -> "D:${it.date.dayOfMonth}"
                is HistoryRow.SessionHeader -> "S:${it.sessionId}:${it.itemCount}"
                is HistoryRow.Item -> "I:${it.entry.recognitionId}"
            }
        }
        assertEquals(listOf("D:4", "I:4", "S:s1:2", "I:3", "I:2", "D:3", "I:1"), kinds)
        assertTrue(rows.filterIsInstance<HistoryRow.Item>().first { it.entry.recognitionId == "3" }.inSession)
    }

    @Test
    fun sessionSpanningMidnightGetsAHeaderPerDay() {
        val rows = HistoryGrouping.group(listOf(entry("a", at(4, 0) + 60_000, "s"), entry("b", at(3, 23), "s")), zone)
        assertEquals(2, rows.filterIsInstance<HistoryRow.SessionHeader>().size)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun emptyInputGivesNoRows() = assertTrue(HistoryGrouping.group(emptyList(), zone).isEmpty())
}
