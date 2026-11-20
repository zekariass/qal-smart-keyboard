package com.qalsmartkeyboard.app.billing

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ClientInfo
import com.qalsmartkeyboard.api.ClientSurface
import com.qalsmartkeyboard.api.DistributionChannel
import com.qalsmartkeyboard.api.InMemoryTokenStore
import com.qalsmartkeyboard.api.SessionManager
import com.qalsmartkeyboard.api.TokenPair
import com.qalsmartkeyboard.api.TransportConfig
import com.qalsmartkeyboard.api.billing.BillingClient
import com.qalsmartkeyboard.api.billing.Money
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The paywall states and the gateway/store checkout flows against MockWebServer (ADR 0047). */
class PlansModelTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var route: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }
    private val pending = MemoryPendingCheckoutStore()
    private var refreshes = 0
    private var buyer = BuyerKind.REGISTERED
    private val pauses = mutableListOf<Long>()
    private var orderStatus = "AWAITING_PAYMENT"
    private var attemptStatus = "REQUIRES_ACTION"
    private var optionsBody = OPTIONS
    private var plan = "FREE"
    private lateinit var model: PlansModel

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return route(request)
            }
        }
        server.start()
        route = ::backend
        model = model()
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun model(store: StoreBilling = StoreBilling.Unavailable): PlansModel {
        val clients = ApiClients(
            TransportConfig(
                baseUrl = "http://localhost:${server.port}",
                client = ClientInfo("1.0", ClientSurface.COMPANION, distributionChannel = DistributionChannel.DIRECT_DOWNLOAD),
                readTimeoutMs = 5_000,
            ),
            SessionManager(InMemoryTokenStore(TokenPair("access-token", "refresh-token"))),
        )
        return PlansModel(
            billing = BillingClient(clients),
            buyer = { buyer },
            userId = { "user-1" },
            pending = pending,
            refresher = PlanRefresher { refreshes++; plan = if (orderStatus == "PAID") "PREMIUM" else plan },
            store = store,
            locale = { "am" },
            packageName = "com.qalsmartkeyboard.app",
            pause = { pauses += it },
            now = { NOW },
        )
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun backend(r: RecordedRequest): MockResponse {
        val path = r.path!!.removePrefix("/api/v1").substringBefore('?')
        return when {
            path == "/billing/options" -> json(200, optionsBody)
            path == "/me/entitlements" -> json(200, """{"plan":{"planCode":"$plan","displayName":"${if (plan == "FREE") "Free" else "Premium"}","planRank":0,"source":"DEFAULT"${if (plan == "FREE") "" else ""","effectiveUntil":"2026-12-20T09:00:00Z""""}},"features":[],"entitlementToken":{}}""")
            path == "/me/subscriptions" -> json(200, """{"items":[]}""")
            r.method == "POST" && path == "/billing/orders" -> json(201, order("AWAITING_PAYMENT"))
            r.method == "POST" && path == "/billing/orders/$ORDER_ID/attempts" -> json(201, attempt(attemptStatus))
            path == "/billing/orders/$ORDER_ID/attempts/$ATTEMPT_ID" -> json(200, attempt(attemptStatus))
            path == "/billing/orders/$ORDER_ID" -> json(200, order(orderStatus))
            path == "/billing/return/$ATTEMPT_ID" -> MockResponse().setResponseCode(303).setHeader("Location", "qalkeyboard://billing/return")
            path == "/billing/store-purchases" -> json(200, """{"storePurchaseId":"$ORDER_ID","status":"ACTIVE","environment":"SANDBOX"}""")
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun calls() = requests.map { it.method + " " + it.path!!.removePrefix("/api/v1").substringBefore('?') }

    @Test fun readyShowsServerPricesAndMethodsDefaultFirst() = runBlocking {
        model.load()
        val s = model.state.value
        assertEquals(PlansPhase.READY, s.phase)
        assertEquals(listOf("PLAN_PREMIUM/MONTHLY", "PLAN_PREMIUM/ANNUAL"), s.offers.map { it.key })
        assertEquals(Money(29900, "ETB", 2), s.offers[0].price)
        assertEquals(listOf("ARIFPAY_CHECKOUT", "CHAPA_CHECKOUT"), s.methods.map { it.methodCode })
        assertEquals("PLAN_PREMIUM/MONTHLY", s.selectedOffer)
        assertEquals("ARIFPAY_CHECKOUT", s.selectedMethod)
        assertTrue(s.current!!.isFree)
        assertTrue(MoneyFormat.format(s.offers[0].price, java.util.Locale.US).contains("299.00"))
    }

    @Test fun signedOutOfflineErrorAndNotAvailable() = runBlocking {
        buyer = BuyerKind.SIGNED_OUT
        model.load()
        assertEquals(PlansPhase.SIGNED_OUT, model.state.value.phase)
        assertTrue(requests.isEmpty())

        buyer = BuyerKind.REGISTERED
        route = { json(500, """{"type":"about:blank","title":"t","status":500,"code":"INTERNAL","correlationId":"c"}""") }
        model.load()
        assertEquals(PlansPhase.ERROR, model.state.value.phase)

        route = ::backend
        optionsBody = """{"marketCountry":"ZZ","purchaseFlow":"NONE","purchaseUiAllowed":false,"products":[],"paymentMethods":[],"policyEvaluatedAt":"2026-11-20T09:00:00Z"}"""
        model.load()
        assertEquals(PlansPhase.NOT_AVAILABLE, model.state.value.phase)
        assertEquals(UnavailableReason.MARKET, model.state.value.unavailable)

        // Store billing for this market, but this build has no store SDK (the direct APK).
        optionsBody = STORE_OPTIONS
        model.load()
        assertEquals(UnavailableReason.STORE_NOT_IN_BUILD, model.state.value.unavailable)

        server.shutdown()
        val offline = model()
        offline.load()
        assertEquals(PlansPhase.OFFLINE, offline.state.value.phase)
    }

    @Test fun gatewayCheckoutReturnPollPaidRefreshesTheEntitlement() = runBlocking {
        model.load()
        model.buy()
        var s = model.state.value
        assertEquals(CheckoutStep.CHECKOUT, s.checkout.step)
        assertEquals("https://checkout.arifpay.org/s/abc", s.checkout.checkoutUrl)
        val orderReq = requests.first { it.method == "POST" && it.path!!.endsWith("/billing/orders") }
        assertNotNull(orderReq.getHeader("Idempotency-Key"))
        assertFalse("the client never sends a price", orderReq.body.clone().readUtf8().contains("amount"))
        assertEquals(ATTEMPT_ID, pending.load()!!.attemptId)

        // The WebView intercepts the return URL; the server verifies; the second poll sees PAID.
        var polls = 0
        route = { r ->
            if (r.path!!.endsWith("/billing/orders/$ORDER_ID")) {
                polls++
                if (polls >= 2) orderStatus = "PAID"
            }
            backend(r)
        }
        assertEquals(CheckoutNavigation.Return("success", false), CheckoutUrlPolicy.decide("https://api.example/api/v1/billing/return/$ATTEMPT_ID?result=success"))
        model.onReturned("success")
        s = model.state.value
        assertEquals(CheckoutStep.PAID, s.checkout.step)
        assertEquals("EAK-ET-2611-7Q4M2X", s.checkout.receipt!!.orderReference)
        assertEquals(Money(29900, "ETB", 2), s.checkout.receipt!!.amount)
        assertEquals(1, refreshes)
        assertEquals("PREMIUM", s.current!!.planCode)
        assertNull(pending.load())
        assertTrue(calls().contains("GET /billing/return/$ATTEMPT_ID"))
    }

    @Test fun aFailedOrderEndsTheWait() = runBlocking {
        model.load()
        model.buy()
        orderStatus = "FAILED"
        model.onReturned("error")
        assertEquals(CheckoutStep.FAILED, model.state.value.checkout.step)
        assertEquals("FAILED", model.state.value.checkout.finalStatus)
        assertEquals(0, refreshes)
        assertNull(pending.load())
    }

    @Test fun aFailedAttemptShowsNotCompleted() = runBlocking {
        model.load()
        model.buy()
        attemptStatus = "FAILED"
        model.onReturned("error")
        assertEquals(CheckoutStep.NOT_COMPLETED, model.state.value.checkout.step)
    }

    @Test fun noAnswerInTheWindowIsStillConfirmingAndResumesAfterProcessDeath() = runBlocking {
        model.load()
        model.buy()
        model.onReturned("success")
        assertEquals(CheckoutStep.STILL_CONFIRMING, model.state.value.checkout.step)
        assertEquals(PlansModel.POLL_SCHEDULE_MS.filter { it > 0 }, pauses)
        assertNotNull(pending.load())
        assertEquals(0, refreshes)

        // A new process: the pending order is confirmed first and pays this time.
        orderStatus = "PAID"
        val again = model()
        again.load()
        assertEquals(CheckoutStep.PAID, again.state.value.checkout.step)
        assertEquals(1, refreshes)
    }

    @Test fun closingTheCheckoutWithoutPayingIsNotCompleted() = runBlocking {
        model.load()
        model.buy()
        model.onCheckoutClosed()
        assertEquals(CheckoutStep.NOT_COMPLETED, model.state.value.checkout.step)
        model.closeCheckout()
        assertNull(pending.load())
        assertEquals(CheckoutStep.IDLE, model.state.value.checkout.step)
    }

    @Test fun guestsSeePricesButMustSignInToBuy() = runBlocking {
        buyer = BuyerKind.GUEST
        model.load()
        assertEquals(PlansPhase.READY, model.state.value.phase)
        model.buy()
        assertEquals(PlansMessage.NEEDS_ACCOUNT, model.state.value.message)
        assertFalse(calls().any { it.startsWith("POST") })
    }

    @Test fun storePurchaseIsReportedAndOnlyTheServerUnlocks() = runBlocking {
        optionsBody = STORE_OPTIONS
        val fake = FakeStore()
        val m = model(fake)
        m.load()
        assertEquals(PlansPhase.READY, m.state.value.phase)
        assertTrue(m.state.value.storeFlow)
        assertEquals("monthly", m.state.value.selectedOffer)
        plan = "PREMIUM"
        m.buyFromStore { throw UnsupportedOperationException("no activity in JVM tests") }
        assertEquals(CheckoutStep.PAID, m.state.value.checkout.step)
        val report = requests.first { it.path!!.endsWith("/billing/store-purchases") }
        assertTrue(report.body.clone().readUtf8().contains("\"purchaseToken\":\"tok\""))
        assertNotNull(report.getHeader("Idempotency-Key"))
        assertEquals(1, refreshes)
    }

    @Test fun storeHiddenWithoutKeyAndCancelledPurchaseChangesNothing() = runBlocking {
        optionsBody = STORE_OPTIONS
        val m = model(FakeStore(outcome = StorePurchaseOutcome.Cancelled))
        m.load()
        m.buyFromStore { throw UnsupportedOperationException() }
        assertEquals(CheckoutStep.IDLE, m.state.value.checkout.step)
        assertFalse(calls().any { it.endsWith("/billing/store-purchases") })
    }

    private class FakeStore(val outcome: StorePurchaseOutcome = StorePurchaseOutcome.Purchased("premium_monthly", "tok", "com.qalsmartkeyboard.app")) : StoreBilling {
        override val available = true
        override suspend fun offers(appUserId: String) = listOf(StoreOffer("monthly", "premium_monthly", "MONTHLY", "Premium", "\$4.99"))
        override suspend fun purchase(host: PurchaseHost, appUserId: String, offerId: String) = outcome
    }

    companion object {
        const val NOW = 1_795_000_000_000L
        const val ORDER_ID = "0193a5c2-7a10-7d11-8e2f-1a2b3c4d5e6f"
        const val ATTEMPT_ID = "0193a5c2-7a10-7d11-8e2f-aaaaaaaaaaaa"

        val OPTIONS = """{"marketCountry":"ET","marketResolution":{"source":"IP_EDGE","confidence":"HIGH","globalDefault":false},
            "clientPlatform":"ANDROID","distributionChannel":"DIRECT_DOWNLOAD","purchaseFlow":"LOCAL_GATEWAY_IN_APP","purchaseUiAllowed":true,
            "products":[{"productCode":"PLAN_PREMIUM","productType":"SUBSCRIPTION","displayName":"Premium","planCode":"PREMIUM",
              "prices":[{"billingPeriod":"ANNUAL","renewalModel":"MANUAL_RENEW","listPrice":{"amountMinor":299000,"currency":"ETB","exponent":2},"taxBehavior":"INCLUSIVE"},
                        {"billingPeriod":"MONTHLY","renewalModel":"MANUAL_RENEW","listPrice":{"amountMinor":29900,"currency":"ETB","exponent":2},"taxBehavior":"INCLUSIVE"}]}],
            "paymentMethods":[{"methodCode":"CHAPA_CHECKOUT","providerCode":"CHAPA","displayName":"Chapa","methodType":"HOSTED_CHECKOUT","priority":2,"isDefault":false},
                              {"methodCode":"ARIFPAY_CHECKOUT","providerCode":"ARIFPAY","displayName":"ArifPay","methodType":"HOSTED_CHECKOUT","priority":1,"isDefault":true}],
            "policyEvaluatedAt":"2026-11-20T09:00:00Z"}"""

        val STORE_OPTIONS = """{"marketCountry":"ZZ","purchaseFlow":"STORE_BILLING","purchaseUiAllowed":true,"products":[],
            "paymentMethods":[{"methodCode":"REVENUECAT_STORE","providerCode":"REVENUECAT","displayName":"Google Play","methodType":"APP_STORE","priority":1,"isDefault":true}],
            "policyEvaluatedAt":"2026-11-20T09:00:00Z"}"""

        fun order(status: String) = """{"orderId":"$ORDER_ID","orderReference":"EAK-ET-2611-7Q4M2X","orderType":"SUBSCRIPTION_PURCHASE","status":"$status",
            "productCode":"PLAN_PREMIUM","billingPeriod":"MONTHLY","renewalModel":"MANUAL_RENEW","marketCountry":"ET","salesChannel":"ANDROID_DIRECT","taxBehavior":"INCLUSIVE",
            "amounts":{"list":{"amountMinor":29900,"currency":"ETB","exponent":2},"discount":{"amountMinor":0,"currency":"ETB","exponent":2},
            "tax":{"amountMinor":3900,"currency":"ETB","exponent":2},"total":{"amountMinor":29900,"currency":"ETB","exponent":2}},
            "expiresAt":"2026-11-20T09:30:00Z",${if (status == "PAID") "\"paidAt\":\"2026-11-20T09:05:00Z\"," else ""}"createdAt":"2026-11-20T09:00:00Z"}"""

        fun attempt(status: String) = """{"attemptId":"$ATTEMPT_ID","attemptNumber":1,"status":"$status","methodCode":"ARIFPAY_CHECKOUT","providerCode":"ARIFPAY",
            "nextAction":{"type":"REDIRECT","checkoutUrl":"https://checkout.arifpay.org/s/abc"},"createdAt":"2026-11-20T09:00:00Z"}"""
    }
}
