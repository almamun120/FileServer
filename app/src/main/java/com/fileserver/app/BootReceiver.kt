// app/src/main/java/com/fileserver/app/BootReceiver.kt
package com.fileserver.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppPrefs.autoStartBoot(context)) return

        val serviceIntent = Intent(context, FileServerService::class.java).apply {
            action = FileServerService.ACTION_START
            putExtra("port", AppPrefs.port(context))
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // Some Android versions forbid starting this service type from BOOT_COMPLETED.
        }
    }
}
