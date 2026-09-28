package io.github.ottershelf.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the device has a working internet connection (not whether the server answers): the
 * default network has INTERNET and has been VALIDATED by the system, so a captive portal or a Wi-Fi
 * without a route out counts as offline.
 */
class Connectivity(context: Context) {

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var watching = false

    /**
     * Starts following the default network. [onReturned] runs (on a system thread) each time the
     * device goes from offline to online; not at registration for a network that is already up.
     */
    @Synchronized
    fun watch(onReturned: () -> Unit) {
        if (watching || manager == null) return
        watching = true
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                update(capabilities.isUsable(), onReturned)
            }

            override fun onLost(network: Network) {
                update(false, onReturned)
            }
        })
    }

    private fun update(now: Boolean, onReturned: () -> Unit) {
        val was = _online.value
        _online.value = now
        if (now && !was) onReturned()
    }

    private fun currentlyOnline(): Boolean {
        val manager = manager ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.isUsable() == true
    }

    private fun NetworkCapabilities.isUsable() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
