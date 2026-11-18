package com.qalsmartkeyboard.ime.ai

/**
 * The AI port of the keyboard (A2.5). `:ime` is network-free (`NoNetworkTest`), so the keyboard only
 * knows this interface; the implementation lives in `:ai-client` and is injected by `:app`
 * ([AiActionsHolder]). Pure Kotlin: no Android, no coroutines, no HTTP types.
 *
 * Rules every implementation keeps:
 * - [submit] is the **only** call that sends user text, and it is called only after an explicit
 *   tap on a toolbar action. Nothing in `:ime` calls it on keystrokes, selection changes or focus.
 * - [refresh] and [availability] never carry user text.
 * - Nothing here logs or stores user text or results.
 */
interface AiActions {
    /** The cached state of the account and the network: never blocks, never touches the network. */
    val availability: AiAvailability

    /** Destination profiles (A2.4's local store) first, then plain languages. Cached, never blocks. */
    fun destinations(): List<AiDestination>

    /**
     * The destination to preselect in an app. Precedence: the app's A2.4 rule, else the destination the
     * user last picked in that app (device-local), else the first profile, else English.
     */
    fun defaultDestination(packageName: String?): AiDestination

    /** The user picked [destination] in [packageName]: remember it as that app's last-used one (device-local, never synced). */
    fun rememberDestination(packageName: String?, destination: AiDestination)

    /**
     * Starts one request. Returns at once; [onOutcome] is called exactly once, on any thread, unless
     * the call is cancelled (then possibly never). [AiRequest.requestId] is the `Idempotency-Key`:
     * calling again with the same id after a network error lets the server replay instead of
     * charging twice.
     */
    fun submit(request: AiRequest, onOutcome: (AiOutcome) -> Unit): AiCall

    /**
     * Refreshes plan state and the offline-gate token in the background (throttled by the
     * implementation). Sends no user text. [availability] listeners fire when something changed.
     * Called on a user action in the toolbar ([AiToolbarController.onToolbarOpened]) and by the app's
     * background worker, never because the keyboard appeared.
     */
    fun refresh()

    /** [listener] runs on any thread when [availability] changed. Close the handle to remove it. */
    fun addAvailabilityListener(listener: () -> Unit): AutoCloseable
}

/** A running request. */
fun interface AiCall {
    /** Aborts the HTTP call. Safe to call more than once and after completion. */
    fun cancel()
}

enum class AiTaskKind { TRANSLATE, GRAMMAR, REWRITE }

/** Contract `Tone` values. */
enum class AiTone { FRIENDLY, POLITE, PROFESSIONAL, CONCISE, APOLOGETIC, HONORIFIC }

/**
 * Where the translation goes: a destination profile (N3: "Mum, Amharic, honorific") or a plain
 * language. The string members are the contract's values, carried and not interpreted here.
 */
data class AiDestination(
    val id: String,
    val label: String,
    val language: String,
    val isProfile: Boolean = false,
    /** The profile's server id when synced (sent as `destinationProfileId`). */
    val serverProfileId: String? = null,
    val formality: String? = null,
    val addresseeGender: String? = null,
    val style: String? = null,
    val glossaryId: String? = null,
)

sealed interface AiTask {
    data class Translate(val destination: AiDestination) : AiTask

    data object Grammar : AiTask

    /** [tone] null: a natural rewrite (`REWRITE`); set: `TONE_ADJUST`. */
    data class Rewrite(val tone: AiTone?) : AiTask

    val kind: AiTaskKind
        get() = when (this) {
            is Translate -> AiTaskKind.TRANSLATE
            Grammar -> AiTaskKind.GRAMMAR
            is Rewrite -> AiTaskKind.REWRITE
        }
}

/** [text] is user text: never log or persist a request. */
class AiRequest(val requestId: String, val task: AiTask, val text: String) {
    override fun toString() = "AiRequest(${task.kind})"
}

class AiAlternative(val label: String?, val text: String) {
    override fun toString() = "AiAlternative"
}

/** [text] and [alternatives] are model output about user text: never log or persist. */
class AiResult(
    val text: String,
    val alternatives: List<AiAlternative> = emptyList(),
    /** Short static messages from the server (cultural notes, ambiguity), shown under the result. */
    val notes: List<String> = emptyList(),
    val remaining: Int? = null,
    val resetsAtMs: Long? = null,
    val replayed: Boolean = false,
) {
    override fun toString() = "AiResult"
}

sealed interface AiOutcome {
    class Success(val result: AiResult) : AiOutcome
    class Failure(val failure: AiFailure) : AiOutcome
}

/** Where a failure message sends the user (the companion app; the service opens it). */
enum class AiLink {
    SIGN_IN, AI_SETTINGS, UPGRADE, UPDATE_APP,

    /** The keyboard settings screen, opened by the top bar's gear (not an AI failure link). */
    SETTINGS,
}

/**
 * Every way a request ends without a result. [retrySameKey]: a retry should reuse the
 * `Idempotency-Key` (the first attempt may have reached the server).
 */
sealed interface AiFailure {
    val retrySameKey: Boolean get() = false
    val link: AiLink? get() = null

    /** No connection (known before the call, or the call failed with an I/O error). */
    data object Offline : AiFailure { override val retrySameKey get() = true }

    data object Timeout : AiFailure { override val retrySameKey get() = true }

    /** 409 `REQUEST_IN_PROGRESS`: the same key is still running on the server. */
    data object Busy : AiFailure { override val retrySameKey get() = true }

    /** 429 `QUOTA_EXCEEDED`. [resetAtMs] when the allowance renews; [canUpgrade] when the server offered options. */
    data class QuotaExceeded(val resetAtMs: Long?, val canUpgrade: Boolean) : AiFailure {
        override val link get() = if (canUpgrade) AiLink.UPGRADE else null
    }

    /** 429 `RATE_LIMITED`. */
    data class RateLimited(val retryAfterSeconds: Long?) : AiFailure

    /** 403 `FEATURE_NOT_ENTITLED` (or a guest that may not use this feature). */
    data object NotEntitled : AiFailure { override val link get() = AiLink.UPGRADE }

    /** 403 `ON_DEVICE_ONLY_PREFERRED`: the account chose on-device only; nothing was sent or charged. */
    data object OnDeviceOnly : AiFailure { override val link get() = AiLink.AI_SETTINGS }

    /** 403 `CONSENT_REQUIRED`. */
    data class ConsentRequired(val consentCode: String?) : AiFailure { override val link get() = AiLink.AI_SETTINGS }

    /** 422 `OUTPUT_GUARDRAIL_FAILED`: no safe result could be produced. */
    data object GuardrailFailed : AiFailure

    /** `CONTENT_REJECTED`. */
    data object ContentRejected : AiFailure

    /** `LANGUAGE_NOT_SUPPORTED` / `LANGUAGE_PAIR_UNAVAILABLE`. */
    data object LanguageUnsupported : AiFailure

    /** Signed out, a guest that cannot call (no attestation) or a session the server refused. */
    data object SignInRequired : AiFailure { override val link get() = AiLink.SIGN_IN }

    /** `ACCOUNT_SUSPENDED` / `DELETION_PENDING`. */
    data object AccountBlocked : AiFailure { override val link get() = AiLink.AI_SETTINGS }

    /** 426: the app is below the platform minimum. */
    data object UpdateRequired : AiFailure { override val link get() = AiLink.UPDATE_APP }

    /** 5xx, `PROVIDER_UNAVAILABLE`, `PROVIDER_TIMEOUT`. */
    data object ServiceUnavailable : AiFailure { override val retrySameKey get() = true }

    /** Anything else (unreadable answer, unknown code). */
    data object Other : AiFailure

    // Local failures: decided on the device, no call was made.
    data object NothingToProcess : AiFailure
    data object TooLong : AiFailure
}

/** Why the toolbar cannot make calls right now (no call is made in any of these states). */
enum class AiBlock { NONE, OFFLINE, SIGNED_OUT, UPDATE_REQUIRED }

enum class AiPeriod { DAILY, WEEKLY, MONTHLY, YEARLY, NONE }

/** Remaining allowance of one feature, from `/me/entitlements` and the last answers. */
data class AiQuotaHint(val remaining: Int?, val limit: Int?, val period: AiPeriod, val resetsAtMs: Long?)

/**
 * The cached state the toolbar renders. [onDeviceModels] is the verified entitlement token's
 * offline gate for on-device premium features (ADR 0013): it says nothing about server AI, which the
 * server decides on every call.
 */
data class AiAvailability(
    val block: AiBlock = AiBlock.NONE,
    val quota: Map<AiTaskKind, AiQuotaHint> = emptyMap(),
    val planLabel: String? = null,
    val onDeviceModels: Boolean = false,
)

/**
 * Process-wide injection point. The system instantiates the IME service, so the app (which knows
 * the network stack) registers a factory in `Application.onCreate`; the service calls [create] on a
 * background thread (the app side opens Keystore-backed files).
 */
object AiActionsHolder {
    @Volatile var factory: (() -> AiActions?)? = null

    @Volatile private var instance: AiActions? = null

    /** Builds (once) and returns the actions, or null when the app registered none. Call off the main thread. */
    fun create(): AiActions? = instance ?: synchronized(this) {
        instance ?: factory?.invoke()?.also { instance = it }
    }

    internal fun resetForTest() {
        factory = null
        instance = null
    }
}
