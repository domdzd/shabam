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
        label: String? = null,
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
                label = label,
            )
        } finally {
            pcm.fill(0)
        }
        applyRetention()
        if (retryWhenOnline) onRetryNeeded()
        store.get(saved.id) ?: saved
    }

    suspend fun recordAttempt(id: String, attempt: ClipAttempt, identified: Identified?): SavedClip? = withContext(Dispatchers.IO) {
        val updated = store.update(id) { clip ->
            clip.copy(
                attempts = (clip.attempts + attempt).takeLast(MAX_ATTEMPTS),
                status = if (identified != null) ClipStatus.IDENTIFIED else clip.status,
                reason = if (identified != null && clip.reason != ClipReason.IMPORTED) ClipReason.MATCHED else clip.reason,
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

    suspend fun setRetry(id: String, retry: Boolean) {
        withContext(Dispatchers.IO) {
            store.update(id) { it.copy(retryWhenOnline = retry && it.status == ClipStatus.UNIDENTIFIED) }
            _clips.value = store.list()
        }
        if (retry) onRetryNeeded()
    }

    suspend fun stopRetrying(id: String) = setRetry(id, false)

    suspend fun rename(id: String, label: String?) = withContext(Dispatchers.IO) {
        store.update(id) { it.copy(label = label) }
        _clips.value = store.list()
    }

    /** Keeps only [startMs] until [endMs] of the recording. Returns null if the result is too short. */
    suspend fun trim(id: String, startMs: Long, endMs: Long): SavedClip? = withContext(Dispatchers.IO) {
        val (pcm, rate) = store.readPcm(id) ?: return@withContext null
        try {
            val from = Pcm.offsetOf(startMs, rate, pcm.size)
            val to = Pcm.offsetOf(endMs, rate, pcm.size)
            if ((to - from) * 1_000L / (rate * 2) < ClipPolicy.MIN_DURATION_MS) return@withContext null
            val part = pcm.copyOfRange(from, to)
            val updated = try {
                store.replaceAudio(id, part, part.size, rate)
            } finally {
                part.fill(0)
            }
            _clips.value = store.list()
            updated
        } finally {
            pcm.fill(0)
        }
    }

    suspend fun pcm(id: String): Pair<ByteArray, Int>? = withContext(Dispatchers.IO) { store.readPcm(id) }

    /** The recording as a WAV file (decrypted) for playing, sharing or exporting. Wipe it after use. */
    suspend fun wav(id: String): ByteArray? = withContext(Dispatchers.IO) { store.readWav(id) }

    suspend fun pendingRetries(): List<SavedClip> = withContext(Dispatchers.IO) {
        store.list().filter { it.retryWhenOnline && it.status == ClipStatus.UNIDENTIFIED }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        store.delete(id)
        _clips.value = store.list()
    }

    suspend fun deleteMany(ids: Collection<String>) = withContext(Dispatchers.IO) {
        ids.forEach { store.delete(it) }
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

    /** Encrypts recordings that older versions stored as plain WAV. */
    suspend fun sealLegacy(): Int = withContext(Dispatchers.IO) { store.sealLegacy() }

    data class Identified(val recognitionId: String, val trackId: String, val title: String, val artist: String)

    private companion object {
        const val MAX_ATTEMPTS = 10
    }
}
