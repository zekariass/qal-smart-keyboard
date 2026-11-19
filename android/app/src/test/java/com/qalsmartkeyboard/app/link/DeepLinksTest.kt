package com.qalsmartkeyboard.app.link

import com.qalsmartkeyboard.app.auth.AccountStatus
import com.qalsmartkeyboard.ime.KeyboardImeService
import com.qalsmartkeyboard.ime.ai.AiLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinksTest {
    private val out = AccountStatus.SignedOut(null)

    @Test fun parsesEveryLinkTheKeyboardCanSend() {
        for (l in AiLink.entries) assertEquals(l, DeepLinks.parse(l.name))
        assertNull(DeepLinks.parse(null))
        assertNull(DeepLinks.parse("EVIL"))
        assertNull(DeepLinks.parse(""))
    }

    @Test fun theExtraNameMatchesTheKeyboardsContract() {
        assertEquals("com.qalsmartkeyboard.OPEN_LINK", KeyboardImeService.EXTRA_OPEN_LINK)
    }

    @Test fun routesEachLinkToItsScreen() {
        assertEquals(DeepLinkAction.SHOW_SIGN_IN, DeepLinks.route(AiLink.SIGN_IN, out, true))
        assertEquals(DeepLinkAction.SHOW_SIGN_IN, DeepLinks.route(AiLink.SIGN_IN, AccountStatus.Guest, true))
        // ADR 0035: already signed in with an account: the keyboard settings, which show who (with "Back to typing").
        assertEquals(DeepLinkAction.SHOW_KEYBOARD_SETTINGS, DeepLinks.route(AiLink.SIGN_IN, AccountStatus.Registered, true))
        assertEquals(DeepLinkAction.SHOW_PRIVACY, DeepLinks.route(AiLink.AI_SETTINGS, out, true))
        assertEquals(DeepLinkAction.SHOW_PRIVACY, DeepLinks.route(AiLink.AI_SETTINGS, AccountStatus.Guest, true))
        assertEquals(DeepLinkAction.SHOW_PLANS, DeepLinks.route(AiLink.UPGRADE, AccountStatus.Registered, true))
        assertEquals(DeepLinkAction.OPEN_STORE, DeepLinks.route(AiLink.UPDATE_APP, out, true))
        for (account in listOf(out, AccountStatus.Guest, AccountStatus.Registered)) {
            assertEquals(DeepLinkAction.SHOW_KEYBOARD_SETTINGS, DeepLinks.route(AiLink.SETTINGS, account, true))
        }
    }

    @Test fun keyboardGearExtraOpensKeyboardSettings() {
        // The gear sends EXTRA_OPEN_LINK = "SETTINGS" (AiLink.name).
        assertEquals(AiLink.SETTINGS, DeepLinks.parse("SETTINGS"))
        assertEquals(DeepLinkAction.SHOW_KEYBOARD_SETTINGS, DeepLinks.route(DeepLinks.parse("SETTINGS"), out, true))
    }

    @Test fun theKeyboardsOpenFullEditorOpensTheEmailWriter() {
        // ADR 0031: the link carries no text (the inputs travel in memory, AiEmailHandoff).
        assertEquals(AiLink.EMAIL_WRITER, DeepLinks.parse("EMAIL_WRITER"))
        for (account in listOf(out, AccountStatus.Guest, AccountStatus.Registered)) {
            assertEquals(DeepLinkAction.SHOW_EMAIL_WRITER, DeepLinks.route(AiLink.EMAIL_WRITER, account, true))
        }
    }

    @Test fun theKeyboardsToolsOpenLanguagesAndThemes() {
        // ADR 0035: the tools panel's Languages tile and the Theme shortcut / tile.
        for (account in listOf(out, AccountStatus.Guest, AccountStatus.Registered)) {
            assertEquals(DeepLinkAction.SHOW_KEYBOARD_LANGUAGES, DeepLinks.route(AiLink.LANGUAGES, account, true))
            assertEquals(DeepLinkAction.SHOW_KEYBOARD_THEMES, DeepLinks.route(AiLink.THEMES, account, true))
        }
        assertEquals(AiLink.THEMES, DeepLinks.parse("THEMES"))
        assertEquals(AiLink.LANGUAGES, DeepLinks.parse("LANGUAGES"))
    }

    @Test fun theTranslatePanelsSettingsButtonOpensTranslateInto() {
        // ADR 0037: the translate panel's settings button opens the settings' "Translate into" page.
        for (account in listOf(out, AccountStatus.Guest, AccountStatus.Registered)) {
            assertEquals(DeepLinkAction.SHOW_KEYBOARD_TRANSLATION, DeepLinks.route(AiLink.TRANSLATION, account, true))
        }
        assertEquals(AiLink.TRANSLATION, DeepLinks.parse("TRANSLATION"))
    }

    @Test fun firstRunSetupIsNotInterruptedExceptByAnUpdate() {
        for (l in listOf(AiLink.SIGN_IN, AiLink.AI_SETTINGS, AiLink.UPGRADE, AiLink.SETTINGS, AiLink.EMAIL_WRITER, AiLink.LANGUAGES, AiLink.THEMES, AiLink.TRANSLATION)) {
            assertEquals(DeepLinkAction.NONE, DeepLinks.route(l, out, false))
        }
        assertEquals(DeepLinkAction.OPEN_STORE, DeepLinks.route(AiLink.UPDATE_APP, out, false))
    }

    @Test fun noLinkDoesNothing() {
        assertEquals(DeepLinkAction.NONE, DeepLinks.route(null, AccountStatus.Registered, true))
    }
}
