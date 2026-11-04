package app.nanotune.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nanotune.BuildConfig
import app.nanotune.data.repository.ServerState
import app.nanotune.data.settings.AppSettings
import app.nanotune.data.settings.SessionMode
import app.nanotune.data.settings.ThemeMode
import app.nanotune.feature.common.SectionHeader
import app.nanotune.feature.common.clickableRow
import app.nanotune.recognition.shazamkit.ShazamKitBridge
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenAccount: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val server by viewModel.server.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var consentDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete local data?") },
            text = {
                Text(
                    "Deletes your history, favourites, settings, cached tokens and artwork from this phone and signs you out. " +
                        "Your cloud account (if any) is not deleted — use Account › Delete account for that.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteLocalData(context)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    val enhancedConfig = (server as? ServerState.Online)?.config?.enhanced
    if (consentDialog && enhancedConfig != null) {
        AlertDialog(
            onDismissRequest = { consentDialog = false },
            title = { Text("Turn on enhanced recognition?") },
            text = {
                Column {
                    Text(
                        "When the Shazam catalogue finds no match, NanoTune will upload the clip it just heard " +
                            "(at most ${enhancedConfig.maxUploadSeconds} s, 16 kHz mono) to your NanoTune server, which forwards it to:",
                    )
                    enhancedConfig.providers.forEach { provider ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "• ${provider.name}" + (if (provider.paid) " (paid service, billed to the server owner)" else "") +
                                (provider.disclosure?.let { " — $it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Results show which engine found them. Engines can disagree; NanoTune shows that too.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    consentDialog = false
                    viewModel.setEnhanced(true)
                }) { Text("I agree, turn on") }
            },
            dismissButton = { TextButton(onClick = { consentDialog = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Appearance")
            Column(Modifier.padding(horizontal = 16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light")
                    options.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.theme == mode,
                            onClick = { viewModel.update { it.copy(theme = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(label) }
                    }
                }
            }

            SectionHeader("Identify")
            ListenDurationSlider(settings, onChange = { seconds -> viewModel.update { it.copy(manualListenSeconds = seconds) } })

            SectionHeader("Listening sessions")
            Column(Modifier.padding(horizontal = 16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(SessionMode.CONTINUOUS to "Continuous", SessionMode.INTERVAL to "Interval")
                    options.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.sessionMode == mode,
                            onClick = { viewModel.update { it.copy(sessionMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (settings.sessionMode == SessionMode.CONTINUOUS) {
                        "Streams audio continuously for the fastest results. Uses the most battery."
                    } else {
                        "Listens for 15 seconds, then rests with the microphone off. Saves battery and data; may miss short songs."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.sessionMode == SessionMode.INTERVAL) {
                    Spacer(Modifier.height(8.dp))
                    Text("Listen every", style = MaterialTheme.typography.labelLarge)
                    ChipRow(
                        options = listOf(30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min"),
                        selected = settings.sessionIntervalSeconds,
                        onSelect = { seconds -> viewModel.update { it.copy(sessionIntervalSeconds = seconds) } },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Skip repeats of the same song for", style = MaterialTheme.typography.labelLarge)
                ChipRow(
                    options = listOf(3 to "3 min", 4 to "4 min", 5 to "5 min", 10 to "10 min"),
                    selected = settings.duplicateCooldownMinutes,
                    onSelect = { minutes -> viewModel.update { it.copy(duplicateCooldownMinutes = minutes) } },
                )
            }
            SwitchRow(
                title = "Use mobile data during sessions",
                subtitle = "Each recognition request is small, but continuous sessions add up. When off, sessions pause on mobile data.",
                checked = settings.sessionsOnMobileData,
                onChange = { value -> viewModel.update { it.copy(sessionsOnMobileData = value) } },
            )

            SectionHeader("Metadata & artwork")
            SwitchRow(
                title = "Look up album and release date",
                subtitle = "Asks MusicBrainz using the song's ISRC. Only the ISRC is sent; results are cached.",
                checked = settings.enrichmentEnabled,
                onChange = { value -> viewModel.update { it.copy(enrichmentEnabled = value) } },
            )
            SwitchRow(
                title = "Wi-Fi only for metadata and artwork",
                subtitle = "On mobile data, show only artwork that is already cached.",
                checked = settings.enrichmentWifiOnly,
                onChange = { value -> viewModel.update { it.copy(enrichmentWifiOnly = value) } },
            )

            SectionHeader("NanoTune server")
            ServerSection(
                currentUrl = settings.serverUrl,
                server = server,
                onSave = viewModel::saveServerUrl,
                onTest = viewModel::testServer,
            )

            SectionHeader("Account & sync")
            ListItem(
                headlineContent = { Text(account?.email ?: "Optional account") },
                supportingContent = {
                    Text(if (account != null) "Signed in · cloud sync ${if (settings.syncEnabled) "on" else "off"}" else "Back up and sync your history. Never required to identify music.")
                },
                leadingContent = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                modifier = Modifier.clickableRow(onOpenAccount),
            )
            EnhancedRow(
                server = server,
                signedIn = account != null,
                enabled = settings.enhancedEnabled && settings.enhancedConsentAt != null,
                onEnable = { consentDialog = true },
                onDisable = { viewModel.setEnhanced(false) },
            )

            SectionHeader("Privacy")
            ListItem(
                headlineContent = { Text("How NanoTune handles your data") },
                supportingContent = { Text("Microphone, recognition, sync and deletion") },
                leadingContent = { Icon(Icons.Filled.PrivacyTip, contentDescription = null) },
                modifier = Modifier.clickableRow(onOpenPrivacy),
            )
            ListItem(
                headlineContent = { Text("Delete local data") },
                supportingContent = { Text("Erase history, favourites, settings and caches on this phone") },
                leadingContent = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickableRow { confirmDelete = true },
            )

            SectionHeader("About")
            ListItem(
                headlineContent = { Text("NanoTune ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text("Recognition engine: ${ShazamKitBridge.SDK_DESCRIPTION}") },
            )
            ListItem(
                headlineContent = { Text("Open-source notices") },
                leadingContent = { Icon(Icons.Filled.Code, contentDescription = null) },
                modifier = Modifier.clickableRow(onOpenLicenses),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ListenDurationSlider(settings: AppSettings, onChange: (Int) -> Unit) {
    var value by remember(settings.manualListenSeconds) { mutableFloatStateOf(settings.manualListenSeconds.toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Listen for up to ${value.roundToInt()} seconds", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChange(value.roundToInt()) },
            valueRange = AppSettings.MIN_LISTEN_SECONDS.toFloat()..AppSettings.MAX_LISTEN_SECONDS.toFloat(),
            steps = AppSettings.MAX_LISTEN_SECONDS - AppSettings.MIN_LISTEN_SECONDS - 1,
        )
        Text(
            "Identification stops as soon as a song is found; this is only the upper limit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChipRow(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        modifier = Modifier.clickableRow(enabled) { onChange(!checked) },
    )
}

@Composable
private fun ServerSection(currentUrl: String, server: ServerState, onSave: (String) -> Unit, onTest: () -> Unit) {
    var text by rememberSaveable(currentUrl) { mutableStateOf(currentUrl) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Server address") },
            placeholder = { Text("https://api.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                serverSummary(server),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onTest) { Text("Test") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { onSave(text) }, enabled = text.trim() != currentUrl) { Text("Save") }
        }
        Text(
            "Your own NanoTune server signs short-lived ShazamKit tokens (Apple's private key stays on the server) " +
                "and, optionally, stores your account and synced history. Recordings are never sent to it unless you turn on enhanced recognition.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun serverSummary(server: ServerState): String = when (server) {
    ServerState.NotConfigured -> "No server configured"
    ServerState.Checking -> "Checking…"
    is ServerState.Offline -> server.message
    is ServerState.Online -> buildString {
        append("Online")
        append(if (server.config.shazamkit.available) " · ShazamKit tokens ready" else " · no Apple key configured")
        append(if (server.config.accounts.enabled) " · accounts available" else " · accounts disabled")
    }
}

@Composable
private fun EnhancedRow(server: ServerState, signedIn: Boolean, enabled: Boolean, onEnable: () -> Unit, onDisable: () -> Unit) {
    val config = (server as? ServerState.Online)?.config
    val available = config?.enhanced?.available == true
    val subtitle = when {
        config == null -> "Needs a reachable NanoTune server."
        !available -> config.enhanced.message ?: "Additional recognition engines are not configured on this server, so recognition is standard."
        !signedIn -> "Available on your server for signed-in users (${config.enhanced.providers.joinToString { it.name }})."
        enabled -> "On · falls back to ${config.enhanced.providers.joinToString { it.name }} when Shazam finds nothing. Uploads a short clip."
        else -> "Off · would use ${config.enhanced.providers.joinToString { it.name }}. Requires uploading a short clip."
    }
    SwitchRow(
        title = "Enhanced recognition",
        subtitle = subtitle,
        checked = enabled && available && signedIn,
        enabled = available && signedIn,
        onChange = { on -> if (on) onEnable() else onDisable() },
    )
}
