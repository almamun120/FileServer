// app/src/main/java/com/fileserver/app/QrUtil.kt
package com.fileserver.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrUtil {
    fun encode(text: String, size: Int): Bitmap {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) px[off + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Payload that phone cameras understand as "join this Wi-Fi network". */
    fun wifiPayload(ssid: String, password: String): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
            .replace(":", "\\:").replace("\"", "\\\"")
        return if (password.isEmpty()) "WIFI:T:nopass;S:${esc(ssid)};;"
        else "WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};;"
    }
}
