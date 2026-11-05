package app.nanotune.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import app.nanotune.AppContainer
import app.nanotune.feature.account.AccountScreen
import app.nanotune.feature.account.AccountViewModel
import app.nanotune.feature.history.HistoryScreen
import app.nanotune.feature.history.HistoryViewModel
import app.nanotune.feature.home.HomeScreen
import app.nanotune.feature.home.HomeViewModel
import app.nanotune.feature.listening.SessionScreen
import app.nanotune.feature.recordings.RecordingsViewModel
import app.nanotune.feature.listening.SessionViewModel
import app.nanotune.feature.settings.LicensesScreen
import app.nanotune.feature.settings.PrivacyScreen
import app.nanotune.feature.settings.SettingsScreen
import app.nanotune.feature.settings.SettingsViewModel
import app.nanotune.feature.trackdetails.TrackDetailsScreen
import app.nanotune.feature.trackdetails.TrackDetailsViewModel
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable data class HistoryRoute(val tab: Int = 0)
@Serializable object SettingsRoute
@Serializable object SessionRoute
@Serializable object AccountRoute
@Serializable object PrivacyRoute
@Serializable object LicensesRoute
@Serializable data class TrackRoute(val trackId: String)

private class TopLevel(val route: Any, val label: String, val icon: ImageVector, val matches: (NavDestination) -> Boolean)

private val topLevels = listOf(
    TopLevel(HomeRoute, "Identify", Icons.Filled.Mic) { it.hasRoute<HomeRoute>() },
    TopLevel(HistoryRoute(), "History", Icons.Filled.History) { it.hasRoute<HistoryRoute>() },
    TopLevel(SettingsRoute, "Settings", Icons.Filled.Settings) { it.hasRoute<SettingsRoute>() },
)

@Composable
fun NanoTuneNavHost(container: AppContainer, navController: NavHostController, identifyRequest: Long = 0L) {
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBottomBar = topLevels.any { top -> destination?.hierarchy?.any(top.matches) == true }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevels.forEach { top ->
                        val selected = destination?.hierarchy?.any(top.matches) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(top.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(top.icon, contentDescription = null) },
                            label = { Text(top.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            NavHost(navController = navController, startDestination = HomeRoute) {
                composable<HomeRoute> {
                    HomeScreen(
                        viewModel = viewModel { HomeViewModel(container) },
                        onOpenSession = { navController.navigate(SessionRoute) },
                        onOpenTrack = { navController.navigate(TrackRoute(it)) },
                        onOpenSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } },
                        onOpenRecordings = { navController.navigate(HistoryRoute(tab = 1)) { launchSingleTop = true } },
                        autoIdentifyRequest = identifyRequest,
                    )
                }
                composable<HistoryRoute> { entry ->
                    HistoryScreen(
                        viewModel = viewModel { HistoryViewModel(container) },
                        recordings = viewModel { RecordingsViewModel(container) },
                        initialTab = entry.toRoute<HistoryRoute>().tab,
                        onOpenTrack = { navController.navigate(TrackRoute(it)) },
                        onOpenSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } },
                    )
                }
                composable<SettingsRoute> {
                    SettingsScreen(
                        viewModel = viewModel { SettingsViewModel(container) },
                        onOpenAccount = { navController.navigate(AccountRoute) },
                        onOpenPrivacy = { navController.navigate(PrivacyRoute) },
                        onOpenLicenses = { navController.navigate(LicensesRoute) },
                    )
                }
                composable<SessionRoute> {
                    SessionScreen(
                        viewModel = viewModel { SessionViewModel(container) },
                        onBack = { navController.popBackStack() },
                        onOpenTrack = { navController.navigate(TrackRoute(it)) },
                    )
                }
                composable<TrackRoute> { entry ->
                    val route = entry.toRoute<TrackRoute>()
                    TrackDetailsScreen(
                        viewModel = viewModel(key = "track-${route.trackId}") { TrackDetailsViewModel(container, route.trackId) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<AccountRoute> {
                    AccountScreen(viewModel = viewModel { AccountViewModel(container) }, onBack = { navController.popBackStack() })
                }
                composable<PrivacyRoute> { PrivacyScreen(onBack = { navController.popBackStack() }) }
                composable<LicensesRoute> { LicensesScreen(onBack = { navController.popBackStack() }) }
            }
        }
    }
}
