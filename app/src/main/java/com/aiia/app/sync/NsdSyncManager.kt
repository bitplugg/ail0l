package com.aiia.app.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

data class P2pPeer(
    val name: String,
    val host: String,
    val port: Int,
    val deviceId: String? = null
)

class NsdSyncManager(
    context: Context,
    private val servicePort: Int,
    private val deviceId: String
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val peers = ConcurrentHashMap<String, P2pPeer>()
    private val _peerFlow = MutableStateFlow<List<P2pPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<P2pPeer>> = _peerFlow.asStateFlow()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    fun startAdvertising() {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = "AIIA-$deviceId"
            serviceType = SERVICE_TYPE
            port = servicePort
            setAttribute("deviceId", deviceId)
            setAttribute("protocol", "1")
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }.also { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) }
    }

    fun discover() {
        stopDiscovery()
        discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val id = info.attributes["deviceId"]?.toString(Charsets.UTF_8)
                        val peer = P2pPeer(info.serviceName, host, info.port, id)
                        peers[info.serviceName] = peer
                        _peerFlow.value = peers.values.toList()
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                peers.remove(info.serviceName)
                _peerFlow.value = peers.values.toList()
            }
        }.also { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, it) }
    }

    suspend fun discoverFor(timeoutMs: Long = 1500): List<P2pPeer> {
        discover()
        return withTimeoutOrNull(timeoutMs) {
            discoveredPeers.first { it.isNotEmpty() }
        }.orEmpty()
    }

    fun stopAdvertising() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    fun stop() {
        stopAdvertising()
        stopDiscovery()
    }

    companion object {
        const val SERVICE_TYPE = "_aiia-sync._tcp"
    }
}
