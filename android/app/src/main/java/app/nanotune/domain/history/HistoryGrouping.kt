package app.nanotune.domain.history

import app.nanotune.domain.model.HistoryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Flattened rows for the history list: day headers, session headers and items. */
sealed interface HistoryRow {
    val key: String

    data class DayHeader(val date: LocalDate) : HistoryRow {
        override val key: String get() = "day-$date"
    }

    data class SessionHeader(val sessionId: String, val date: LocalDate, val firstSeenAt: Long, val itemCount: Int) : HistoryRow {
        override val key: String get() = "session-$sessionId-$date"
    }

    data class Item(val entry: HistoryEntry, val inSession: Boolean) : HistoryRow {
        override val key: String get() = "item-${entry.recognitionId}"
    }
}

object HistoryGrouping {
    /**
     * Groups entries (any order) newest-first by local day; within a day, consecutive entries of
     * the same listening session are placed under a session header.
     */
    fun group(entries: List<HistoryEntry>, zone: ZoneId): List<HistoryRow> {
        if (entries.isEmpty()) return emptyList()
        val sorted = entries.sortedWith(compareByDescending<HistoryEntry> { it.recognizedAt }.thenBy { it.recognitionId })
        val rows = ArrayList<HistoryRow>(sorted.size + 8)
        var currentDay: LocalDate? = null
        var index = 0
        while (index < sorted.size) {
            val entry = sorted[index]
            val day = Instant.ofEpochMilli(entry.recognizedAt).atZone(zone).toLocalDate()
            if (day != currentDay) {
                rows += HistoryRow.DayHeader(day)
                currentDay = day
            }
            val sessionId = entry.sessionId
            if (sessionId == null) {
                rows += HistoryRow.Item(entry, inSession = false)
                index++
                continue
            }
            // Collect the run of entries from this session on this day.
            var end = index
            while (end < sorted.size) {
                val candidate = sorted[end]
                val candidateDay = Instant.ofEpochMilli(candidate.recognizedAt).atZone(zone).toLocalDate()
                if (candidate.sessionId != sessionId || candidateDay != day) break
                end++
            }
            val run = sorted.subList(index, end)
            rows += HistoryRow.SessionHeader(
                sessionId = sessionId,
                date = day,
                firstSeenAt = run.minOf { it.recognizedAt },
                itemCount = run.size,
            )
            run.forEach { rows += HistoryRow.Item(it, inSession = true) }
            index = end
        }
        return rows
    }
}
