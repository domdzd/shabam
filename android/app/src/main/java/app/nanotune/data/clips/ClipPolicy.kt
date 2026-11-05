package app.nanotune.data.clips

import app.nanotune.domain.model.ClipReason
import app.nanotune.domain.model.RecognitionOutcome
import app.nanotune.domain.model.RecordingsPolicy

/** Pure rules for which recordings are kept automatically. */
object ClipPolicy {
    /** Recordings are kept at 16 kHz mono: plenty for every engine, a third of the size of 48 kHz. */
    const val STORAGE_SAMPLE_RATE = 16_000

    /** Hard cap for all saved recordings together. */
    const val MAX_TOTAL_BYTES: Long = 200L * 1024 * 1024

    /** Clips shorter than this are not worth keeping. */
    const val MIN_DURATION_MS = 3_000L

    /** Imported audio files are cut to this length (trim afterwards to pick another part). */
    const val MAX_IMPORT_SECONDS = 90

    /**
     * Why [outcome]'s recording is kept, or null to not keep it. With "Keep recordings" on, nothing
     * that could not be identified is ever lost: no engine, offline, errors and no-matches are all
     * kept; matches only with [RecordingsPolicy.ALL].
     */
    fun autoSaveReason(
        outcome: RecognitionOutcome,
        policy: RecordingsPolicy,
        offlineCapture: Boolean,
        noEngine: Boolean = false,
    ): ClipReason? = when {
        offlineCapture -> ClipReason.OFFLINE
        noEngine -> ClipReason.NO_ENGINE
        outcome is RecognitionOutcome.Match -> if (policy == RecordingsPolicy.ALL) ClipReason.MATCHED else null
        outcome == RecognitionOutcome.NoMatch -> ClipReason.NO_MATCH
        else -> ClipReason.ERROR
    }

    fun retentionMs(days: Int): Long = if (days <= 0) 0L else days * 86_400_000L
}
