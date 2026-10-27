// app/src/main/java/com/fileserver/app/NetworkUtils.kt
package com.fileserver.app

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

enum class NetType(val label: String, val icon: String, val order: Int) {
    WIFI("Wi-Fi", "📶", 0),
    HOTSPOT("Hotspot", "🔥", 1),
    ETHERNET("Ethernet", "🔌", 2),
    USB("USB tethering", "🔗", 3),
    BLUETOOTH("Bluetooth tethering", "🔷", 4),
    DIRECT("Wi-Fi Direct", "📡", 5),
    OTHER("Network", "🌐", 6)
}

data class NetAddress(val type: NetType, val ifName: String, val ip: String) {
    fun url(port: Int) = "http://$ip:$port"
}

object NetworkUtils {

    /** All IPv4 addresses on which other devices can reach this phone. */
    fun listAddresses(context: Context): List<NetAddress> {
        val out = ArrayList<NetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            if (ifaces != null) {
                for (ni in ifaces) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (addr in ni.inetAddresses) {
                        if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                        val ip = addr.hostAddress ?: continue
                        val type = classify(ni.name.lowercase(Locale.US), ip) ?: continue
                        out.add(NetAddress(type, ni.name, ip))
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (out.isEmpty()) {
            // fallback: legacy Wi-Fi info
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val i = legacyIp(wm)
                if (i != 0) {
                    val ip = String.format(Locale.US, "%d.%d.%d.%d", i and 0xff, i shr 8 and 0xff, i shr 16 and 0xff, i shr 24 and 0xff)
                    out.add(NetAddress(NetType.WIFI, "wlan0", ip))
                }
            } catch (_: Exception) {
            }
        }
        return out.sortedBy { it.type.order }
    }

    @Suppress("DEPRECATION")
    private fun legacyIp(wm: WifiManager): Int = wm.connectionInfo?.ipAddress ?: 0

    private fun isPrivate(ip: String): Boolean {
        val p = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    /**
     * Interface name -> kind of network. Mobile data / VPN / virtual interfaces return null
     * (not reachable by other devices). A phone that is itself the gateway (address ending in .1)
     * on a wlan interface is acting as a hotspot - on many devices the hotspot uses wlan0/wlan1.
     */
    private fun classify(name: String, ip: String): NetType? {
        val endsWithOne = ip.endsWith(".1")
        return when {
            name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("v4-") ||
                name.startsWith("radio") || name.startsWith("tun") || name.startsWith("ppp") ||
                name.startsWith("dummy") || name.startsWith("clat") || name.startsWith("ifb") -> null
            name.startsWith("ap") || name.startsWith("softap") || name.startsWith("swlan") ||
                name.startsWith("wigig") -> NetType.HOTSPOT
            name.startsWith("p2p") -> NetType.DIRECT
            name.startsWith("wlan") -> if (endsWithOne && isPrivate(ip)) NetType.HOTSPOT else NetType.WIFI
            name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> NetType.USB
            name.startsWith("bt-pan") || name.startsWith("bnep") -> NetType.BLUETOOTH
            name.startsWith("eth") -> if (endsWithOne && ip.startsWith("192.168.42.")) NetType.USB else NetType.ETHERNET
            isPrivate(ip) -> NetType.OTHER
            else -> null
        }
    }

    fun primary(context: Context): NetAddress? = listAddresses(context).firstOrNull()

    fun primaryUrl(context: Context, port: Int): String? = primary(context)?.url(port)

    fun isWifiEnabled(context: Context): Boolean = try {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    } catch (_: Exception) {
        false
    }

    // ---- Settings shortcuts (apps cannot toggle Wi-Fi / system hotspot themselves on modern Android) ----

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    fun openWifiSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            tryStart(context, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        ) return
        tryStart(context, Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    fun openHotspotSettings(context: Context) {
        val component = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        if (tryStart(context, component)) return
        if (tryStart(context, Intent("com.android.settings.TETHER_SETTINGS"))) return
        if (tryStart(context, Intent(Settings.ACTION_WIRELESS_SETTINGS))) return
        tryStart(context, Intent(Settings.ACTION_SETTINGS))
    }

    fun openLocationSettings(context: Context) {
        tryStart(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }
}
