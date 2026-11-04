package app.nanotune.data.clips

import app.nanotune.core.audio.Pcm
import app.nanotune.domain.model.ClipAttempt
import app.nanotune.domain.model.ClipReason
import app.nanotune.domain.model.ClipStatus
import app.nanotune.domain.model.SavedClip
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Saved recordings as plain files in app-private storage: `<id>.wav` (16-bit mono PCM) plus a
 * small `<id>.json` with metadata. Files are written to a temporary name and renamed, so a crash
 * never leaves a half-written clip behind. Pure JVM code so it is unit-tested without a device.
 */
class ClipStore(
    private val directory: File,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()

    fun list(): List<SavedClip> = synchronized(lock) {
        directory.listFiles { file -> file.name.endsWith(META) }.orEmpty()
            .mapNotNull { file -> readMeta(file)?.takeIf { File(directory, it.id + AUDIO).isFile } }
            .map { it.toDomain() }
            .sortedByDescending { it.createdAt }
    }

    fun get(id: String): SavedClip? = synchronized(lock) { readMeta(metaFile(id))?.toDomain() }

    /** Stores [length] bytes of mono PCM16 at [sampleRateHz]. */
    fun save(
        pcm: ByteArray,
        length: Int,
        sampleRateHz: Int,
        reason: ClipReason,
        status: ClipStatus = ClipStatus.UNIDENTIFIED,
        retryWhenOnline: Boolean = false,
        recognitionId: String? = null,
        trackId: String? = null,
        title: String? = null,
        artist: String? = null,
    ): SavedClip = synchronized(lock) {
        require(length in 2..pcm.size && length % 2 == 0) { "invalid PCM length" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create ${directory.path}")
        val id = sanitize(newId())
        val wav = Pcm.wav(pcm, length, sampleRateHz)
        writeAtomically(audioFile(id), wav)
        wav.fill(0)
        val meta = ClipMeta(
            id = id,
            createdAt = clock(),
            durationMs = length * 1_000L / (sampleRateHz * 2),
            sampleRateHz = sampleRateHz,
            sizeBytes = 44L + length,
            reason = reason.name,
            status = status.name,
            recognitionId = recognitionId,
            trackId = trackId,
            title = title,
            artist = artist,
            retryWhenOnline = retryWhenOnline,
        )
        writeMeta(meta)
        meta.toDomain()
    }

    fun update(id: String, transform: (SavedClip) -> SavedClip): SavedClip? = synchronized(lock) {
        val current = readMeta(metaFile(id))?.toDomain() ?: return null
        val updated = transform(current).copy(id = current.id, createdAt = current.createdAt)
        writeMeta(ClipMeta.from(updated))
        updated
    }

    /** Reads the PCM samples back (for "identify again"). */
    fun readPcm(id: String): Pair<ByteArray, Int>? = synchronized(lock) {
        val file = audioFile(id)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        val rate = (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or
            ((bytes[26].toInt() and 0xFF) shl 16) or ((bytes[27].toInt() and 0xFF) shl 24)
        bytes.copyOfRange(44, bytes.size) to rate
    }

    fun audioFile(id: String): File = File(directory, sanitize(id) + AUDIO)

    fun delete(id: String): Boolean = synchronized(lock) {
        val audio = audioFile(id).delete()
        val meta = metaFile(id).delete()
        audio || meta
    }

    fun deleteAll(): Int = synchronized(lock) {
        val files = directory.listFiles().orEmpty()
        files.count { it.delete() && it.name.endsWith(AUDIO) }
    }

    fun totalBytes(): Long = synchronized(lock) {
        directory.listFiles().orEmpty().sumOf { it.length() }
    }

    /**
     * Deletes clips older than [maxAgeMs] (when > 0) and then the oldest clips until the total size
     * is at most [maxTotalBytes]. Returns the number of clips removed.
     */
    fun purge(maxAgeMs: Long, maxTotalBytes: Long): Int = synchronized(lock) {
        var removed = 0
        val now = clock()
        val clips = list().toMutableList()
        if (maxAgeMs > 0) {
            clips.filter { now - it.createdAt > maxAgeMs }.forEach {
                if (delete(it.id)) removed++
                clips.remove(it)
            }
        }
        var total = clips.sumOf { it.sizeBytes }
        for (clip in clips.sortedBy { it.createdAt }) {
            if (total <= maxTotalBytes) break
            if (delete(clip.id)) {
                removed++
                total -= clip.sizeBytes
            }
        }
        // Remove leftovers from interrupted writes.
        directory.listFiles { file -> file.name.endsWith(TMP) }.orEmpty().forEach { it.delete() }
        removed
    }

    private fun metaFile(id: String) = File(directory, sanitize(id) + META)

    private fun readMeta(file: File): ClipMeta? = runCatching {
        if (!file.isFile) return null
        json.decodeFromString(ClipMeta.serializer(), file.readText())
    }.getOrNull()

    private fun writeMeta(meta: ClipMeta) {
        writeAtomically(metaFile(meta.id), json.encodeToString(ClipMeta.serializer(), meta).toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + TMP)
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("Cannot write ${target.name}")
            }
        }
    }

    /** IDs become file names; never let them escape the directory. */
    private fun sanitize(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' }.take(64).ifEmpty { "clip" }

    @Serializable
    private data class AttemptMeta(val at: Long, val engine: String, val outcome: String)

    @Serializable
    private data class ClipMeta(
        val id: String,
        @SerialName("created_at") val createdAt: Long,
        @SerialName("duration_ms") val durationMs: Long,
        @SerialName("sample_rate") val sampleRateHz: Int,
        @SerialName("size_bytes") val sizeBytes: Long,
        val reason: String,
        val status: String,
        @SerialName("recognition_id") val recognitionId: String? = null,
        @SerialName("track_id") val trackId: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val attempts: List<AttemptMeta> = emptyList(),
        @SerialName("retry_when_online") val retryWhenOnline: Boolean = false,
    ) {
        fun toDomain() = SavedClip(
            id = id,
            createdAt = createdAt,
            durationMs = durationMs,
            sampleRateHz = sampleRateHz,
            sizeBytes = sizeBytes,
            reason = runCatching { ClipReason.valueOf(reason) }.getOrDefault(ClipReason.MANUAL),
            status = runCatching { ClipStatus.valueOf(status) }.getOrDefault(ClipStatus.UNIDENTIFIED),
            recognitionId = recognitionId,
            trackId = trackId,
            title = title,
            artist = artist,
            attempts = attempts.map { ClipAttempt(it.at, it.engine, it.outcome) },
            retryWhenOnline = retryWhenOnline,
        )

        companion object {
            fun from(clip: SavedClip) = ClipMeta(
                id = clip.id,
                createdAt = clip.createdAt,
                durationMs = clip.durationMs,
                sampleRateHz = clip.sampleRateHz,
                sizeBytes = clip.sizeBytes,
                reason = clip.reason.name,
                status = clip.status.name,
                recognitionId = clip.recognitionId,
                trackId = clip.trackId,
                title = clip.title,
                artist = clip.artist,
                attempts = clip.attempts.map { AttemptMeta(it.at, it.engine, it.outcome) },
                retryWhenOnline = clip.retryWhenOnline,
            )
        }
    }

    private companion object {
        const val AUDIO = ".wav"
        const val META = ".json"
        const val TMP = ".tmp"
    }
}
