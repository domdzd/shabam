package app.nanotune.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.nanotune.domain.model.RecognizedTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SessionStatus {
    IDLE,
    STARTING,
    LISTENING,
    /** Interval mode: microphone closed until the next listening window. */
    WAITING_NEXT_WINDOW,
    PAUSED,
    WAITING_FOR_NETWORK,
    MIC_BUSY,
    RETRYING,
    ERROR,
}

data class SessionUiState(
    val status: SessionStatus = SessionStatus.IDLE,
    val sessionId: String? = null,
    /** Wall-clock start (epoch ms) for display. */
    val startedAt: Long? = null,
    val tracksFound: Int = 0,
    val lastTrack: RecognizedTrack? = null,
    val message: String? = null,
    val level: Float = 0f,
    val nextWindowAt: Long? = null,
) {
    val active: Boolean get() = status != SessionStatus.IDLE && status != SessionStatus.ERROR
    val microphoneOpen: Boolean get() = status == SessionStatus.LISTENING || status == SessionStatus.MIC_BUSY
}

/** Process-wide session state shared by the foreground service, the notification and the UI. */
class SessionStateHolder {
    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    fun update(transform: (SessionUiState) -> SessionUiState) = _state.update(transform)

    fun reset(message: String? = null, status: SessionStatus = SessionStatus.IDLE) {
        _state.value = SessionUiState(status = status, message = message)
    }
}

/** Entry points used by the UI. Starting must happen while an Activity is visible. */
object SessionController {
    fun start(context: Context) {
        ContextCompat.startForegroundService(context, intent(context, ListeningSessionService.ACTION_START))
    }

    fun pause(context: Context) = send(context, ListeningSessionService.ACTION_PAUSE)

    fun resume(context: Context) = send(context, ListeningSessionService.ACTION_RESUME)

    fun stop(context: Context) = send(context, ListeningSessionService.ACTION_STOP)

    private fun send(context: Context, action: String) {
        // The service is already in the foreground, so a plain startService is allowed.
        runCatching { context.startService(intent(context, action)) }
    }

    fun intent(context: Context, action: String): Intent =
        Intent(context, ListeningSessionService::class.java).setAction(action)
}
