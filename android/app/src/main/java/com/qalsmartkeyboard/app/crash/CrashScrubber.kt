package com.qalsmartkeyboard.app.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.User
import java.util.Collections

/**
 * The content-free filter for crash reports (ADR 0024, rule N2): Sentry's `beforeSend` and `beforeBreadcrumb`.
 * Pure functions over the event (no I/O, no Android API), unit-tested on the JVM.
 *
 * Rule for messages: an exception's message is **always** replaced by [REDACTED]; the type, module and stack frames
 * stay. A message can quote whatever the code had at hand, including a word the user typed, so there is no allow-list
 * of our own messages to rot. The only exception is the SDK's own ANR text ("ANR", "Background ANR", "ANR for at
 * least 5000 ms."), matched exactly. A captured log message keeps its template only (we capture none today).
 *
 * Breadcrumbs: only app foreground/background and activity lifecycle ("MainActivity created") survive, with their
 * `state`/`screen` data and no message. Everything else (taps, system events, network, navigation with URIs, logs) is
 * dropped. Also removed: request, extras, every user field except the SDK's random installation id (crash-free-user
 * counts), the device name, frame variables and every context outside the SDK's app/device/os/runtime ones.
 */
object CrashScrubber {
    const val REDACTED = "[redacted]"

    private val SDK_ANR_MESSAGE = Regex("(Background )?ANR( for at least \\d+ ms\\.)?")
    private const val ANR_TYPE = "ApplicationNotResponding"

    /** Breadcrumb categories kept, and the data keys kept on them. */
    val ALLOWED_BREADCRUMB_CATEGORIES = setOf("app.lifecycle", "ui.lifecycle")
    private val ALLOWED_BREADCRUMB_DATA = setOf("state", "screen")

    /** Contexts the SDK fills with facts about the build and the phone, never about what the user did. */
    val ALLOWED_CONTEXTS = setOf("app", "device", "os", "runtime", "gpu", "trace")

    /** `beforeSend`. Never drops the event (a crash is worth its type and stack), only strips it. */
    fun scrub(event: SentryEvent): SentryEvent {
        event.exceptions?.forEach { e ->
            val value = e.value
            if (value != null && !(e.type == ANR_TYPE && SDK_ANR_MESSAGE.matches(value))) e.value = REDACTED
            scrubFrames(e.stacktrace)
        }
        event.threads?.forEach { scrubFrames(it.stacktrace) }
        event.message?.let {
            it.formatted = null
            it.params = null
        }
        event.request = null
        event.extras = null
        event.user = event.user?.id?.let { id -> User().apply { this.id = id } }
        event.breadcrumbs = event.breadcrumbs?.mapNotNull { scrubBreadcrumb(it) }?.toMutableList()
        val contexts = event.contexts
        for (key in Collections.list(contexts.keys())) if (key !in ALLOWED_CONTEXTS) contexts.remove(key)
        contexts.device?.name = null // user-chosen ("Abebe's phone"); the SDK only reads it with sendDefaultPii
        return event
    }

    /** `beforeBreadcrumb`: null drops it. */
    fun scrubBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb? {
        if (breadcrumb.category !in ALLOWED_BREADCRUMB_CATEGORIES) return null
        val clean = Breadcrumb(breadcrumb.timestamp)
        clean.type = breadcrumb.type
        clean.category = breadcrumb.category
        clean.level = breadcrumb.level
        for (key in ALLOWED_BREADCRUMB_DATA) breadcrumb.getData(key)?.let { clean.setData(key, it) }
        return clean
    }

    private fun scrubFrames(trace: SentryStackTrace?) {
        trace?.frames?.forEach {
            it.vars = null
            it.contextLine = null
            it.preContext = null
            it.postContext = null
        }
        trace?.registers = null
    }
}
