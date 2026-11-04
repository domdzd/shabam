package app.nanotune.feature.history

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nanotune.AppContainer
import app.nanotune.BuildConfig
import app.nanotune.domain.export.HistoryExporter
import app.nanotune.domain.history.HistoryGrouping
import app.nanotune.domain.history.HistoryRow
import app.nanotune.domain.model.DateFilter
import app.nanotune.domain.model.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

enum class ExportFormat(val mimeType: String, val extension: String) {
    CSV("text/csv", "csv"),
    JSON("application/json", "json"),
}

data class HistoryFilters(val query: String = "", val date: DateFilter = DateFilter.ALL, val favouritesOnly: Boolean = false)

class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    private val history = container.historyRepository

    private val _filters = MutableStateFlow(HistoryFilters())
    val filters: StateFlow<HistoryFilters> = _filters.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val rows: StateFlow<List<HistoryRow>?> = _filters
        .debounce(150)
        .flatMapLatest { f -> history.observeHistory(f.query, f.date, f.favouritesOnly) }
        .map { HistoryGrouping.group(it, ZoneId.systemDefault()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totalCount: StateFlow<Int> = history.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setQuery(query: String) = _filters.value.let { _filters.value = it.copy(query = query) }

    fun setDate(date: DateFilter) = _filters.value.let { _filters.value = it.copy(date = date) }

    fun setFavouritesOnly(value: Boolean) = _filters.value.let { _filters.value = it.copy(favouritesOnly = value) }

    fun toggleFavourite(entry: HistoryEntry) {
        viewModelScope.launch {
            history.setFavourite(entry.trackId, !entry.favourite)
            container.syncRepository.requestSync()
        }
    }

    fun delete(entry: HistoryEntry) {
        viewModelScope.launch {
            history.deleteRecognition(entry.recognitionId)
            container.syncRepository.requestSync()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            history.clearHistory()
            container.syncRepository.requestSync(delayMs = 0)
            _message.value = "History cleared"
        }
    }

    fun export(resolver: ContentResolver, uri: Uri, format: ExportFormat) {
        viewModelScope.launch {
            val result = runCatching {
                val entries = history.allEntries()
                val zone = ZoneId.systemDefault()
                val text = when (format) {
                    ExportFormat.CSV -> HistoryExporter.toCsv(entries, zone)
                    ExportFormat.JSON -> HistoryExporter.toJson(entries, zone, BuildConfig.VERSION_NAME, System.currentTimeMillis())
                }
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("Could not open the file")
                }
                entries.size
            }
            _message.value = result.fold(
                onSuccess = { "Exported $it recognitions" },
                onFailure = { "Export failed: ${it.message ?: "unknown error"}" },
            )
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
