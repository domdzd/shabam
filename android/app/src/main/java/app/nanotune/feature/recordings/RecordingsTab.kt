package app.nanotune.feature.recordings

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nanotune.domain.model.ClipReason
import app.nanotune.domain.model.ClipStatus
import app.nanotune.domain.model.SavedClip
import app.nanotune.feature.common.Formats
import app.nanotune.feature.common.InfoBanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Plays one recording at a time from memory (recordings may be encrypted on disk). */
class ClipPlayer {
    var playingId by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    private var player: MediaPlayer? = null
    private var stopAtMs: Long? = null

    /** Starts [wav] (owned by the player from now on and wiped when it stops). */
    fun play(id: String, wav: ByteArray, fromMs: Long = 0, untilMs: Long? = null) {
        stop()
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            mediaPlayer.setDataSource(BytesDataSource(wav))
            mediaPlayer.setOnCompletionListener { stop() }
            mediaPlayer.prepare()
            if (fromMs > 0) mediaPlayer.seekTo(fromMs.toInt())
            mediaPlayer.start()
            player = mediaPlayer
            playingId = id
            isPlaying = true
            durationMs = mediaPlayer.duration.toLong().coerceAtLeast(0)
            positionMs = fromMs
            stopAtMs = untilMs
        } catch (e: Exception) {
            mediaPlayer.release()
            wav.fill(0)
        }
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            isPlaying = false
        } else {
            p.start()
            isPlaying = true
        }
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.toInt())
        positionMs = ms
    }

    /** Called periodically while playing to update the position (and stop at the end of a selection). */
    fun tick() {
        val p = player ?: return
        positionMs = runCatching { p.currentPosition.toLong() }.getOrDefault(positionMs)
        stopAtMs?.let { if (positionMs >= it) stop() }
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        playingId = null
        isPlaying = false
        positionMs = 0
        stopAtMs = null
    }
}

/** Feeds MediaPlayer from memory; wipes the bytes when the player is released. */
private class BytesDataSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(size.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() {
        bytes.fill(0)
    }
}

private enum class Filter(val label: String) { ALL("All"), UNIDENTIFIED("Not identified"), IDENTIFIED("Identified") }

@Composable
fun RecordingsTab(viewModel: RecordingsViewModel, onOpenTrack: (String) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val canIdentify by viewModel.canIdentify.collectAsStateWithLifecycle()
    val player = remember { ClipPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    LaunchedEffect(player.playingId, player.isPlaying) {
        while (player.isPlaying) {
            player.tick()
            delay(100)
        }
    }

    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var selection by rememberSaveable { mutableStateOf(setOf<String>()) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var trimId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(context, uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val id = exportId
        exportId = null
        if (uri != null && id != null) viewModel.export(context, id, uri)
    }
    val play: (SavedClip, Long, Long?) -> Unit = { clip, from, until ->
        if (player.playingId == clip.id && until == null && from == 0L) {
            player.toggle()
        } else {
            scope.launch {
                val wav = viewModel.wav(clip.id)
                if (wav != null) player.play(clip.id, wav, from, until)
            }
        }
    }
    val byId = clips.associateBy { it.id }
    // Forget selections and dialogs for recordings that are gone.
    LaunchedEffect(clips) {
        selection = selection.filterTo(mutableSetOf()) { it in byId }
        if (openId != null && openId !in byId) openId = null
        if (renameId != null && renameId !in byId) renameId = null
        if (trimId != null && trimId !in byId) trimId = null
        if (player.playingId != null && player.playingId !in byId) player.stop()
    }

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
    if (confirmDelete.isNotEmpty()) {
        val count = confirmDelete.size
        AlertDialog(
            onDismissRequest = { confirmDelete = emptySet() },
            title = { Text(if (count == 1) "Delete this recording?" else "Delete $count recordings?") },
            text = { Text("The audio is removed from this phone. Songs already identified stay in your history.") },
            confirmButton = {
                TextButton(onClick = {
                    if (player.playingId in confirmDelete) player.stop()
                    viewModel.deleteMany(confirmDelete)
                    selection = selection - confirmDelete
                    if (openId in confirmDelete) openId = null
                    confirmDelete = emptySet()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = emptySet() }) { Text("Cancel") } },
        )
    }
    renameId?.let { id ->
        byId[id]?.let { clip ->
            RenameDialog(
                initial = clip.label ?: clip.displayName.orEmpty(),
                onDismiss = { renameId = null },
                onSave = { label ->
                    viewModel.rename(id, label)
                    renameId = null
                },
            )
        }
    }
    trimId?.let { id ->
        byId[id]?.let { clip ->
            TrimDialog(
                clip = clip,
                canIdentify = canIdentify,
                player = player,
                onPlay = { from, until -> play(clip, from, until) },
                onDismiss = {
                    player.stop()
                    trimId = null
                },
                onIdentify = { from, until ->
                    player.stop()
                    trimId = null
                    viewModel.identify(id, from, until)
                },
                onKeep = { from, until ->
                    player.stop()
                    trimId = null
                    viewModel.trim(id, from, until)
                },
            )
        }
    }
    openId?.let { id ->
        byId[id]?.let { clip ->
            RecordingSheet(
                clip = clip,
                player = player,
                busy = id in busy,
                canIdentify = canIdentify,
                onDismiss = { openId = null },
                onPlay = { play(clip, 0L, null) },
                onIdentify = { viewModel.identify(id) },
                onTrim = { trimId = id },
                onRename = { renameId = id },
                onShare = { viewModel.share(context, listOf(clip)) },
                onExport = {
                    exportId = id
                    exportLauncher.launch(RecordingsViewModel.fileName(clip))
                },
                onDelete = { confirmDelete = setOf(id) },
                onOpenTrack = { clip.trackId?.let(onOpenTrack) },
            )
        }
    }

    val unidentified = clips.count { it.status == ClipStatus.UNIDENTIFIED }
    val visible = when (filter) {
        Filter.ALL -> clips
        Filter.UNIDENTIFIED -> clips.filter { it.status == ClipStatus.UNIDENTIFIED }
        Filter.IDENTIFIED -> clips.filter { it.status == ClipStatus.IDENTIFIED }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item {
            if (selection.isNotEmpty()) {
                SelectionBar(
                    count = selection.size,
                    onClose = { selection = emptySet() },
                    onShare = { viewModel.share(context, clips.filter { it.id in selection }) },
                    onDelete = { confirmDelete = selection },
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val totalMb = clips.sumOf { it.sizeBytes } / (1024.0 * 1024.0)
                    Text(
                        if (clips.isEmpty()) {
                            "No recordings"
                        } else {
                            "${clips.size} ${if (clips.size == 1) "recording" else "recordings"} · ${String.format(Locale.ROOT, "%.1f", totalMb)} MB"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { importLauncher.launch(arrayOf("audio/*", "video/*")) }, enabled = !importing) {
                        Icon(Icons.Filled.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Identify all not identified ($unidentified)") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                enabled = canIdentify && unidentified > 0,
                                onClick = {
                                    menu = false
                                    viewModel.identifyAll()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete all") },
                                leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
                                enabled = clips.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    confirmDeleteAll = true
                                },
                            )
                        }
                    }
                }
            }
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        if (clips.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (settings.saveRecordings) {
                        InfoBanner(
                            icon = Icons.Filled.GraphicEq,
                            title = "No recordings yet",
                            body = "Everything you record with the Identify button appears here, so you can play it, share it, " +
                                "rename or trim it, or identify it again. You can also import an audio or video file.",
                        )
                    } else {
                        InfoBanner(
                            icon = Icons.Filled.GraphicEq,
                            title = "Saved recordings are off",
                            body = "Turn on Settings › Recordings to keep what the Identify button records. You can still import " +
                                "an audio or video file here.",
                            action = { TextButton(onClick = onOpenSettings) { Text("Open settings") } },
                        )
                    }
                }
            }
            return@LazyColumn
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Filter.entries.forEach { option ->
                    val count = when (option) {
                        Filter.ALL -> clips.size
                        Filter.UNIDENTIFIED -> unidentified
                        Filter.IDENTIFIED -> clips.size - unidentified
                    }
                    FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text("${option.label} ($count)") })
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                Text(
                    "Nothing here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(visible, key = { it.id }) { clip ->
            RecordingRow(
                clip = clip,
                playing = player.playingId == clip.id && player.isPlaying,
                busy = clip.id in busy,
                canIdentify = canIdentify,
                selecting = selection.isNotEmpty(),
                selected = clip.id in selection,
                onClick = {
                    if (selection.isNotEmpty()) {
                        selection = if (clip.id in selection) selection - clip.id else selection + clip.id
                    } else {
                        openId = clip.id
                    }
                },
                onLongClick = { selection = selection + clip.id },
                onPlay = { play(clip, 0L, null) },
                onIdentify = { viewModel.identify(clip.id) },
            )
            HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel selection") }
            Text("$count selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share selected") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete selected") }
        }
    }
}

@Composable
private fun RecordingRow(
    clip: SavedClip,
    playing: Boolean,
    busy: Boolean,
    canIdentify: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
    onIdentify: () -> Unit,
) {
    val identified = clip.status == ClipStatus.IDENTIFIED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            FilledTonalIconButton(onClick = onPlay) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play recording")
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                clip.displayName ?: "Recording · ${Formats.dateTime(clip.createdAt)}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (clip.displayName != null) Formats.dateTime(clip.createdAt) + " · " else "") +
                    Formats.duration(clip.durationMs) + " · " + statusLine(clip),
                style = MaterialTheme.typography.bodySmall,
                color = if (identified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            selecting -> Unit
            busy -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
            identified -> Icon(Icons.Filled.CheckCircle, contentDescription = "Identified", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
            clip.retryWhenOnline -> Icon(Icons.Filled.CloudQueue, contentDescription = "Waiting for a connection", modifier = Modifier.padding(12.dp))
            else -> TextButton(onClick = onIdentify, enabled = canIdentify) { Text("Identify") }
        }
    }
}

@Composable
private fun RecordingSheet(
    clip: SavedClip,
    player: ClipPlayer,
    busy: Boolean,
    canIdentify: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onIdentify: () -> Unit,
    onTrim: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onOpenTrack: () -> Unit,
) {
    val identified = clip.status == ClipStatus.IDENTIFIED
    val active = player.playingId == clip.id
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(clip.displayName ?: "Recording", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${Formats.dateTime(clip.createdAt)} · ${Formats.duration(clip.durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                statusLine(clip),
                style = MaterialTheme.typography.bodyMedium,
                color = if (identified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onPlay) {
                    Icon(if (active && player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (active && player.isPlaying) "Pause" else "Play")
                }
                Spacer(Modifier.width(8.dp))
                val duration = (if (active && player.durationMs > 0) player.durationMs else clip.durationMs).coerceAtLeast(1)
                Slider(
                    value = if (active) player.positionMs.coerceIn(0, duration).toFloat() else 0f,
                    onValueChange = { if (active) player.seekTo(it.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                    enabled = active,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${Formats.duration(if (active) player.positionMs else 0)} / ${Formats.duration(clip.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (identified && clip.trackId != null) {
                    AssistChip(onClick = onOpenTrack, label = { Text("Open song") }, leadingIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) })
                }
                if (!identified) {
                    AssistChip(
                        onClick = onIdentify,
                        enabled = canIdentify && !busy,
                        label = { Text(if (busy) "Identifying…" else "Identify") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    )
                }
                AssistChip(onClick = onTrim, label = { Text("Trim") }, leadingIcon = { Icon(Icons.Filled.ContentCut, contentDescription = null) })
                AssistChip(onClick = onRename, label = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) })
                AssistChip(onClick = onShare, label = { Text("Share") }, leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) })
                AssistChip(onClick = onExport, label = { Text("Save to device") }, leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) })
                AssistChip(onClick = onDelete, label = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) })
            }
            if (!identified && !canIdentify) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "No recognition engine is available right now. Set one up in Settings to identify this recording.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (clip.attempts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Attempts", style = MaterialTheme.typography.labelLarge)
                clip.attempts.takeLast(5).reversed().forEach { attempt ->
                    Text(
                        "${Formats.dateTime(attempt.at)} · ${attempt.engine}: ${outcomeLabel(attempt.outcome)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename recording") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(80) },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim().ifEmpty { null }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TrimDialog(
    clip: SavedClip,
    canIdentify: Boolean,
    player: ClipPlayer,
    onPlay: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
    onIdentify: (Long, Long) -> Unit,
    onKeep: (Long, Long) -> Unit,
) {
    val total = clip.durationMs.coerceAtLeast(1).toFloat()
    var range by remember(clip.id) { mutableStateOf(0f..total) }
    val start = range.start.toLong()
    val end = range.endInclusive.toLong()
    val longEnough = end - start >= 3_000
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trim") },
        text = {
            Column {
                Text("Pick the part with the music. Engines listen to at most 12 seconds of it.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..total)
                Text(
                    "${Formats.duration(start)} – ${Formats.duration(end)} (${Formats.duration(end - start)})" +
                        if (longEnough) "" else " · at least 3 seconds",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (longEnough) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                val previewing = player.playingId == clip.id && player.isPlaying
                TextButton(onClick = { if (previewing) player.stop() else onPlay(start, end) }) {
                    Icon(if (previewing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (previewing) "Stop" else "Play this part")
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onIdentify(start, end) }, enabled = longEnough && canIdentify) { Text("Identify part") }
                TextButton(onClick = { onKeep(start, end) }, enabled = longEnough && (start > 0 || end < clip.durationMs)) { Text("Keep part") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun outcomeLabel(outcome: String): String = when (outcome) {
    "match" -> "match"
    "no_match" -> "no match"
    else -> outcome.replace('_', ' ')
}

private fun statusLine(clip: SavedClip): String = when {
    clip.status == ClipStatus.IDENTIFIED -> "Identified: ${clip.title} — ${clip.artist}"
    clip.retryWhenOnline -> "Saved offline · will be identified when you're back online"
    clip.attempts.isNotEmpty() -> "Not identified · tried " + clip.attempts.takeLast(3).joinToString(", ") {
        "${it.engine} (${outcomeLabel(it.outcome)})"
    }
    clip.reason == ClipReason.IMPORTED -> "Imported · not identified yet"
    clip.reason == ClipReason.NO_ENGINE -> "Saved without an engine · not identified yet"
    clip.reason == ClipReason.MANUAL -> "Saved by you · not identified yet"
    else -> "Not identified yet"
}
