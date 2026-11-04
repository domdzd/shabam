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

    /** Reason to keep [outcome]'s recording automatically, or null to not keep it. */
    fun autoSaveReason(outcome: RecognitionOutcome, policy: RecordingsPolicy, offlineCapture: Boolean): ClipReason? = when {
        offlineCapture -> ClipReason.OFFLINE
        outcome is RecognitionOutcome.Match -> if (policy == RecordingsPolicy.ALL) ClipReason.MATCHED else null
        outcome == RecognitionOutcome.NoMatch -> ClipReason.NO_MATCH
        outcome is RecognitionOutcome.Error && outcome.retryable -> ClipReason.ERROR
        outcome is RecognitionOutcome.Unavailable -> ClipReason.ERROR
        else -> null
    }

    fun retentionMs(days: Int): Long = if (days <= 0) 0L else days * 86_400_000L
}
