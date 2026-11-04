package app.nanotune.recognition

import app.nanotune.domain.model.ErrorKind
import app.nanotune.domain.model.RecognitionOutcome
import app.nanotune.domain.model.RecognizedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifySessionTest {
    private val match = RecognitionOutcome.Match(RecognizedTrack(title = "T", artist = "A"), providerId = "shazamkit")
    private val networkError = RecognitionOutcome.Error("Network", retryable = true, kind = ErrorKind.NETWORK)
    private val authError = RecognitionOutcome.Error("Auth", retryable = true, kind = ErrorKind.AUTH)

    @Test
    fun firstMatchFinishes() {
        val session = IdentifySession()
        assertEquals(IdentifySession.Decision.Continue, session.onOutcome(RecognitionOutcome.NoMatch))
        assertEquals(IdentifySession.Decision.Finish(match), session.onOutcome(match))
    }

    @Test
    fun deadlineAfterNoMatchesIsNoMatch() {
        val session = IdentifySession()
        session.onOutcome(networkError)
        session.onOutcome(RecognitionOutcome.NoMatch)
        assertEquals(RecognitionOutcome.NoMatch, session.onDeadline())
    }

    @Test
    fun deadlineWithOnlyErrorsReportsTheError() {
        val session = IdentifySession()
        assertEquals(IdentifySession.Decision.Continue, session.onOutcome(networkError))
        assertEquals(networkError, session.onDeadline())
    }

    @Test
    fun authErrorRefreshesOnceThenFails() {
        val session = IdentifySession(maxAuthRetries = 1)
        assertEquals(IdentifySession.Decision.RefreshTokenAndContinue, session.onOutcome(authError))
        val second = session.onOutcome(authError)
        assertTrue(second is IdentifySession.Decision.Finish)
    }

    @Test
    fun fatalErrorsFinishImmediately() {
        val fatal = RecognitionOutcome.Error("Audio", retryable = false, kind = ErrorKind.AUDIO)
        assertEquals(IdentifySession.Decision.Finish(fatal), IdentifySession().onOutcome(fatal))
        val unavailable = RecognitionOutcome.Unavailable("down")
        assertEquals(IdentifySession.Decision.Finish(unavailable), IdentifySession().onOutcome(unavailable))
    }

    @Test
    fun silenceWithNoResultsIsNoMatch() = assertEquals(RecognitionOutcome.NoMatch, IdentifySession().onDeadline())
}
