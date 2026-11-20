package com.qalsmartkeyboard.app.billing

import com.qalsmartkeyboard.api.DistributionChannel
import com.qalsmartkeyboard.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checkout WebView's URL policy (ADR 0047) and the build's channel. */
class CheckoutUrlPolicyTest {
    private val attempt = "0193a5c2-7a10-7d11-8e2f-aaaaaaaaaaaa"

    @Test fun theReturnUrlOnAnyHostAndTheAppReturnUriCloseTheCheckout() {
        assertEquals(CheckoutNavigation.Return("success", viaServer = false), CheckoutUrlPolicy.decide("https://api.qal.example/api/v1/billing/return/$attempt?result=success"))
        assertEquals(CheckoutNavigation.Return("cancel", viaServer = false), CheckoutUrlPolicy.decide("http://localhost:8080/api/v1/billing/return/$attempt?result=cancel"))
        assertEquals(CheckoutNavigation.Return(null, viaServer = false), CheckoutUrlPolicy.decide("https://x.ngrok.app/api/v1/billing/return/$attempt?result=%3Cscript%3E"))
        assertEquals(
            CheckoutNavigation.Return("error", viaServer = true),
            CheckoutUrlPolicy.decide("qalkeyboard://billing/return?orderId=o&attemptId=$attempt&result=error"),
        )
        // Other app-scheme paths and malformed return ids are not returns.
        assertEquals(CheckoutNavigation.Block, CheckoutUrlPolicy.decide("qalkeyboard://settings"))
        assertEquals(CheckoutNavigation.Load, CheckoutUrlPolicy.decide("https://api.qal.example/api/v1/billing/return/not-a-uuid"))
    }

    @Test fun onlyHttpsPagesLoad() {
        assertEquals(CheckoutNavigation.Load, CheckoutUrlPolicy.decide("https://checkout.arifpay.org/s/abc"))
        assertEquals(CheckoutNavigation.Block, CheckoutUrlPolicy.decide("http://checkout.arifpay.org/s/abc"))
        assertTrue(CheckoutUrlPolicy.isAllowedStart("https://checkout.arifpay.org/s/abc"))
        assertFalse(CheckoutUrlPolicy.isAllowedStart("http://checkout.arifpay.org/s/abc"))
        assertFalse(CheckoutUrlPolicy.isAllowedStart(null))
        assertFalse(CheckoutUrlPolicy.isAllowedStart("https:///nohost"))
    }

    @Test fun dangerousSchemesAreBlocked() {
        for (u in listOf("javascript:alert(1)", "file:///sdcard/x", "content://com.qalsmartkeyboard.app/x", "data:text/html,hi", "blob:https://a/b", "about:blank", "intent", "")) {
            assertEquals(u, CheckoutNavigation.Block, CheckoutUrlPolicy.decide(u))
        }
    }

    @Test fun appLinksGoToOtherApps() {
        for (u in listOf("intent://pay#Intent;scheme=telebirr;package=cn.tydic.ethiopay;end", "telebirr://pay?x=1", "tel:+251911000000", "mailto:help@arifpay.net", "market://details?id=x")) {
            assertEquals(u, CheckoutNavigation.External(u), CheckoutUrlPolicy.decide(u))
        }
    }

    @Test fun theFlavourSellsOnItsChannel() {
        val expected = if (BuildConfig.FLAVOR == "play") DistributionChannel.GOOGLE_PLAY else DistributionChannel.DIRECT_DOWNLOAD
        assertEquals(expected, DistributionChannel.ofFlavor(BuildConfig.FLAVOR))
        if (BuildConfig.FLAVOR == "direct") assertEquals("", BuildConfig.REVENUECAT_ANDROID_KEY)
    }
}
