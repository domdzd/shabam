package app.nanotune.core.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Dedicated high-priority thread for microphone reads, so audio never runs on the main thread. */
object AudioDispatcher {
    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runnable.run()
        }, "nanotune-audio")
    }.asCoroutineDispatcher()
}

/** Thin wrapper around [AudioRecord] producing 16-bit mono PCM, as ShazamKit requires. */
class AudioCapture(private val context: Context) {

    class Recorder internal constructor(
        private val record: AudioRecord,
        val sampleRateHz: Int,
        /** Size of each read, in bytes. */
        val chunkSizeBytes: Int,
        val usesUnprocessedSource: Boolean,
    ) {
        val bytesPerSecond: Int get() = sampleRateHz * Pcm.BYTES_PER_SAMPLE

        fun start(): Boolean {
            record.startRecording()
            return record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }

        /** Blocking read; returns bytes read or a negative AudioRecord error code. */
        fun read(buffer: ByteArray): Int = record.read(buffer, 0, minOf(buffer.size, chunkSizeBytes))

        /** True when Android is feeding us silence because another app holds the microphone. */
        fun isSilencedByOtherApp(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                record.activeRecordingConfiguration?.isClientSilenced == true
            } else {
                false
            }

        fun release() {
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            record.release()
        }
    }

    sealed interface OpenResult {
        data class Opened(val recorder: Recorder) : OpenResult
        data object PermissionMissing : OpenResult
        data class Failed(val reason: String) : OpenResult
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Opens the microphone at the first of [preferredRates] the device supports. */
    fun open(preferredRates: List<Int>): OpenResult {
        if (!hasPermission()) return OpenResult.PermissionMissing
        @Suppress("MissingPermission")
        return openGranted(preferredRates)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openGranted(preferredRates: List<Int>): OpenResult {
        val unprocessed = supportsUnprocessedSource()
        val sources = if (unprocessed) {
            listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        } else {
            listOf(MediaRecorder.AudioSource.MIC)
        }
        var lastError = "The microphone could not be opened."
        for (source in sources) {
            for (rate in preferredRates) {
                val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuffer <= 0) continue
                // Read ~100 ms at a time; give AudioRecord a larger internal buffer to avoid overruns.
                val chunk = maxOf(minBuffer, rate * Pcm.BYTES_PER_SAMPLE / 10)
                val record = try {
                    AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(chunk * 4)
                        .build()
                } catch (e: UnsupportedOperationException) {
                    lastError = "This device does not support the requested audio format."
                    null
                } catch (e: IllegalArgumentException) {
                    lastError = "This device does not support the requested audio format."
                    null
                } catch (e: SecurityException) {
                    return OpenResult.PermissionMissing
                } ?: continue
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    lastError = "The microphone is unavailable. Another app may be using it."
                    continue
                }
                return OpenResult.Opened(
                    Recorder(record, rate, chunk, usesUnprocessedSource = source == MediaRecorder.AudioSource.UNPROCESSED),
                )
            }
        }
        return OpenResult.Failed(lastError)
    }

    private fun supportsUnprocessedSource(): Boolean {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
        return audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }
}
