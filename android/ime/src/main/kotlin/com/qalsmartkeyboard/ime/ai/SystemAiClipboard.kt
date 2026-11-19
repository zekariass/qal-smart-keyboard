package com.qalsmartkeyboard.ime.ai

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/**
 * [AiClipboard] over the system `ClipboardManager` (ADR 0031).
 *
 * - [availability] calls only `getPrimaryClipDescription()`: MIME types and extras, never the content, so it reads
 *   nothing the user copied and triggers no "pasted from your clipboard" notice (Android 12+).
 * - A clip whose description carries `EXTRA_IS_SENSITIVE` (Android 13+, also honoured as the plain extra name on older
 *   releases) is reported as [ClipAvailability.SENSITIVE] and is never read.
 * - [readText] runs only from the user's tap on "Paste from clipboard"; nothing is cached, logged or stored.
 */
class SystemAiClipboard(context: Context) : AiClipboard {
    private val app = context.applicationContext
    private val cm: ClipboardManager? get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun availability(): ClipAvailability = try {
        val d = cm?.primaryClipDescription
        when {
            d == null -> ClipAvailability.NONE
            isSensitive(d) -> ClipAvailability.SENSITIVE
            d.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || d.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) -> ClipAvailability.TEXT
            else -> ClipAvailability.NONE
        }
    } catch (e: RuntimeException) {
        ClipAvailability.NONE
    }

    override fun readText(): String? = try {
        val m = cm
        val d = m?.primaryClipDescription
        if (m == null || d == null || isSensitive(d)) {
            null
        } else {
            val clip = m.primaryClip
            if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(app)?.toString()?.takeIf { it.isNotEmpty() }
        }
    } catch (e: RuntimeException) {
        null
    }

    override fun copy(text: String): Boolean = try {
        val m = cm
        if (m == null) false else {
            m.setPrimaryClip(ClipData.newPlainText("Qal AI", text))
            true
        }
    } catch (e: RuntimeException) {
        false
    }

    companion object {
        /** `ClipDescription.EXTRA_IS_SENSITIVE`'s value; the constant itself exists from API 33. */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        fun isSensitive(d: ClipDescription): Boolean {
            val extras = d.extras ?: return false
            val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else EXTRA_IS_SENSITIVE
            return extras.getBoolean(key, false)
        }
    }
}
