# ADR 0024 — Sentry crash reporting, content-free

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** H3.1 (crash reporter clause), ahead of the rest of H3.1 ·
**Rules:** N2 (no user content in crash reports), N1 · **Decision:** ADR 0007 D47 (Sentry for app and backend, off without a
DSN, content-free, user switch on the privacy dashboard, on by default), D43 (privacy dashboard).

## Context
D47 chose Sentry for the Android app (companion UI and keyboard, one process) and the backend. N2 forbids typed text,
clipboard, transcripts and AI output in crash reports; H3.1 asks for content-free breadcrumbs and no screenshot or
view-hierarchy capture in the IME. No DSN exists yet; local, CI and test runs must send nothing.

## Decisions

### Common
1. **Off without a DSN.** Android: `BuildConfig.SENTRY_DSN` is blank unless the build gets one, and then Sentry is never
   started (the settings store is not even read). Backend: `sentry.dsn` defaults to `${QAL_SENTRY_DSN:}`, and a blank DSN
   leaves the SDK disabled (no client, no transport). Tests use obviously fake DSNs only.
2. **SDK 8.62.0** (Android `io.sentry:sentry-android-core`, backend `sentry-spring-boot-4-starter` + `sentry-logback` through
   `sentry-bom`), released 2026-10-27: the newest release at least two weeks old on 2026-11-18. The Spring Boot 4 starter is
   official and built against Boot 4.1. No Sentry Gradle plugin and no Maven plugin: nothing is uploaded at build time and
   builds stay offline-capable (stack traces are not obfuscated today, `isMinifyEnabled = false`; mapping upload is a
   release-pipeline follow-up).
3. **Exception messages are always replaced by `[redacted]`.** Type, module and stack frames are sent; the message is not.
   A message can quote whatever the code had at hand (a word the user typed, a SQL parameter, a provider's echo), and an
   allow-list of "our own safe messages" would rot silently, so there is none. The only kept messages are the Android SDK's
   own ANR texts (`ANR`, `Background ANR`, `ANR for at least N ms.`), matched exactly and only on `ApplicationNotResponding`.
   A log event keeps its message **template** (a literal in our code, such as `Unhandled exception while serving request`);
   the formatted text and the arguments are dropped.
4. **Always removed by `beforeSend`:** request bodies, cookies, query strings and fragments, environment variables, extras,
   stack-frame variables and source context, the device name, contexts outside a small allow-list, and every user field
   except (Android) the SDK's random installation id. The scrubbers are pure functions with unit tests
   (`app/.../crash/CrashScrubber.kt`, `backend/.../platform/observability/SentryScrubber.java`), including a test that
   serialises a scrubbed event and finds none of the planted text.
5. **Owner settings in the Sentry project** (not enforceable from code): turn on *Prevent Storing of IP Addresses* and the
   server-side data scrubbers, keep *Store Native Crash Reports* and attachments off, and set data retention to the
   shortest useful period.

### Android
6. **Manual init, one place.** `QalApplication.onCreate` calls `CrashReporting.install`; the keyboard service runs in the app
   process (no `android:process` on `KeyboardImeService`), so the keyboard's crashes are covered and `:ime` keeps no network
   code (`NoNetworkTest` unchanged and green). `io.sentry.auto-init=false`, and `SentryInitProvider` /
   `SentryPerformanceProvider` are removed from the merged manifest (a Robolectric test checks both).
7. **The gate.** Sentry runs only while the DSN is present **and** `KeyboardSettings.crashReports` is on (DataStore key
   `crash_reports`, default `true`, additive so the schema stays 1, local only, not synced). The switch is applied at run
   time: off closes Sentry (off the main thread, it may flush for up to the shutdown timeout) and deletes the SDK's queue
   (`cacheDir/sentry`); on starts it again. A crash in the few milliseconds before the stored switch is read is not
   reported: the user's "off" must win. "Reset to defaults" keeps the user's crash-report choice.
8. **Switch** on the privacy dashboard, section "Crash reports": "Send crash reports — no typed text, ever", with a
   plain-language list of what is and is not sent. It works signed out (no account is involved).
9. **Off:** screenshots, view hierarchy, session replay, user-interaction breadcrumbs and tracing (view ids), system-event,
   network-event and app-component breadcrumbs, performance tracing, frames tracking, Sentry Logs, NDK and tombstones, ANR
   thread-dump attachments, attaching all threads to events, external-storage context, root check. Not on the classpath at
   all: the OkHttp (URLs may carry data), Timber/logcat, Fragment, Navigation, Compose and replay integrations.
10. **Kept:** uncaught exceptions (any thread, app or keyboard), ANRs (main-thread stack; thread dumps are content-free but
    are attachments, so they stay off), release-health sessions (crash-free rate for the M3 exit criterion), and app
    foreground/background plus activity-lifecycle breadcrumbs reduced to category, type, level, time and the `state` /
    `screen` data (activity class names). `beforeBreadcrumb` drops every other category. Environment = build type,
    tag `distribution` = flavour (`play` / `direct`), release = the SDK default (`package@versionName+versionCode`).

### Backend
11. **Hardening wins over properties.** `platform.observability.SentryConfiguration` registers a
    `Sentry.OptionsConfiguration` that forces `sendDefaultPii=false`, `maxRequestBodySize=NONE`, no tracing (rate and
    sampler cleared), Sentry Logs off, no attached threads, zero breadcrumbs, the scrubber as `beforeSend` and a
    `beforeBreadcrumb` that drops everything. A stray `SENTRY_*` variable cannot turn content on (integration test).
12. **What becomes an event:** uncaught exceptions and **ERROR log lines** through the logback appender
    (`minimum-event-level: error`). 5xx responses reach Sentry because `ProblemExceptionHandler` logs them at ERROR;
    Sentry's own MVC exception resolver keeps its default order (after Spring's handlers), so 4xx business errors are not
    reported. No log line becomes a breadcrumb.
13. **Request data:** method, URL without query or fragment (paths carry ids only), body size, and only these headers:
    `User-Agent`, `X-Correlation-Id`, `X-Request-Id`, `X-App-Version`, `X-Client-Platform`, `X-Client-Surface`,
    `Content-Type`, `Accept`, `Content-Length`. Contexts: `os`, `runtime`, `app`, `trace`, `spring`, and from the MDC only
    `correlationId` (so an event can be matched to our logs). No user is attached.
14. **Environment** = `QAL_SENTRY_ENVIRONMENT` when set, else the active Spring profiles (`default` without one). Release:
    set `SENTRY_RELEASE` (Spring relaxed binding of `sentry.release`) in the deployment.

## Configuration (owner)
| Where | Setting | Default |
|---|---|---|
| Android build | Gradle property `-Pqal.sentryDsn=...`, or `qal.sentryDsn` in `android/local.properties`, or env `QAL_SENTRY_DSN_ANDROID` | blank: never started |
| Backend | env `QAL_SENTRY_DSN` | blank: disabled |
| Backend | env `QAL_SENTRY_ENVIRONMENT` | active profiles |
| Backend | env `SENTRY_RELEASE` (optional) | none |

Use separate Sentry projects (and DSNs) for Android and the backend. A DSN only allows sending events, it is not a secret,
but it differs per environment and stays out of git.

## Consequences
- Crash triage works from type + stack + version + device model; the cause text of an exception is never available. When a
  message is needed, put the useful facts (codes, sizes, states, never content) in the code that throws, as a dedicated
  exception type or a log template, not in the message.
- Release-health sessions mean one small request per app start while the switch is on, even without a crash.
- A future integration (OkHttp, Compose, replay, NDK, OpenTelemetry) must update this ADR and the scrubber tests first.

## Open
1. Mapping/source upload once minification is turned on (release pipeline, needs network at build time: keep it optional).
2. The privacy notice must list Sentry as a processor before release (H3.5 checklist).
3. H3.1's dashboards and alerting are separate; this ADR covers crash reporting only.
