// app/src/main/java/com/fileserver/app/FileServerService.kt
package com.fileserver.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class FileServerService : Service() {

    companion object {
        const val ACTION_START = "com.fileserver.START"
        const val ACTION_STOP = "com.fileserver.STOP"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "FileServerChannel"

        val isRunning: Boolean get() = ServerState.isRunning
    }

    private var httpServer: FileHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer(userRequested = true)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val port = intent.getIntExtra("port", AppPrefs.port(this))
                // Must happen quickly after startForegroundService()
                startForeground(NOTIFICATION_ID, createNotification(port))
                startServer(port)
            }
            else -> {
                // Restarted by the system after the process was killed
                if (AppPrefs.wasRunning(this)) {
                    val port = AppPrefs.port(this)
                    startForeground(NOTIFICATION_ID, createNotification(port))
                    startServer(port)
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun startServer(port: Int) {
        if (httpServer != null) {
            ServerState.log("Server is already running")
            ServerState.setRunning(true)
            return
        }
        try {
            val server = FileHttpServer(this, port, AppPrefs.password(this)) { ServerState.log(it) }
            server.start()
            httpServer = server
            acquireLocks()
            AppPrefs.setWasRunning(this, true)
            ServerState.setRunning(true)
            ServerState.log("Server started on port $port")
            if (AppPrefs.password(this).isNotEmpty()) ServerState.log("Password protection: ON")
            else ServerState.log("Warning: no password set - anyone on this network can access your files")
        } catch (e: Exception) {
            val msg = if (e is java.net.BindException) "Port $port is already in use" else (e.message ?: "unknown error")
            ServerState.log("Failed to start server: $msg")
            httpServer = null
            AppPrefs.setWasRunning(this, false)
            ServerState.setRunning(false, msg)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopServer(userRequested: Boolean) {
        try {
            httpServer?.stop()
        } catch (e: Exception) {
            ServerState.log("Error stopping server: ${e.message}")
        }
        val wasRunning = httpServer != null
        httpServer = null
        releaseLocks()
        if (userRequested) AppPrefs.setWasRunning(this, false)
        ServerState.setRunning(false)
        if (wasRunning) ServerState.log("Server stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "File Server", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while the file server is running"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(port: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FileServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val url = NetworkUtils.primaryUrl(this, port) ?: "port $port"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("File Server running")
            .setContentText(url)
            .setSmallIcon(R.drawable.ic_server)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FileServer::WakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (AppPrefs.wifiLock(this) && wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FileServer::WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            ServerState.log("Lock error: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        // Do NOT clear "was_running" here: the system may be killing us and we want to come back.
        try { httpServer?.stop() } catch (_: Exception) {}
        httpServer = null
        releaseLocks()
        if (ServerState.isRunning) ServerState.setRunning(false)
        super.onDestroy()
    }
}
