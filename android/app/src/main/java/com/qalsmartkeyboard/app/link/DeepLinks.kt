package com.qalsmartkeyboard.app.link

import android.content.Intent
import com.qalsmartkeyboard.app.auth.AccountStatus
import com.qalsmartkeyboard.ime.KeyboardImeService
import com.qalsmartkeyboard.ime.ai.AiLink

/** What [MainActivity][com.qalsmartkeyboard.app.MainActivity] does for a toolbar link. */
enum class DeepLinkAction {
    /** Nothing to do (no link, or first-run setup is still running). */
    NONE,

    /** The privacy dashboard (A2.6): consents, history, export, deletion. Where `AI_SETTINGS` leads. */
    SHOW_PRIVACY,

    /**
     * The sign-in pane opened from the keyboard (its "Sign in to use AI" link, ADR 0035). It sits over the keyboard
     * settings: finishing or leaving it shows those settings with "Back to typing", which returns to the app the user
     * was typing in.
     */
    SHOW_SIGN_IN,

    /** The plans screen (paywall and checkout, ADR 0047); leaving it returns to the app the user was typing in. */
    SHOW_PLANS,

    /** The store page of the app (a 426 / update-required answer). */
    OPEN_STORE,

    /**
     * The settings screen opened from the keyboard's gear: it shows "Back to typing", and that or
     * Back finishes the screen so the user lands back in the app they were typing in.
     */
    SHOW_KEYBOARD_SETTINGS,

    /**
     * The "Write Email with AI" screen opened from the keyboard's "Open full editor" (ADR 0031). The keyboard's inputs
     * come through the in-memory handoff, never the intent; Back finishes the screen so the user lands back in the app
     * they were writing in (to paste the copied e-mail).
     */
    SHOW_EMAIL_WRITER,

    /** The settings' Languages page, opened from the keyboard's tools panel (ADR 0035); Back returns to typing. */
    SHOW_KEYBOARD_LANGUAGES,

    /** The theme picker, opened from the keyboard's Theme shortcut or tile (ADR 0035); Back returns to typing. */
    SHOW_KEYBOARD_THEMES,

    /** The settings' "Translate into" page, opened from the translate panel's settings button (ADR 0037); Back returns to typing. */
    SHOW_KEYBOARD_TRANSLATION,
}

/**
 * Routes the keyboard toolbar's [AiLink] (sent by the IME as [KeyboardImeService.EXTRA_OPEN_LINK],
 * the enum's name) to a screen of the companion app (ADR 0007 D40). Pure, so the routing is a JVM test.
 *
 * | link | action |
 * |---|---|
 * | `SIGN_IN` | sign-in pane over the keyboard settings; the keyboard settings when already signed in with an account |
 * | `AI_SETTINGS` | privacy dashboard (consents, AI history, export, deletion; A2.6) |
 * | `UPGRADE` | plans screen (paywall; a keyboard lock badge or "See plans"), returning to typing when left |
 * | `UPDATE_APP` | store page (even during first-run setup) |
 * | `SETTINGS` | keyboard settings, opened from the keyboard's gear (with a way back to typing) |
 * | `EMAIL_WRITER` | the e-mail writer with the keyboard's inputs (ADR 0031) |
 * | `LANGUAGES` | the settings' Languages page (the keyboard's tools panel, ADR 0035) |
 * | `THEMES` | the theme picker (the keyboard's Theme shortcut or tile, ADR 0035) |
 * | `TRANSLATION` | the settings' "Translate into" page (the translate panel's settings button, ADR 0037) |
 *
 * While first-run setup has not finished, every link except `UPDATE_APP` is ignored: the setup flow
 * has its own account step and is not interrupted. An unknown or missing extra does nothing.
 */
object DeepLinks {
    fun parse(extra: String?): AiLink? = AiLink.entries.firstOrNull { it.name == extra }

    fun fromIntent(intent: Intent?): AiLink? = parse(intent?.getStringExtra(KeyboardImeService.EXTRA_OPEN_LINK))

    fun route(link: AiLink?, account: AccountStatus, onboardingCompleted: Boolean): DeepLinkAction = when {
        link == null -> DeepLinkAction.NONE
        link == AiLink.UPDATE_APP -> DeepLinkAction.OPEN_STORE
        !onboardingCompleted -> DeepLinkAction.NONE
        link == AiLink.SIGN_IN -> if (account == AccountStatus.Registered) DeepLinkAction.SHOW_KEYBOARD_SETTINGS else DeepLinkAction.SHOW_SIGN_IN
        link == AiLink.AI_SETTINGS -> DeepLinkAction.SHOW_PRIVACY
        link == AiLink.SETTINGS -> DeepLinkAction.SHOW_KEYBOARD_SETTINGS
        link == AiLink.EMAIL_WRITER -> DeepLinkAction.SHOW_EMAIL_WRITER
        link == AiLink.LANGUAGES -> DeepLinkAction.SHOW_KEYBOARD_LANGUAGES
        link == AiLink.THEMES -> DeepLinkAction.SHOW_KEYBOARD_THEMES
        link == AiLink.TRANSLATION -> DeepLinkAction.SHOW_KEYBOARD_TRANSLATION
        else -> DeepLinkAction.SHOW_PLANS
    }
}
