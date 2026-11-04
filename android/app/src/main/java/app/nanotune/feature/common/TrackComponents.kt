package app.nanotune.feature.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nanotune.domain.model.RecognizedTrack

@Composable
fun FavouriteButton(favourite: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector = if (favourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (favourite) "Remove from favourites" else "Add to favourites",
            tint = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Large card shown on the Identify screen after a match. */
@Composable
fun TrackResultCard(
    track: RecognizedTrack,
    providerLabel: String,
    favourite: Boolean,
    onToggleFavourite: () -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenDetails)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track.artworkUrl, contentDescription = "Artwork for ${track.title}", size = 88.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val albumLine = listOfNotNull(track.album, Formats.year(track.releaseDate)).joinToString(" · ")
                if (albumLine.isNotEmpty()) {
                    Text(albumLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                Text("Found by $providerLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            FavouriteButton(favourite, onToggleFavourite)
        }
        TrackLinkChips(track, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
    }
}

/** Opens official search pages / deep links; nothing is scraped or downloaded. */
@Composable
fun TrackLinkChips(track: RecognizedTrack, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    FlowRow(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinkChip("YouTube", Icons.Filled.PlayCircle) { Links.open(context, Links.youTube(track)) }
        LinkChip("Spotify", Icons.Filled.Headphones) { Links.open(context, Links.spotify(track)) }
        Links.appleMusic(track)?.let { url -> LinkChip("Apple Music", Icons.AutoMirrored.Filled.OpenInNew) { Links.open(context, url) } }
        Links.shazamPage(track)?.let { url -> LinkChip("Shazam page", Icons.AutoMirrored.Filled.OpenInNew) { Links.open(context, url) } }
        LinkChip("Share", Icons.Filled.Share) { Links.share(context, track) }
    }
}

@Composable
private fun LinkChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}
