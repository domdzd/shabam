package app.nanotune.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nanotune.AppContainer
import app.nanotune.data.remote.dto.ProviderInfoDto
import app.nanotune.data.repository.ServerState
import app.nanotune.domain.model.CapturedClip
import app.nanotune.domain.model.ErrorKind
import app.nanotune.domain.model.RecognitionOutcome
import app.nanotune.domain.model.RecognizedTrack
import app.nanotune.recognition.EnhancedGate
import app.nanotune.recognition.RecognitionEngine
import app.nanotune.recognition.SetupHint
import app.nanotune.service.SessionUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EngineStatus {
    READY,
    /** No on-device engine, but the user agreed to use the server's engines. */
    READY_SERVER,
    /** No on-device engine; the server offers engines the user hasn't agreed to yet. */
    SERVER_CONSENT_NEEDED,
    NOT_BUNDLED,
    NO_SERVER,
    SERVER_OFFLINE,
    SERVER_NOT_CONFIGURED,
    CHECKING,
}

sealed interface IdentifyPhase {
    data object Idle : IdentifyPhase
    data object Preparing : IdentifyPhase
    data class Listening(val elapsedMs: Long, val maxMs: Long, val level: Float, val micSilenced: Boolean) : IdentifyPhase
    data object SearchingEnhanced : IdentifyPhase
}

sealed interface IdentifyResultUi {
    data class Matched(val track: RecognizedTrack, val trackId: String, val providerLabel: String) : IdentifyResultUi
    data object NoMatch : IdentifyResultUi
    data class Problem(val message: String, val hint: SetupHint?, val retryable: Boolean, val permission: Boolean = false) : IdentifyResultUi

    /** Offline: the recording was kept and will be identified automatically later. */
    data object SavedForLater : IdentifyResultUi

    /** The server offers recognition engines, but the user hasn't agreed to send them audio. */
    data class ConsentNeeded(val providers: List<ProviderInfoDto>) : IdentifyResultUi
}

/** What happened to the recording of the last identification (only with "Keep recordings" on). */
enum class ClipNote { SAVED, PENDING }

data class HomeUiState(
    val phase: IdentifyPhase = IdentifyPhase.Idle,
    val result: IdentifyResultUi? = null,
    val clipNote: ClipNote? = null,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var job: Job? = null
    private val resultTrackId = MutableStateFlow<String?>(null)
    private var lastResult: RecognitionEngine.Result? = null
    private var pendingClip: CapturedClip? = null

    val resultFavourite: StateFlow<Boolean> = resultTrackId
        .flatMapLatest { id -> if (id == null) flowOf(false) else container.historyRepository.observeTrack(id).map { it?.favourite == true } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val session: StateFlow<SessionUiState> = container.sessionState.state

    val engineStatus: StateFlow<EngineStatus> = combine(
        container.serverRepository.state,
        flowOf(container.recognitionEngine.engineBundled),
        container.settingsRepository.settings,
        container.credentialStore.account,
    ) { server, bundled, _, _ ->
        when {
            !bundled && container.enhancedGate.primaryEligible() -> EngineStatus.READY_SERVER
            !bundled && container.enhancedGate.consentNeeded() -> EngineStatus.SERVER_CONSENT_NEEDED
            !bundled -> EngineStatus.NOT_BUNDLED
            server is ServerState.NotConfigured -> EngineStatus.NO_SERVER
            server is ServerState.Checking -> EngineStatus.CHECKING
            server is ServerState.Offline -> EngineStatus.SERVER_OFFLINE
            server is ServerState.Online && !server.config.shazamkit.available -> EngineStatus.SERVER_NOT_CONFIGURED
            else -> EngineStatus.READY
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EngineStatus.CHECKING)

    val serverProviders: StateFlow<List<ProviderInfoDto>> = container.serverRepository.state
        .map { (it as? ServerState.Online)?.config?.serverEngines?.providers.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val keepsRecordings: StateFlow<Boolean> = container.settingsRepository.settings
        .map { it.saveRecordings }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val busy: Boolean get() = job?.isActive == true

    fun identify() {
        if (busy) return // double-tap protection
        if (session.value.active) {
            _state.update {
                it.copy(result = IdentifyResultUi.Problem("Stop the listening session first — it is already using the microphone.", null, retryable = false))
            }
            return
        }
        resultTrackId.value = null
        discardPendingClip()
        _state.value = HomeUiState(phase = IdentifyPhase.Preparing)
        job = viewModelScope.launch {
            val result = container.recognitionEngine.identifyOnce { progress ->
                val phase = when (progress) {
                    RecognitionEngine.Progress.Preparing -> IdentifyPhase.Preparing
                    is RecognitionEngine.Progress.Listening ->
                        IdentifyPhase.Listening(progress.elapsedMs, progress.maxMs, progress.level, progress.micSilenced)
                    RecognitionEngine.Progress.SearchingEnhanced -> IdentifyPhase.SearchingEnhanced
                }
                _state.update { it.copy(phase = phase) }
            }
            lastResult = result
            pendingClip = result.pendingClip
            val clipNote = when {
                result.savedClip != null -> ClipNote.SAVED
                result.pendingClip != null -> ClipNote.PENDING
                else -> null
            }
            val ui = if (result.savedForLater) {
                IdentifyResultUi.SavedForLater
            } else if (result.consentNeeded) {
                IdentifyResultUi.ConsentNeeded(container.enhancedGate.providers())
            } else when (val outcome = result.outcome) {
                is RecognitionOutcome.Match -> {
                    val trackId = result.saved?.trackId
                    resultTrackId.value = trackId
                    if (trackId != null) IdentifyResultUi.Matched(outcome.track, trackId, outcome.providerLabel) else IdentifyResultUi.NoMatch
                }
                RecognitionOutcome.NoMatch -> IdentifyResultUi.NoMatch
                is RecognitionOutcome.Unavailable -> IdentifyResultUi.Problem(outcome.reason, result.setupHint, retryable = false)
                is RecognitionOutcome.Error -> IdentifyResultUi.Problem(
                    outcome.safeMessage,
                    result.setupHint,
                    retryable = outcome.retryable,
                    permission = outcome.kind == ErrorKind.AUDIO && !container.audioCapture.hasPermission(),
                )
            }
            _state.value = HomeUiState(phase = IdentifyPhase.Idle, result = ui, clipNote = if (result.savedForLater) null else clipNote)
        }
    }

    /** Keeps the recording of the last identification (user tapped "Keep recording"). */
    fun keepRecording() {
        val clip = pendingClip ?: return
        val result = lastResult ?: return
        pendingClip = null
        viewModelScope.launch {
            val saved = container.recognitionEngine.keepPendingClip(clip, result)
            _state.update { it.copy(clipNote = if (saved != null) ClipNote.SAVED else null) }
        }
    }

    /** The user agreed to send clips to the server's engines listed in [providers]. */
    fun grantServerEngines(providers: List<ProviderInfoDto>) {
        viewModelScope.launch {
            container.settingsRepository.update {
                it.copy(
                    enhancedEnabled = true,
                    enhancedConsentAt = System.currentTimeMillis(),
                    enhancedConsentProviders = EnhancedGate.consentKey(providers),
                )
            }
            identify()
        }
    }

    private fun discardPendingClip() {
        pendingClip?.wipe()
        pendingClip = null
    }

    override fun onCleared() {
        discardPendingClip()
        super.onCleared()
    }

    /** Called when the app goes to the background: Android would silence the mic anyway. */
    fun cancelBecauseBackgrounded() {
        if (!busy) return
        job?.cancel()
        discardPendingClip()
        _state.value = HomeUiState(
            result = IdentifyResultUi.Problem("Listening stopped because NanoTune left the screen.", null, retryable = true),
        )
    }

    fun cancel() {
        job?.cancel()
        _state.value = HomeUiState()
    }

    fun dismissResult() {
        resultTrackId.value = null
        discardPendingClip()
        _state.update { it.copy(result = null, clipNote = null) }
    }

    fun toggleFavourite() {
        val trackId = resultTrackId.value ?: return
        viewModelScope.launch {
            container.historyRepository.setFavourite(trackId, !resultFavourite.value)
            container.syncRepository.requestSync()
        }
    }

    fun refreshServer() {
        viewModelScope.launch { container.serverRepository.refresh() }
    }
}
