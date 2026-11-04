package app.nanotune.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Current network as far as NanoTune cares: is there internet, and is it metered (mobile data)? */
data class NetworkState(val connected: Boolean, val metered: Boolean) {
    val unmetered: Boolean get() = connected && !metered
}

class Connectivity(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    fun current(): NetworkState {
        val network = manager?.activeNetwork ?: return NetworkState(connected = false, metered = false)
        val caps = manager.getNetworkCapabilities(network) ?: return NetworkState(connected = false, metered = false)
        return caps.toState(manager.isActiveNetworkMetered)
    }

    val states: Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(current())
            }

            override fun onLost(network: Network) {
                trySend(current())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(current())
            }
        }
        trySend(current())
        manager?.registerDefaultNetworkCallback(callback)
        awaitClose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    private fun NetworkCapabilities.toState(meteredHint: Boolean): NetworkState {
        val internet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val notMetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetworkState(connected = internet, metered = meteredHint || !notMetered)
    }
}
