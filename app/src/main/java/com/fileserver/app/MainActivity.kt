// app/src/main/java/com/fileserver/app/MainActivity.kt
package com.fileserver.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fileserver.app.databinding.ActivityMainBinding
import com.fileserver.app.databinding.ItemAddressBinding
import com.fileserver.app.databinding.ItemPeerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    private var updatingUi = false
    private var pendingStart = false
    private var placeholderShown = true
    private var addressSignature = ""

    private val poll = object : Runnable {
        override fun run() {
            refreshNetwork()
            updateStorageWarning()
            handler.postDelayed(this, 2500)
        }
    }

    private val listener = object : ServerState.Listener {
        override fun onLog(line: String) = appendLog(line)

        override fun onStatus(running: Boolean, error: String?) {
            pendingStart = false
            if (error != null) Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
            renderState()
            refreshNetwork()
        }
    }

    // ---------------- permissions ----------------

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val legacyStorageLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { updateStorageWarning() }

    private val hotspotPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startHotspot()
            } else {
                setHotspotSwitch(false)
                Toast.makeText(this, "Permission is required to create a hotspot", Toast.LENGTH_LONG).show()
            }
        }

    private var pendingDirect: (() -> Unit)? = null
    private var peerSignature = ""

    private val directPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingDirect
            pendingDirect = null
            if (granted) action?.invoke()
            else Toast.makeText(this, "Permission is required for Wi-Fi Direct", Toast.LENGTH_LONG).show()
        }

    private fun withDirectPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, hotspotPermission()) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingDirect = action
            directPermissionLauncher.launch(hotspotPermission())
        }
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                safeStart(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStorageLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    private fun hotspotPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInitialValues()
        setupClicks()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (savedInstanceState == null) {
            if (!hasStorageAccess() && !AppPrefs.askedStorage(this)) {
                AppPrefs.setAskedStorage(this)
                MaterialAlertDialogBuilder(this)
                    .setTitle("Allow file access")
                    .setMessage("To show and manage your files in the browser, File Server needs \"All files access\".")
                    .setPositiveButton("Allow") { _, _ -> requestStorageAccess() }
                    .setNegativeButton("Later", null)
                    .show()
            } else if (AppPrefs.autoStartApp(this) && !ServerState.isRunning) {
                startServer()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ServerState.addListener(listener)
        WifiDirectController.attach(this)
        WifiDirectController.onChanged = { renderDirect() }
        WifiDirectController.refresh()
        loadLog()
        renderState()
        poll.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
        ServerState.removeListener(listener)
        WifiDirectController.onChanged = null
        WifiDirectController.stopDiscovery()
    }

    // ---------------- setup ----------------

    private fun setupInitialValues() {
        updatingUi = true
        binding.editPort.setText(AppPrefs.port(this).toString())
        binding.editPassword.setText(AppPrefs.password(this))
        binding.switchAutoBoot.isChecked = AppPrefs.autoStartBoot(this)
        binding.switchAutoStartApp.isChecked = AppPrefs.autoStartApp(this)
        binding.switchWifiLock.isChecked = AppPrefs.wifiLock(this)
        binding.switchLocalHotspot.isChecked = HotspotController.isActive
        updatingUi = false

        val version = readVersionName()
        binding.textVersion.text = "File Server v$version"
    }

    @Suppress("DEPRECATION")
    private fun readVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun setupClicks() {
        binding.buttonToggleServer.setOnClickListener {
            if (ServerState.isRunning) stopServer() else startServer()
        }
        binding.buttonCopyUrl.setOnClickListener { currentUrl()?.let { copyText("Server URL", it) } ?: noNetworkToast() }
        binding.buttonShareUrl.setOnClickListener {
            val url = currentUrl()
            if (url == null) noNetworkToast() else {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                startActivity(Intent.createChooser(i, "Share server address"))
            }
        }
        binding.buttonQrUrl.setOnClickListener {
            val url = currentUrl()
            if (url == null) noNetworkToast() else showQr("Scan to open", url, url)
        }

        binding.buttonGrantStorage.setOnClickListener { requestStorageAccess() }
        binding.buttonWifiSettings.setOnClickListener { NetworkUtils.openWifiSettings(this) }
        binding.buttonHotspotSettings.setOnClickListener { NetworkUtils.openHotspotSettings(this) }

        binding.switchLocalHotspot.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked) {
                if (ContextCompat.checkSelfPermission(this, hotspotPermission()) == PackageManager.PERMISSION_GRANTED) {
                    startHotspot()
                } else {
                    hotspotPermissionLauncher.launch(hotspotPermission())
                }
            } else {
                HotspotController.stop()
                refreshNetwork()
            }
        }
        binding.buttonHotspotQr.setOnClickListener {
            val info = HotspotController.info
            if (info == null) return@setOnClickListener
            showQr("Scan to join hotspot", QrUtil.wifiPayload(info.ssid, info.password), "${info.ssid}\nPassword: ${info.password}")
        }
        binding.buttonHotspotCopy.setOnClickListener {
            HotspotController.info?.let { copyText("Hotspot password", it.password) }
        }

        binding.buttonDirectCreate.setOnClickListener {
            if (!WifiDirectController.supported) {
                Toast.makeText(this, "Wi-Fi Direct is not supported on this device", Toast.LENGTH_LONG).show()
            } else withDirectPermission {
                WifiDirectController.createGroup { ok, err ->
                    if (!ok) showError("Could not create group", err)
                    renderDirect()
                }
            }
        }
        binding.buttonDirectFind.setOnClickListener {
            if (!WifiDirectController.supported) {
                Toast.makeText(this, "Wi-Fi Direct is not supported on this device", Toast.LENGTH_LONG).show()
            } else withDirectPermission {
                WifiDirectController.discover { ok, err ->
                    if (!ok) showError("Could not search", err)
                }
            }
        }
        binding.buttonDirectLeave.setOnClickListener {
            WifiDirectController.leave { ok, err -> if (!ok && err != null) showError("Could not leave group", err) }
        }
        binding.buttonDirectQr.setOnClickListener {
            val g = WifiDirectController.group ?: return@setOnClickListener
            val pass = g.passphrase ?: ""
            showQr("Scan to join group", QrUtil.wifiPayload(g.networkName, pass), "${g.networkName}\nPassword: $pass")
        }
        binding.buttonDirectOpen.setOnClickListener {
            val ip = WifiDirectController.ownerIp ?: return@setOnClickListener
            val port = enteredPort() ?: AppPrefs.port(this)
            safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("http://$ip:$port")))
        }

        binding.switchAutoBoot.setOnCheckedChangeListener { _, c -> if (!updatingUi) AppPrefs.setAutoStartBoot(this, c) }
        binding.switchAutoStartApp.setOnCheckedChangeListener { _, c -> if (!updatingUi) AppPrefs.setAutoStartApp(this, c) }
        binding.switchWifiLock.setOnCheckedChangeListener { _, c ->
            if (!updatingUi) {
                AppPrefs.setWifiLock(this, c)
                if (ServerState.isRunning) Toast.makeText(this, "Applies the next time the server starts", Toast.LENGTH_SHORT).show()
            }
        }
        binding.buttonBattery.setOnClickListener { openBatterySettings() }

        binding.buttonClearLog.setOnClickListener {
            ServerState.clearLog()
            loadLog()
        }
        binding.scrollLog.setOnTouchListener { v, ev ->
            v.parent.requestDisallowInterceptTouchEvent(ev.action != MotionEvent.ACTION_UP && ev.action != MotionEvent.ACTION_CANCEL)
            false
        }

        binding.buttonEmail.setOnClickListener { safeStart(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:mamun995599@gmail.com"))) }
        binding.buttonCall.setOnClickListener { safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+8801945120109"))) }
        binding.buttonFacebook.setOnClickListener { safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/technicalmamun90"))) }
        binding.buttonYoutube.setOnClickListener { safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/@MamunTechie"))) }
    }

    // ---------------- server control ----------------

    private fun enteredPort(): Int? = binding.editPort.text?.toString()?.trim()?.toIntOrNull()

    private fun startServer() {
        val port = enteredPort()
        if (port == null || port !in 1024..65535) {
            binding.editPort.error = "Enter a port between 1024 and 65535"
            return
        }
        binding.editPort.error = null
        AppPrefs.setPort(this, port)
        AppPrefs.setPassword(this, binding.editPassword.text?.toString() ?: "")

        pendingStart = true
        renderState()
        handler.postDelayed({
            if (pendingStart) {
                pendingStart = false
                renderState()
            }
        }, 6000)

        val intent = Intent(this, FileServerService::class.java).apply {
            action = FileServerService.ACTION_START
            putExtra("port", port)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            pendingStart = false
            renderState()
            Toast.makeText(this, "Could not start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServer() {
        startService(Intent(this, FileServerService::class.java).apply { action = FileServerService.ACTION_STOP })
    }

    // ---------------- hotspot ----------------

    private fun setHotspotSwitch(on: Boolean) {
        updatingUi = true
        binding.switchLocalHotspot.isChecked = on
        updatingUi = false
    }

    private fun startHotspot() {
        HotspotController.start(this) { ok, error ->
            if (!ok) {
                setHotspotSwitch(false)
                val b = MaterialAlertDialogBuilder(this)
                    .setTitle("Hotspot could not start")
                    .setMessage(error ?: "Unknown error")
                    .setPositiveButton("OK", null)
                if (error != null && error.contains("Location")) {
                    b.setNeutralButton("Location settings") { _, _ -> NetworkUtils.openLocationSettings(this) }
                }
                b.show()
            }
            refreshNetwork()
        }
    }

    // ---------------- rendering ----------------

    private fun renderState() {
        val running = ServerState.isRunning
        val port = enteredPort() ?: AppPrefs.port(this)

        binding.buttonToggleServer.isEnabled = !pendingStart
        binding.buttonToggleServer.text = when {
            pendingStart -> "Starting…"
            running -> "Stop Server"
            else -> "Start Server"
        }
        val tint = if (running) R.color.status_stopped else R.color.primary
        binding.buttonToggleServer.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, tint))
        binding.buttonToggleServer.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.white else R.color.on_primary)
        )

        binding.textStatusPill.text = if (running) "🟢  Running · port $port" else "🔴  Stopped"
        binding.editPort.isEnabled = !running && !pendingStart
        binding.editPassword.isEnabled = !running && !pendingStart
        binding.textServerUrl.alpha = if (running) 1f else 0.5f
        refreshUrl()
    }

    private fun currentUrl(): String? {
        val port = enteredPort() ?: AppPrefs.port(this)
        return NetworkUtils.primaryUrl(this, port)
    }

    private fun refreshUrl() {
        binding.textServerUrl.text = currentUrl() ?: "No network\nConnect to Wi-Fi or start a hotspot"
    }

    private fun refreshNetwork() {
        val addrs = NetworkUtils.listAddresses(this)
        val port = enteredPort() ?: AppPrefs.port(this)

        val wifi = addrs.firstOrNull { it.type == NetType.WIFI }
        binding.textWifiStatus.text = when {
            wifi != null -> "🟢  Wi-Fi: connected · ${wifi.ip}"
            NetworkUtils.isWifiEnabled(this) -> "🟡  Wi-Fi: on, not connected"
            else -> "🔴  Wi-Fi: off"
        }
        val hot = addrs.firstOrNull { it.type == NetType.HOTSPOT }
        binding.textHotspotStatus.text =
            if (hot != null) "🟢  Hotspot: active · ${hot.ip}" else "⚪  Hotspot: off"

        // local hotspot section
        val info = HotspotController.info
        setHotspotSwitch(HotspotController.isActive)
        binding.layoutHotspotInfo.visibility = if (info != null) View.VISIBLE else View.GONE
        if (info != null) {
            binding.textHotspotSsid.text = "Name: ${info.ssid}"
            binding.textHotspotPassword.text = "Password: ${info.password}"
        }

        // address list (only rebuilt when something changed, to avoid flicker)
        val signature = addrs.joinToString("|") { it.ifName + it.ip } + ":" + port
        if (signature != addressSignature) {
            addressSignature = signature
            binding.layoutAddresses.removeAllViews()
            addrs.forEach { a ->
                val row = ItemAddressBinding.inflate(layoutInflater, binding.layoutAddresses, false)
                val url = a.url(port)
                row.textAddrLabel.text = "${a.type.icon}  ${a.type.label}"
                row.textAddrUrl.text = url
                row.buttonAddrCopy.setOnClickListener { copyText("Server URL", url) }
                row.buttonAddrQr.setOnClickListener { showQr("Scan to open", url, url) }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
                binding.layoutAddresses.addView(row.root, lp)
            }
        }
        refreshUrl()
        renderDirect()
    }

    private fun showError(title: String, message: String?) {
        val b = MaterialAlertDialogBuilder(this).setTitle(title)
            .setMessage(message ?: "Unknown error").setPositiveButton("OK", null)
        if (message != null && message.contains("Location")) {
            b.setNeutralButton("Location settings") { _, _ -> NetworkUtils.openLocationSettings(this) }
        }
        b.show()
    }

    private fun renderDirect() {
        val g = WifiDirectController.group
        val owner = g != null && g.isGroupOwner
        val ownerIp = WifiDirectController.ownerIp
        val port = enteredPort() ?: AppPrefs.port(this)

        binding.textDirectStatus.text = when {
            !WifiDirectController.supported -> "⚪  Wi-Fi Direct: not supported on this device"
            g != null && owner -> "🟢  Wi-Fi Direct: you are the group owner"
            g != null -> "🟢  Wi-Fi Direct: connected as client"
            WifiDirectController.enabled == false -> "🔴  Wi-Fi Direct: off (turn Wi-Fi on)"
            else -> "⚪  Wi-Fi Direct: no group"
        }

        binding.layoutDirectInfo.visibility = if (g != null) View.VISIBLE else View.GONE
        binding.buttonDirectLeave.visibility = if (g != null) View.VISIBLE else View.GONE
        binding.buttonDirectQr.visibility = if (owner) View.VISIBLE else View.GONE
        binding.buttonDirectOpen.visibility = if (g != null && !owner && ownerIp != null) View.VISIBLE else View.GONE
        if (g != null) {
            if (owner) {
                binding.textDirectSsid.text = "Name: ${g.networkName}"
                binding.textDirectPassword.text = "Password: ${g.passphrase ?: "-"}"
            } else {
                binding.textDirectSsid.text = "Group: ${g.networkName}"
                binding.textDirectPassword.text =
                    if (ownerIp != null) "Owner server: http://$ownerIp:$port" else "Waiting for owner address…"
            }
        }

        binding.buttonDirectCreate.isEnabled = g == null
        binding.buttonDirectFind.isEnabled = g == null
        binding.buttonDirectFind.text = if (WifiDirectController.discovering) "Searching…" else "Find devices (Client)"

        // nearby devices (client side)
        val peers = if (g == null) WifiDirectController.peers else emptyList()
        val sig = peers.joinToString("|") { it.deviceAddress + it.status } + (g != null)
        if (sig != peerSignature) {
            peerSignature = sig
            binding.layoutPeers.removeAllViews()
            peers.forEach { d ->
                val row = ItemPeerBinding.inflate(layoutInflater, binding.layoutPeers, false)
                row.textPeerName.text = d.deviceName.ifEmpty { d.deviceAddress }
                row.textPeerStatus.text = WifiDirectController.statusText(d)
                row.buttonPeerConnect.visibility = if (d.status == android.net.wifi.p2p.WifiP2pDevice.CONNECTED) View.GONE else View.VISIBLE
                row.buttonPeerConnect.setOnClickListener {
                    withDirectPermission {
                        WifiDirectController.connect(d) { ok, err ->
                            if (ok) Toast.makeText(this, "Request sent. Accept it on the other phone if asked.", Toast.LENGTH_LONG).show()
                            else showError("Could not connect", err)
                        }
                    }
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
                binding.layoutPeers.addView(row.root, lp)
            }
        }
    }

    private fun updateStorageWarning() {
        binding.layoutStorageWarning.visibility = if (hasStorageAccess()) View.GONE else View.VISIBLE
    }

    // ---------------- log ----------------

    private fun loadLog() {
        val lines = ServerState.snapshot()
        placeholderShown = lines.isEmpty()
        binding.textLog.text = if (lines.isEmpty()) "Waiting for activity…" else lines.joinToString("\n") + "\n"
        scrollLogToEnd()
    }

    private fun appendLog(line: String) {
        if (placeholderShown) {
            binding.textLog.text = ""
            placeholderShown = false
        }
        binding.textLog.append(line + "\n")
        if (binding.textLog.lineCount > 600) loadLog() else scrollLogToEnd()
    }

    private fun scrollLogToEnd() {
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------- helpers ----------------

    private fun noNetworkToast() {
        Toast.makeText(this, "No network address yet. Connect to Wi-Fi or start a hotspot.", Toast.LENGTH_SHORT).show()
    }

    private fun copyText(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatterySettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Battery optimization is already off for this app", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun showQr(title: String, payload: String, caption: String) {
        val density = resources.displayMetrics.density
        val size = (260 * density).toInt()
        val bitmap = try {
            QrUtil.encode(payload, size)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not create QR code", Toast.LENGTH_SHORT).show()
            return
        }
        val pad = (20 * density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, 0)
        }
        box.addView(ImageView(this).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(size, size)
        })
        box.addView(TextView(this).apply {
            text = caption
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
            setTextIsSelectable(true)
        })
        MaterialAlertDialogBuilder(this).setTitle(title).setView(box).setPositiveButton("Close", null).show()
    }
}
