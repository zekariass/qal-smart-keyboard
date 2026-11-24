package com.qalsmartkeyboard.app.billing

import com.qalsmartkeyboard.api.DistributionChannel
import com.qalsmartkeyboard.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checkout start-URL policy (ADR 0047) and the build's channel. */
class CheckoutUrlPolicyTest {
    private val attempt = "0193a5c2-7a10-7d11-8e2f-aaaaaaaaaaaa"

    @Test fun onlyHttpsPagesWithAHostMayStart() {
        assertTrue(CheckoutUrlPolicy.isAllowedStart("https://checkout.arifpay.org/s/abc"))
        assertFalse(CheckoutUrlPolicy.isAllowedStart("http://checkout.arifpay.org/s/abc"))
        assertFalse(CheckoutUrlPolicy.isAllowedStart(null))
        assertFalse(CheckoutUrlPolicy.isAllowedStart("https:///nohost"))
        for (u in listOf("javascript:alert(1)", "file:///sdcard/x", "content://com.qalsmartkeyboard.app/x", "data:text/html,hi", "intent://pay#Intent;scheme=telebirr;end", "qalkeyboard://billing/return", "")) {
            assertFalse(u, CheckoutUrlPolicy.isAllowedStart(u))
        }
    }

    @Test fun theFlavourSellsOnItsChannel() {
        val expected = if (BuildConfig.FLAVOR == "play") DistributionChannel.GOOGLE_PLAY else DistributionChannel.DIRECT_DOWNLOAD
        assertEquals(expected, DistributionChannel.ofFlavor(BuildConfig.FLAVOR))
        if (BuildConfig.FLAVOR == "direct") assertEquals("", BuildConfig.REVENUECAT_ANDROID_KEY)
    }
}
