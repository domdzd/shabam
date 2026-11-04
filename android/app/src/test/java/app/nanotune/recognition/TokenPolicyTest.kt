package app.nanotune.recognition

import app.nanotune.recognition.shazamkit.TokenPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenPolicyTest {
    @Test
    fun tokenIsRefreshedBeforeExpiry() {
        assertTrue(TokenPolicy.isUsable(expiresAtEpochSeconds = 10_000, nowEpochSeconds = 9_000))
        assertFalse(TokenPolicy.isUsable(expiresAtEpochSeconds = 10_000, nowEpochSeconds = 9_800))
        assertFalse(TokenPolicy.isUsable(expiresAtEpochSeconds = 10_000, nowEpochSeconds = 11_000))
    }

    @Test
    fun localExpiryUsesRelativeLifetime() {
        assertEquals(5_000L + 3_600, TokenPolicy.localExpiry(expiresInSeconds = 3_600, nowEpochSeconds = 5_000))
        assertEquals(5_000L, TokenPolicy.localExpiry(expiresInSeconds = -10, nowEpochSeconds = 5_000))
    }
}
