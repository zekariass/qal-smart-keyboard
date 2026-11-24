package com.qalsmartkeyboard.app.billing

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The hosted checkout in a Custom Tab (ADR 0047, M4.11) on the JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckoutScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val url = "https://checkout.arifpay.org/s/abc"
    private var returns = mutableListOf<String?>()
    private var closes = 0

    private fun show(checkoutUrl: String = url) = compose.setContent {
        CheckoutScreen(checkoutUrl, onReturn = { returns += it }, onClose = { closes++ })
    }

    private fun launched(): List<Intent> {
        val app = shadowOf(compose.activity)
        return generateSequence { app.nextStartedActivity }.toList()
    }

    private fun pauseAndResume() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test fun opensThePageOnceInTheBrowserWhenThereIsNoCustomTabsProvider() {
        show()
        compose.waitForIdle()
        val started = launched()
        assertEquals(1, started.size)
        assertEquals(Intent.ACTION_VIEW, started[0].action)
        assertEquals(Uri.parse(url), started[0].data)
        compose.onNodeWithText("Finish paying on the ArifPay page").assertExists()
    }

    @Test fun rotationDoesNotOpenItAgain() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CheckoutScreen(url, onReturn = { returns += it }, onClose = { closes++ }) }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(1, launched().size)
    }

    @Test fun aCustomTabIsUsedWhenAProviderExists() {
        val ctx = compose.activity
        assertTrue(launchCheckout(ctx, url, 0xFFFFFFFF.toInt(), provider = "com.android.chrome"))
        val i = shadowOf(ctx).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("com.android.chrome", i.`package`)
        assertEquals(Uri.parse(url), i.data)
        assertTrue(i.hasExtra(CustomTabsIntent.EXTRA_TITLE_VISIBILITY_STATE))
    }

    @Test fun anUnsafeStartUrlShowsTheErrorAndOpensNothing() {
        show("http://checkout.arifpay.org/s/abc")
        compose.waitForIdle()
        assertTrue(launched().isEmpty())
        compose.onNodeWithText("The payment page didn't load").assertExists()
        compose.onNodeWithText("Cancel payment").performClick()
        assertEquals(1, closes)
    }

    @Test fun comingBackAfterTheTabCallsReturnOnce() {
        show()
        compose.waitForIdle()
        pauseAndResume()
        assertEquals(listOf<String?>(null), returns)
        pauseAndResume()
        assertEquals(1, returns.size)
        assertEquals(0, closes)
    }

    @Test fun ivePaidReturnsOnceAndCancelCloses() {
        show()
        compose.waitForIdle()
        compose.onNodeWithText("I've paid").performClick()
        compose.onNodeWithText("I've paid").performClick()
        assertEquals(1, returns.size)
        assertNull(returns[0])
        assertEquals(0, closes)
    }

    @Test fun cancelAndBackClose() {
        show()
        compose.waitForIdle()
        compose.onNodeWithText("Cancel payment").performClick()
        assertEquals(1, closes)
        assertTrue(returns.isEmpty())
    }

    @Test fun backCancels() {
        show()
        compose.waitForIdle()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(1, closes)
    }

    @Test fun openAgainRelaunches() {
        show()
        compose.waitForIdle()
        launched()
        compose.onNodeWithText("Open payment page again").performClick()
        assertEquals(1, launched().size)
        assertFalse(returns.isNotEmpty())
        assertNotNull(url)
    }
}
