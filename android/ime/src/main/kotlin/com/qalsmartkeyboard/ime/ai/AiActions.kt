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
     * The destination of the user's enabled A2.4 app rule for [packageName] (its profile if it still exists, else its
     * language), or null when there is no rule. Cached, never blocks. The keyboard's direction control (ADR 0031) puts
     * an explicit app rule before its own per-source memory and the preferred destination.
     */
    fun appRuleDestination(packageName: String?): AiDestination? = null

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

/**
 * What a request is metered as: one value per contract `FeatureCode` the keyboard uses. [TONE] is `TONE_ADJUST`
 * ("make professional", a rewrite with a tone), [REPLY] `SMART_REPLY`, [EMAIL] `DRAFT_DOCUMENT` (ADR 0027).
 */
enum class AiTaskKind { TRANSLATE, GRAMMAR, REWRITE, TONE, EXPLAIN, SUMMARIZE, REPLY, EMAIL }

/** Contract `targetLength` (SUMMARIZE) and `DraftRequest.length`. */
enum class AiLength { SHORT, MEDIUM, LONG }

/** Contract `DraftRequest.mode`: a new e-mail, a reply to a received one, or an improved draft. */
enum class AiEmailMode { NEW, REPLY, IMPROVE }

/** Contract `EmailTone` (the e-mail writer's eight tones; PROFESSIONAL is the server default). */
enum class AiEmailTone { PROFESSIONAL, FORMAL, FRIENDLY, CASUAL, POLITE, PERSUASIVE, APOLOGETIC, CONCISE }

/**
 * One e-mail request (`POST /ai/drafts`, ADR 0027). Every string except [language] is user text: [toString] never
 * prints it. [variation] 0 is the first answer; Regenerate sends the same spec with a higher one and a new key.
 */
data class AiEmailSpec(
    val mode: AiEmailMode,
    /** The language of the e-mail (am, ti, om, en). */
    val language: String,
    val instructions: String = "",
    /** REPLY only: the e-mail being answered. */
    val originalEmail: String = "",
    /** IMPROVE only: the user's draft. */
    val draft: String = "",
    val tone: AiEmailTone = AiEmailTone.PROFESSIONAL,
    val length: AiLength = AiLength.MEDIUM,
    val includeSubject: Boolean = true,
    val recipientName: String = "",
    val senderName: String = "",
    val variation: Int = 0,
) {
    /** The main user text of the mode (what the server checks first): the description, the e-mail answered or the draft. */
    val mainText: String get() = when (mode) {
        AiEmailMode.NEW -> instructions
        AiEmailMode.REPLY -> originalEmail
        AiEmailMode.IMPROVE -> draft
    }

    override fun toString() = "AiEmailSpec($mode, $language, $tone, $length, variation=$variation)"
}

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

/**
 * What to do with the request's text. An `outputLanguage` of null keeps the language of the text (the server's
 * default); a set one is the contract's `outputLanguage` (ADR 0027). Tasks that carry more user text than
 * [AiRequest.text] ([Reply], [Email]) never print it in `toString`. A `variation` of 0 is the first answer; Regenerate
 * sends a higher one (the contract's `variation`, 0..20) with a new key, for the transform tasks as well as replies.
 */
sealed interface AiTask {
    /** [sourceLanguage] null: the server detects it (the "Auto" source of the direction control). */
    data class Translate(val destination: AiDestination, val sourceLanguage: String? = null) : AiTask

    /** `GRAMMAR_CORRECTION`: always in the language of the text (another output language is refused by the server). */
    data object Grammar : AiTask

    /** [tone] null: a natural rewrite (`REWRITE`); set: `TONE_ADJUST` ("make professional" is PROFESSIONAL). */
    data class Rewrite(val tone: AiTone?, val outputLanguage: String? = null, val variation: Int = 0) : AiTask

    /** `REWRITE` mode `SIMPLIFY`: short sentences, everyday words. */
    data class Simplify(val outputLanguage: String? = null, val variation: Int = 0) : AiTask

    /** `EXPLAIN_TEXT`, written in [outputLanguage]. */
    data class Explain(val outputLanguage: String, val variation: Int = 0) : AiTask

    /** `SUMMARIZE`, written in [outputLanguage]. */
    data class Summarize(val outputLanguage: String, val length: AiLength = AiLength.MEDIUM, val variation: Int = 0) : AiTask

    /**
     * `POST /ai/replies`: [count] replies to the request's text (the message received) in [replyLanguage]. [instructions]
     * are the user's own notes on what to answer (any language, user text).
     */
    data class Reply(val replyLanguage: String, val instructions: String = "", val count: Int = 3, val variation: Int = 0) : AiTask {
        override fun toString() = "Reply($replyLanguage, count=$count, variation=$variation)"
    }

    /** `POST /ai/drafts` (e-mail). The request's text is [AiEmailSpec.mainText]; the spec holds everything that is sent. */
    data class Email(val spec: AiEmailSpec) : AiTask {
        override fun toString() = "Email($spec)"
    }

    val kind: AiTaskKind
        get() = when (this) {
            is Translate -> AiTaskKind.TRANSLATE
            Grammar -> AiTaskKind.GRAMMAR
            is Rewrite -> if (tone == null) AiTaskKind.REWRITE else AiTaskKind.TONE
            is Simplify -> AiTaskKind.REWRITE
            is Explain -> AiTaskKind.EXPLAIN
            is Summarize -> AiTaskKind.SUMMARIZE
            is Reply -> AiTaskKind.REPLY
            is Email -> AiTaskKind.EMAIL
        }
}

/** [text] is user text: never log or persist a request. */
class AiRequest(val requestId: String, val task: AiTask, val text: String) {
    override fun toString() = "AiRequest(${task.kind})"
}

class AiAlternative(val label: String?, val text: String) {
    override fun toString() = "AiAlternative"
}

/**
 * [text] and [alternatives] are model output about user text: never log or persist. For a reply request [text] is the
 * first suggestion and [suggestions] all of them; for an e-mail [text] is the body and [subject] the subject line.
 */
class AiResult(
    val text: String,
    val alternatives: List<AiAlternative> = emptyList(),
    /** Short static messages from the server (cultural notes, ambiguity), shown under the result. */
    val notes: List<String> = emptyList(),
    val remaining: Int? = null,
    val resetsAtMs: Long? = null,
    val replayed: Boolean = false,
    /** The language of [text] as the server reports it (`language` / `destinationLanguage`), or null. */
    val language: String? = null,
    /** Translation: the source language the server used or detected, or null. */
    val sourceLanguage: String? = null,
    /** Reply: every suggestion, in the server's order. */
    val suggestions: List<String> = emptyList(),
    /** E-mail: the subject line (absent when not asked for or an improved draft had none). */
    val subject: String? = null,
    /** E-mail: the bracketed items the user must fill in (`[date]`), as the server listed them. */
    val placeholders: List<String> = emptyList(),
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

    /** The companion app's "Write Email with AI" screen; the keyboard's inputs travel in memory ([AiEmailHandoff]). */
    EMAIL_WRITER,

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

    /** 400 `INPUT_TOO_LONG` from the server; [field] is the request field it names (`text`, `originalEmail`, ...), if any. */
    data class InputTooLong(val field: String?) : AiFailure

    /** 400 `VALIDATION_FAILED` (a combination the server does not allow, e.g. grammar into another language); [field] as named. */
    data class Invalid(val field: String?) : AiFailure

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
    /**
     * The features the plan includes (`/me/entitlements` `accessEnabled`), or null while unknown (never fetched). Only a
     * hint for the "Premium" marks of the panels: the server decides on every call.
     */
    val entitled: Set<AiTaskKind>? = null,
    /**
     * Changes whenever the signed-in account changes (sign-in, sign-out, another account), in the app or the keyboard
     * (ADR 0034). What the panels learned about the previous account (e.g. a "not on your plan" answer) is dropped when
     * it moves on.
     */
    val accountGeneration: Long = 0,
) {
    /** True when the cached plan state says [kind] is not part of the plan. Unknown state never locks. */
    fun locks(kind: AiTaskKind): Boolean = entitled?.let { kind !in it } == true
}

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
