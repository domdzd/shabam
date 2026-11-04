package app.nanotune.data.clips

import app.nanotune.core.audio.Pcm
import app.nanotune.data.settings.SettingsRepository
import app.nanotune.domain.model.CapturedClip
import app.nanotune.domain.model.ClipAttempt
import app.nanotune.domain.model.ClipReason
import app.nanotune.domain.model.ClipStatus
import app.nanotune.domain.model.SavedClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Observable access to saved recordings; all file work happens off the main thread. */
class ClipRepository(
    private val store: ClipStore,
    private val settings: SettingsRepository,
    private val onRetryNeeded: () -> Unit,
) {
    private val _clips = MutableStateFlow<List<SavedClip>>(emptyList())
    val clips: StateFlow<List<SavedClip>> = _clips.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _clips.value = store.list()
    }

    /** Saves [clip] (downsampled to 16 kHz) and applies retention. Returns null if too short. */
    suspend fun save(
        clip: CapturedClip,
        reason: ClipReason,
        retryWhenOnline: Boolean = false,
        recognitionId: String? = null,
        trackId: String? = null,
        title: String? = null,
        artist: String? = null,
        firstAttempt: ClipAttempt? = null,
    ): SavedClip? = withContext(Dispatchers.IO) {
        if (clip.durationMs < ClipPolicy.MIN_DURATION_MS) return@withContext null
        val pcm = Pcm.resample(clip.pcm, clip.length, clip.sampleRateHz, ClipPolicy.STORAGE_SAMPLE_RATE)
        val saved = try {
            store.save(
                pcm = pcm,
                length = pcm.size - pcm.size % 2,
                sampleRateHz = ClipPolicy.STORAGE_SAMPLE_RATE,
                reason = reason,
                status = if (recognitionId != null) ClipStatus.IDENTIFIED else ClipStatus.UNIDENTIFIED,
                retryWhenOnline = retryWhenOnline,
                recognitionId = recognitionId,
                trackId = trackId,
                title = title,
                artist = artist,
            )
        } finally {
            pcm.fill(0)
        }
        val withAttempt = if (firstAttempt != null) store.update(saved.id) { it.copy(attempts = listOf(firstAttempt)) } ?: saved else saved
        applyRetention()
        _clips.value = store.list()
        if (retryWhenOnline) onRetryNeeded()
        withAttempt
    }

    suspend fun recordAttempt(id: String, attempt: ClipAttempt, identified: Identified?): SavedClip? = withContext(Dispatchers.IO) {
        val updated = store.update(id) { clip ->
            clip.copy(
                attempts = (clip.attempts + attempt).takeLast(MAX_ATTEMPTS),
                status = if (identified != null) ClipStatus.IDENTIFIED else clip.status,
                recognitionId = identified?.recognitionId ?: clip.recognitionId,
                trackId = identified?.trackId ?: clip.trackId,
                title = identified?.title ?: clip.title,
                artist = identified?.artist ?: clip.artist,
                retryWhenOnline = if (identified != null) false else clip.retryWhenOnline,
            )
        }
        _clips.value = store.list()
        updated
    }

    suspend fun stopRetrying(id: String) = withContext(Dispatchers.IO) {
        store.update(id) { it.copy(retryWhenOnline = false) }
        _clips.value = store.list()
    }

    suspend fun pcm(id: String): Pair<ByteArray, Int>? = withContext(Dispatchers.IO) { store.readPcm(id) }

    fun file(id: String): File = store.audioFile(id)

    suspend fun pendingRetries(): List<SavedClip> = withContext(Dispatchers.IO) {
        store.list().filter { it.retryWhenOnline && it.status == ClipStatus.UNIDENTIFIED }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        store.delete(id)
        _clips.value = store.list()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        store.deleteAll()
        _clips.value = emptyList()
    }

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) { store.totalBytes() }

    suspend fun applyRetention() = withContext(Dispatchers.IO) {
        val prefs = settings.current()
        store.purge(ClipPolicy.retentionMs(prefs.recordingsRetentionDays), ClipPolicy.MAX_TOTAL_BYTES)
        _clips.value = store.list()
    }

    data class Identified(val recognitionId: String, val trackId: String, val title: String, val artist: String)

    private companion object {
        const val MAX_ATTEMPTS = 10
    }
}
