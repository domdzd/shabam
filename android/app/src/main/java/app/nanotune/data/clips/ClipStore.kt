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
 * Saved recordings as files in app-private storage: the audio (`<id>.wav`, or `<id>` plus the
 * [codec]'s extension when encrypted) and a small `<id>.json` with metadata. Files are written to a
 * temporary name and renamed, so a crash never leaves a half-written recording behind. If the codec
 * fails (e.g. a broken Keystore) the audio is stored as plain WAV rather than lost. Pure JVM code so
 * it is unit-tested without a device.
 */
class ClipStore(
    private val directory: File,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val codec: ClipCodec = ClipCodec.Plain,
) {
    private val lock = Any()

    fun list(): List<SavedClip> = synchronized(lock) {
        directory.listFiles { file -> file.name.endsWith(META) }.orEmpty()
            .mapNotNull { file -> readMeta(file)?.takeIf { audioFileOf(it.id) != null } }
            .map { it.toDomain() }
            .sortedByDescending { it.createdAt }
    }

    fun get(id: String): SavedClip? = synchronized(lock) {
        readMeta(metaFile(id))?.takeIf { audioFileOf(it.id) != null }?.toDomain()
    }

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
        label: String? = null,
    ): SavedClip = synchronized(lock) {
        require(length in 2..pcm.size && length % 2 == 0) { "invalid PCM length" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create ${directory.path}")
        val id = sanitize(newId())
        writeAudio(id, pcm, length, sampleRateHz)
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
            label = label?.let { cleanLabel(it) },
        )
        writeMeta(meta)
        meta.toDomain()
    }

    fun update(id: String, transform: (SavedClip) -> SavedClip): SavedClip? = synchronized(lock) {
        val current = readMeta(metaFile(id))?.toDomain() ?: return null
        val changed = transform(current)
        val updated = changed.copy(
            id = current.id,
            createdAt = current.createdAt,
            durationMs = current.durationMs,
            sampleRateHz = current.sampleRateHz,
            sizeBytes = current.sizeBytes,
            label = changed.label?.let { cleanLabel(it) },
        )
        writeMeta(ClipMeta.from(updated))
        updated
    }

    /** The recording as a playable WAV file (decrypted), or null if it is missing or unreadable. */
    fun readWav(id: String): ByteArray? = synchronized(lock) {
        val file = audioFileOf(sanitize(id)) ?: return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val wav = if (file.name.endsWith(PLAIN_AUDIO)) bytes else runCatching { codec.open(bytes) }.getOrNull()
        wav?.takeIf { it.size >= 44 && String(it, 0, 4, Charsets.US_ASCII) == "RIFF" }
    }

    /** Reads the PCM samples back (for "identify again" and trimming). */
    fun readPcm(id: String): Pair<ByteArray, Int>? = synchronized(lock) {
        val wav = readWav(id) ?: return null
        val rate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or
            ((wav[26].toInt() and 0xFF) shl 16) or ((wav[27].toInt() and 0xFF) shl 24)
        val pcm = wav.copyOfRange(44, wav.size)
        wav.fill(0)
        pcm to rate
    }

    /** Replaces the audio (e.g. after trimming) and updates duration and size. */
    fun replaceAudio(id: String, pcm: ByteArray, length: Int, sampleRateHz: Int): SavedClip? = synchronized(lock) {
        require(length in 2..pcm.size && length % 2 == 0) { "invalid PCM length" }
        val current = readMeta(metaFile(id)) ?: return null
        val oldFile = audioFileOf(current.id)
        val written = writeAudio(current.id, pcm, length, sampleRateHz)
        if (oldFile != null && oldFile != written) oldFile.delete()
        val meta = current.copy(
            durationMs = length * 1_000L / (sampleRateHz * 2),
            sampleRateHz = sampleRateHz,
            sizeBytes = 44L + length,
        )
        writeMeta(meta)
        meta.toDomain()
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val clean = sanitize(id)
        val audio = audioFiles(clean).map { it.delete() }.any { it }
        val meta = metaFile(clean).delete()
        audio || meta
    }

    fun deleteAll(): Int = synchronized(lock) {
        val files = directory.listFiles().orEmpty()
        files.count { it.delete() && it.name.endsWith(META) }
    }

    fun totalBytes(): Long = synchronized(lock) {
        directory.listFiles().orEmpty().sumOf { it.length() }
    }

    /** Re-writes recordings stored as plain WAV with the [codec] (e.g. those saved by 0.2). */
    fun sealLegacy(): Int = synchronized(lock) {
        if (codec.extension == PLAIN_AUDIO) return 0
        var sealed = 0
        for (meta in directory.listFiles { file -> file.name.endsWith(META) }.orEmpty().mapNotNull { readMeta(it) }) {
            val plain = File(directory, meta.id + PLAIN_AUDIO)
            if (!plain.isFile) continue
            val wav = runCatching { plain.readBytes() }.getOrNull() ?: continue
            val ok = runCatching { writeAtomically(File(directory, meta.id + codec.extension), codec.seal(wav)) }.isSuccess
            wav.fill(0)
            if (ok) {
                plain.delete()
                sealed++
            }
        }
        sealed
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
        // Remove leftovers from interrupted writes and audio whose metadata is gone.
        directory.listFiles().orEmpty().forEach { file ->
            val isAudio = file.name.endsWith(PLAIN_AUDIO) || file.name.endsWith(codec.extension)
            if (file.name.endsWith(TMP) || (isAudio && !metaFile(file.name.substringBeforeLast('.')).isFile)) file.delete()
        }
        removed
    }

    private fun writeAudio(id: String, pcm: ByteArray, length: Int, sampleRateHz: Int): File {
        val wav = Pcm.wav(pcm, length, sampleRateHz)
        try {
            if (codec.extension != PLAIN_AUDIO) {
                val sealed = runCatching { codec.seal(wav) }.getOrNull()
                if (sealed != null) {
                    val target = File(directory, id + codec.extension)
                    writeAtomically(target, sealed)
                    File(directory, id + PLAIN_AUDIO).delete()
                    return target
                }
            }
            val target = File(directory, id + PLAIN_AUDIO)
            writeAtomically(target, wav)
            if (codec.extension != PLAIN_AUDIO) File(directory, id + codec.extension).delete()
            return target
        } finally {
            wav.fill(0)
        }
    }

    private fun audioFiles(id: String): List<File> =
        listOf(File(directory, id + codec.extension), File(directory, id + PLAIN_AUDIO)).distinct()

    private fun audioFileOf(id: String): File? = audioFiles(id).firstOrNull { it.isFile }

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

    private fun cleanLabel(label: String): String? = label.replace(Regex("\\s+"), " ").trim().take(MAX_LABEL).ifEmpty { null }

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
        val label: String? = null,
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
            label = label,
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
                label = clip.label,
            )
        }
    }

    private companion object {
        const val PLAIN_AUDIO = ".wav"
        const val META = ".json"
        const val TMP = ".tmp"
        const val MAX_LABEL = 80
    }
}
