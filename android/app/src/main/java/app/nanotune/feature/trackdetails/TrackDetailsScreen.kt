package app.nanotune.feature.trackdetails

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.nanotune.AppContainer
import app.nanotune.data.repository.TrackDetails
import app.nanotune.domain.model.RecognitionMode
import app.nanotune.feature.common.Artwork
import app.nanotune.feature.common.FavouriteButton
import app.nanotune.feature.common.Formats
import app.nanotune.feature.common.Links
import app.nanotune.feature.common.TrackLinkChips
import app.nanotune.recognition.MetadataEnricher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TrackDetailsState {
    data object Loading : TrackDetailsState
    data object Missing : TrackDetailsState
    data class Loaded(val details: TrackDetails) : TrackDetailsState
}

class TrackDetailsViewModel(private val container: AppContainer, private val trackId: String) : ViewModel() {
    val details: StateFlow<TrackDetails?> = container.historyRepository.observeTrack(trackId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Retry enrichment opportunistically (e.g. it was skipped on mobile data).
        container.metadataEnricher.enrichLater(trackId)
    }

    fun toggleFavourite() {
        val current = details.value ?: return
        viewModelScope.launch {
            container.historyRepository.setFavourite(trackId, !current.favourite)
            container.syncRepository.requestSync()
        }
    }
}

@Composable
fun TrackDetailsScreen(viewModel: TrackDetailsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val details by viewModel.details.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Track details") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    details?.let { d ->
                        FavouriteButton(d.favourite, viewModel::toggleFavourite)
                        IconButton(onClick = { Links.share(context, d.track) }) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                    }
                },
            )
        },
    ) { padding ->
        val d = details
        if (d == null) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val track = d.track
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Artwork(track.artworkUrl, contentDescription = "Artwork for ${track.title}", size = 260.dp, shape = RoundedCornerShape(20.dp))
            Spacer(Modifier.height(20.dp))
            Text(track.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(track.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            TrackLinkChips(track)
            Spacer(Modifier.height(16.dp))

            val enriched = d.enrichmentSource == MetadataEnricher.SOURCE
            DetailsCard(title = "About this recording") {
                MetaRow("Album", track.album ?: "Unknown", enriched && track.album != null)
                MetaRow("Released", track.releaseDate ?: "Unknown", enriched && track.releaseDate != null)
                MetaRow("Duration", track.durationMs?.let { Formats.duration(it) } ?: "Unknown", enriched && track.durationMs != null)
                if (track.genres.isNotEmpty()) MetaRow("Genres", track.genres.joinToString(", "), false)
                track.isrc?.let { MetaRow("ISRC", it, false) }
                if (track.explicit == true) MetaRow("Content", "Explicit", false)
            }
            Spacer(Modifier.height(12.dp))
            DetailsCard(title = "Where this information comes from") {
                val firstProvider = d.recognitions.lastOrNull()?.providerId
                Text(
                    "Recognised by ${providerName(firstProvider)}: title, artist" +
                        (if (track.artworkUrl != null && !enriched) ", artwork" else "") +
                        (if (track.genres.isNotEmpty()) ", genres" else "") +
                        (if (track.isrc != null) ", ISRC" else "") + ".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (enriched) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Fields marked • were added afterwards from MusicBrainz (looked up by ISRC) and may describe a different release of the same recording.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            DetailsCard(title = if (d.recognitions.size == 1) "Identified once" else "Identified ${d.recognitions.size} times") {
                d.recognitions.take(20).forEach { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(Formats.dateTime(entry.recognizedAt), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(if (entry.mode == RecognitionMode.SESSION) "Session" else "Manual", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun providerName(providerId: String?): String = when {
    providerId == null -> "the recognition engine"
    providerId == "shazamkit" -> "the Shazam catalogue (ShazamKit)"
    providerId.startsWith("enhanced:") -> providerId.removePrefix("enhanced:").replace(",", " + ") + " via your NanoTune server"
    else -> providerId
}

@Composable
private fun DetailsCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String, enriched: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
            Text(if (enriched) "$value •" else value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
