package app.nanotune.feature.account

import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.nanotune.AppContainer
import app.nanotune.data.repository.AuthResult
import app.nanotune.data.repository.ServerState
import app.nanotune.data.repository.SyncOutcome
import app.nanotune.data.settings.AccountSession
import app.nanotune.data.settings.AppSettings
import app.nanotune.data.settings.SyncStatus
import app.nanotune.feature.common.Formats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AccountViewModel(private val container: AppContainer) : ViewModel() {
    val account: StateFlow<AccountSession?> = container.accountRepository.account
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val syncStatus: StateFlow<SyncStatus> = container.syncRepository.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus())
    val syncing: StateFlow<Boolean> = container.syncRepository.running
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val server: StateFlow<ServerState> = container.serverRepository.state

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun register(email: String, password: String) = authenticate { container.accountRepository.register(email, password) }

    fun login(email: String, password: String) = authenticate { container.accountRepository.login(email, password) }

    private fun authenticate(block: suspend () -> AuthResult) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            when (val result = block()) {
                AuthResult.Success -> {
                    container.historyRepository.markEverythingPendingSync()
                    _message.value = syncMessage(container.syncRepository.syncNow())
                }
                is AuthResult.Failure -> _message.value = result.message
            }
            _busy.value = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            container.accountRepository.logout()
            _message.value = "Signed out. Your history stays on this phone."
        }
    }

    fun deleteAccount(password: String) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = when (val result = container.accountRepository.deleteAccount(password)) {
                AuthResult.Success -> "Your account and all cloud data were deleted."
                is AuthResult.Failure -> result.message
            }
            _busy.value = false
        }
    }

    fun syncNow() {
        viewModelScope.launch { _message.value = syncMessage(container.syncRepository.syncNow()) }
    }

    fun setSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.update { it.copy(syncEnabled = enabled) }
            if (enabled) container.syncRepository.syncNow()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun syncMessage(outcome: SyncOutcome): String = when (outcome) {
        SyncOutcome.Done -> "Signed in and synced"
        SyncOutcome.Skipped -> "Signed in"
        is SyncOutcome.Failed -> "Signed in, but sync failed: ${outcome.message}"
    }
}

@Composable
fun AccountScreen(viewModel: AccountViewModel, onBack: () -> Unit) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Account") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val session = account
            if (session == null) SignedOut(viewModel, busy, onBack) else SignedIn(viewModel, session)
        }
    }
}

@Composable
private fun SignedOut(viewModel: AccountViewModel, busy: Boolean, onBack: () -> Unit) {
    val server by viewModel.server.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val accounts = (server as? ServerState.Online)?.config?.accounts
    val emailValid = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val passwordValid = password.length >= MIN_PASSWORD

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("An account is optional", style = MaterialTheme.typography.titleLarge)
        Text(
            "Identifying music, history, favourites and export all work without one. Signing in to your NanoTune server " +
                "lets you back up and synchronise history, favourites and settings between phones. Registering does not " +
                "change how recognition works and never turns on listening or uploads recordings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            server is ServerState.NotConfigured -> Text("Add your NanoTune server address in Settings first.", color = MaterialTheme.colorScheme.error)
            accounts != null && !accounts.enabled -> Text("Accounts are turned off on this server.", color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            supportingText = { Text("At least $MIN_PASSWORD characters") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { viewModel.login(email, password) }, enabled = !busy && emailValid && password.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("Sign in")
            }
            OutlinedButton(
                onClick = { viewModel.register(email, password) },
                enabled = !busy && emailValid && passwordValid && accounts?.registrationOpen != false,
                modifier = Modifier.weight(1f),
            ) { Text("Create account") }
        }
        TextButton(onClick = onBack) { Text("Continue without an account") }
        if (accounts != null && !accounts.passwordReset) {
            Text(
                "Password reset by email is not available on this server, so keep your password safe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SignedIn(viewModel: AccountViewModel, session: AccountSession) {
    val status by viewModel.syncStatus.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var deletePassword by rememberSaveable { mutableStateOf("") }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
            title = { Text("Delete account and cloud data?") },
            text = {
                Column {
                    Text("This permanently deletes your account and every synced recognition, favourite and setting on the server. History on this phone is kept.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deletePassword,
                        onValueChange = { deletePassword = it },
                        label = { Text("Confirm with your password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = deletePassword.isNotEmpty(), onClick = {
                    confirmDelete = false
                    viewModel.deleteAccount(deletePassword)
                    deletePassword = ""
                }) { Text("Delete forever") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    ListItem(headlineContent = { Text(session.email) }, supportingContent = { Text("Signed in") })
    ListItem(
        headlineContent = { Text("Cloud sync") },
        supportingContent = {
            Text(
                when {
                    syncing -> "Syncing…"
                    status.lastError != null -> "Last attempt failed: ${status.lastError}"
                    status.lastSyncAt != null -> "Last synced ${Formats.relative(status.lastSyncAt!!)}"
                    else -> "Not synced yet"
                },
            )
        },
        leadingContent = { Icon(Icons.Filled.CloudSync, contentDescription = null) },
        trailingContent = { Switch(checked = settings.syncEnabled, onCheckedChange = viewModel::setSyncEnabled) },
    )
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = viewModel::syncNow, enabled = settings.syncEnabled && !syncing) { Text("Sync now") }
        OutlinedButton(onClick = viewModel::logout) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
            Text(" Sign out")
        }
    }
    Spacer(Modifier.height(24.dp))
    ListItem(
        headlineContent = { Text("Delete account and cloud data", color = MaterialTheme.colorScheme.error) },
        supportingContent = { Text("Removes everything stored on your NanoTune server") },
        leadingContent = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        trailingContent = { TextButton(onClick = { confirmDelete = true }) { Text("Delete") } },
    )
}

private const val MIN_PASSWORD = 10
