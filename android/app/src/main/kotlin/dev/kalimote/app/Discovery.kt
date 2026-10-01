package dev.kalimote.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

data class DiscoveredTv(val name: String, val host: String)

/**
 * Finds TVs advertising the remote service (_androidtvremote2._tcp) via mDNS.
 * Resolves one service at a time because older NsdManager versions reject
 * concurrent resolves.
 */
class Discovery(context: Context, private val onChange: (List<DiscoveredTv>) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, DiscoveredTv>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (listener != null) return
        multicastLock = wifi.createMulticastLock("kalimote").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    pending.addLast(info)
                    resolveNext()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (found.remove(info.serviceName) != null) publish()
                }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        pending.clear()
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
    }

    fun restart() {
        stop()
        found.clear()
        publish()
        start()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val address = serviceInfo.host
                        if (address is Inet4Address) {
                            found[serviceInfo.serviceName] = DiscoveredTv(serviceInfo.serviceName, address.hostAddress!!)
                            publish()
                        }
                        resolveNext()
                    }
                }
            },
        )
    }

    private fun publish() = onChange(found.values.toList())

    companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp."
    }
}
