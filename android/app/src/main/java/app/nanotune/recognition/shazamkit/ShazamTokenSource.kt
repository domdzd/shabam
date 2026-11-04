package app.nanotune.recognition.shazamkit

import app.nanotune.data.remote.ApiResult
import app.nanotune.data.remote.BackendClient
import app.nanotune.data.settings.CachedDeveloperToken
import app.nanotune.data.settings.CredentialStore
import app.nanotune.recognition.ProviderAvailability
import app.nanotune.recognition.SetupHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Supplies ShazamKit developer tokens. Tokens are signed by the NanoTune server (the Apple
 * private key never reaches the device), cached until shortly before expiry, and never logged.
 */
class ShazamTokenSource(
    private val backend: BackendClient,
    private val credentials: CredentialStore,
    private val installId: suspend () -> String,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    sealed interface TokenResult {
        data class Ok(val token: String) : TokenResult
        data class Failed(val availability: ProviderAvailability) : TokenResult
    }

    private val mutex = Mutex()

    @Volatile
    private var memory: CachedDeveloperToken? = null

    suspend fun get(forceRefresh: Boolean = false): TokenResult = mutex.withLock {
        if (!forceRefresh) {
            val cached = memory ?: credentials.developerToken()?.also { memory = it }
            if (cached != null && TokenPolicy.isUsable(cached.expiresAtEpochSeconds, nowEpochSeconds())) {
                return TokenResult.Ok(cached.token)
            }
        }
        when (val result = backend.call { shazamKitToken(installId()) }) {
            is ApiResult.Ok -> {
                val token = CachedDeveloperToken(result.value.token, TokenPolicy.localExpiry(result.value.expiresIn, nowEpochSeconds()))
                memory = token
                credentials.saveDeveloperToken(token)
                TokenResult.Ok(token.token)
            }
            ApiResult.NotConfigured -> TokenResult.Failed(
                ProviderAvailability.NotConfigured("Add your NanoTune server address in Settings to enable recognition.", SetupHint.SET_SERVER_URL),
            )
            is ApiResult.NetworkError -> TokenResult.Failed(ProviderAvailability.Unavailable(result.message, retryable = true))
            is ApiResult.HttpError -> when {
                result.status == 503 && result.code == "shazamkit_not_configured" -> TokenResult.Failed(
                    ProviderAvailability.NotConfigured(
                        "Your NanoTune server has no Apple ShazamKit key configured yet.",
                        SetupHint.SERVER_NOT_CONFIGURED,
                    ),
                )
                result.status == 429 -> TokenResult.Failed(
                    ProviderAvailability.Unavailable("The server is rate limiting recognition. Try again in a minute.", retryable = true),
                )
                else -> TokenResult.Failed(ProviderAvailability.Unavailable(result.message, retryable = result.status >= 500))
            }
        }
    }

    /** Drops cached tokens, e.g. after ShazamKit reported UNAUTHORIZED. */
    suspend fun invalidate() = mutex.withLock {
        memory = null
        credentials.saveDeveloperToken(null)
    }

    /**
     * Blocking accessor for ShazamKit's synchronous `DeveloperTokenProvider`. ShazamKit calls it
     * from its own worker thread; engines prefetch a token before matching so this is normally a
     * memory hit. Returns an empty token on failure, which ShazamKit reports as UNAUTHORIZED.
     */
    fun blockingToken(): String {
        memory?.let { if (TokenPolicy.isUsable(it.expiresAtEpochSeconds, nowEpochSeconds())) return it.token }
        return runBlocking(Dispatchers.IO) {
            when (val result = get()) {
                is TokenResult.Ok -> result.token
                is TokenResult.Failed -> ""
            }
        }
    }
}
