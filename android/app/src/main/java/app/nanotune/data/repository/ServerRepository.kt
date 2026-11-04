package app.nanotune.data.repository

import app.nanotune.data.remote.ApiResult
import app.nanotune.data.remote.BackendClient
import app.nanotune.data.remote.dto.ServerConfigDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ServerState {
    data object NotConfigured : ServerState
    data object Checking : ServerState
    data class Online(val config: ServerConfigDto, val checkedAt: Long) : ServerState
    data class Offline(val message: String) : ServerState
}

/** Knows what the configured NanoTune server offers (token signing, accounts, enhanced mode). */
class ServerRepository(private val backend: BackendClient, private val clock: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow<ServerState>(ServerState.Checking)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    suspend fun refresh(): ServerState {
        _state.value = ServerState.Checking
        val next = when (val result = backend.call { config() }) {
            is ApiResult.Ok -> ServerState.Online(result.value, clock())
            ApiResult.NotConfigured -> ServerState.NotConfigured
            is ApiResult.NetworkError -> ServerState.Offline(result.message)
            is ApiResult.HttpError -> ServerState.Offline(result.message)
        }
        _state.value = next
        return next
    }

    fun currentConfig(): ServerConfigDto? = (state.value as? ServerState.Online)?.config
}
