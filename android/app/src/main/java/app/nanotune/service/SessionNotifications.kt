package app.nanotune.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.nanotune.MainActivity
import app.nanotune.R

/** The ongoing "microphone in use" notification shown for the whole listening session. */
class SessionNotifications(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Listening sessions", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while NanoTune is listening with the microphone, with a Stop button."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(state: SessionUiState): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_SESSION)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = servicePendingIntent(ListeningSessionService.ACTION_STOP, REQUEST_STOP)
        val paused = state.status == SessionStatus.PAUSED
        val toggle = if (paused) {
            servicePendingIntent(ListeningSessionService.ACTION_RESUME, REQUEST_TOGGLE)
        } else {
            servicePendingIntent(ListeningSessionService.ACTION_PAUSE, REQUEST_TOGGLE)
        }

        val title = when (state.status) {
            SessionStatus.PAUSED -> "Listening session paused"
            SessionStatus.WAITING_NEXT_WINDOW -> "Listening session · microphone resting"
            SessionStatus.WAITING_FOR_NETWORK -> "Listening session · waiting for network"
            SessionStatus.MIC_BUSY -> "Listening session · microphone busy"
            SessionStatus.RETRYING -> "Listening session · reconnecting"
            else -> "NanoTune is listening (microphone on)"
        }
        val count = state.tracksFound
        val text = buildString {
            append(if (count == 1) "1 track found" else "$count tracks found")
            state.lastTrack?.let { append(" · Last: ${it.title} — ${it.artist}") }
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_listening)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text, state.message).joinToString("\n")))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setUsesChronometer(state.startedAt != null)
            .setWhen(state.startedAt ?: System.currentTimeMillis())
            .addAction(
                if (paused) R.drawable.ic_action_play else R.drawable.ic_action_pause,
                if (paused) "Resume" else "Pause",
                toggle,
            )
            .addAction(R.drawable.ic_action_stop, "Stop", stop)
            .build()
    }

    @SuppressLint("MissingPermission") // Guarded by areNotificationsEnabled().
    fun update(state: SessionUiState) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(NOTIFICATION_ID, build(state)) }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            SessionController.intent(context, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val CHANNEL_ID = "listening_session"
        const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_TOGGLE = 3
    }
}
