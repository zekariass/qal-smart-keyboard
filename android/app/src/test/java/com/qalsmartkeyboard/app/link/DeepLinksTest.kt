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
        assertEquals(DeepLinkAction.SHOW_SETTINGS, DeepLinks.route(AiLink.SIGN_IN, AccountStatus.Registered, true))
        assertEquals(DeepLinkAction.SHOW_PRIVACY, DeepLinks.route(AiLink.AI_SETTINGS, out, true))
        assertEquals(DeepLinkAction.SHOW_PRIVACY, DeepLinks.route(AiLink.AI_SETTINGS, AccountStatus.Guest, true))
        assertEquals(DeepLinkAction.SHOW_PLANS, DeepLinks.route(AiLink.UPGRADE, AccountStatus.Registered, true))
        assertEquals(DeepLinkAction.OPEN_STORE, DeepLinks.route(AiLink.UPDATE_APP, out, true))
    }

    @Test fun firstRunSetupIsNotInterruptedExceptByAnUpdate() {
        for (l in listOf(AiLink.SIGN_IN, AiLink.AI_SETTINGS, AiLink.UPGRADE)) {
            assertEquals(DeepLinkAction.NONE, DeepLinks.route(l, out, false))
        }
        assertEquals(DeepLinkAction.OPEN_STORE, DeepLinks.route(AiLink.UPDATE_APP, out, false))
    }

    @Test fun noLinkDoesNothing() {
        assertEquals(DeepLinkAction.NONE, DeepLinks.route(null, AccountStatus.Registered, true))
    }
}
