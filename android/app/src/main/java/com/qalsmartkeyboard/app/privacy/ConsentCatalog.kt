package com.qalsmartkeyboard.app.privacy

import androidx.annotation.StringRes
import com.qalsmartkeyboard.app.R

/** How a consent is shown on the consents screen. */
enum class ConsentKind {
    /** Terms and privacy notice: required to have an account, cannot be withdrawn (delete the account instead). */
    REQUIRED,

    /** An optional consent with a switch. */
    SWITCH,

    /** `DICTIONARY_SYNC`: owned by the sync card of A2.4, which is embedded instead of a second switch. */
    SYNC_CARD,

    /** `PAYMENT_MANDATE`: given and withdrawn in the payment flow (it cancels the linked mandate); shown read-only. */
    INFO,
}

/**
 * One consent of SD 19.3 with its plain-language text. [effect] says what withdrawing it does
 * (shown in the confirmation before the decision is sent).
 */
data class ConsentDef(
    val code: String,
    val kind: ConsentKind,
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val effect: Int? = null,
)

object ConsentCatalog {
    const val TERMS = "TERMS_ACCEPTANCE"
    const val PRIVACY = "PRIVACY_NOTICE"
    const val AI_PROCESSING = "AI_PROCESSING"
    const val SAVE_AI_HISTORY = "SAVE_AI_HISTORY"
    const val SAVE_AUDIO_HISTORY = "SAVE_AUDIO_HISTORY"
    const val DICTIONARY_SYNC = "DICTIONARY_SYNC"
    const val FEEDBACK_TEXT = "FEEDBACK_TEXT"
    const val COMMUNITY_CONTRIBUTION = "COMMUNITY_CONTRIBUTION"
    const val PERSONAL_STYLE = "PERSONAL_STYLE"
    const val PAYMENT_MANDATE = "PAYMENT_MANDATE"
    const val ANALYTICS = "ANALYTICS"
    const val MARKETING = "MARKETING"
    const val CREATIVE_BACKUP = "CREATIVE_BACKUP"

    /** Every consent code of the contract, in the order the screen lists them. */
    val all: List<ConsentDef> = listOf(
        ConsentDef(TERMS, ConsentKind.REQUIRED, R.string.consent_terms_title, R.string.consent_terms_body),
        ConsentDef(PRIVACY, ConsentKind.REQUIRED, R.string.consent_privacy_title, R.string.consent_privacy_body),
        ConsentDef(AI_PROCESSING, ConsentKind.SWITCH, R.string.consent_ai_title, R.string.consent_ai_body, R.string.consent_ai_effect),
        ConsentDef(SAVE_AI_HISTORY, ConsentKind.SWITCH, R.string.consent_history_title, R.string.consent_history_body, R.string.consent_history_effect),
        ConsentDef(DICTIONARY_SYNC, ConsentKind.SYNC_CARD, R.string.sync_dict_title, R.string.sync_dict_body),
        ConsentDef(PERSONAL_STYLE, ConsentKind.SWITCH, R.string.consent_style_title, R.string.consent_style_body, R.string.consent_style_effect),
        ConsentDef(FEEDBACK_TEXT, ConsentKind.SWITCH, R.string.consent_feedback_title, R.string.consent_feedback_body, R.string.consent_feedback_effect),
        ConsentDef(COMMUNITY_CONTRIBUTION, ConsentKind.SWITCH, R.string.consent_community_title, R.string.consent_community_body, R.string.consent_community_effect),
        ConsentDef(SAVE_AUDIO_HISTORY, ConsentKind.SWITCH, R.string.consent_audio_title, R.string.consent_audio_body, R.string.consent_audio_effect),
        ConsentDef(CREATIVE_BACKUP, ConsentKind.SWITCH, R.string.consent_creative_title, R.string.consent_creative_body, R.string.consent_creative_effect),
        ConsentDef(ANALYTICS, ConsentKind.SWITCH, R.string.consent_analytics_title, R.string.consent_analytics_body, R.string.consent_analytics_effect),
        ConsentDef(MARKETING, ConsentKind.SWITCH, R.string.consent_marketing_title, R.string.consent_marketing_body, R.string.consent_marketing_effect),
        ConsentDef(PAYMENT_MANDATE, ConsentKind.INFO, R.string.consent_payment_title, R.string.consent_payment_body, R.string.consent_payment_effect),
    )

    fun of(code: String): ConsentDef? = all.firstOrNull { it.code == code }

    /** Codes the server never lets a person withdraw (422 `CONSENT_NOT_REVOCABLE`). */
    val notRevocable = setOf(TERMS, PRIVACY)
}
