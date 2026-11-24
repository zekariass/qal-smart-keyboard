package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.qalsmartkeyboard.ime.R
import com.qalsmartkeyboard.ime.utilities.QrBitmap
import com.qalsmartkeyboard.ime.utilities.QrController
import com.qalsmartkeyboard.ime.utilities.QrKind
import com.qalsmartkeyboard.ime.utilities.QrState
import com.qalsmartkeyboard.ime.utilities.WifiSecurity

/**
 * The QR panel (T8.3), opened by the Tools grid's "QR code" tile: pick what the code carries (text, a link, a Wi-Fi
 * network), tap "Use text in field" or "Use clipboard" (the only moments text is read, both explicit), and the code shows.
 * A Wi-Fi code takes the network name from the first line and the password from the second (shown masked). "Copy text"
 * puts the encoded string on the clipboard.
 *
 * The code itself is drawn black on white with the standard quiet zone, not in theme colours: scanners need the contrast.
 * Everything is computed on the device. TODO(T8.4): "Share image" and "Insert image" arrive with the Draw step's
 * `commitContent` and share-sheet plumbing; [QrBitmap.render] is the bitmap they will take.
 */
class QrPanelView(context: Context) : UtilityPanelView(context) {
    var controller: QrController = QrController()
        set(value) {
            field = value
            refresh()
        }

    /** The selection, else the text before the cursor; read only when "Use text in field" is tapped. */
    var fieldText: () -> String = { "" }

    /** The current clip; read only when "Use clipboard" is tapped. */
    var clipboardText: () -> String = { "" }

    var onCopy: (String) -> Unit = {}

    private var image: ImageView? = null
    private var sourceView: TextView? = null
    private var hint: TextView? = null
    private var bitmap: Bitmap? = null

    /** The state shown last (tests). */
    var shownState: QrState = QrState.Empty
        private set

    /** The text of the source preview (tests). */
    val sourceText: CharSequence get() = sourceView?.text ?: ""

    override fun build() {
        fun s(id: Int) = context.getString(id)
        val kinds = listOf(
            QrKind.TEXT to R.string.qr_kind_text,
            QrKind.URL to R.string.qr_kind_url,
            QrKind.WIFI to R.string.qr_kind_wifi,
        ).map { (k, id) ->
            chip(s(id), s(id), controller.kind == k) { controller.selectKind(k); render() }
        }
        chipStrip(kinds)
        if (controller.kind == QrKind.WIFI) {
            chipStrip(
                listOf(
                    WifiSecurity.WPA to R.string.qr_security_wpa,
                    WifiSecurity.WEP to R.string.qr_security_wep,
                    WifiSecurity.NONE to R.string.qr_security_none,
                ).map { (sec, id) -> chip(s(id), s(id), controller.security == sec) { controller.selectSecurity(sec); render() } },
            )
        }

        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = s(R.string.qr_image_description)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        image = img
        val source = caption("", 13f, 3)
        sourceView = source
        val hintView = caption("", 12f, 4)
        hint = hintView
        val useField = button(s(R.string.qr_use_field), s(R.string.qr_use_field), sp = 13f) { controller.setSource(fieldText()); refresh() }
        val useClip = button(s(R.string.qr_use_clipboard), s(R.string.qr_use_clipboard), sp = 13f) { controller.setSource(clipboardText()); refresh() }
        val copy = button(s(R.string.qr_copy_text), s(R.string.qr_copy_text), PadStyle.ACCENT, sp = 13f) {
            (controller.state() as? QrState.Ready)?.let { onCopy(it.payload) }
        }
        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(source, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(hintView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })
            for (b in listOf(useField, useClip, copy)) {
                addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })
            }
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(img, LinearLayout.LayoutParams(dp(CODE_DP), dp(CODE_DP)))
            addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8f) })
        }
        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        refresh()
    }

    /** Re-encodes and shows the code for the controller's source. */
    fun refresh() {
        val img = image ?: return
        val state = controller.state()
        shownState = state
        bitmap?.recycle()
        bitmap = null
        when (state) {
            is QrState.Ready -> {
                val px = dp(CODE_DP)
                bitmap = QrBitmap.render(state.matrix, px).also { img.setImageBitmap(it) }
                img.visibility = View.VISIBLE
            }
            else -> {
                img.setImageDrawable(null)
                img.visibility = View.VISIBLE // keeps the layout still; the white square is the empty code area
            }
        }
        sourceView?.text = maskedSource()
        hint?.text = when {
            state is QrState.TooLong -> context.getString(R.string.qr_too_long)
            controller.source.isEmpty() -> context.getString(if (controller.kind == QrKind.WIFI) R.string.qr_hint_wifi else R.string.qr_hint_text)
            controller.kind == QrKind.WIFI -> ""
            else -> ""
        }
    }

    /** The source line(s) for the preview; a Wi-Fi password (second line) is shown as dots. */
    private fun maskedSource(): String {
        val s = controller.source.trim()
        if (controller.kind != QrKind.WIFI || controller.security == WifiSecurity.NONE) return s
        val lines = s.lines()
        return if (lines.size > 1 && lines[1].isNotEmpty()) lines[0] + "\n" + "•".repeat(minOf(lines[1].length, 12)) else s
    }

    companion object {
        /** The code's square, in dp (a 25-module code gets 5 px per module at 3x density). */
        const val CODE_DP = 150f
    }
}
