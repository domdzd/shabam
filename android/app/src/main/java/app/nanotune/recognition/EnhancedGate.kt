package app.nanotune.recognition

import app.nanotune.data.repository.ServerRepository
import app.nanotune.data.settings.CredentialStore
import app.nanotune.data.settings.SettingsRepository

/**
 * Enhanced recognition is only used when every condition holds: the server advertises it (it
 * has at least two usable engines), the user is signed in, turned it on, and consented to
 * uploading a short clip. Registration alone never changes how recognition works.
 */
class EnhancedGate(
    private val server: ServerRepository,
    private val settings: SettingsRepository,
    private val credentials: CredentialStore,
) {
    suspend fun isEligible(): Boolean {
        val config = server.currentConfig() ?: return false
        val prefs = settings.current()
        return config.enhanced.available &&
            prefs.enhancedEnabled &&
            prefs.enhancedConsentAt != null &&
            credentials.currentAccount() != null
    }

    fun maxUploadSeconds(): Int = (server.currentConfig()?.enhanced?.maxUploadSeconds ?: DEFAULT_MAX_UPLOAD_SECONDS)
        .coerceIn(3, DEFAULT_MAX_UPLOAD_SECONDS)

    private companion object {
        const val DEFAULT_MAX_UPLOAD_SECONDS = 12
    }
}
