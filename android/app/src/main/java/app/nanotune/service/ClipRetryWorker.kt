package app.nanotune.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.nanotune.MainActivity
import app.nanotune.NanoTuneApp
import app.nanotune.R
import app.nanotune.recognition.ClipRecognizer
import java.util.concurrent.TimeUnit

/**
 * Identifies recordings that were captured while offline, once a network is available. Runs as a
 * WorkManager job (network constraint, exponential backoff); no microphone access is involved.
 */
class ClipRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as NanoTuneApp).container
        val pending = container.clipRepository.pendingRetries()
        if (pending.isEmpty()) return Result.success()
        container.serverRepository.refresh()
        var tryAgainLater = false
        for (clip in pending) {
            when (val result = container.clipRecognizer.identify(clip.id)) {
                is ClipRecognizer.Result.Identified -> notifyFound(applicationContext, result)
                ClipRecognizer.Result.NoMatch -> container.clipRepository.stopRetrying(clip.id)
                is ClipRecognizer.Result.Failed ->
                    if (result.retryable) tryAgainLater = true else container.clipRepository.stopRetrying(clip.id)
                is ClipRecognizer.Result.NoEngine -> tryAgainLater = true
            }
        }
        return when {
            !tryAgainLater -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> {
                // Give up: the recordings stay, marked as not identified; the user can still tap Identify.
                container.clipRepository.pendingRetries().forEach { container.clipRepository.stopRetrying(it.id) }
                Result.success()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "nanotune-clip-retry"
        private const val MAX_ATTEMPTS = 8
        private const val CHANNEL_ID = "identified_later"
        private const val NOTIFICATION_ID = 2001

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<ClipRetryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        @SuppressLint("MissingPermission") // checked below
        private fun notifyFound(context: Context, result: ClipRecognizer.Result.Identified) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Songs identified later", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When a recording saved offline has been identified."
                },
            )
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_TRACK)
                    .putExtra(MainActivity.EXTRA_TRACK_ID, result.trackId)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_listening)
                .setContentTitle("Found it: ${result.track.title}")
                .setContentText("${result.track.artist} · identified from a saved recording (${result.engine})")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + result.trackId.hashCode() % 1000, notification) }
        }
    }
}
