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
enum class ClipReason { NO_MATCH, OFFLINE, ERROR, MATCHED, MANUAL }

enum class ClipStatus { UNIDENTIFIED, IDENTIFIED }

data class ClipAttempt(
    val at: Long,
    /** Engine label, e.g. "Shazam" or "AudD via your server". */
    val engine: String,
    /** `match`, `no_match` or `error`. */
    val outcome: String,
)

/** A recording the user chose to keep (opt-in), stored only on this phone. */
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
)

/** Which identifications keep their recording when "Keep recordings" is on. */
enum class RecordingsPolicy { NOT_IDENTIFIED, ALL }
