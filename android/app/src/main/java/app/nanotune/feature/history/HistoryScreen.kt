package app.nanotune.feature.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nanotune.domain.history.HistoryRow
import app.nanotune.domain.model.DateFilter
import app.nanotune.domain.model.HistoryEntry
import app.nanotune.feature.common.Artwork
import app.nanotune.feature.common.FavouriteButton
import app.nanotune.feature.common.Formats
import app.nanotune.feature.recordings.RecordingsTab
import app.nanotune.feature.recordings.RecordingsViewModel
import java.time.LocalDate

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    recordings: RecordingsViewModel,
    initialTab: Int,
    onOpenTrack: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    val clips by recordings.clips.collectAsStateWithLifecycle()
    val recordingsMessage by recordings.message.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val total by viewModel.totalCount.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.CSV.mimeType)) { uri ->
        if (uri != null) viewModel.export(context.contentResolver, uri, ExportFormat.CSV)
    }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.JSON.mimeType)) { uri ->
        if (uri != null) viewModel.export(context.contentResolver, uri, ExportFormat.JSON)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(recordingsMessage) {
        recordingsMessage?.let {
            snackbar.showSnackbar(it)
            recordings.consumeMessage()
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("This removes every recognition from this phone (and from your cloud history if sync is on). Favourites stay unless you remove them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearAll()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("History") },
                actions = {
                    if (tab == 0) Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Export as CSV") },
                                leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                enabled = total > 0,
                                onClick = {
                                    menuOpen = false
                                    csvLauncher.launch("nanotune-history-${LocalDate.now()}.csv")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export as JSON") },
                                leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                enabled = total > 0,
                                onClick = {
                                    menuOpen = false
                                    jsonLauncher.launch("nanotune-history-${LocalDate.now()}.json")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear all history") },
                                leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                                enabled = total > 0,
                                onClick = {
                                    menuOpen = false
                                    confirmClear = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Songs") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(if (clips.isEmpty()) "Recordings" else "Recordings (${clips.size})") },
                )
            }
            if (tab == 1) {
                RecordingsTab(recordings, onOpenTrack = onOpenTrack, onOpenSettings = onOpenSettings)
                return@Column
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = filters.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search title, artist or album") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (filters.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filters.favouritesOnly, onClick = { viewModel.setFavouritesOnly(!filters.favouritesOnly) }, label = { Text("Favourites") })
                DateFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filters.date == option,
                        onClick = { viewModel.setDate(option) },
                        label = {
                            Text(
                                when (option) {
                                    DateFilter.ALL -> "All time"
                                    DateFilter.TODAY -> "24 hours"
                                    DateFilter.WEEK -> "7 days"
                                    DateFilter.MONTH -> "30 days"
                                },
                            )
                        },
                    )
                }
            }
            val list = rows
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyHistory(filtered = filters != HistoryFilters())
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.key }) { row ->
                        when (row) {
                            is HistoryRow.DayHeader -> Text(
                                Formats.day(row.date),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                            is HistoryRow.SessionHeader -> Row(
                                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Listening session · from ${Formats.time(row.firstSeenAt)} · ${row.itemCount} ${if (row.itemCount == 1) "track" else "tracks"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            is HistoryRow.Item -> HistoryItem(
                                entry = row.entry,
                                indented = row.inSession,
                                onClick = { onOpenTrack(row.entry.trackId) },
                                onToggleFavourite = { viewModel.toggleFavourite(row.entry) },
                                onDelete = { viewModel.delete(row.entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(entry: HistoryEntry, indented: Boolean, onClick: () -> Unit, onToggleFavourite: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (indented) 24.dp else 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(entry.track.artworkUrl, contentDescription = null, size = 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Formats.time(entry.recognizedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FavouriteButton(entry.favourite, onToggleFavourite)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyHistory(filtered: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(if (filtered) "Nothing matches these filters" else "No songs yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (filtered) "Try a different search or date range." else "Songs you identify appear here. They're stored only on this phone unless you turn on sync.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
