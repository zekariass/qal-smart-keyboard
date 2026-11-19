package com.qalsmartkeyboard.ime.toolbar

import android.content.ClipboardManager
import android.content.Context
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.ai.SystemAiClipboard

/** When a copied clip may join the history (ADR 0035). Pure, JVM-tested. */
object ClipPolicy {
    /**
     * [historyOn]: the "Clipboard history" setting. [sensitive]: the clip carries `EXTRA_IS_SENSITIVE` (passwords from
     * password managers, one-time codes). [isText]: its MIME type is text. [field]: the field focused when it was copied;
     * nothing copied while a password, incognito or raw key-event field has focus is kept.
     */
    fun mayRecord(historyOn: Boolean, sensitive: Boolean, isText: Boolean, field: InputProfile?): Boolean =
        historyOn && !sensitive && isText && (field == null || ToolContext(field).clipboardAllowed)
}

/**
 * The system clipboard behind the clipboard panel (ADR 0035).
 *
 * Access rules (Android 10+): only the default input method and the app in focus may read the clipboard; Qal reads it as
 * the default keyboard, and the system does not show its "pasted from your clipboard" notice for the default keyboard
 * (Android 12+). A read that is refused anyway (not the default keyboard any more, a work profile policy, a system bug)
 * throws or returns null, and is treated as "no clip".
 *
 * What is read and when: the clip's description (MIME types, the sensitive flag) on every clip change while
 * [start]ed; its text only when [ClipPolicy.mayRecord] allows keeping it, and when the user opens the panel
 * ([currentText]). The text goes into the in-memory [history] only; it is never logged, stored or sent.
 */
class SystemClipboardSource(
    context: Context,
    private val history: ClipboardHistory,
    private val historyOn: () -> Boolean,
    private val focusedField: () -> InputProfile?,
    private val onChanged: () -> Unit = {},
) {
    private val app = context.applicationContext
    private val cm: ClipboardManager? get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private var registered: ClipboardManager? = null
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }

    /** Starts listening for copies (the setting is on). Idempotent. */
    fun start() {
        if (registered != null) return
        val m = cm ?: return
        try {
            m.addPrimaryClipChangedListener(listener)
            registered = m
        } catch (e: RuntimeException) {
            registered = null
        }
    }

    /** Stops listening (the setting turned off, the service ends). */
    fun stop() {
        val m = registered ?: return
        try {
            m.removePrimaryClipChangedListener(listener)
        } catch (e: RuntimeException) {
            // already gone
        }
        registered = null
    }

    private fun onClipChanged() {
        val text = readIfAllowed(record = true) ?: return
        if (history.add(text)) onChanged()
    }

    /**
     * The current clip's text for the panel the user just opened, or null (no clip, not text, sensitive, read refused).
     * When the history is on and the clip may be kept, it also joins the history (it may predate the keyboard process).
     */
    fun currentText(): String? {
        val text = readIfAllowed(record = false) ?: return null
        if (historyOn() && ClipPolicy.mayRecord(true, false, true, focusedField())) history.add(text)
        return text
    }

    private fun readIfAllowed(record: Boolean): String? = try {
        val m = cm
        val d = m?.primaryClipDescription
        if (m == null || d == null) {
            null
        } else {
            val sensitive = SystemAiClipboard.isSensitive(d)
            val isText = d.hasMimeType(TEXT_ANY) // plain, HTML, URI lists; never images or intents
            val allowed = if (record) ClipPolicy.mayRecord(historyOn(), sensitive, isText, focusedField()) else !sensitive && isText
            if (!allowed) {
                null
            } else {
                val clip = m.primaryClip
                if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(app)?.toString()?.takeIf { it.isNotBlank() }
            }
        }
    } catch (e: RuntimeException) {
        // SecurityException (not the default keyboard), a dead clipboard service, ...: no clip.
        null
    }

    private companion object {
        const val TEXT_ANY = "text/*"
    }
}
