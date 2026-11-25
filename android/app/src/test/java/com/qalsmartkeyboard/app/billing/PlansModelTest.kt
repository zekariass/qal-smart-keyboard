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
import com.qalsmartkeyboard.api.billing.CreditSummary
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
    private var creditsBody: String? = CREDITS
    private var historyPages = mutableListOf(HISTORY_1, HISTORY_2)
    private var orderProduct = "PLAN_PREMIUM"
    private var storeReport: (RecordedRequest) -> MockResponse = { json(200, """{"storePurchaseId":"$ORDER_ID","status":"ACTIVE","environment":"SANDBOX"}""") }
    private var onPause: (Int) -> Unit = {}
    private val outbox = StoreCreditOutbox()
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

    private fun model(
        store: StoreBilling = StoreBilling.Unavailable,
        channel: DistributionChannel = DistributionChannel.DIRECT_DOWNLOAD,
        outbox: StoreCreditOutbox = this.outbox,
    ): PlansModel {
        val clients = ApiClients(
            TransportConfig(
                baseUrl = "http://localhost:${server.port}",
                client = ClientInfo("1.0", ClientSurface.COMPANION, distributionChannel = channel),
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
            outbox = outbox,
            pause = { pauses += it; onPause(pauses.size) },
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
            path == "/me/credits" -> creditsBody?.let { json(200, it) } ?: MockResponse().setResponseCode(404)
            path == "/me/credits/transactions" -> json(200, if (r.path!!.contains("cursor=")) historyPages[1] else historyPages[0])
            r.method == "POST" && path == "/billing/orders" -> json(201, order("AWAITING_PAYMENT", orderProduct))
            r.method == "POST" && path == "/billing/orders/$ORDER_ID/attempts" -> json(201, attempt(attemptStatus))
            path == "/billing/orders/$ORDER_ID/attempts/$ATTEMPT_ID" -> json(200, attempt(attemptStatus))
            path == "/billing/orders/$ORDER_ID" -> json(200, order(orderStatus, orderProduct))
            path == "/billing/return/$ATTEMPT_ID" -> MockResponse().setResponseCode(303).setHeader("Location", "qalkeyboard://billing/return")
            path == "/billing/store-purchases" -> storeReport(r)
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

    // ------------------------------------------------------------ AI credit packs (T8.10)

    @Test fun bothFamiliesListPlansAndPacksSmallestFirstWithTheBalanceAndTheSoonestExpiry() = runBlocking {
        optionsBody = withProducts(PLAN_PRODUCT, PACKS)
        model.load()
        val s = model.state.value
        assertEquals(PlansPhase.READY, s.phase)
        assertEquals(listOf("PLAN_PREMIUM/MONTHLY", "PLAN_PREMIUM/ANNUAL"), s.offers.map { it.key })
        assertEquals(listOf("CREDITS_100", "CREDITS_500"), s.packs.map { it.productCode })
        assertEquals(listOf(100L, 500L), s.packs.map { it.credits })
        assertEquals(listOf(365, 365), s.packs.map { it.validityDays })
        assertEquals(Money(4900, "ETB", 2), s.packs[0].price)
        assertEquals("49.00 ETB", s.packs[0].displayPrice)
        // 120.75 credits in two lots; the soonest one (100 left) expires first.
        assertEquals(CreditsInfo(balance = 120, nextExpiryMs = 1_827_049_600_000L, expiringUnits = 100), s.credits)
    }

    @Test fun creditsOnlyMarketListsNoPlansAndStillReady() = runBlocking {
        optionsBody = withProducts(null, PACKS)
        model.load()
        val s = model.state.value
        assertEquals(PlansPhase.READY, s.phase)
        assertTrue(s.offers.isEmpty())
        assertNull(s.selectedOffer)
        assertEquals(2, s.packs.size)
        assertEquals("ARIFPAY_CHECKOUT", s.selectedMethod)
    }

    @Test fun premiumOnlyMarketHasNoCreditsSection() = runBlocking {
        model.load()
        assertTrue(model.state.value.packs.isEmpty())
        assertEquals(2, model.state.value.offers.size)
    }

    @Test fun aMarketThatSellsNothingIsNotAvailable() = runBlocking {
        optionsBody = withProducts(null, null)
        model.load()
        assertEquals(PlansPhase.NOT_AVAILABLE, model.state.value.phase)
        assertNotNull(model.state.value.credits)
    }

    @Test fun theBalanceRoundsDownAndFailsQuietly() = runBlocking {
        assertEquals(0L, CreditsInfo.of(CreditSummary(0.999999, null, emptyList())).balance)
        assertEquals(7L, CreditsInfo.of(CreditSummary(7.5, null, emptyList())).balance)
        creditsBody = null // /me/credits fails: the paywall still works, without a balance
        optionsBody = withProducts(PLAN_PRODUCT, PACKS)
        model.load()
        assertEquals(PlansPhase.READY, model.state.value.phase)
        assertNull(model.state.value.credits)
    }

    @Test fun buyingAPackSendsAOneTimeOrderWithoutARenewalPreferenceAndShowsTheCreditReceipt() = runBlocking {
        optionsBody = withProducts(PLAN_PRODUCT, PACKS)
        orderProduct = "CREDITS_500"
        model.load()
        model.buyPack("CREDITS_500")
        assertEquals(CheckoutStep.CHECKOUT, model.state.value.checkout.step)
        val orderCall = requests.first { it.method == "POST" && it.path!!.endsWith("/billing/orders") }
        val body = orderCall.body.clone().readUtf8()
        assertTrue(body, body.contains("\"productCode\":\"CREDITS_500\"") && body.contains("\"billingPeriod\":\"ONE_TIME\""))
        assertFalse(body, body.contains("renewalPreference"))
        creditsBody = CREDITS_AFTER
        orderStatus = "PAID"
        model.onReturned("success")
        val s = model.state.value
        assertEquals(CheckoutStep.PAID, s.checkout.step)
        val r = s.checkout.receipt!!
        assertEquals(500L, r.credits)
        assertEquals("500 AI credits", r.planName)
        assertEquals(1_827_449_600_000L, r.validUntilMs) // the granted lot's own expiry
        assertEquals(620L, s.credits!!.balance)
        assertEquals(1, refreshes)
    }

    @Test fun aPlanOrderStillSendsManualRenew() = runBlocking {
        optionsBody = withProducts(PLAN_PRODUCT, PACKS)
        model.load()
        model.buy()
        val body = requests.first { it.method == "POST" && it.path!!.endsWith("/billing/orders") }.body.clone().readUtf8()
        assertTrue(body, body.contains("\"renewalPreference\":\"MANUAL_RENEW\""))
    }

    @Test fun aGuestSeesThePacksButBuyingNeedsAnAccount() = runBlocking {
        buyer = BuyerKind.GUEST
        optionsBody = withProducts(null, PACKS)
        model.load()
        assertEquals(2, model.state.value.packs.size)
        assertNotNull(model.state.value.credits)
        model.buyPack("CREDITS_100")
        assertEquals(PlansMessage.NEEDS_ACCOUNT, model.state.value.message)
        assertTrue(requests.none { it.method == "POST" })
    }

    @Test fun guestNotAllowedAndAFamilyTheMarketDoesNotSellEndGracefully() = runBlocking {
        optionsBody = withProducts(null, PACKS)
        model.load()
        val ok = route
        route = { r ->
            if (r.method == "POST" && r.path!!.endsWith("/billing/orders")) {
                json(403, """{"type":"about:blank","title":"t","status":403,"code":"GUEST_NOT_ALLOWED","correlationId":"c"}""")
            } else ok(r)
        }
        model.buyPack("CREDITS_100")
        assertEquals(PlansMessage.NEEDS_ACCOUNT, model.state.value.message)
        assertEquals(CheckoutStep.IDLE, model.state.value.checkout.step)

        route = { r ->
            if (r.method == "POST" && r.path!!.endsWith("/billing/orders")) {
                json(422, """{"type":"about:blank","title":"t","status":422,"code":"PRODUCT_NOT_AVAILABLE","correlationId":"c"}""")
            } else ok(r)
        }
        model.buyPack("CREDITS_100")
        assertEquals(PlansMessage.PRODUCT_UNAVAILABLE, model.state.value.message)
        assertEquals(CheckoutStep.IDLE, model.state.value.checkout.step)
    }

    @Test fun creditHistoryPagesByCursorNewestFirst() = runBlocking {
        optionsBody = withProducts(null, PACKS)
        model.load()
        model.openHistory()
        var h = model.state.value.history
        assertTrue(h.open)
        assertFalse(h.loading)
        assertEquals(listOf("PURCHASE", "CONSUMPTION"), h.items.map { it.transactionType })
        assertEquals("CUR2", h.nextCursor)
        model.loadMoreHistory()
        h = model.state.value.history
        assertEquals(listOf("PURCHASE", "CONSUMPTION", "EXPIRY"), h.items.map { it.transactionType })
        assertNull(h.nextCursor)
        assertTrue(requests.any { it.path!!.contains("/me/credits/transactions") && it.path!!.contains("cursor=CUR2") })
        model.loadMoreHistory() // nothing more: no call
        assertEquals(1, requests.count { it.path!!.contains("cursor=CUR2") })
        model.closeHistory()
        assertFalse(model.state.value.history.open)
    }

    @Test fun creditHistoryFailureKeepsWhatWasLoadedAndOffersRetry() = runBlocking {
        optionsBody = withProducts(null, PACKS)
        model.load()
        val ok = route
        route = { r -> if (r.path!!.contains("/me/credits/transactions")) json(500, """{"type":"about:blank","title":"t","status":500,"code":"INTERNAL","correlationId":"c"}""") else ok(r) }
        model.openHistory()
        assertEquals(PlansMessage.SERVER, model.state.value.history.error)
        assertTrue(model.state.value.history.items.isEmpty())
        route = ok
        model.loadMoreHistory()
        assertNull(model.state.value.history.error)
        assertEquals(2, model.state.value.history.items.size)
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

        // The payer comes back from the Custom Tab; the server verifies; the second poll sees PAID.
        var polls = 0
        route = { r ->
            if (r.path!!.endsWith("/billing/orders/$ORDER_ID")) {
                polls++
                if (polls >= 2) orderStatus = "PAID"
            }
            backend(r)
        }
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
        val m = model(fake, DistributionChannel.GOOGLE_PLAY)
        m.load()
        assertEquals(PlansPhase.READY, m.state.value.phase)
        assertTrue(m.state.value.storeFlow)
        assertEquals("monthly", m.state.value.selectedOffer)
        assertTrue(m.state.value.packs.isEmpty())
        plan = "PREMIUM"
        m.buyFromStore { throw UnsupportedOperationException("no activity in JVM tests") }
        assertEquals(CheckoutStep.PAID, m.state.value.checkout.step)
        val report = requests.first { it.path!!.endsWith("/billing/store-purchases") }
        val body = report.body.clone().readUtf8()
        assertTrue(body.contains("\"purchaseToken\":\"tok\""))
        // A subscription report names the channel but carries no transaction id.
        assertTrue(body, body.contains("\"distributionChannel\":\"GOOGLE_PLAY\""))
        assertFalse(body, body.contains("transactionId"))
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

    // ------------------------------------------------------------ store-billed credit packs (T8.10)

    private val host = PurchaseHost { throw UnsupportedOperationException("no activity in JVM tests") }

    private fun storeModel(fake: FakeStore = FakeStore.withPacks(), outbox: StoreCreditOutbox = this.outbox) =
        model(fake, DistributionChannel.GOOGLE_PLAY, outbox)

    @Test fun storeMarketListsPlansAndPacksWithTheStorePriceNotTheServerPlaceholder() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_500, STORE_PACK_100)
        val m = storeModel()
        m.load()
        val s = m.state.value
        assertEquals(PlansPhase.READY, s.phase)
        assertTrue(s.storeFlow)
        assertFalse(s.creditsOnly)
        assertEquals(listOf("monthly"), s.storeOffers.map { it.id })
        assertEquals(listOf("CREDITS_100", "CREDITS_500"), s.packs.map { it.productCode })
        assertEquals(listOf("\$0.99", "\$3.99"), s.packs.map { it.displayPrice })
        assertEquals(listOf("credits_100", "credits_500"), s.packs.map { it.storePackageId })
        assertEquals(listOf("credits", "credits"), s.packs.map { it.storeOfferingId })
        assertEquals(listOf(100L, 500L), s.packs.map { it.credits })
        assertEquals(listOf(365, 365), s.packs.map { it.validityDays })
        assertEquals(120L, s.credits!!.balance)
    }

    @Test fun storeCreditsOnlyHasNoPlanChooser() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_500, STORE_PACK_100)
        val m = storeModel(FakeStore.withPacks(plans = emptyList()))
        m.load()
        val s = m.state.value
        assertEquals(PlansPhase.READY, s.phase)
        assertTrue(s.storeOffers.isEmpty())
        assertTrue(s.creditsOnly)
        assertEquals(2, s.packs.size)
        assertNull(s.selectedOffer)
    }

    @Test fun storePremiumOnlyListsNoPacksAndKeepsThePlans() = runBlocking {
        optionsBody = STORE_OPTIONS // the server lists no credit pack
        val m = storeModel()
        m.load()
        val s = m.state.value
        assertEquals(PlansPhase.READY, s.phase)
        assertEquals(1, s.storeOffers.size)
        assertTrue(s.packs.isEmpty())
        assertFalse(s.creditsOnly)
        assertFalse(calls().any { it.contains("billing/products") })
    }

    @Test fun aServerPackTheStoreDidNotReturnIsHidden() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_500, STORE_PACK_100)
        val fake = FakeStore.withPacks(credits = listOf(CREDIT_100_OFFER)) // no credits_500 in the store's offering
        val m = storeModel(fake)
        m.load()
        assertEquals(listOf("CREDITS_100"), m.state.value.packs.map { it.productCode })
        // The store returned nothing for the packs and has no plans: nothing to sell, retry.
        val none = storeModel(FakeStore.withPacks(plans = emptyList(), credits = emptyList()))
        none.load()
        assertEquals(PlansPhase.ERROR, none.state.value.phase)
        assertTrue(none.state.value.packs.isEmpty())
    }

    @Test fun aGuestSeesTheStorePacksWithoutAPriceAndBuyingNeedsAnAccount() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_100)
        buyer = BuyerKind.GUEST
        val fake = FakeStore.withPacks()
        val m = storeModel(fake)
        m.load()
        assertEquals(PlansPhase.READY, m.state.value.phase)
        assertEquals(listOf("CREDITS_100"), m.state.value.packs.map { it.productCode })
        assertNull(m.state.value.packs.single().displayPrice) // never the placeholder
        assertEquals(0, fake.offeringCalls)
        m.buyPackFromStore(host, "CREDITS_100")
        assertEquals(PlansMessage.NEEDS_ACCOUNT, m.state.value.message)
        assertTrue(fake.purchases.isEmpty())
    }

    @Test fun buyingAStorePackReportsTheTransactionIdAndShowsTheCreditReceipt() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_500, STORE_PACK_100)
        val fake = FakeStore.withPacks(outcome = StorePurchaseOutcome.Purchased("credits_100", "secret-tok", "com.qalsmartkeyboard.app", "GPA.3333-4444"))
        fake.onPurchase = { creditsBody = CREDITS_AFTER }
        storeReport = { json(200, STORE_GRANTED) }
        val m = storeModel(fake)
        m.load()
        m.buyPackFromStore(host, "CREDITS_100")
        assertEquals(listOf(Triple("user-1", "credits_100", "credits")), fake.purchases)
        val report = requests.last { it.path!!.endsWith("/billing/store-purchases") }
        val body = report.body.clone().readUtf8()
        assertTrue(body, body.contains("\"transactionId\":\"GPA.3333-4444\""))
        assertTrue(body, body.contains("\"distributionChannel\":\"GOOGLE_PLAY\""))
        assertTrue(body, body.contains("\"productId\":\"credits_100\"") && body.contains("\"purchaseToken\":\"secret-tok\""))
        assertNotNull(report.getHeader("Idempotency-Key"))
        assertFalse(calls().any { it.contains("/billing/orders") }) // store purchases never use orders
        val s = m.state.value
        assertEquals(CheckoutStep.PAID, s.checkout.step)
        assertTrue(s.checkout.store && s.checkout.credits)
        val r = s.checkout.receipt!!
        assertEquals(100L, r.credits)
        assertEquals("100 AI credits", r.planName)
        assertEquals("\$0.99", r.storePrice)
        assertEquals(1_827_049_600_000L, r.validUntilMs) // creditsExpireAt of the answer
        assertEquals(620L, s.credits!!.balance) // the balance was read again
        assertEquals(1, refreshes)
        assertTrue(outbox.all().isEmpty())
        assertTrue(pauses.isEmpty())
    }

    @Test fun aPendingReportIsBeingConfirmedAndPollsAtMostTheWindowThenKeepsTheOutbox() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_100)
        storeReport = { json(200, STORE_PENDING) }
        val m = storeModel(FakeStore.withPacks(outcome = StorePurchaseOutcome.Purchased("credits_100", "tok", "com.qalsmartkeyboard.app", "GPA.1")))
        m.load()
        m.buyPackFromStore(host, "CREDITS_100")
        val s = m.state.value
        assertEquals(CheckoutStep.STILL_CONFIRMING, s.checkout.step)
        assertTrue(s.checkout.store && s.checkout.credits)
        assertNull(s.message) // not a failure
        assertEquals(PlansModel.CREDIT_SETTLE_MS.drop(1), pauses) // bounded
        assertEquals(PlansModel.CREDIT_SETTLE_MS.size, requests.count { it.path!!.endsWith("/billing/store-purchases") })
        assertEquals(1, outbox.all().size)
        assertEquals(0, refreshes)
    }

    @Test fun aPendingPurchaseThatTheWebhookGrantsShowsTheCreditsWithoutLeavingTheScreen() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_100)
        storeReport = { json(200, STORE_PENDING) }
        onPause = { n -> if (n == 2) creditsBody = CREDITS_AFTER } // the webhook lands during the second wait
        val m = storeModel(FakeStore.withPacks(outcome = StorePurchaseOutcome.Purchased("credits_100", "tok", "com.qalsmartkeyboard.app", "GPA.1")))
        m.load()
        m.buyPackFromStore(host, "CREDITS_100")
        val s = m.state.value
        assertEquals(CheckoutStep.PAID, s.checkout.step)
        assertEquals(2, pauses.size)
        assertEquals(100L, s.checkout.receipt!!.credits)
        assertEquals(1_827_049_600_000L, s.checkout.receipt!!.validUntilMs) // the new lot's own expiry
        assertEquals(620L, s.credits!!.balance)
        assertTrue(outbox.all().isEmpty())
    }

    @Test fun serverAnswersThatKeepAVerifiedPurchaseAreConfirmingNotFailures() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_100)
        for ((status, code) in listOf(403 to "BILLING_POLICY_DENIED", 422 to "PRODUCT_NOT_AVAILABLE", 422 to "STORE_PURCHASE_INVALID")) {
            storeReport = { json(status, """{"type":"about:blank","title":"t","status":$status,"code":"$code","correlationId":"c"}""") }
            val m = storeModel(FakeStore.withPacks(outcome = StorePurchaseOutcome.Purchased("credits_100", "tok", "com.qalsmartkeyboard.app", "GPA.1")), StoreCreditOutbox())
            m.load()
            m.buyPackFromStore(host, "CREDITS_100")
            assertEquals(code, CheckoutStep.STILL_CONFIRMING, m.state.value.checkout.step)
            assertNull(code, m.state.value.message)
        }
    }

    @Test fun aReportThatFailsOfflineIsRetriedWhenPlansOpensAgain() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_100)
        storeReport = { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val fake = FakeStore.withPacks(outcome = StorePurchaseOutcome.Purchased("credits_100", "tok", "com.qalsmartkeyboard.app", "GPA.9"))
        val first = storeModel(fake)
        first.load()
        first.buyPackFromStore(host, "CREDITS_100")
        assertEquals(CheckoutStep.STILL_CONFIRMING, first.state.value.checkout.step)
        assertEquals(PlansMessage.OFFLINE, first.state.value.message) // the retry message
        assertEquals(1, outbox.all().size)
        val keyOfFirst = requests.first { it.path!!.endsWith("/billing/store-purchases") }.getHeader("Idempotency-Key")

        // Back online; a new screen (a new model sharing the outbox) reports it again with the same idempotency key.
        storeReport = { json(200, STORE_GRANTED) }
        creditsBody = CREDITS_AFTER
        val reopened = storeModel(FakeStore.withPacks())
        reopened.load()
        val s = reopened.state.value
        assertEquals(CheckoutStep.PAID, s.checkout.step)
        assertEquals(100L, s.checkout.receipt!!.credits)
        assertEquals(keyOfFirst, requests.last { it.path!!.endsWith("/billing/store-purchases") }.getHeader("Idempotency-Key"))
        assertTrue(requests.last { it.path!!.endsWith("/billing/store-purchases") }.body.clone().readUtf8().contains("\"transactionId\":\"GPA.9\""))
        assertTrue(outbox.all().isEmpty())
    }

    @Test fun refreshOfAStillConfirmingPurchaseReportsOnceMore() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_100)
        storeReport = { json(200, STORE_PENDING) }
        val m = storeModel(FakeStore.withPacks(outcome = StorePurchaseOutcome.Purchased("credits_100", "tok", "com.qalsmartkeyboard.app", "GPA.1")))
        m.load()
        m.buyPackFromStore(host, "CREDITS_100")
        assertEquals(CheckoutStep.STILL_CONFIRMING, m.state.value.checkout.step)
        storeReport = { json(200, STORE_GRANTED) }
        m.refreshCheckout()
        assertEquals(CheckoutStep.PAID, m.state.value.checkout.step)
        assertTrue(outbox.all().isEmpty())
    }

    @Test fun cancelledFailedAndAlreadyOwnedStorePacks() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_100)
        val cancelled = storeModel(FakeStore.withPacks(outcome = StorePurchaseOutcome.Cancelled))
        cancelled.load()
        cancelled.buyPackFromStore(host, "CREDITS_100")
        assertEquals(CheckoutStep.IDLE, cancelled.state.value.checkout.step)
        assertNull(cancelled.state.value.message) // cancelling is no error
        assertNull(cancelled.state.value.buyingPack)
        assertFalse(calls().any { it.endsWith("/billing/store-purchases") })

        val failed = storeModel(FakeStore.withPacks(outcome = StorePurchaseOutcome.Failed("StoreProblemError")))
        failed.load()
        failed.buyPackFromStore(host, "CREDITS_100")
        assertEquals(PlansMessage.STORE_FAILED, failed.state.value.message)
        assertEquals(CheckoutStep.IDLE, failed.state.value.checkout.step)

        val owned = storeModel(FakeStore.withPacks(outcome = StorePurchaseOutcome.AlreadyOwned))
        owned.load()
        owned.buyPackFromStore(host, "CREDITS_100")
        assertEquals(CheckoutStep.STILL_CONFIRMING, owned.state.value.checkout.step)
        assertTrue(owned.state.value.checkout.credits)
        assertFalse(calls().any { it.endsWith("/billing/store-purchases") }) // nothing to report: the store has no new transaction

        val pending = storeModel(FakeStore.withPacks(outcome = StorePurchaseOutcome.Pending))
        pending.load()
        pending.buyPackFromStore(host, "CREDITS_100")
        assertEquals(CheckoutStep.STILL_CONFIRMING, pending.state.value.checkout.step)
        assertNull(pending.state.value.message)
    }

    @Test fun guestNotAllowedAndARefundedPurchaseEndTheWait() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_100)
        storeReport = { json(403, """{"type":"about:blank","title":"t","status":403,"code":"GUEST_NOT_ALLOWED","correlationId":"c"}""") }
        val m = storeModel(FakeStore.withPacks(outcome = StorePurchaseOutcome.Purchased("credits_100", "tok", "com.qalsmartkeyboard.app", "GPA.1")), StoreCreditOutbox())
        m.load()
        m.buyPackFromStore(host, "CREDITS_100")
        assertEquals(PlansMessage.NEEDS_ACCOUNT, m.state.value.message)
        assertEquals(CheckoutStep.IDLE, m.state.value.checkout.step)

        storeReport = { json(200, STORE_PENDING.replace("PENDING", "REFUNDED")) }
        val o = StoreCreditOutbox()
        val refunded = storeModel(FakeStore.withPacks(outcome = StorePurchaseOutcome.Purchased("credits_100", "tok", "com.qalsmartkeyboard.app", "GPA.2")), o)
        refunded.load()
        refunded.buyPackFromStore(host, "CREDITS_100")
        assertEquals(PlansMessage.STORE_FAILED, refunded.state.value.message)
        assertTrue(o.all().isEmpty())
    }

    @Test fun theDirectBuildInAStoreMarketSellsNothingAndSaysSo() = runBlocking {
        optionsBody = """{"marketCountry":"ZZ","purchaseFlow":"NONE","purchaseUiAllowed":false,"products":[],"paymentMethods":[],
            "notice":"Purchases are not available in this version of the app. Install the app from Google Play to buy plans or AI credits.",
            "policyEvaluatedAt":"2026-11-20T09:00:00Z"}"""
        model.load() // the direct flavour: StoreBilling.Unavailable
        val s = model.state.value
        assertEquals(PlansPhase.NOT_AVAILABLE, s.phase)
        assertEquals(UnavailableReason.STORE_NOT_IN_BUILD, s.unavailable)
        assertTrue(s.packs.isEmpty() && s.offers.isEmpty() && s.storeOffers.isEmpty())
        assertNotNull(s.notice)
        model.buyPackFromStore(host, "CREDITS_100")
        assertFalse(calls().any { it.endsWith("/billing/store-purchases") })
    }

    @Test fun aStoreMarketWithoutAKeyInThePlayBuildHidesThePacksToo() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_100)
        model.load() // StoreBilling.Unavailable (no key)
        assertEquals(UnavailableReason.STORE_NOT_IN_BUILD, model.state.value.unavailable)
        assertTrue(model.state.value.packs.isEmpty())
    }

    @Test fun signingOutForgetsTheUnreportedCreditPurchases() = runBlocking {
        optionsBody = storeOptions(STORE_PACK_100)
        storeReport = { json(200, STORE_PENDING) }
        val m = storeModel(FakeStore.withPacks(outcome = StorePurchaseOutcome.Purchased("credits_100", "tok", "com.qalsmartkeyboard.app", "GPA.1")))
        m.load()
        m.buyPackFromStore(host, "CREDITS_100")
        assertEquals(1, outbox.all().size)
        m.signedOut()
        assertTrue(outbox.all().isEmpty())
    }

    private class FakeStore(
        val outcome: StorePurchaseOutcome = StorePurchaseOutcome.Purchased("premium_monthly", "tok", "com.qalsmartkeyboard.app"),
        val plans: List<StoreOffer> = listOf(StoreOffer("monthly", "premium_monthly", "MONTHLY", "Premium", "\$4.99")),
        val credits: List<StoreOffer> = emptyList(),
    ) : StoreBilling {
        override val available = true
        val purchases = mutableListOf<Triple<String, String, String?>>()
        var offeringCalls = 0
        var onPurchase: () -> Unit = {}
        override suspend fun offers(appUserId: String) = plans
        override suspend fun offering(appUserId: String, offeringId: String): List<StoreOffer> {
            offeringCalls++
            return if (offeringId == "credits") credits else emptyList()
        }
        override suspend fun purchase(host: PurchaseHost, appUserId: String, offerId: String, offeringId: String?): StorePurchaseOutcome {
            purchases += Triple(appUserId, offerId, offeringId)
            onPurchase()
            return outcome
        }

        companion object {
            fun withPacks(
                outcome: StorePurchaseOutcome = StorePurchaseOutcome.Purchased("credits_100", "tok", "com.qalsmartkeyboard.app", "GPA.1"),
                plans: List<StoreOffer> = listOf(StoreOffer("monthly", "premium_monthly", "MONTHLY", "Premium", "\$4.99")),
                credits: List<StoreOffer> = listOf(CREDIT_500_OFFER, CREDIT_100_OFFER),
            ) = FakeStore(outcome, plans, credits)
        }
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

        val CREDIT_100_OFFER = StoreOffer("credits_100", "credits_100", "OTHER", "100 credits (Qal)", "\$0.99")
        val CREDIT_500_OFFER = StoreOffer("credits_500", "credits_500", "OTHER", "500 credits (Qal)", "\$3.99")

        /** The server's store-billed packs: a placeholder list price, the store ids, and listed largest first on purpose. */
        val STORE_PACK_500 = """{"productCode":"CREDITS_500","productType":"AI_CREDIT_PACK","displayName":"500 AI credits","creditUnits":500,"creditValidityDays":365,
              "prices":[{"billingPeriod":"ONE_TIME","renewalModel":"STORE_MANAGED","listPrice":{"amountMinor":1,"currency":"USD","exponent":2},
              "storeProductId":"credits_500","storeOfferingId":"credits","storePackageId":"credits_500"}]}"""
        val STORE_PACK_100 = """{"productCode":"CREDITS_100","productType":"AI_CREDIT_PACK","displayName":"100 AI credits","creditUnits":100,"creditValidityDays":365,
              "prices":[{"billingPeriod":"ONE_TIME","renewalModel":"STORE_MANAGED","listPrice":{"amountMinor":1,"currency":"USD","exponent":2},
              "storeProductId":"credits_100","storeOfferingId":"credits","storePackageId":"credits_100"}]}"""

        fun storeOptions(vararg products: String): String {
            val i = STORE_OPTIONS.indexOf("\"products\":[")
            val j = STORE_OPTIONS.indexOf("\"paymentMethods\"")
            return STORE_OPTIONS.substring(0, i) + "\"products\":[" + products.joinToString(",") + "]," + STORE_OPTIONS.substring(j)
        }

        val STORE_GRANTED = """{"storePurchaseId":"$ORDER_ID","status":"ACTIVE","environment":"SANDBOX","purchaseType":"CONSUMABLE","productCode":"CREDITS_100",
            "transactionId":"GPA.1","creditUnits":100,"creditsExpireAt":"2027-11-24T09:46:40Z","creditLotId":"0193a5c2-7a10-7d11-8e2f-cccccccccccc"}"""
        val STORE_PENDING = """{"storePurchaseId":"$ORDER_ID","status":"PENDING","environment":"SANDBOX","purchaseType":"CONSUMABLE","productCode":"CREDITS_100","transactionId":"GPA.1"}"""

        const val LOT_ID = "0193a5c2-7a10-7d11-8e2f-aaaaaaaaaaaa"

        val PLAN_PRODUCT = """{"productCode":"PLAN_PREMIUM","productType":"SUBSCRIPTION","displayName":"Premium","planCode":"PREMIUM",
              "prices":[{"billingPeriod":"ANNUAL","renewalModel":"MANUAL_RENEW","listPrice":{"amountMinor":299000,"currency":"ETB","exponent":2},"taxBehavior":"INCLUSIVE"},
                        {"billingPeriod":"MONTHLY","renewalModel":"MANUAL_RENEW","listPrice":{"amountMinor":29900,"currency":"ETB","exponent":2},"taxBehavior":"INCLUSIVE"}]}"""

        /** Listed largest first on purpose: the model sorts by credits. */
        val PACKS = """{"productCode":"CREDITS_500","productType":"AI_CREDIT_PACK","displayName":"500 AI credits","creditUnits":500,"creditValidityDays":365,
              "prices":[{"billingPeriod":"ONE_TIME","renewalModel":"NONE","listPrice":{"amountMinor":19900,"currency":"ETB","exponent":2},"taxBehavior":"INCLUSIVE","displayPrice":"199.00 ETB"}]},
            {"productCode":"CREDITS_100","productType":"AI_CREDIT_PACK","displayName":"100 AI credits","creditUnits":100,"creditValidityDays":365,
              "prices":[{"billingPeriod":"ONE_TIME","renewalModel":"NONE","listPrice":{"amountMinor":4900,"currency":"ETB","exponent":2},"taxBehavior":"INCLUSIVE","displayPrice":"49.00 ETB"}]}"""

        fun withProducts(vararg products: String?): String {
            val i = OPTIONS.indexOf("\"products\":[")
            val j = OPTIONS.indexOf("\"paymentMethods\"")
            return OPTIONS.substring(0, i) + "\"products\":[" + products.filterNotNull().joinToString(",") + "]," + OPTIONS.substring(j)
        }

        val CREDITS = """{"balanceUnits":120.75,"nextExpiryAt":"2027-11-24T09:46:40Z","lots":[
            {"lotId":"$LOT_ID","originalUnits":100,"remainingUnits":100,"source":"PURCHASE","expiresAt":"2027-11-24T09:46:40Z"},
            {"lotId":"0193a5c2-7a10-7d11-8e2f-bbbbbbbbbbbb","originalUnits":50,"remainingUnits":20.75,"source":"GRANT","expiresAt":"2028-11-24T09:46:40Z"}]}"""
        val CREDITS_AFTER = """{"balanceUnits":620.75,"nextExpiryAt":"2027-11-24T09:46:40Z","lots":[
            {"lotId":"$LOT_ID","originalUnits":500,"remainingUnits":500,"source":"PURCHASE","expiresAt":"2027-11-29T00:53:20Z"},
            {"lotId":"0193a5c2-7a10-7d11-8e2f-bbbbbbbbbbbb","originalUnits":50,"remainingUnits":20.75,"source":"GRANT","expiresAt":"2028-11-24T09:46:40Z"},
            {"lotId":"0193a5c2-7a10-7d11-8e2f-cccccccccccc","originalUnits":100,"remainingUnits":100,"source":"PURCHASE","expiresAt":"2027-11-24T09:46:40Z"}]}"""

        val HISTORY_1 = """{"items":[{"id":"0193a5c2-7a10-7d11-8e2f-000000000001","transactionType":"PURCHASE","deltaUnits":100,"balanceAfter":120.75,"createdAt":"2026-11-20T09:05:00Z"},
            {"id":"0193a5c2-7a10-7d11-8e2f-000000000002","transactionType":"CONSUMPTION","deltaUnits":-0.5,"balanceAfter":20.75,"featureCode":"TRANSLATE","createdAt":"2026-11-19T09:05:00Z"}],"nextCursor":"CUR2"}"""
        val HISTORY_2 = """{"items":[{"id":"0193a5c2-7a10-7d11-8e2f-000000000003","transactionType":"EXPIRY","deltaUnits":-3,"balanceAfter":21.25,"createdAt":"2026-10-19T09:05:00Z"}]}"""

        fun order(status: String, product: String = "PLAN_PREMIUM"): String {
            val pack = product != "PLAN_PREMIUM"
            val fulfilment = if (pack && status == "PAID") ",\"fulfilment\":{\"creditLotId\":\"$LOT_ID\"}" else ""
            return order0(status, product, if (pack) "ONE_TIME" else "MONTHLY", if (pack) "NONE" else "MANUAL_RENEW", fulfilment)
        }

        private fun order0(status: String, product: String, period: String, renewal: String, fulfilment: String) = """{"orderId":"$ORDER_ID","orderReference":"EAK-ET-2611-7Q4M2X","orderType":"SUBSCRIPTION_PURCHASE","status":"$status",
            "productCode":"$product","billingPeriod":"$period","renewalModel":"$renewal","marketCountry":"ET","salesChannel":"ANDROID_DIRECT","taxBehavior":"INCLUSIVE"$fulfilment,
            "amounts":{"list":{"amountMinor":29900,"currency":"ETB","exponent":2},"discount":{"amountMinor":0,"currency":"ETB","exponent":2},
            "tax":{"amountMinor":3900,"currency":"ETB","exponent":2},"total":{"amountMinor":29900,"currency":"ETB","exponent":2}},
            "expiresAt":"2026-11-20T09:30:00Z",${if (status == "PAID") "\"paidAt\":\"2026-11-20T09:05:00Z\"," else ""}"createdAt":"2026-11-20T09:00:00Z"}"""

        fun attempt(status: String) = """{"attemptId":"$ATTEMPT_ID","attemptNumber":1,"status":"$status","methodCode":"ARIFPAY_CHECKOUT","providerCode":"ARIFPAY",
            "nextAction":{"type":"REDIRECT","checkoutUrl":"https://checkout.arifpay.org/s/abc"},"createdAt":"2026-11-20T09:00:00Z"}"""
    }
}
