package app.nanotune.data.remote

import app.nanotune.data.remote.dto.ApiErrorEnvelopeDto
import app.nanotune.data.settings.SettingsRepository
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException

/** Outcome of a backend call, with errors already mapped to safe, user-presentable messages. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    data class HttpError(val status: Int, val code: String?, val message: String, val retryAfterSeconds: Long?) : ApiResult<Nothing>

    data class NetworkError(val message: String) : ApiResult<Nothing>

    /** No server URL configured, or it is not a valid http(s) URL. */
    data object NotConfigured : ApiResult<Nothing>
}

/**
 * Builds Retrofit clients for the user-configurable NanoTune server URL and converts responses
 * into [ApiResult]. Tokens are passed per call and never logged.
 */
class BackendClient(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) {
    @Volatile
    private var cached: Pair<String, BackendApi>? = null

    suspend fun api(): BackendApi? {
        val base = normalizeBaseUrl(settings.current().serverUrl) ?: return null
        cached?.let { (url, api) -> if (url == base) return api }
        val api = Retrofit.Builder()
            .baseUrl("$base/api/v1/")
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BackendApi::class.java)
        cached = base to api
        return api
    }

    /** Runs [call] against the configured server and maps the response. */
    suspend fun <T> call(call: suspend BackendApi.() -> Response<T>): ApiResult<T> {
        val api = api() ?: return ApiResult.NotConfigured
        val response = try {
            api.call()
        } catch (e: IOException) {
            return ApiResult.NetworkError(networkMessage(e))
        } catch (e: kotlinx.serialization.SerializationException) {
            return ApiResult.HttpError(0, "bad_response", "The server sent a response this app does not understand.", null)
        } catch (e: IllegalArgumentException) {
            return ApiResult.HttpError(0, "bad_response", "The server sent a response this app does not understand.", null)
        }
        return toResult(response)
    }

    private fun <T> toResult(response: Response<T>): ApiResult<T> {
        if (response.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            val body = response.body() ?: (Unit as T)
            return ApiResult.Ok(body)
        }
        val raw = runCatching { response.errorBody()?.string() }.getOrNull()
        val envelope = raw?.let { runCatching { json.decodeFromString(ApiErrorEnvelopeDto.serializer(), it) }.getOrNull() }
        val retryAfter = response.headers()["Retry-After"]?.trim()?.toLongOrNull()
        val message = envelope?.error?.message ?: defaultMessage(response.code())
        return ApiResult.HttpError(response.code(), envelope?.error?.code, message, retryAfter)
    }

    private fun networkMessage(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "Can't reach the NanoTune server. Check your internet connection and the server address."
        is java.net.SocketTimeoutException -> "The NanoTune server took too long to respond."
        is javax.net.ssl.SSLException -> "A secure connection to the NanoTune server could not be established."
        is java.net.UnknownServiceException -> "This build only connects to https:// server addresses."
        else -> "Network error while contacting the NanoTune server."
    }

    private fun defaultMessage(status: Int): String = when (status) {
        400, 422 -> "The request was rejected by the server."
        401 -> "Please sign in again."
        403 -> "This action is not allowed."
        404 -> "This server does not support the requested feature."
        413 -> "The audio clip is too large for this server."
        429 -> "Too many requests. Please wait a moment and try again."
        in 500..599 -> "The NanoTune server had a problem. Try again later."
        else -> "Unexpected server response ($status)."
    }

    companion object {
        /** Accepts `example.com`, `https://example.com/`, `http://10.0.2.2:8000` → canonical base without slash. */
        fun normalizeBaseUrl(input: String): String? {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
            val parsed = withScheme.toHttpUrlOrNull() ?: return null
            if (parsed.host.isBlank()) return null
            return parsed.toString().trimEnd('/')
        }
    }
}
