package com.qalsmartkeyboard.app.link

import android.content.Intent
import com.qalsmartkeyboard.app.auth.AccountStatus
import com.qalsmartkeyboard.ime.KeyboardImeService
import com.qalsmartkeyboard.ime.ai.AiLink

/** What [MainActivity][com.qalsmartkeyboard.app.MainActivity] does for a toolbar link. */
enum class DeepLinkAction {
    /** Nothing to do (no link, or first-run setup is still running). */
    NONE,

    /** The settings screen. */
    SHOW_SETTINGS,

    /** The privacy dashboard (A2.6): consents, history, export, deletion. Where `AI_SETTINGS` leads. */
    SHOW_PRIVACY,

    /** The sign-in pane. */
    SHOW_SIGN_IN,

    /** The plans screen (a placeholder until billing screens exist). */
    SHOW_PLANS,

    /** The store page of the app (a 426 / update-required answer). */
    OPEN_STORE,
}

/**
 * Routes the keyboard toolbar's [AiLink] (sent by the IME as [KeyboardImeService.EXTRA_OPEN_LINK],
 * the enum's name) to a screen of the companion app (ADR 0007 D40). Pure, so the routing is a JVM test.
 *
 * | link | action |
 * |---|---|
 * | `SIGN_IN` | sign-in pane; settings when already signed in with an account |
 * | `AI_SETTINGS` | privacy dashboard (consents, AI history, export, deletion; A2.6) |
 * | `UPGRADE` | plans screen |
 * | `UPDATE_APP` | store page (even during first-run setup) |
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
        link == AiLink.SIGN_IN -> if (account == AccountStatus.Registered) DeepLinkAction.SHOW_SETTINGS else DeepLinkAction.SHOW_SIGN_IN
        link == AiLink.AI_SETTINGS -> DeepLinkAction.SHOW_PRIVACY
        else -> DeepLinkAction.SHOW_PLANS
    }
}
