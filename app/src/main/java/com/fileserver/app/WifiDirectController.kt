// app/src/main/java/com/fileserver/app/WifiDirectController.kt
package com.fileserver.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Wi-Fi Direct (Wi-Fi P2P).
 *  - Group Owner: createGroup() makes this phone the "access point" of a direct group (network name +
 *    password, usually IP 192.168.49.1). Other devices join it like a normal Wi-Fi network.
 *  - Client: discover() lists nearby Wi-Fi Direct devices, connect() joins one. If that phone is the
 *    group owner and runs File Server, its page is at http://<ownerIp>:<port>.
 */
@SuppressLint("MissingPermission")
object WifiDirectController {

    private val main = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false

    /** Called on the main thread whenever anything below changes. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    var supported = true
        private set
    /** null until the system has reported the state. */
    var enabled: Boolean? = null
        private set
    var discovering = false
        private set
    var group: WifiP2pGroup? = null
        private set
    var ownerIp: String? = null
        private set
    var peers: List<WifiP2pDevice> = emptyList()
        private set

    val isOwner: Boolean get() = group?.isGroupOwner == true

    private fun changed() {
        main.post { onChanged?.invoke() }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    changed()
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refresh()
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    discovering = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    changed()
                }
            }
        }
    }

    /** Safe to call repeatedly. */
    fun attach(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val m = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (m == null) {
            supported = false
            return
        }
        val ch = try {
            m.initialize(app, Looper.getMainLooper(), null)
        } catch (e: Exception) {
            null
        }
        if (ch == null) {
            supported = false
            return
        }
        manager = m
        channel = ch
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    /** Re-reads the current group / connection (also adopts a group created earlier). */
    fun refresh() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestConnectionInfo(ch) { info ->
                val formed = info != null && info.groupFormed
                ownerIp = if (formed) info.groupOwnerAddress?.hostAddress else null
                try {
                    m.requestGroupInfo(ch) { g ->
                        group = g
                        if (g == null) ownerIp = null
                        changed()
                    }
                } catch (e: SecurityException) {
                    changed()
                }
            }
        } catch (e: SecurityException) {
            // permission not granted yet
        }
    }

    private fun requestPeers() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestPeers(ch) { list ->
                peers = list?.deviceList?.toList() ?: emptyList()
                changed()
            }
        } catch (e: SecurityException) {
        }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device."
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Turn Wi-Fi on, leave any current group and try again."
        else -> "Wi-Fi Direct failed. Make sure Wi-Fi and Location are turned ON."
    }

    private fun listener(onResult: (Boolean, String?) -> Unit, after: (() -> Unit)? = null) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onResult(true, null)
                after?.invoke()
            }

            override fun onFailure(reason: Int) {
                onResult(false, reasonText(reason))
            }
        }

    /** Become Group Owner. */
    fun createGroup(onResult: (ok: Boolean, error: String?) -> Unit) {
        val m = manager
        val ch = channel
        if (m == null || ch == null) { onResult(false, reasonText(WifiP2pManager.P2P_UNSUPPORTED)); return }
        try {
            m.createGroup(ch, listener(onResult) {
                main.postDelayed({ refresh() }, 800)
                main.postDelayed({ refresh() }, 2500)
            })
        } catch (e: SecurityException) {
            onResult(false, "Permission denied. Allow Nearby devices / Location.")
        }
    }

    /** Leave / dissolve the current group. */
    fun leave(onResult: (ok: Boolean, error: String?) -> Unit) {
        val m = manager
        val ch = channel
        if (m == null || ch == null) { onResult(false, null); return }
        try {
            m.removeGroup(ch, listener(onResult) {
                group = null
                ownerIp = null
                changed()
            })
        } catch (e: SecurityException) {
            onResult(false, "Permission denied")
        }
    }

    /** Client side: look for nearby Wi-Fi Direct devices. */
    fun discover(onResult: (ok: Boolean, error: String?) -> Unit) {
        val m = manager
        val ch = channel
        if (m == null || ch == null) { onResult(false, reasonText(WifiP2pManager.P2P_UNSUPPORTED)); return }
        peers = emptyList()
        changed()
        try {
            m.discoverPeers(ch, listener(onResult) { requestPeers() })
        } catch (e: SecurityException) {
            onResult(false, "Permission denied. Allow Nearby devices / Location.")
        }
    }

    fun stopDiscovery() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.stopPeerDiscovery(ch, null)
        } catch (_: Exception) {
        }
    }

    /** Client side: join a discovered device (the other phone may need to accept). */
    fun connect(device: WifiP2pDevice, onResult: (ok: Boolean, error: String?) -> Unit) {
        val m = manager
        val ch = channel
        if (m == null || ch == null) { onResult(false, reasonText(WifiP2pManager.P2P_UNSUPPORTED)); return }
        // The default WPS setup of WifiP2pConfig is already push-button (PBC).
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        try {
            m.connect(ch, config, listener(onResult) {
                main.postDelayed({ refresh() }, 1500)
                main.postDelayed({ refresh() }, 4000)
            })
        } catch (e: SecurityException) {
            onResult(false, "Permission denied. Allow Nearby devices / Location.")
        }
    }

    fun statusText(d: WifiP2pDevice): String = when (d.status) {
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.INVITED -> "Invited…"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Available"
    }
}
