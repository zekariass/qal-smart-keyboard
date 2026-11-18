package com.qalsmartkeyboard.app.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Device
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/** ADR 0024: the Android `beforeSend` / `beforeBreadcrumb` keep the crash and drop everything that could be content. */
class CrashScrubberTest {
    private val typed = "selam alem, ይህ የኔ ጽሑፍ ነው"

    private fun exception(type: String, value: String?) = SentryException().apply {
        this.type = type
        this.module = "java.lang"
        this.value = value
        stacktrace = SentryStackTrace(
            mutableListOf(
                SentryStackFrame().apply {
                    module = "com.qalsmartkeyboard.ime.KeyboardImeService"
                    function = "onKey"
                    lineno = 120
                    vars = mapOf("draft" to typed)
                    contextLine = "val draft = \"$typed\""
                },
            ),
        )
    }

    private fun fullEvent() = SentryEvent().apply {
        exceptions = mutableListOf(exception("IllegalArgumentException", "cannot convert '$typed'"))
        message = Message().apply {
            message = "Commit failed for {}"
            formatted = "Commit failed for $typed"
            params = listOf(typed)
        }
        user = User().apply {
            id = "installation-uuid"
            email = "a@example.com"
            username = "abebe"
            ipAddress = "196.188.0.1"
        }
        request = Request().apply { url = "https://api.example/x?q=$typed" }
        setExtra("draft", typed)
        breadcrumbs = mutableListOf(
            Breadcrumb().apply { category = "ui.click"; type = "user"; setData("view.id", "send_button"); message = typed },
            Breadcrumb().apply { category = "ui.lifecycle"; type = "navigation"; setData("screen", "MainActivity"); setData("state", "resumed"); setData("extra", typed); message = "MainActivity resumed" },
            Breadcrumb().apply { category = "navigation"; setData("to", "qal://ai?text=$typed") },
        )
        contexts.setDevice(Device().apply { name = "Abebe's phone"; model = "Pixel 7" })
        contexts.put("response", mapOf("body" to typed))
        contexts.put("feedback", mapOf("message" to typed))
    }

    @Test fun exceptionMessageIsReplacedButTypeAndFramesStay() {
        val e = CrashScrubber.scrub(fullEvent()).exceptions!!.single()
        assertEquals(CrashScrubber.REDACTED, e.value)
        assertEquals("IllegalArgumentException", e.type)
        assertEquals("java.lang", e.module)
        val frame = e.stacktrace!!.frames!!.single()
        assertEquals("onKey", frame.function)
        assertEquals(120, frame.lineno)
        assertNull(frame.vars)
        assertNull(frame.contextLine)
    }

    @Test fun theSdksOwnAnrMessagesAreKeptOnlyExactly() {
        for (m in listOf("ANR", "Background ANR", "ANR for at least 5000 ms.", "Background ANR for at least 5000 ms.")) {
            val event = SentryEvent().apply { exceptions = mutableListOf(exception("ApplicationNotResponding", m)) }
            assertEquals(m, CrashScrubber.scrub(event).exceptions!!.single().value)
        }
        for (m in listOf("ANR $typed", "ANR for at least five ms.")) {
            val event = SentryEvent().apply { exceptions = mutableListOf(exception("ApplicationNotResponding", m)) }
            assertEquals(CrashScrubber.REDACTED, CrashScrubber.scrub(event).exceptions!!.single().value)
        }
        // The same text under another type is not the SDK's.
        val other = SentryEvent().apply { exceptions = mutableListOf(exception("IllegalStateException", "ANR")) }
        assertEquals(CrashScrubber.REDACTED, CrashScrubber.scrub(other).exceptions!!.single().value)
    }

    @Test fun messageKeepsTemplateOnly() {
        val m = CrashScrubber.scrub(fullEvent()).message!!
        assertEquals("Commit failed for {}", m.message)
        assertNull(m.formatted)
        assertNull(m.params)
    }

    @Test fun userKeepsOnlyTheRandomInstallationId() {
        val u = CrashScrubber.scrub(fullEvent()).user!!
        assertEquals("installation-uuid", u.id)
        assertNull(u.email)
        assertNull(u.username)
        assertNull(u.ipAddress)
        assertNull(CrashScrubber.scrub(SentryEvent()).user)
    }

    @Test fun requestExtrasForeignContextsAndDeviceNameAreDropped() {
        val e = CrashScrubber.scrub(fullEvent())
        assertNull(e.request)
        assertNull(e.extras)
        assertNull(e.contexts["response"])
        assertNull(e.contexts["feedback"])
        assertNull(e.contexts.device!!.name)
        assertEquals("Pixel 7", e.contexts.device!!.model)
    }

    @Test fun onlyLifecycleBreadcrumbsSurviveWithoutMessageOrExtraData() {
        val crumbs = CrashScrubber.scrub(fullEvent()).breadcrumbs!!
        val kept = crumbs.single()
        assertEquals("ui.lifecycle", kept.category)
        assertNull(kept.message)
        assertEquals(mapOf("screen" to "MainActivity", "state" to "resumed"), kept.data)
    }

    @Test fun beforeBreadcrumbDropsTapsSystemNetworkAndNavigation() {
        for (category in listOf("ui.click", "ui.scroll", "device.event", "network.event", "navigation", "http", "sentry.event", null)) {
            assertNull(category, CrashScrubber.scrubBreadcrumb(Breadcrumb().apply { this.category = category; message = typed }))
        }
        val app = CrashScrubber.scrubBreadcrumb(Breadcrumb().apply { category = "app.lifecycle"; type = "navigation"; setData("state", "foreground") })
        assertNotNull(app)
        assertEquals("foreground", app!!.getData("state"))
    }

    @Test fun nothingTypedSurvivesSerialisation() {
        val out = StringWriter()
        SentryOptions().serializer.serialize(CrashScrubber.scrub(fullEvent()), out)
        val json = out.toString()
        assertFalse(json, json.contains("selam"))
        assertFalse(json, json.contains("ጽሑፍ"))
        assertFalse(json, json.contains("Abebe"))
        assertFalse(json, json.contains("a@example.com"))
        assertFalse(json, json.contains("196.188"))
        assertTrue(json, json.contains("IllegalArgumentException"))
    }
}
