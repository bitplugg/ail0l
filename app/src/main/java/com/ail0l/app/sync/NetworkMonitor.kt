package com.ail0l.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Следит за подключением к сети. При появлении сети запускает
 * немедленную синхронизацию (отправить накопленное / забрать новое).
 */
class NetworkMonitor(context: Context, private val onConnected: () -> Unit) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _connected = MutableStateFlow(isCurrentlyConnected())
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val callback = object : NetworkCallback() {
        override fun onAvailable(network: Network) {
            _connected.value = true
            onConnected()
        }

        override fun onLost(network: Network) {
            _connected.value = isCurrentlyConnected()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _connected.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    fun start() {
        cm.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        cm.unregisterNetworkCallback(callback)
    }

    private fun isCurrentlyConnected(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}