package app.nanotune.data.repository

import android.os.Build
import app.nanotune.data.remote.ApiResult
import app.nanotune.data.remote.BackendApi
import app.nanotune.data.remote.BackendClient
import app.nanotune.data.remote.dto.CredentialsDto
import app.nanotune.data.remote.dto.DeleteAccountRequestDto
import app.nanotune.data.remote.dto.RefreshRequestDto
import app.nanotune.data.remote.dto.TokenPairDto
import app.nanotune.data.settings.AccountSession
import app.nanotune.data.settings.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val message: String) : AuthResult
}

/**
 * Optional account. Short-lived access tokens are refreshed with rotating refresh tokens; the
 * refresh is single-flight so two concurrent requests never present the same refresh token
 * twice (the server treats reuse as theft and revokes the session).
 */
class AccountRepository(
    private val backend: BackendClient,
    private val credentials: CredentialStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val account: Flow<AccountSession?> = credentials.account
    private val refreshMutex = Mutex()

    suspend fun register(email: String, password: String): AuthResult =
        authenticate { register(CredentialsDto(email.trim(), password, deviceName())) }

    suspend fun login(email: String, password: String): AuthResult =
        authenticate { login(CredentialsDto(email.trim(), password, deviceName())) }

    /** Revokes the refresh token on the server (best effort) and forgets it locally. */
    suspend fun logout() {
        val session = credentials.currentAccount()
        if (session != null) {
            backend.call { logout(RefreshRequestDto(session.refreshToken)) }
        }
        credentials.clearAccount()
    }

    /** Deletes the account and all cloud data. Local history on this phone is kept. */
    suspend fun deleteAccount(password: String): AuthResult {
        val result = authorized { api, auth -> api.deleteMe(auth, DeleteAccountRequestDto(password)) }
        return when (result) {
            is ApiResult.Ok -> {
                credentials.clearAccount()
                AuthResult.Success
            }
            is ApiResult.HttpError -> AuthResult.Failure(if (result.status == 403) "Password is incorrect." else result.message)
            is ApiResult.NetworkError -> AuthResult.Failure(result.message)
            ApiResult.NotConfigured -> AuthResult.Failure("No NanoTune server is configured.")
        }
    }

    /** Runs [block] with a valid `Authorization` header, refreshing once on 401. */
    suspend fun <T> authorized(block: suspend (BackendApi, String) -> Response<T>): ApiResult<T> {
        var session = credentials.currentAccount() ?: return notSignedIn()
        if (session.accessExpiresAt - EXPIRY_MARGIN_MS < clock()) {
            session = refreshSession(session) ?: return notSignedIn()
        }
        val first = backend.call { block(this, "Bearer ${session.accessToken}") }
        if (first is ApiResult.HttpError && first.status == 401) {
            val refreshed = refreshSession(session) ?: return first
            return backend.call { block(this, "Bearer ${refreshed.accessToken}") }
        }
        return first
    }

    private suspend fun refreshSession(stale: AccountSession): AccountSession? = refreshMutex.withLock {
        val current = credentials.currentAccount() ?: return null
        // Another coroutine refreshed while we waited for the lock.
        if (current.accessToken != stale.accessToken && current.accessExpiresAt - EXPIRY_MARGIN_MS > clock()) return current
        when (val result = backend.call { refresh(RefreshRequestDto(current.refreshToken)) }) {
            is ApiResult.Ok -> save(result.value)
            is ApiResult.HttpError -> {
                if (result.status == 401 || result.status == 403) credentials.clearAccount()
                null
            }
            else -> null
        }
    }

    private suspend fun authenticate(call: suspend BackendApi.() -> Response<TokenPairDto>): AuthResult =
        when (val result = backend.call(call)) {
            is ApiResult.Ok -> {
                save(result.value)
                AuthResult.Success
            }
            is ApiResult.HttpError -> AuthResult.Failure(result.message)
            is ApiResult.NetworkError -> AuthResult.Failure(result.message)
            ApiResult.NotConfigured -> AuthResult.Failure("Add your NanoTune server address in Settings first.")
        }

    private suspend fun save(tokens: TokenPairDto): AccountSession {
        val session = AccountSession(
            userId = tokens.user.id,
            email = tokens.user.email,
            accessToken = tokens.accessToken,
            accessExpiresAt = clock() + tokens.expiresIn * 1000,
            refreshToken = tokens.refreshToken,
        )
        credentials.saveAccount(session)
        return session
    }

    private fun notSignedIn() = ApiResult.HttpError(401, "not_signed_in", "Sign in to use this feature.", null)

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(64)

    private companion object {
        const val EXPIRY_MARGIN_MS = 30_000L
    }
}
