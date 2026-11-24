package com.qalsmartkeyboard.app.billing

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.qalsmartkeyboard.api.billing.CreditTransaction
import com.qalsmartkeyboard.api.billing.Money
import com.qalsmartkeyboard.api.billing.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/** The "AI credits" section of the paywall (T8.10) in each offering mode, on the JVM (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h3000dp")
class PlansScreenCreditsTest {
    @get:Rule val compose = createComposeRule()

    private val packsBought = ArrayList<String>()
    private var signIns = 0
    private var historyOpens = 0
    private var focused = 0

    private val methods = listOf(PaymentMethod("ARIFPAY_CHECKOUT", "ARIFPAY", "ArifPay", "HOSTED_CHECKOUT", true, 1, true))
    private val offers = listOf(
        PlanOffer("PLAN_PREMIUM", "Premium", null, "PREMIUM", "MONTHLY", Money(29900, "ETB", 2), "299.00 ETB"),
        PlanOffer("PLAN_PREMIUM", "Premium", null, "PREMIUM", "ANNUAL", Money(299000, "ETB", 2), "2,990.00 ETB"),
    )
    private val packs = listOf(
        CreditPack("CREDITS_100", "100 AI credits", null, 100, 365, Money(4900, "ETB", 2), "49.00 ETB"),
        CreditPack("CREDITS_500", "500 AI credits", null, 500, 365, Money(19900, "ETB", 2), "199.00 ETB"),
    )
    private val nov2027 = 1_827_049_600_000L // 24 Nov 2027, 09:46 UTC

    private fun state(
        offers: List<PlanOffer> = this.offers,
        packs: List<CreditPack> = this.packs,
        buyer: BuyerKind = BuyerKind.REGISTERED,
        credits: CreditsInfo? = CreditsInfo(120, nov2027, 100),
        history: CreditHistoryState = CreditHistoryState(),
        checkout: CheckoutState = CheckoutState(),
    ) = PlansState(
        phase = PlansPhase.READY, buyer = buyer, offers = offers, packs = packs, methods = methods,
        selectedOffer = offers.firstOrNull()?.key, selectedMethod = "ARIFPAY_CHECKOUT", credits = credits, history = history, checkout = checkout,
    )

    private fun show(s: PlansState, focus: Boolean = false) {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        compose.setContent {
            PlansScreen(
                s,
                PlansActions(
                    buyPack = { packsBought += it }, signIn = { signIns++ }, openHistory = { historyOpens++ },
                    creditsFocused = { focused++ },
                ),
                focusCredits = focus,
            )
        }
    }

    @Test fun bothFamiliesShowPlansAndTheCreditsSection() {
        show(state())
        compose.onNodeWithText("Choose a plan").assertExists()
        compose.onNodeWithTag("pay_button").assertExists()
        compose.onNodeWithTag("credits_header").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("credits_balance").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("120").assertExists()
        compose.onNodeWithText("100 expire on Nov 24, 2027").assertExists()
        compose.onNodeWithText("500 AI credits").performScrollTo().assertExists()
        compose.onNodeWithText("500 credits").assertExists()
        compose.onAllNodesWithText("One-time purchase · no subscription · valid 365 days", substring = true).assertCountEquals(2)
        compose.onNodeWithTag("pack_buy_CREDITS_500").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("CREDITS_500"), packsBought)
    }

    @Test fun creditsOnlyShowsNoPlansAndTheCreditsTitle() {
        show(state(offers = emptyList()))
        compose.onAllNodesWithText("AI credits").assertCountEquals(2) // the page title and the section header
        compose.onAllNodesWithText("Choose a plan").assertCountEquals(0)
        compose.onAllNodesWithText("Your plan").assertCountEquals(0)
        compose.onNodeWithTag("pay_button").assertDoesNotExist()
        compose.onNodeWithText("Pay with").assertExists()
        compose.onNodeWithTag("pack_buy_CREDITS_100").performScrollTo().assertIsDisplayed()
    }

    @Test fun premiumOnlyHasNoCreditsSection() {
        show(state(packs = emptyList(), credits = CreditsInfo(0, null, 0)))
        compose.onNodeWithText("Choose a plan").assertExists()
        compose.onAllNodesWithText("AI credits").assertCountEquals(0)
        compose.onNodeWithTag("credits_header").assertDoesNotExist()
    }

    @Test fun aGuestSeesThePacksAndBuyingAsksToSignIn() {
        show(state(offers = emptyList(), buyer = BuyerKind.GUEST))
        compose.onNodeWithText("Buying credits needs an account", substring = true).performScrollTo().assertExists()
        compose.onNodeWithTag("pack_buy_CREDITS_100").performScrollTo().performClick()
        assertEquals(1, signIns)
        assertTrue(packsBought.isEmpty())
        compose.onAllNodesWithText("Sign in to buy").assertCountEquals(2)
    }

    @Test fun anUnknownBalanceHidesTheBalanceRowButKeepsThePacks() {
        show(state(credits = null))
        compose.onNodeWithTag("credits_balance").assertDoesNotExist()
        compose.onNodeWithTag("pack_CREDITS_500").performScrollTo().assertExists()
    }

    @Test fun packButtonsAreDisabledWhileACheckoutStarts() {
        show(state(checkout = CheckoutState(CheckoutStep.STARTING), offers = emptyList()).copy(buyingPack = "CREDITS_100"))
        compose.onNodeWithTag("pack_buy_CREDITS_100").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("pack_buy_CREDITS_500").performScrollTo().assertIsNotEnabled()
    }

    @Test fun aKeyboardDeepLinkScrollsToTheSectionOnce() {
        show(state(), focus = true)
        compose.waitForIdle()
        assertEquals(1, focused)
        compose.onNodeWithTag("credits_header").assertIsDisplayed()
    }

    @Test fun theHistoryLinkOpensTheLedger() {
        show(state())
        compose.onNodeWithTag("credit_history_open").performScrollTo().performClick()
        assertEquals(1, historyOpens)
    }

    @Test fun theHistoryPageShowsRowsAndLoadMore() {
        val h = CreditHistoryState(
            open = true,
            items = listOf(
                CreditTransaction("1", "PURCHASE", 100.0, 120.75, null, nov2027),
                CreditTransaction("2", "CONSUMPTION", -0.5, 20.75, "TRANSLATE", nov2027),
            ),
            nextCursor = "c2",
        )
        show(state(history = h))
        compose.onNodeWithText("Credits bought").assertExists()
        compose.onNodeWithText("+100").assertExists()
        compose.onNodeWithText("Used").assertExists()
        compose.onNodeWithText("-0.5").assertExists()
        compose.onNodeWithText("Balance: 20.75").assertExists()
        compose.onNodeWithTag("credit_history_more").assertExists()
    }

    @Test fun theHistoryPageHasEmptyAndErrorStates() {
        show(state(history = CreditHistoryState(open = true)))
        compose.onNodeWithText("No credit activity yet").assertExists()
    }

    @Test fun theCreditReceiptShowsTheCreditsAndTheirExpiry() {
        val receipt = Receipt("EAK-1", "500 AI credits", "ONE_TIME", Money(19900, "ETB", 2), null, nov2027, nov2027 + 365L * 86_400_000L, credits = 500)
        show(state(checkout = CheckoutState(CheckoutStep.PAID, receipt = receipt)))
        compose.onNodeWithText("Credits added").assertExists()
        compose.onNodeWithText("Credits expire on").assertExists()
        compose.onNodeWithText("500").assertExists()
    }
}
