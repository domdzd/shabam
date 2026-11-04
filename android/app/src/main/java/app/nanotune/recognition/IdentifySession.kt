package app.nanotune.recognition

import app.nanotune.domain.model.ErrorKind
import app.nanotune.domain.model.RecognitionOutcome

/**
 * Decides when a single "tap to identify" attempt is finished, given the stream of outcomes a
 * streaming engine produces. Pure logic so it can be unit tested without audio or an SDK.
 *
 * * The first match wins immediately.
 * * "No match" results are expected while the engine is still collecting audio, so they only
 *   matter when time runs out.
 * * Transient errors (e.g. one failed network request) are tolerated until the deadline; an
 *   authorisation error triggers a single token refresh before it becomes final.
 */
class IdentifySession(private val maxAuthRetries: Int = 1) {
    sealed interface Decision {
        data object Continue : Decision
        data object RefreshTokenAndContinue : Decision
        data class Finish(val outcome: RecognitionOutcome) : Decision
    }

    private var sawNoMatch = false
    private var lastError: RecognitionOutcome.Error? = null
    private var authRetries = 0

    fun onOutcome(outcome: RecognitionOutcome): Decision = when (outcome) {
        is RecognitionOutcome.Match -> Decision.Finish(outcome)
        is RecognitionOutcome.Unavailable -> Decision.Finish(outcome)
        RecognitionOutcome.NoMatch -> {
            sawNoMatch = true
            Decision.Continue
        }
        is RecognitionOutcome.Error -> when {
            outcome.kind == ErrorKind.AUTH && authRetries < maxAuthRetries -> {
                authRetries++
                lastError = outcome
                Decision.RefreshTokenAndContinue
            }
            outcome.kind == ErrorKind.AUTH -> Decision.Finish(outcome)
            !outcome.retryable -> Decision.Finish(outcome)
            else -> {
                lastError = outcome
                Decision.Continue
            }
        }
    }

    /** Result when the listening deadline passes without a match. */
    fun onDeadline(): RecognitionOutcome = when {
        sawNoMatch -> RecognitionOutcome.NoMatch
        lastError != null -> lastError!!
        else -> RecognitionOutcome.NoMatch
    }
}
