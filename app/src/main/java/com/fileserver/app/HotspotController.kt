// app/src/main/java/com/fileserver/app/HotspotController.kt
package com.fileserver.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Local-only hotspot (the only hotspot an ordinary app is allowed to start).
 * Other devices join it with the generated name/password and open the file server URL.
 * It has no internet access - ideal for fast offline file transfer.
 */
object HotspotController {
    data class Info(val ssid: String, val password: String)

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    var info: Info? = null
        private set

    val isActive: Boolean get() = reservation != null

    @SuppressLint("MissingPermission")
    fun start(context: Context, onResult: (ok: Boolean, error: String?) -> Unit) {
        if (reservation != null) {
            onResult(true, null)
            return
        }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    info = readInfo(res)
                    ServerState.log("Hotspot started: ${info?.ssid}")
                    onResult(true, null)
                }

                override fun onStopped() {
                    reservation = null
                    info = null
                    ServerState.log("Hotspot stopped")
                }

                override fun onFailed(reason: Int) {
                    reservation = null
                    info = null
                    onResult(false, reasonText(reason))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            onResult(false, "Permission denied. Allow Location / Nearby devices permission.")
        } catch (e: Exception) {
            onResult(false, e.message ?: "Could not start hotspot")
        }
    }

    fun stop() {
        try { reservation?.close() } catch (_: Exception) {}
        reservation = null
        info = null
    }

    @Suppress("DEPRECATION")
    private fun readInfo(res: WifiManager.LocalOnlyHotspotReservation): Info {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = res.softApConfiguration
            Info(c.ssid ?: "", c.passphrase ?: "")
        } else {
            val c = res.wifiConfiguration
            Info((c?.SSID ?: "").trim('"'), (c?.preSharedKey ?: "").trim('"'))
        }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "No free Wi-Fi channel. Turn Wi-Fi off and try again."
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "Incompatible mode: turn off the system hotspot / tethering first."
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "Hotspot is disabled by device policy."
        else -> "Could not start hotspot. Make sure Location is turned ON."
    }
}
