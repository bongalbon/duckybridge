package org.duckybridge.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredAgent(val name: String, val host: String, val port: Int)
class Discovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    @Suppress("DEPRECATION")
    fun agents() = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { close(IllegalStateException("Découverte indisponible ($code)")) }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        service.host?.hostAddress?.let { trySend(DiscoveredAgent(service.serviceName, it, service.port)) }
                    }
                })
            }
        }
        nsd.discoverServices("_duckybridge._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
}
