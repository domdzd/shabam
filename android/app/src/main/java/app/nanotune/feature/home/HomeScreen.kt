package app.nanotune.feature.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nanotune.R
import app.nanotune.feature.common.Formats
import app.nanotune.feature.common.InfoBanner
import app.nanotune.feature.common.Links
import app.nanotune.feature.common.TrackResultCard
import app.nanotune.feature.common.findActivity
import app.nanotune.recognition.SetupHint
import app.nanotune.service.SessionStatus
import app.nanotune.ui.theme.Brand
import androidx.compose.ui.res.stringResource

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSession: () -> Unit,
    onOpenTrack: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favourite by viewModel.resultFavourite.collectAsStateWithLifecycle()
    val engine by viewModel.engineStatus.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()

    var showRationale by rememberSaveable { mutableStateOf(false) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedOnce = true
        if (granted) viewModel.identify()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.cancelBecauseBackgrounded() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshServer() }

    val listening = state.phase !is IdentifyPhase.Idle
    val onTap: () -> Unit = {
        when {
            listening -> viewModel.cancel()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> viewModel.identify()
            else -> showRationale = true
        }
    }

    if (showRationale) {
        val activity = context.findActivity()
        val permanentlyDenied = askedOnce && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        MicrophoneRationaleDialog(
            permanentlyDenied = permanentlyDenied,
            onContinue = {
                showRationale = false
                if (permanentlyDenied) Links.openAppSettings(context) else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDismiss = { showRationale = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            EngineChip(engine, onClick = onOpenSettings)
        }

        Spacer(Modifier.height(40.dp))
        ListenButton(
            listening = listening,
            level = (state.phase as? IdentifyPhase.Listening)?.level ?: 0f,
            onClick = onTap,
        )
        Spacer(Modifier.height(28.dp))

        PhaseText(state.phase)

        Spacer(Modifier.height(24.dp))
        if (session.active) {
            SessionRunningCard(
                tracks = session.tracksFound,
                paused = session.status == SessionStatus.PAUSED,
                onOpen = onOpenSession,
            )
        } else {
            OutlinedButton(onClick = onOpenSession, enabled = !listening) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Start a listening session")
            }
        }

        Spacer(Modifier.height(24.dp))
        AnimatedContent(
            targetState = state.result,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
            label = "result",
        ) { result ->
            when (result) {
                null -> if (engine != EngineStatus.READY && engine != EngineStatus.CHECKING && !listening) {
                    EngineSetupBanner(engine, onOpenSettings)
                } else {
                    Spacer(Modifier.height(1.dp))
                }
                is IdentifyResultUi.Matched -> TrackResultCard(
                    track = result.track,
                    providerLabel = result.providerLabel,
                    favourite = favourite,
                    onToggleFavourite = viewModel::toggleFavourite,
                    onOpenDetails = { onOpenTrack(result.trackId) },
                )
                IdentifyResultUi.NoMatch -> InfoBanner(
                    icon = Icons.Filled.SearchOff,
                    title = "No match found",
                    body = "Nothing in the catalogue matched what we heard. Move closer to the speaker, reduce background noise, or try again during a chorus. Live versions and DJ mixes often can't be matched.",
                    action = { TextButton(onClick = onTap) { Text("Try again") } },
                )
                is IdentifyResultUi.Problem -> ProblemBanner(result, onRetry = onTap, onOpenSettings = onOpenSettings)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ListenButton(listening: Boolean, level: Float, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val smoothedLevel by animateFloatAsState(targetValue = if (listening) level else 0f, animationSpec = tween(120), label = "level")
    val accent = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(280.dp)) {
        if (listening) {
            Canvas(Modifier.fillMaxSize()) {
                val base = size.minDimension / 2 * 0.62f
                for (ring in 0 until 3) {
                    val phase = (pulse + ring / 3f) % 1f
                    val radius = base + (size.minDimension / 2 - base) * phase
                    drawCircle(color = accent.copy(alpha = (1f - phase) * 0.45f), radius = radius, style = Stroke(width = 3.dp.toPx()))
                }
                drawCircle(color = accent.copy(alpha = 0.18f), radius = base * (1f + 0.25f * smoothedLevel))
            }
        }
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (listening) Brand.Live else accent,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(176.dp)
                .semantics { contentDescription = if (listening) "Stop listening" else "Tap to identify" },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(76.dp),
                )
            }
        }
    }
}

@Composable
private fun PhaseText(phase: IdentifyPhase) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        when (phase) {
            IdentifyPhase.Idle -> {
                Text("Tap to identify", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Listens only while you're on this screen. Nothing is recorded or stored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            IdentifyPhase.Preparing -> Text("Getting ready…", style = MaterialTheme.typography.titleLarge)
            is IdentifyPhase.Listening -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).padding(1.dp)) {
                        Canvas(Modifier.fillMaxSize()) { drawCircle(Brand.Live) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Listening · ${Formats.duration(phase.elapsedMs)}", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (phase.elapsedMs.toFloat() / phase.maxMs).coerceIn(0f, 1f) },
                    modifier = Modifier.width(200.dp),
                )
                if (phase.micSilenced) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.MicOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Another app is using the microphone", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            IdentifyPhase.SearchingEnhanced -> {
                Text("Checking additional engines…", style = MaterialTheme.typography.titleLarge)
                Text("Sending the clip to your NanoTune server", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EngineChip(status: EngineStatus, onClick: () -> Unit) {
    val label = when (status) {
        EngineStatus.READY -> "Engine ready"
        EngineStatus.CHECKING -> "Checking…"
        EngineStatus.NOT_BUNDLED -> "Engine not in this build"
        EngineStatus.NO_SERVER -> "Server not set"
        EngineStatus.SERVER_OFFLINE -> "Server offline"
        EngineStatus.SERVER_NOT_CONFIGURED -> "Server needs Apple key"
    }
    AssistChip(onClick = onClick, label = { Text(label) })
}

@Composable
private fun EngineSetupBanner(status: EngineStatus, onOpenSettings: () -> Unit) {
    val (title, body) = when (status) {
        EngineStatus.NOT_BUNDLED -> "Recognition engine not included" to
            "This APK was built without Apple's ShazamKit library, which Apple only distributes to signed-in developers. " +
            "Everything else works; to identify songs, build NanoTune with the ShazamKit AAR (see the project README)."
        EngineStatus.NO_SERVER -> "Connect your NanoTune server" to
            "ShazamKit needs a short-lived developer token that your own NanoTune server signs. Add the server address in Settings."
        EngineStatus.SERVER_OFFLINE -> "Server unreachable" to
            "NanoTune can't reach your server right now, so it can't get a recognition token. Check your connection or the server."
        EngineStatus.SERVER_NOT_CONFIGURED -> "Server has no Apple key yet" to
            "Your NanoTune server is running but has no Apple Media Services key configured, so it can't sign ShazamKit tokens."
        else -> return
    }
    InfoBanner(
        icon = if (status == EngineStatus.SERVER_OFFLINE) Icons.Filled.CloudOff else Icons.Filled.Dns,
        title = title,
        body = body,
        action = { TextButton(onClick = onOpenSettings) { Text("Open settings") } },
    )
}

@Composable
private fun ProblemBanner(problem: IdentifyResultUi.Problem, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    InfoBanner(
        icon = Icons.Filled.ErrorOutline,
        title = when (problem.hint) {
            SetupHint.ENGINE_NOT_BUNDLED -> "Recognition engine not included"
            SetupHint.SET_SERVER_URL, SetupHint.SERVER_NOT_CONFIGURED -> "Recognition isn't set up yet"
            null -> if (problem.permission) "Microphone permission needed" else "Couldn't identify"
        },
        body = problem.message,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    problem.permission -> TextButton(onClick = { Links.openAppSettings(context) }) { Text("Open app settings") }
                    problem.hint != null -> TextButton(onClick = onOpenSettings) { Text("Open settings") }
                    problem.retryable -> TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
        },
    )
}

@Composable
private fun SessionRunningCard(tracks: Int, paused: Boolean, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (paused) "Listening session paused" else "Listening session running", style = MaterialTheme.typography.titleMedium)
                Text(if (tracks == 1) "1 track found" else "$tracks tracks found", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onOpen) { Text("Open") }
        }
    }
}

@Composable
private fun MicrophoneRationaleDialog(permanentlyDenied: Boolean, onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
        title = { Text("Allow microphone access") },
        text = {
            Text(
                "NanoTune uses the microphone only to identify music: while you tap Identify, or while a listening " +
                    "session you started shows its notification. Audio is processed in memory and never saved or uploaded" +
                    " by default." + if (permanentlyDenied) "\n\nMicrophone access is turned off for NanoTune. Enable it in the app's system settings." else "",
            )
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(if (permanentlyDenied) "Open settings" else "Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
