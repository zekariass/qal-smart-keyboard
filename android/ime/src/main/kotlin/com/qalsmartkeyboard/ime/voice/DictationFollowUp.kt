package com.qalsmartkeyboard.ime.voice

import com.qalsmartkeyboard.ime.ai.AiDestination
import com.qalsmartkeyboard.ime.ai.AiFailure

/** The two one-tap actions offered on a fresh dictation (T8.12, ADR 0049). */
enum class FollowUpAction { GRAMMAR, TRANSLATE }

/** Where a [DictationFollowUp] is. */
sealed interface FollowUpPhase {
    /** The strip is offered; nothing was sent. */
    data object Offer : FollowUpPhase

    /** [action] is running on the server. */
    data class Working(val action: FollowUpAction) : FollowUpPhase

    /** [action]'s result replaced the dictated text; Undo is offered. */
    data class Done(val action: FollowUpAction) : FollowUpPhase

    /** [action] failed: [failure] from the server or local checks, or [moved] when the field changed meanwhile. */
    data class Failed(val failure: AiFailure?, val moved: Boolean = false, val action: FollowUpAction) : FollowUpPhase
}

/**
 * The strip shown after a dictation into the app's field (ADR 0049 D2/D3). [text] is user text (what was committed, or the
 * result after a [FollowUpPhase.Done]): [toString] never prints it, and nothing stores or logs it (N2).
 */
class DictationFollowUp(
    val text: String,
    /** The language the user spoke (am, ti, om, en, ar). */
    val spokenLanguage: String,
    /** Where Translate goes. */
    val destination: AiDestination,
    val phase: FollowUpPhase = FollowUpPhase.Offer,
) {
    override fun toString() = "DictationFollowUp($spokenLanguage, $phase)"
}
