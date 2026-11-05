package app.nanotune.feature.recordings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nanotune.AppContainer
import app.nanotune.core.audio.AudioFileDecoder
import app.nanotune.data.clips.ClipPolicy
import app.nanotune.data.settings.AppSettings
import app.nanotune.domain.model.CapturedClip
import app.nanotune.domain.model.ClipReason
import app.nanotune.domain.model.ClipStatus
import app.nanotune.domain.model.SavedClip
import app.nanotune.feature.common.Formats
import app.nanotune.recognition.ClipRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecordingsViewModel(private val container: AppContainer) : ViewModel() {
    val clips: StateFlow<List<SavedClip>> = container.clipRepository.clips
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Whether "Identify" has any engine to use. */
    val canIdentify: StateFlow<Boolean> = combine(container.serverRepository.state, container.settingsRepository.settings) { _, _ ->
        container.clipRecognizer.canIdentify()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.primaryProvider != null)

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { container.clipRepository.refresh() }
    }

    fun identify(id: String, fromMs: Long? = null, toMs: Long? = null) {
        if (id in _busy.value) return
        viewModelScope.launch {
            _busy.update { it + id }
            _message.value = describe(container.clipRecognizer.identify(id, fromMs, toMs))
            _busy.update { it - id }
        }
    }

    /** Tries every recording that isn't identified yet, one after another. */
    fun identifyAll() {
        val pending = clips.value.filter { it.status == ClipStatus.UNIDENTIFIED && it.id !in _busy.value }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            _busy.update { it + pending.map(SavedClip::id) }
            var found = 0
            var lastProblem: String? = null
            for (clip in pending) {
                when (val result = container.clipRecognizer.identify(clip.id)) {
                    is ClipRecognizer.Result.Identified -> found++
                    is ClipRecognizer.Result.NoEngine -> {
                        lastProblem = result.message
                        _busy.update { it - clip.id }
                        break
                    }
                    is ClipRecognizer.Result.Failed -> lastProblem = result.message
                    ClipRecognizer.Result.NoMatch -> Unit
                }
                _busy.update { it - clip.id }
            }
            _busy.update { it - pending.map(SavedClip::id).toSet() }
            _message.value = "Identified $found of ${pending.size} recordings." + (lastProblem?.let { " $it" } ?: "")
        }
    }

    fun rename(id: String, label: String?) {
        viewModelScope.launch { container.clipRepository.rename(id, label) }
    }

    fun trim(id: String, startMs: Long, endMs: Long) {
        viewModelScope.launch {
            _message.value = if (container.clipRepository.trim(id, startMs, endMs) != null) {
                "Recording trimmed to ${Formats.duration(endMs - startMs)}."
            } else {
                "Keep at least 3 seconds."
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { container.clipRepository.delete(id) }
    }

    fun deleteMany(ids: Collection<String>) {
        viewModelScope.launch {
            container.clipRepository.deleteMany(ids)
            _message.value = if (ids.size == 1) "Recording deleted." else "${ids.size} recordings deleted."
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            container.clipRepository.deleteAll()
            _message.value = "All recordings deleted."
        }
    }

    /** Decrypted WAV bytes for the player; the caller wipes them when done. */
    suspend fun wav(id: String): ByteArray? = container.clipRepository.wav(id)

    /** Shares one or more recordings as WAV files through the system share sheet. */
    fun share(context: Context, selected: List<SavedClip>) {
        if (selected.isEmpty()) return
        val app = context.applicationContext
        viewModelScope.launch {
            val uris = withContext(Dispatchers.IO) {
                val dir = sharedDir(app)
                dir.listFiles().orEmpty().forEach { it.delete() } // previous shares
                selected.mapNotNull { clip ->
                    val wav = container.clipRepository.wav(clip.id) ?: return@mapNotNull null
                    val file = File(dir, fileName(clip))
                    try {
                        file.writeBytes(wav)
                    } finally {
                        wav.fill(0)
                    }
                    FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                }
            }
            if (uris.isEmpty()) {
                _message.value = "The recording file is missing."
                return@launch
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            intent.setType("audio/wav").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(fileName(selected.first()), uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            selected.singleOrNull()?.displayName?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
            runCatching {
                app.startActivity(Intent.createChooser(intent, "Share recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { _message.value = "No app on this phone can receive audio files." }
        }
    }

    /** Writes the recording as a WAV file to a place the user picked (system file picker). */
    fun export(context: Context, id: String, target: Uri) {
        val resolver = context.applicationContext.contentResolver
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val wav = container.clipRepository.wav(id) ?: return@withContext false
                try {
                    runCatching { resolver.openOutputStream(target)?.use { it.write(wav) } != null }.getOrDefault(false)
                } finally {
                    wav.fill(0)
                }
            }
            _message.value = if (ok) "Recording saved." else "Couldn't save the recording."
        }
    }

    /** Imports an audio (or video) file as a recording and identifies it if an engine is available. */
    fun import(context: Context, uri: Uri) {
        if (_importing.value) return
        val app = context.applicationContext
        viewModelScope.launch {
            _importing.value = true
            try {
                val name = withContext(Dispatchers.IO) { AudioFileDecoder.displayName(app, uri) }
                when (val decoded = withContext(Dispatchers.Default) { AudioFileDecoder.decode(app, uri, ClipPolicy.MAX_IMPORT_SECONDS) }) {
                    is AudioFileDecoder.Result.Failed -> _message.value = decoded.reason
                    is AudioFileDecoder.Result.Ok -> {
                        val saved = try {
                            container.clipRepository.save(
                                CapturedClip(decoded.pcm, decoded.pcm.size - decoded.pcm.size % 2, decoded.sampleRateHz),
                                ClipReason.IMPORTED,
                                label = name,
                            )
                        } finally {
                            decoded.pcm.fill(0)
                        }
                        if (saved == null) {
                            _message.value = "The file is too short (at least 3 seconds)."
                        } else {
                            val note = if (decoded.truncated) " (first ${ClipPolicy.MAX_IMPORT_SECONDS} s; trim to pick another part)" else ""
                            if (container.clipRecognizer.canIdentify()) {
                                _busy.update { it + saved.id }
                                _message.value = describe(container.clipRecognizer.identify(saved.id)) + note
                                _busy.update { it - saved.id }
                            } else {
                                _message.value = "Imported \"${saved.displayName ?: "audio file"}\"$note. Identify it once an engine is available."
                            }
                        }
                    }
                }
            } finally {
                _importing.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun describe(result: ClipRecognizer.Result): String = when (result) {
        is ClipRecognizer.Result.Identified -> "Found it: ${result.track.title} — ${result.track.artist} (${result.engine})"
        ClipRecognizer.Result.NoMatch -> "No match for this recording."
        is ClipRecognizer.Result.Failed -> result.message
        is ClipRecognizer.Result.NoEngine -> result.message
    }

    companion object {
        /** Cache folder for decrypted copies handed to other apps; cleared before each share and at start. */
        fun sharedDir(context: Context): File = File(context.cacheDir, "shared").apply { mkdirs() }

        fun fileName(clip: SavedClip): String {
            val base = (clip.displayName ?: "NanoTune recording ${Formats.dateTime(clip.createdAt)}")
                .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
                .trim()
                .take(60)
                .ifEmpty { "NanoTune recording" }
            return "$base.wav"
        }
    }
}
