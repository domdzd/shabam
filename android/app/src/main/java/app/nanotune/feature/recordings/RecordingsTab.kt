package app.nanotune.feature.recordings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.nanotune.AppContainer
import app.nanotune.data.settings.AppSettings
import app.nanotune.domain.model.ClipReason
import app.nanotune.domain.model.ClipStatus
import app.nanotune.domain.model.SavedClip
import app.nanotune.feature.common.Formats
import app.nanotune.feature.common.InfoBanner
import app.nanotune.recognition.ClipRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class RecordingsViewModel(private val container: AppContainer) : ViewModel() {
    val clips: StateFlow<List<SavedClip>> = container.clipRepository.clips
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Whether "Identify again" has any engine to use. */
    val canIdentify: StateFlow<Boolean> = combine(container.serverRepository.state, container.settingsRepository.settings) { _, _ ->
        container.primaryProvider != null || container.enhancedGate.primaryEligible()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.primaryProvider != null)

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { container.clipRepository.refresh() }
    }

    fun identify(id: String) {
        if (id in _busy.value) return
        viewModelScope.launch {
            _busy.update { it + id }
            _message.value = when (val result = container.clipRecognizer.identify(id)) {
                is ClipRecognizer.Result.Identified -> "Found it: ${result.track.title} — ${result.track.artist} (${result.engine})"
                ClipRecognizer.Result.NoMatch -> "Still no match for this recording."
                is ClipRecognizer.Result.Failed -> result.message
                is ClipRecognizer.Result.NoEngine -> result.message
            }
            _busy.update { it - id }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { container.clipRepository.delete(id) }
    }

    fun deleteAll() {
        viewModelScope.launch {
            container.clipRepository.deleteAll()
            _message.value = "All recordings deleted."
        }
    }

    fun file(id: String): File = container.clipRepository.file(id)

    fun consumeMessage() {
        _message.value = null
    }
}

/** Plays one saved clip at a time; released when the screen goes away. */
class ClipPlayer {
    var playingId by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null

    fun toggle(id: String, file: File) {
        if (playingId == id) {
            stop()
            return
        }
        stop()
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setOnCompletionListener { stop() }
            mediaPlayer.prepare()
            mediaPlayer.start()
            player = mediaPlayer
            playingId = id
        } catch (e: Exception) {
            mediaPlayer.release()
        }
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        playingId = null
    }
}

fun shareRecording(context: Context, file: File, clip: SavedClip) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val label = clip.title?.let { "$it — ${clip.artist}" } ?: "Recording from ${Formats.dateTime(clip.createdAt)}"
    val intent = Intent(Intent.ACTION_SEND)
        .setType("audio/wav")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, label)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(label, uri)
    context.startActivity(Intent.createChooser(intent, "Share recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun RecordingsTab(viewModel: RecordingsViewModel, onOpenTrack: (String) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val canIdentify by viewModel.canIdentify.collectAsStateWithLifecycle()
    val player = remember { ClipPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all recordings?") },
            text = { Text("The audio files are removed from this phone. Songs already identified stay in your history.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    player.stop()
                    viewModel.deleteAll()
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }

    if (clips.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            if (settings.saveRecordings) {
                InfoBanner(
                    icon = Icons.Filled.GraphicEq,
                    title = "No recordings yet",
                    body = "When a song can't be identified, its recording appears here so you can play it, share it or try again later.",
                )
            } else {
                InfoBanner(
                    icon = Icons.Filled.GraphicEq,
                    title = "Saved recordings are off",
                    body = "Turn on Settings › Recordings to keep the audio of songs that couldn't be identified (or every identification). " +
                        "You can then play it, share it, or identify it later — automatically when you're back online, or with another engine.",
                    action = { TextButton(onClick = onOpenSettings) { Text("Open settings") } },
                )
            }
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val totalMb = clips.sumOf { it.sizeBytes } / (1024.0 * 1024.0)
                Text(
                    "${clips.size} ${if (clips.size == 1) "recording" else "recordings"} · ${String.format(Locale.ROOT, "%.1f", totalMb)} MB on this phone",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirmDeleteAll = true }) { Text("Delete all") }
            }
        }
        items(clips, key = { it.id }) { clip ->
            RecordingRow(
                clip = clip,
                playing = player.playingId == clip.id,
                busy = clip.id in busy,
                canIdentify = canIdentify,
                onPlay = { player.toggle(clip.id, viewModel.file(clip.id)) },
                onIdentify = { viewModel.identify(clip.id) },
                onShare = { shareRecording(context, viewModel.file(clip.id), clip) },
                onDelete = {
                    if (player.playingId == clip.id) player.stop()
                    viewModel.delete(clip.id)
                },
                onOpenTrack = onOpenTrack,
            )
        }
    }
}

@Composable
private fun RecordingRow(
    clip: SavedClip,
    playing: Boolean,
    busy: Boolean,
    canIdentify: Boolean,
    onPlay: () -> Unit,
    onIdentify: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onOpenTrack: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val identified = clip.status == ClipStatus.IDENTIFIED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = identified && clip.trackId != null) { clip.trackId?.let(onOpenTrack) }
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onPlay) {
            Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = if (playing) "Stop" else "Play recording")
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${Formats.dateTime(clip.createdAt)} · ${Formats.duration(clip.durationMs)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                statusLine(clip),
                style = MaterialTheme.typography.bodySmall,
                color = if (identified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            busy -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
            identified -> Icon(Icons.Filled.CheckCircle, contentDescription = "Identified", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
            clip.retryWhenOnline -> Icon(Icons.Filled.CloudQueue, contentDescription = "Waiting for a connection", modifier = Modifier.padding(12.dp))
            else -> TextButton(onClick = onIdentify, enabled = canIdentify) { Text("Identify") }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!identified && clip.retryWhenOnline) {
                    DropdownMenuItem(text = { Text("Identify now") }, onClick = { menu = false; onIdentify() }, enabled = canIdentify)
                }
                DropdownMenuItem(
                    text = { Text("Share audio") },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    onClick = { menu = false; onShare() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

private fun statusLine(clip: SavedClip): String = when {
    clip.status == ClipStatus.IDENTIFIED -> "Identified: ${clip.title} — ${clip.artist}"
    clip.retryWhenOnline -> "Saved offline · will be identified when you're back online"
    clip.attempts.isNotEmpty() -> "Not identified · tried " + clip.attempts.takeLast(3).joinToString(", ") {
        "${it.engine} (${if (it.outcome == "no_match") "no match" else it.outcome.replace('_', ' ')})"
    }
    clip.reason == ClipReason.MANUAL -> "Saved by you · not identified yet"
    else -> "Not identified yet"
}
