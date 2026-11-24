package com.qalsmartkeyboard.ime.utilities

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/** What the QR panel encodes the source text as. */
enum class QrKind { TEXT, URL, WIFI }

sealed interface QrState {
    /** No source text yet. */
    data object Empty : QrState

    /** The payload is longer than the encoder's limit. */
    data object TooLong : QrState

    data class Ready(val payload: String, val matrix: QrMatrix) : QrState
}

/**
 * The QR panel's state (no Views, JVM-tested). The source text comes from the user's explicit taps ("use the field's
 * text", "use the clipboard"); nothing is read before that. For [QrKind.WIFI] the source is the network name on its first
 * line and the password on the second.
 */
class QrController(private val onChanged: () -> Unit = {}) {
    var kind: QrKind = QrKind.TEXT
        private set
    var security: WifiSecurity = WifiSecurity.WPA
        private set

    /** The text the code is made from. Never logged; cleared when the panel closes. */
    var source: String = ""
        private set

    fun selectKind(k: QrKind) {
        kind = k
        onChanged()
    }

    fun selectSecurity(s: WifiSecurity) {
        security = s
        onChanged()
    }

    fun setSource(text: String) {
        source = text.take(MAX_SOURCE_CHARS)
        onChanged()
    }

    fun clear() {
        source = ""
        onChanged()
    }

    /** The string the code carries, or null with no source. */
    fun payload(): String? {
        val s = source.trim()
        if (s.isEmpty()) return null
        return when (kind) {
            QrKind.TEXT -> QrPayload.text(source.trimEnd())
            QrKind.URL -> QrPayload.url(s)
            QrKind.WIFI -> {
                val lines = source.lines()
                val ssid = lines.first().trim()
                if (ssid.isEmpty()) return null
                QrPayload.wifi(ssid, lines.drop(1).firstOrNull().orEmpty().trim(), security)
            }
        }
    }

    fun state(): QrState {
        val p = payload() ?: return QrState.Empty
        return try {
            QrState.Ready(p, QrEncoder.encode(p))
        } catch (e: QrTooLongException) {
            QrState.TooLong
        }
    }

    companion object {
        const val MAX_SOURCE_CHARS = 600
    }
}

/**
 * Renders a [QrMatrix] to a [Bitmap]: dark modules in [dark] on [light], a four-module quiet zone, whole pixels per module
 * so edges stay sharp. This is the image the share / insert path will take.
 *
 * TODO(T8.4): the Draw step adds `commitContent` and the share sheet; hand this bitmap to that path (PNG export) rather
 * than building a second one here.
 */
object QrBitmap {
    const val QUIET_ZONE = 4

    fun render(m: QrMatrix, targetPx: Int, dark: Int = 0xFF000000.toInt(), light: Int = 0xFFFFFFFF.toInt()): Bitmap {
        val modules = m.size + QUIET_ZONE * 2
        val scale = maxOf(1, targetPx / modules)
        val px = modules * scale
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(light)
        val paint = Paint().apply { color = dark; style = Paint.Style.FILL }
        for (y in 0 until m.size) for (x in 0 until m.size) {
            if (m[x, y]) {
                val l = ((x + QUIET_ZONE) * scale).toFloat()
                val t = ((y + QUIET_ZONE) * scale).toFloat()
                canvas.drawRect(l, t, l + scale, t + scale, paint)
            }
        }
        return bmp
    }
}
