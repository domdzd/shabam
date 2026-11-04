package app.nanotune.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nanotune.AppContainer
import app.nanotune.data.remote.BackendClient
import app.nanotune.data.repository.ServerState
import app.nanotune.data.settings.AccountSession
import app.nanotune.data.settings.AppSettings
import app.nanotune.service.SessionController
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val server: StateFlow<ServerState> = container.serverRepository.state

    val account: StateFlow<AccountSession?> = container.accountRepository.account
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val engineBundled: Boolean get() = container.recognitionEngine.engineBundled

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            container.settingsRepository.update(transform)
            container.syncRepository.requestSync()
        }
    }

    /** Validates, stores and tests a new server address. */
    fun saveServerUrl(input: String) {
        viewModelScope.launch {
            val normalized = if (input.isBlank()) "" else BackendClient.normalizeBaseUrl(input)
            if (normalized == null) {
                _message.value = "That doesn't look like a valid server address."
                return@launch
            }
            if (normalized.startsWith("http://") && !isLocalAddress(normalized)) {
                _message.value = "Use https:// for internet servers. Plain http is only allowed for local test servers in debug builds."
            }
            container.settingsRepository.update { it.copy(serverUrl = normalized) }
            container.tokenSource.invalidate()
            when (val state = container.serverRepository.refresh()) {
                is ServerState.Online -> _message.value = "Connected to ${state.config.serverName ?: "your NanoTune server"}"
                is ServerState.Offline -> _message.value = state.message
                ServerState.NotConfigured -> _message.value = "Server address removed."
                ServerState.Checking -> Unit
            }
        }
    }

    fun testServer() {
        viewModelScope.launch { container.serverRepository.refresh() }
    }

    fun setEnhanced(enabled: Boolean) {
        update { it.copy(enhancedEnabled = enabled, enhancedConsentAt = if (enabled) System.currentTimeMillis() else null) }
    }

    /** Wipes history, favourites, settings, cached tokens and images from this phone. */
    fun deleteLocalData(context: Context) {
        viewModelScope.launch {
            SessionController.stop(context)
            runCatching { container.accountRepository.logout() }
            container.historyRepository.deleteAllLocalData()
            container.credentialStore.clearAll()
            container.settingsRepository.clear()
            container.resetInstallId()
            withContext(Dispatchers.IO) {
                val loader = SingletonImageLoader.get(context)
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
            container.serverRepository.refresh()
            _message.value = "All NanoTune data on this phone was deleted."
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun isLocalAddress(url: String): Boolean {
        val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
        return host == "localhost" || host == "10.0.2.2" || host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("127.")
    }
}
