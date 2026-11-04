package app.nanotune.domain.model

/** One stored recognition joined with its track, as shown in history and exports. */
data class HistoryEntry(
    val recognitionId: String,
    val trackId: String,
    val track: RecognizedTrack,
    /** Epoch milliseconds (UTC). */
    val recognizedAt: Long,
    val providerId: String,
    val mode: RecognitionMode,
    val sessionId: String?,
    val confidence: Float?,
    val favourite: Boolean,
    /** Where album/date/duration came from when they were added after recognition. */
    val enrichmentSource: String? = null,
)

data class ListeningSessionSummary(
    val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val trackCount: Int,
    val userStopped: Boolean,
)

/** Date filter offered on the history screen. */
enum class DateFilter(val days: Int?) {
    ALL(null), TODAY(1), WEEK(7), MONTH(30);
}
