package com.qalsmartkeyboard.app.crash

import android.app.Application
import android.content.Context
import com.qalsmartkeyboard.app.BuildConfig
import com.qalsmartkeyboard.data.SettingsRepository
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What [CrashReportingController] starts and stops; the real one is [SentryCrashReporter]. */
interface CrashReporter {
    fun start(dsn: String)
    fun stop()

    /** Deletes reports captured but not sent yet (the user just said no). */
    fun discardQueued()
}

/**
 * The gate (ADR 0024): crash reporting runs only when the build has a DSN **and** the privacy switch is on. Applied on
 * every change of the switch: on starts it, off stops it and discards what is queued.
 */
class CrashReportingController(private val dsn: String, private val reporter: CrashReporter) {
    var running: Boolean = false
        private set

    @Synchronized
    fun apply(enabled: Boolean) {
        val wanted = shouldRun(dsn, enabled)
        if (wanted && !running) {
            reporter.start(dsn)
            running = true
        } else if (!wanted) {
            if (running) reporter.stop()
            running = false
            if (dsn.isNotBlank()) reporter.discardQueued()
        }
    }

    companion object {
        fun shouldRun(dsn: String, enabled: Boolean): Boolean = enabled && dsn.isNotBlank()
    }
}

/**
 * Sentry for the app and the keyboard. The keyboard service runs in the app's process (no `android:process` in the
 * `:ime` manifest), so one init in [QalApplication][com.qalsmartkeyboard.app.QalApplication] covers both, and `:ime`
 * keeps no network code. Manual init only: auto-init and the SDK's providers are off in the manifest.
 */
class SentryCrashReporter(
    private val context: Context,
    private val environment: String,
    private val distribution: String,
) : CrashReporter {
    override fun start(dsn: String) {
        SentryAndroid.init(context) { options -> configure(options, dsn, environment, distribution) }
    }

    override fun stop() {
        Sentry.close()
    }

    override fun discardQueued() {
        runCatching { File(context.cacheDir, "sentry").deleteRecursively() }
    }

    companion object {
        /**
         * Everything that could carry content is off; what stays is the crash itself (type, stack, threads), ANRs
         * (main-thread stack, no thread-dump attachment), release health sessions (crash-free rate) and the scrubbed
         * app/activity lifecycle breadcrumbs. Session replay, NDK, Timber, Fragment, Navigation, Compose and OkHttp
         * integrations are separate artifacts and not on the classpath; the setters below keep them off if one is
         * ever added by a transitive dependency.
         */
        fun configure(options: SentryAndroidOptions, dsn: String, environment: String, distribution: String) {
            options.dsn = dsn
            options.environment = environment
            options.setTag("distribution", distribution)
            options.isSendDefaultPii = false
            // Pixels and UI text: never.
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.sessionReplay.sessionSampleRate = null
            options.sessionReplay.onErrorSampleRate = null
            // Breadcrumbs: taps (view ids), system intents, network changes, config changes: off.
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableUserInteractionTracing = false
            options.isEnableSystemEventBreadcrumbs = false
            options.isEnableNetworkEventBreadcrumbs = false
            options.isEnableAppComponentBreadcrumbs = false
            options.isEnableActivityLifecycleBreadcrumbs = true // "MainActivity resumed": class name and state only
            options.isEnableAppLifecycleBreadcrumbs = true // foreground / background
            options.maxBreadcrumbs = 30
            // No performance tracing, no profiling, no Sentry Logs.
            options.tracesSampleRate = null
            options.isEnableAutoActivityLifecycleTracing = false
            options.isEnableFramesTracking = false
            options.logs.isEnabled = false
            // Native and extra device data: off.
            options.isEnableNdk = false
            options.isTombstoneEnabled = false
            options.isAttachAnrThreadDump = false
            options.isAttachThreads = false
            options.isCollectExternalStorageContext = false
            options.isEnableRootCheck = false
            options.isEnableSpotlight = false
            // Kept: uncaught exceptions, ANRs, release-health sessions.
            options.isAnrEnabled = true
            options.isEnableAutoSessionTracking = true
            options.setBeforeSend { event, _ -> CrashScrubber.scrub(event) }
            options.setBeforeBreadcrumb { breadcrumb, _ -> CrashScrubber.scrubBreadcrumb(breadcrumb) }
        }
    }
}

object CrashReporting {
    /**
     * Called once per process from `Application.onCreate`. Without a DSN in the build (local, CI, tests) it returns at
     * once: the setting is not even read. Otherwise it follows the privacy switch for the life of the process. A crash
     * in the few milliseconds before the stored switch is read is not reported (the user's "off" must win).
     */
    fun install(app: Application, dsn: String = BuildConfig.SENTRY_DSN) {
        if (dsn.isBlank()) return
        val controller = CrashReportingController(dsn, SentryCrashReporter(app, BuildConfig.BUILD_TYPE, BuildConfig.FLAVOR))
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            SettingsRepository.get(app).settings.map { it.crashReports }.distinctUntilChanged().collect { enabled ->
                // Init on the main thread (as the SDK expects); close flushes for up to its shutdown timeout, so off-main.
                if (enabled) controller.apply(true) else withContext(Dispatchers.IO) { controller.apply(false) }
            }
        }
    }
}
