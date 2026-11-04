package app.nanotune.recognition

import app.nanotune.data.remote.dto.ProviderInfoDto
import app.nanotune.data.repository.ServerRepository
import app.nanotune.data.settings.AppSettings
import app.nanotune.data.settings.CredentialStore
import app.nanotune.data.settings.SettingsRepository

/**
 * Decides when audio may be sent to the server's recognition engines. Always requires that the
 * user turned the feature on and agreed to the *current* list of engines (a new engine asks again).
 *
 * * **Primary**: the server engines are the recognition engine (no ShazamKit in this build, or it is
 *   not configured) and for "identify again" on saved recordings. Needs an account unless the server
 *   owner allowed anonymous use.
 * * **Fallback** ("enhanced"): after ShazamKit found no match; needs an account and a server with
 *   at least two engines in total, so a single engine is never called enhanced.
 */
class EnhancedGate(
    private val server: ServerRepository,
    private val settings: SettingsRepository,
    private val credentials: CredentialStore,
) {
    suspend fun primaryEligible(): Boolean {
        val engines = server.currentConfig()?.serverEngines ?: return false
        if (!engines.available || engines.providers.isEmpty()) return false
        if (!engines.anonymous && credentials.currentAccount() == null) return false
        return consentValid(settings.current(), engines.providers)
    }

    suspend fun fallbackEligible(): Boolean {
        val enhanced = server.currentConfig()?.enhanced ?: return false
        if (!enhanced.available || credentials.currentAccount() == null) return false
        return consentValid(settings.current(), enhanced.providers)
    }

    /** True when the server offers engines this user could use but consent is missing or outdated. */
    suspend fun consentNeeded(): Boolean {
        val engines = server.currentConfig()?.serverEngines ?: return false
        if (!engines.available || engines.providers.isEmpty()) return false
        if (!engines.anonymous && credentials.currentAccount() == null) return false
        return !consentValid(settings.current(), engines.providers)
    }

    fun providers(): List<ProviderInfoDto> = server.currentConfig()?.serverEngines?.providers.orEmpty()

    fun providerNames(): String = providers().joinToString(" + ") { it.name }.ifEmpty { "your server's engines" }

    fun maxUploadSeconds(): Int = (server.currentConfig()?.serverEngines?.maxUploadSeconds ?: DEFAULT_MAX_UPLOAD_SECONDS)
        .coerceIn(3, DEFAULT_MAX_UPLOAD_SECONDS)

    private fun consentValid(prefs: AppSettings, providers: List<ProviderInfoDto>): Boolean =
        prefs.enhancedEnabled && prefs.enhancedConsentAt != null && prefs.enhancedConsentProviders == consentKey(providers)

    companion object {
        const val DEFAULT_MAX_UPLOAD_SECONDS = 12

        fun consentKey(providers: List<ProviderInfoDto>): String = providers.map { it.id }.sorted().joinToString(",")
    }
}
