package com.qalsmartkeyboard.app.billing

import android.content.Intent
import android.net.Uri
import com.qalsmartkeyboard.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The deep-link landing of the hosted checkout (ADR 0047, M4.11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillingReturnActivityTest {
    private fun launch(uri: String): BillingReturnActivity {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        return Robolectric.buildActivity(BillingReturnActivity::class.java, intent).create().get()
    }

    @Test fun theReturnUriBringsMainActivityForwardAndFinishes() {
        val a = launch("qalkeyboard://billing/return?orderId=o&attemptId=a&result=success")
        val next = shadowOf(a).nextStartedActivity
        assertNotNull(next)
        assertEquals(MainActivity::class.java.name, next.component!!.className)
        assertTrue(next.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(next.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertNull(next.data)
        assertTrue(a.isFinishing)
    }

    @Test fun anythingElseIsIgnored() {
        for (u in listOf("qalkeyboard://settings", "qalkeyboard://billing/other", "qalkeyboard://other/return", "https://x.example/return")) {
            val a = launch(u)
            assertNull(u, shadowOf(a).nextStartedActivity)
            assertTrue(a.isFinishing)
        }
        assertFalse(BillingReturnActivity.isBillingReturn(null))
    }
}
