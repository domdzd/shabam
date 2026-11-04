package app.nanotune.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
private fun TextPage(title: String, onBack: () -> Unit, sections: List<Pair<String, String>>) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            sections.forEach { (heading, body) ->
                Text(heading, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) = TextPage(
    title = "Privacy",
    onBack = onBack,
    sections = listOf(
        "Microphone" to
            "NanoTune uses the microphone only to identify music. In Identify mode it listens only after you tap the " +
            "button and stops at the first match or after the time limit (and as soon as you leave the screen). A " +
            "listening session keeps the microphone on only after you start it, and only while its notification is " +
            "visible. Sessions never start by themselves or after a reboot.",
        "Audio" to
            "Raw audio is processed in memory and discarded immediately. It is never saved to storage. With the " +
            "standard engine (Apple ShazamKit) the phone turns the audio into a compact, non-reversible signature and " +
            "only that signature is sent to Apple's Shazam service; the audio itself never leaves the phone.",
        "Internet" to
            "ShazamKit needs an internet connection to search the Shazam catalogue. Your NanoTune server provides " +
            "short-lived developer tokens for this and never receives audio from the standard engine.",
        "Enhanced recognition (off by default)" to
            "Only if you sign in, your server offers it, and you agree: when Shazam finds no match, a clip of at most " +
            "12 seconds is uploaded to your NanoTune server and forwarded to the engines it lists. It is not stored.",
        "Metadata" to
            "Optionally, NanoTune looks up album and release date on MusicBrainz using the song's ISRC code, and loads " +
            "cover art from the URLs the engine returns (or the Cover Art Archive). You can turn this off or limit it to Wi-Fi.",
        "Your history" to
            "History, favourites and settings are stored only on this phone. An account is optional; if you sign in, " +
            "they are synchronised with the NanoTune server you configured.",
        "Deletion" to
            "Settings › Delete local data erases everything on this phone. Account › Delete account removes your " +
            "account and all cloud data from the server. Single songs can be deleted from History.",
        "No tracking" to
            "NanoTune contains no advertising, analytics or crash-reporting SDKs. Anonymous token requests carry a random " +
            "per-install ID so the server can rate-limit fairly; it is not linked to you and is reset when you delete local data.",
    ),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) = TextPage(
    title = "Open-source notices",
    onBack = onBack,
    sections = listOf(
        "NanoTune" to "Source code: https://github.com/domdzd/shabam",
        "Android Jetpack (AndroidX: Compose, Material 3, Room, DataStore, Lifecycle, Navigation, Core)" to "Apache License 2.0 · © The Android Open Source Project",
        "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0 · © JetBrains s.r.o.",
        "OkHttp, Retrofit, Okio" to "Apache License 2.0 · © Square, Inc.",
        "Coil" to "Apache License 2.0 · © Coil Contributors",
        "Material Symbols / Icons" to "Apache License 2.0 · © Google LLC",
        "Apple ShazamKit for Android (only in builds that include it)" to
            "Proprietary · © Apple Inc. Used under Apple's developer terms; not redistributed with NanoTune's source code.",
        "MusicBrainz data" to "Core data CC0; supplementary data CC BY-NC-SA 3.0 · MetaBrainz Foundation",
    ),
)
