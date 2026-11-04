package app.nanotune.feature.listening

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.nanotune.AppContainer
import app.nanotune.domain.model.HistoryEntry
import app.nanotune.feature.common.Artwork
import app.nanotune.feature.common.Formats
import app.nanotune.feature.common.InfoBanner
import app.nanotune.service.SessionController
import app.nanotune.service.SessionStatus
import app.nanotune.service.SessionUiState
import app.nanotune.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SessionViewModel(container: AppContainer) : ViewModel() {
    val state: StateFlow<SessionUiState> = container.sessionState.state

    val entries: StateFlow<List<HistoryEntry>> = state
        .map { it.sessionId }
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else container.historyRepository.observeSessionEntries(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val engineBundled: Boolean = container.recognitionEngine.engineBundled
}

@Composable
fun SessionScreen(viewModel: SessionViewModel, onBack: () -> Unit, onOpenTrack: (String) -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var notificationsDenied by rememberSaveable { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) SessionController.start(context)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsDenied = !granted
        // The session still works without the notification permission (it then only shows in
        // the system's active-apps list), and can always be stopped from this screen.
        if (hasMic(context)) SessionController.start(context) else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    val startSession: () -> Unit = {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            !hasMic(context) -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            else -> SessionController.start(context)
        }
    }

    // Ticks once a second so the elapsed time stays live.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.active) {
        while (state.active) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Listening session") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(state, now)
            }
            item {
                Controls(
                    state = state,
                    onStart = startSession,
                    onPause = { SessionController.pause(context) },
                    onResume = { SessionController.resume(context) },
                    onStop = { SessionController.stop(context) },
                )
            }
            if (notificationsDenied && state.active) {
                item {
                    InfoBanner(
                        icon = Icons.Filled.FiberManualRecord,
                        title = "Notifications are off",
                        body = "Android hides the session notification, but the microphone is still in use. Stop the session here when you're done.",
                    )
                }
            }
            if (!viewModel.engineBundled) {
                item {
                    InfoBanner(
                        icon = Icons.Filled.GraphicEq,
                        title = "Recognition engine not included",
                        body = "This build has no ShazamKit engine, so a session can't identify songs. See the README to build with it.",
                    )
                }
            }
            if (!state.active && state.status != SessionStatus.ERROR) {
                item {
                    Text(
                        "A session keeps the microphone on while its notification is visible and adds each new song to your " +
                            "history once (repeats within the cooldown are skipped). Android may stop it to save battery or " +
                            "if you revoke microphone access; it never restarts on its own or after a reboot. Interval mode " +
                            "(Settings) listens in short windows to save battery and data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entries.isNotEmpty()) {
                item {
                    Text("Found in this session", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                items(entries, key = { it.recognitionId }) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenTrack(entry.trackId) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(entry.track.artworkUrl, contentDescription = null, size = 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(entry.track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(Formats.time(entry.recognizedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun hasMic(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@Composable
private fun StatusCard(state: SessionUiState, now: Long) {
    val micOn = state.microphoneOpen
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                    tint = if (micOn) Brand.Live else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(statusTitle(state), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column {
                    Text("Duration", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val started = state.startedAt
                    Text(if (state.active && started != null) Formats.duration(now - started) else "—", style = MaterialTheme.typography.headlineSmall)
                }
                Column {
                    Text("Tracks found", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.tracksFound.toString(), style = MaterialTheme.typography.headlineSmall)
                }
            }
            if (state.status == SessionStatus.LISTENING) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { state.level }, modifier = Modifier.fillMaxWidth())
            }
            val next = state.nextWindowAt
            if (state.status == SessionStatus.WAITING_NEXT_WINDOW && next != null) {
                Spacer(Modifier.height(8.dp))
                Text("Next listening window in ${Formats.duration((next - now).coerceAtLeast(0))}", style = MaterialTheme.typography.bodyMedium)
            }
            state.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (state.status == SessionStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.lastTrack?.let {
                Spacer(Modifier.height(8.dp))
                Text("Last: ${it.title} — ${it.artist}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun statusTitle(state: SessionUiState): String = when (state.status) {
    SessionStatus.IDLE -> "Not running"
    SessionStatus.STARTING -> "Starting…"
    SessionStatus.LISTENING -> "Listening — microphone on"
    SessionStatus.WAITING_NEXT_WINDOW -> "Resting between windows — microphone off"
    SessionStatus.PAUSED -> "Paused — microphone off"
    SessionStatus.WAITING_FOR_NETWORK -> "Waiting for network — microphone off"
    SessionStatus.MIC_BUSY -> "Microphone busy"
    SessionStatus.RETRYING -> "Reconnecting…"
    SessionStatus.ERROR -> "Session stopped"
}

@Composable
private fun Controls(state: SessionUiState, onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (!state.active) {
            Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Start session")
            }
        } else {
            if (state.status == SessionStatus.PAUSED) {
                FilledTonalButton(onClick = onResume, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Resume")
                }
            } else {
                FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Pause, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pause")
                }
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Live),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Stop")
            }
        }
    }
}
