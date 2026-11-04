package app.nanotune.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.nanotune.NanoTuneApp
import app.nanotune.core.audio.AudioCapture
import app.nanotune.core.audio.AudioDispatcher
import app.nanotune.core.audio.Pcm
import app.nanotune.data.settings.SessionMode
import app.nanotune.domain.dedup.DuplicateFilter
import app.nanotune.domain.model.ErrorKind
import app.nanotune.domain.model.RecognitionMode
import app.nanotune.domain.model.RecognitionOutcome
import app.nanotune.recognition.ProviderAvailability
import app.nanotune.recognition.StreamOpenResult
import app.nanotune.recognition.StreamingRecognitionProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service (type `microphone`) that runs a user-started listening session.
 *
 * * Started only from the visible app after RECORD_AUDIO is granted (Android requires that for
 *   while-in-use microphone access); never started at boot and `START_NOT_STICKY`, so it does
 *   not come back after the user stops it or Android kills the process.
 * * Shows an ongoing notification with Pause/Resume and Stop for the entire session.
 * * Streams microphone audio to ShazamKit's StreamingSession (or listens in windows in
 *   interval mode) and stores each new song once, using a sliding duplicate cooldown.
 */
class ListeningSessionService : LifecycleService() {

    private val container get() = (application as NanoTuneApp).container
    private val holder get() = container.sessionState
    private val notifications by lazy { SessionNotifications(this) }

    private var runJob: Job? = null
    private val paused = MutableStateFlow(false)
    @Volatile
    private var userStopRequested = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_STOP -> stopSession(userStopped = true)
            else -> if (runJob == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        notifications.ensureChannel()
        val alreadyRunning = runJob?.isActive == true
        if (!alreadyRunning) {
            userStopRequested = false
            paused.value = false
            holder.reset(status = SessionStatus.STARTING)
            holder.update { it.copy(startedAt = System.currentTimeMillis()) }
        }
        try {
            // Called on every start request: each startForegroundService() must be answered.
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, SessionNotifications.NOTIFICATION_ID, notifications.build(holder.state.value), type)
        } catch (e: Exception) {
            // SecurityException (permission missing) or ForegroundServiceStartNotAllowedException.
            if (!alreadyRunning) {
                holder.reset("Android did not allow the listening session to start. Open NanoTune and try again.", SessionStatus.ERROR)
                stopSelf()
            }
            return
        }
        if (!alreadyRunning) runJob = lifecycleScope.launch { runSession() }
    }

    private fun setPaused(value: Boolean) {
        if (runJob?.isActive != true) return
        paused.value = value
        publish { it.copy(status = if (value) SessionStatus.PAUSED else SessionStatus.STARTING, level = 0f) }
    }

    private fun stopSession(userStopped: Boolean, finalMessage: String? = null) {
        userStopRequested = userStopped
        val job = runJob
        lifecycleScope.launch {
            job?.cancelAndJoin()
            runJob = null
            holder.reset(finalMessage, if (finalMessage != null) SessionStatus.ERROR else SessionStatus.IDLE)
            ServiceCompat.stopForeground(this@ListeningSessionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        runJob?.cancel()
        if (holder.state.value.active) holder.reset()
        super.onDestroy()
    }

    private suspend fun runSession() {
        val history = container.historyRepository
        val sessionId = history.startSession()
        history.closeDanglingSessions(exceptId = sessionId)
        publish { it.copy(sessionId = sessionId, status = SessionStatus.STARTING) }
        val filter = DuplicateFilter(DuplicateFilter.DEFAULT_COOLDOWN_MS)
        var backoffMs = INITIAL_BACKOFF_MS
        var fatalMessage: String? = null
        try {
            while (currentCoroutineContext().isActive) {
                if (paused.value) {
                    publish { it.copy(status = SessionStatus.PAUSED, level = 0f) }
                    paused.first { !it }
                    continue
                }
                val prefs = container.settingsRepository.current()
                val intervalMs = if (prefs.sessionMode == SessionMode.INTERVAL) prefs.sessionIntervalSeconds * 1_000L else null
                filter.cooldownMs = DuplicateFilter.effectiveCooldownMs(prefs.duplicateCooldownMinutes * 60_000L, intervalMs, WINDOW_MS)

                val network = container.connectivity.current()
                if (!network.connected || (network.metered && !prefs.sessionsOnMobileData)) {
                    val message = if (!network.connected) "Waiting for an internet connection…" else "Paused on mobile data (see Settings › Listening sessions)."
                    publish { it.copy(status = SessionStatus.WAITING_FOR_NETWORK, message = message, level = 0f) }
                    withTimeoutOrNull(NETWORK_RECHECK_MS) { paused.first { it } }
                    continue
                }

                val provider = container.primaryProvider
                if (provider == null) {
                    fatalMessage = "This build of NanoTune does not include the ShazamKit engine, so sessions can't identify music."
                    break
                }
                when (val availability = provider.availability()) {
                    ProviderAvailability.Ready -> Unit
                    is ProviderAvailability.NotConfigured -> {
                        fatalMessage = availability.reason
                        break
                    }
                    is ProviderAvailability.Unavailable -> {
                        publish { it.copy(status = SessionStatus.RETRYING, message = availability.reason, level = 0f) }
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                        continue
                    }
                }

                when (val end = listenWindow(provider, if (intervalMs != null) WINDOW_MS else null, filter, sessionId)) {
                    WindowEnd.Completed -> {
                        backoffMs = INITIAL_BACKOFF_MS
                        if (intervalMs != null) {
                            val next = System.currentTimeMillis() + intervalMs
                            publish { it.copy(status = SessionStatus.WAITING_NEXT_WINDOW, nextWindowAt = next, level = 0f, message = null) }
                            withTimeoutOrNull(intervalMs) { paused.first { it } }
                        }
                    }
                    WindowEnd.Paused -> Unit
                    is WindowEnd.Retry -> {
                        publish { it.copy(status = SessionStatus.RETRYING, message = end.message, level = 0f) }
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                    is WindowEnd.Fatal -> {
                        fatalMessage = end.message
                        break
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                history.endSession(sessionId, userStopped = userStopRequested)
            }
        }
        if (fatalMessage != null) stopSession(userStopped = false, finalMessage = fatalMessage)
    }

    private sealed interface WindowEnd {
        data object Completed : WindowEnd
        data object Paused : WindowEnd
        data class Retry(val message: String) : WindowEnd
        data class Fatal(val message: String) : WindowEnd
    }

    /** Listens until paused/stopped, a fatal error, or (interval mode) [windowMs] elapses. */
    private suspend fun listenWindow(
        provider: StreamingRecognitionProvider,
        windowMs: Long?,
        filter: DuplicateFilter,
        sessionId: String,
    ): WindowEnd = coroutineScope {
        val recorder = when (val opened = container.audioCapture.open(provider.supportedSampleRates)) {
            is AudioCapture.OpenResult.Opened -> opened.recorder
            AudioCapture.OpenResult.PermissionMissing -> return@coroutineScope WindowEnd.Fatal("Microphone permission was revoked, so the session stopped.")
            is AudioCapture.OpenResult.Failed -> return@coroutineScope WindowEnd.Retry(opened.reason)
        }
        try {
            if (!recorder.start()) return@coroutineScope WindowEnd.Retry("The microphone is busy. Retrying…")
            val stream = when (val opened = provider.openStream(recorder.sampleRateHz, recorder.chunkSizeBytes)) {
                is StreamOpenResult.Opened -> opened.stream
                is StreamOpenResult.Failed -> {
                    val outcome = opened.outcome
                    return@coroutineScope if (outcome is RecognitionOutcome.Error && !outcome.retryable) {
                        WindowEnd.Fatal(outcome.safeMessage)
                    } else {
                        WindowEnd.Retry("Couldn't start the recogniser. Retrying…")
                    }
                }
            }
            publish { it.copy(status = SessionStatus.LISTENING, message = null, nextWindowAt = null) }
            val end = CompletableDeferred<WindowEnd>()
            var authFailures = 0
            val collector = launch {
                stream.outcomes.collect { outcome ->
                    when (outcome) {
                        is RecognitionOutcome.Match -> {
                            onMatch(outcome, filter, sessionId)
                            if (windowMs != null) end.complete(WindowEnd.Completed)
                        }
                        is RecognitionOutcome.Error -> when (outcome.kind) {
                            ErrorKind.AUTH -> {
                                authFailures++
                                if (authFailures > MAX_AUTH_FAILURES) {
                                    end.complete(WindowEnd.Retry("The recognition service rejected this app's token. Retrying later…"))
                                } else {
                                    container.tokenSource.get(forceRefresh = true)
                                }
                            }
                            ErrorKind.NETWORK -> publish { it.copy(message = outcome.safeMessage) }
                            else -> Unit
                        }
                        else -> Unit
                    }
                }
            }
            val reader = launch(AudioDispatcher.dispatcher) {
                val buffer = ByteArray(recorder.chunkSizeBytes)
                val streamStart = System.currentTimeMillis()
                var bytes = 0L
                var lastUiUpdate = 0L
                var silenced = false
                while (isActive && !end.isCompleted) {
                    if (paused.value) {
                        end.complete(WindowEnd.Paused)
                        break
                    }
                    val read = recorder.read(buffer)
                    if (read < 0) {
                        end.complete(WindowEnd.Retry("The microphone stopped delivering audio. Retrying…"))
                        break
                    }
                    if (read == 0) continue
                    stream.feed(buffer, read, streamStart + bytes * 1_000 / recorder.bytesPerSecond)
                    bytes += read
                    val elapsed = bytes * 1_000 / recorder.bytesPerSecond
                    if (elapsed - lastUiUpdate >= UI_UPDATE_MS) {
                        lastUiUpdate = elapsed
                        val nowSilenced = recorder.isSilencedByOtherApp()
                        val level = Pcm.level(buffer, read)
                        publish(notify = nowSilenced != silenced) {
                            it.copy(
                                status = if (nowSilenced) SessionStatus.MIC_BUSY else SessionStatus.LISTENING,
                                message = if (nowSilenced) "Another app (or a call) is using the microphone." else it.message,
                                level = level,
                            )
                        }
                        silenced = nowSilenced
                    }
                    if (windowMs != null && elapsed >= windowMs) {
                        end.complete(WindowEnd.Completed)
                        break
                    }
                }
                buffer.fill(0)
            }
            val result = end.await()
            reader.cancelAndJoin()
            collector.cancelAndJoin()
            stream.close()
            result
        } finally {
            recorder.release()
        }
    }

    private suspend fun onMatch(match: RecognitionOutcome.Match, filter: DuplicateFilter, sessionId: String) {
        if (!filter.register(match.track, SystemClock.elapsedRealtime())) return
        val saved = container.historyRepository.saveRecognition(
            match.track,
            match.providerId,
            RecognitionMode.SESSION,
            sessionId,
            match.confidence,
        )
        container.metadataEnricher.enrichLater(saved.trackId)
        container.syncRepository.requestSync(delayMs = 30_000)
        publish { it.copy(tracksFound = it.tracksFound + 1, lastTrack = match.track) }
    }

    private fun publish(notify: Boolean = true, transform: (SessionUiState) -> SessionUiState) {
        holder.update(transform)
        if (notify) notifications.update(holder.state.value)
    }

    companion object {
        const val ACTION_START = "app.nanotune.session.START"
        const val ACTION_PAUSE = "app.nanotune.session.PAUSE"
        const val ACTION_RESUME = "app.nanotune.session.RESUME"
        const val ACTION_STOP = "app.nanotune.session.STOP"

        /** Listening window in interval mode. */
        const val WINDOW_MS = 15_000L
        private const val UI_UPDATE_MS = 250L
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 120_000L
        private const val NETWORK_RECHECK_MS = 10_000L
        private const val MAX_AUTH_FAILURES = 2
    }
}
