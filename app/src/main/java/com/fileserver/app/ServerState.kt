// app/src/main/java/com/fileserver/app/ServerState.kt
package com.fileserver.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Persistent settings. */
object AppPrefs {
    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("file_server", Context.MODE_PRIVATE)

    fun port(c: Context): Int = p(c).getInt("port", 8080)
    fun setPort(c: Context, v: Int) = p(c).edit().putInt("port", v).apply()

    fun password(c: Context): String = p(c).getString("password", "") ?: ""
    fun setPassword(c: Context, v: String) = p(c).edit().putString("password", v).apply()

    fun wasRunning(c: Context): Boolean = p(c).getBoolean("was_running", false)
    fun setWasRunning(c: Context, v: Boolean) = p(c).edit().putBoolean("was_running", v).apply()

    fun autoStartBoot(c: Context): Boolean = p(c).getBoolean("auto_start_boot", false)
    fun setAutoStartBoot(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_start_boot", v).apply()

    fun autoStartApp(c: Context): Boolean = p(c).getBoolean("auto_start_app", false)
    fun setAutoStartApp(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_start_app", v).apply()

    fun wifiLock(c: Context): Boolean = p(c).getBoolean("wifi_lock", true)
    fun setWifiLock(c: Context, v: Boolean) = p(c).edit().putBoolean("wifi_lock", v).apply()

    fun askedStorage(c: Context): Boolean = p(c).getBoolean("asked_storage", false)
    fun setAskedStorage(c: Context) = p(c).edit().putBoolean("asked_storage", true).apply()
}

/** Process-wide server state + log buffer (survives Activity re-creation). */
object ServerState {
    interface Listener {
        fun onLog(line: String)
        fun onStatus(running: Boolean, error: String?)
    }

    private const val MAX_LINES = 300
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Volatile
    var isRunning = false
        private set

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun log(message: String) {
        val line: String
        synchronized(lines) {
            line = "[" + fmt.format(Date()) + "] " + message
            lines.add(line)
            while (lines.size > MAX_LINES) lines.removeAt(0)
        }
        main.post { listeners.forEach { it.onLog(line) } }
    }

    fun snapshot(): List<String> = synchronized(lines) { ArrayList(lines) }

    fun clearLog() = synchronized(lines) { lines.clear() }

    fun setRunning(running: Boolean, error: String? = null) {
        isRunning = running
        main.post { listeners.forEach { it.onStatus(running, error) } }
    }
}
