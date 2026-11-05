package app.nanotune

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import app.nanotune.core.network.NetworkState
import app.nanotune.data.settings.AppSettings
import app.nanotune.feature.common.LocalAllowNetworkImages
import app.nanotune.navigation.HomeRoute
import app.nanotune.navigation.NanoTuneNavHost
import app.nanotune.navigation.SessionRoute
import app.nanotune.navigation.TrackRoute
import app.nanotune.service.IdentifyShortcut
import app.nanotune.ui.theme.NanoTuneTheme
import app.nanotune.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val openSessionRequests = MutableStateFlow(0)
    private val openTrackRequests = MutableStateFlow<String?>(null)
    /** Time of the last "start recording now" request (0 = none); grows across recreation. */
    private val identifyRequests = MutableStateFlow(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as NanoTuneApp).container
        // After a rotation the launching intent is delivered again; only act on it once.
        if (savedInstanceState == null) handleIntent(intent)
        IdentifyShortcut.publish(this)

        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val network by container.connectivity.states.collectAsStateWithLifecycle(initialValue = NetworkState(connected = true, metered = false))
            val dark = isAppInDarkTheme(settings.theme)
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
                onDispose { }
            }
            val allowImages = !(settings.enrichmentWifiOnly && network.metered)
            NanoTuneTheme(dark = dark) {
                CompositionLocalProvider(LocalAllowNetworkImages provides allowImages) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                        val navController = rememberNavController()
                        val openRequests by openSessionRequests.collectAsStateWithLifecycle()
                        val identifyRequest by identifyRequests.collectAsStateWithLifecycle()
                        NanoTuneNavHost(container, navController, identifyRequest)
                        // Runs after NavHost has set its graph in this composition.
                        LaunchedEffect(openRequests) {
                            if (openRequests > 0) navController.navigate(SessionRoute) { launchSingleTop = true }
                        }
                        LaunchedEffect(identifyRequest) {
                            if (identifyRequest > 0L) {
                                navController.navigate(HomeRoute) {
                                    popUpTo(HomeRoute) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        }
                        val trackRequest by openTrackRequests.collectAsStateWithLifecycle()
                        LaunchedEffect(trackRequest) {
                            trackRequest?.let {
                                navController.navigate(TrackRoute(it))
                                openTrackRequests.value = null
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_SESSION -> openSessionRequests.value++
            ACTION_OPEN_TRACK -> openTrackRequests.value = intent.getStringExtra(EXTRA_TRACK_ID)
            ACTION_IDENTIFY -> identifyRequests.value = System.currentTimeMillis()
        }
    }

    companion object {
        const val ACTION_OPEN_SESSION = "app.nanotune.OPEN_SESSION"
        const val ACTION_OPEN_TRACK = "app.nanotune.OPEN_TRACK"
        const val EXTRA_TRACK_ID = "track_id"

        /** Opens the Identify screen and starts recording (tile, widget, launcher shortcut). */
        const val ACTION_IDENTIFY = "app.nanotune.IDENTIFY"

        fun identifyIntent(context: Context): Intent = Intent(context, MainActivity::class.java).setAction(ACTION_IDENTIFY)
    }
}
