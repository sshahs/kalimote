package dev.kalimote.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

data class DiscoveredTv(val name: String, val host: String, val type: String = TvDevice.TYPE_ANDROID_TV)

/**
 * Finds TVs via mDNS: Google TV / Android TV (_androidtvremote2._tcp) and
 * Amazon Fire TV (_amzn-wplay._tcp).
 * Resolves one service at a time because older NsdManager versions reject
 * concurrent resolves.
 */
class Discovery(context: Context, private val onChange: (List<DiscoveredTv>) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, DiscoveredTv>()
    private val pending = ArrayDeque<Pair<NsdServiceInfo, String>>()
    private var resolving = false
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (listeners.isNotEmpty()) return
        multicastLock = wifi.createMulticastLock("kalimote").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        for (serviceType in SERVICE_TYPES) {
            val deviceType = if (serviceType.contains("amzn-wplay")) TvDevice.TYPE_FIRE_TV else TvDevice.TYPE_ANDROID_TV
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    main.post { listeners.remove(this) }
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post {
                        pending.addLast(info to deviceType)
                        resolveNext()
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    main.post {
                        if (found.remove(info.serviceName) != null) publish()
                    }
                }
            }
            listeners += l
            runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, l) }
                .onFailure { listeners.remove(l) }
        }
    }

    fun stop() {
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
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
        val (info, deviceType) = pending.removeFirstOrNull() ?: return
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
                            found[serviceInfo.serviceName] = DiscoveredTv(serviceInfo.serviceName, address.hostAddress!!, deviceType)
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
        /** Google TV / Android TV remote service, and Amazon Fire TV ("whisperplay"). */
        val SERVICE_TYPES = listOf("_androidtvremote2._tcp.", "_amzn-wplay._tcp.")
    }
}
