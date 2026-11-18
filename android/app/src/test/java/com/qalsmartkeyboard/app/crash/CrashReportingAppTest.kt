package com.qalsmartkeyboard.app.crash

import android.content.pm.PackageManager
import com.qalsmartkeyboard.app.BuildConfig
import com.qalsmartkeyboard.app.QalApplication
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroidOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** ADR 0024 on a (Robolectric) device: what the real app process does with Sentry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashReportingAppTest {

    @Test fun withoutADsnTheAppStartsAndSentryStaysOff() {
        val app = RuntimeEnvironment.getApplication() as QalApplication // onCreate ran, CrashReporting.install too
        assertEquals("", BuildConfig.SENTRY_DSN) // test builds never carry a DSN
        assertFalse(Sentry.isEnabled())
        CrashReporting.install(app, dsn = "  ")
        assertFalse(Sentry.isEnabled())
    }

    @Test fun theSdkCannotStartItself() {
        val app = RuntimeEnvironment.getApplication() as QalApplication
        @Suppress("DEPRECATION")
        val providers = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
        assertTrue(providers.none { it.name.startsWith("io.sentry") })
        @Suppress("DEPRECATION")
        val meta = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
        assertFalse(meta.getBoolean("io.sentry.auto-init", true))
    }

    @Test fun optionsAreContentFree() {
        val o = SentryAndroidOptions()
        SentryCrashReporter.configure(o, "https://public@sentry.invalid/1", "release", "play")
        assertEquals("https://public@sentry.invalid/1", o.dsn)
        assertEquals("release", o.environment)
        assertEquals("play", o.tags["distribution"])
        assertFalse(o.isSendDefaultPii)
        assertFalse(o.isAttachScreenshot)
        assertFalse(o.isAttachViewHierarchy)
        assertNull(o.sessionReplay.sessionSampleRate)
        assertNull(o.sessionReplay.onErrorSampleRate)
        assertFalse(o.isEnableUserInteractionBreadcrumbs)
        assertFalse(o.isEnableUserInteractionTracing)
        assertFalse(o.isEnableSystemEventBreadcrumbs)
        assertFalse(o.isEnableNetworkEventBreadcrumbs)
        assertFalse(o.isEnableAppComponentBreadcrumbs)
        assertNull(o.tracesSampleRate)
        assertFalse(o.logs.isEnabled)
        assertFalse(o.isEnableNdk)
        assertFalse(o.isAttachAnrThreadDump)
        assertFalse(o.isAttachThreads)
        assertTrue(o.isAnrEnabled)
        assertTrue(o.beforeSend != null && o.beforeBreadcrumb != null)
    }
}
