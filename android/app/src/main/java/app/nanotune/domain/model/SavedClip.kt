package app.nanotune.domain.model

/** Audio captured during one identification, held in memory until saved or discarded. */
class CapturedClip(
    val pcm: ByteArray,
    val length: Int,
    val sampleRateHz: Int,
) {
    val durationMs: Long get() = length * 1_000L / (sampleRateHz * 2)

    fun wipe() = pcm.fill(0)
}

/** Why a recording was kept. */
enum class ClipReason {
    NO_MATCH,
    OFFLINE,
    ERROR,
    MATCHED,
    MANUAL,

    /** Recorded although no engine could identify it at that moment (none in this build, no server...). */
    NO_ENGINE,

    /** Imported from an audio file chosen by the user. */
    IMPORTED,
}

enum class ClipStatus { UNIDENTIFIED, IDENTIFIED }

data class ClipAttempt(
    val at: Long,
    /** Engine label, e.g. "Shazam" or "AudD via your server". */
    val engine: String,
    /** `match`, `no_match` or `error`. */
    val outcome: String,
)

/** A recording kept on this phone (Settings › Recordings), never synced. */
data class SavedClip(
    val id: String,
    val createdAt: Long,
    val durationMs: Long,
    val sampleRateHz: Int,
    val sizeBytes: Long,
    val reason: ClipReason,
    val status: ClipStatus,
    val recognitionId: String? = null,
    val trackId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val attempts: List<ClipAttempt> = emptyList(),
    /** Retry automatically when the phone is back online (offline captures). */
    val retryWhenOnline: Boolean = false,
    /** Name given by the user (or the imported file's name). */
    val label: String? = null,
) {
    /** What to show as the recording's name. */
    val displayName: String?
        get() = label?.takeIf { it.isNotBlank() } ?: title?.let { t -> artist?.let { "$t — $it" } ?: t }
}

/** Which identifications keep their recording when "Keep recordings" is on. */
enum class RecordingsPolicy { NOT_IDENTIFIED, ALL }
