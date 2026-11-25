package com.qalsmartkeyboard.api.billing

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.ClientInfo
import com.qalsmartkeyboard.api.ClientSurface
import com.qalsmartkeyboard.api.CountryHintSource
import com.qalsmartkeyboard.api.CountryHints
import com.qalsmartkeyboard.api.DistributionChannel
import com.qalsmartkeyboard.api.InMemoryTokenStore
import com.qalsmartkeyboard.api.SessionManager
import com.qalsmartkeyboard.api.TokenPair
import com.qalsmartkeyboard.api.TransportConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID

class BillingClientTest {
    private lateinit var server: MockWebServer
    private lateinit var billing: BillingClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        billing = client(DistributionChannel.GOOGLE_PLAY)
    }

    @After fun tearDown() = server.shutdown()

    private fun client(channel: DistributionChannel?) = BillingClient(
        ApiClients(
            TransportConfig(
                baseUrl = "http://localhost:${server.port}",
                client = ClientInfo("2.0.3", ClientSurface.COMPANION, distributionChannel = channel),
                countryHints = CountryHintSource { CountryHints("ET", "ET") },
            ),
            SessionManager(InMemoryTokenStore(TokenPair("access-token", "refresh-token"))),
        ),
    )

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun optionsSendTheBuildChannelAndTheHintsAndKeepUnknownValues() = runBlocking {
        server.enqueue(json(200, OPTIONS))
        val o = billing.options()
        val r = server.takeRequest()
        assertEquals("/api/v1/billing/options?clientPlatform=ANDROID&distributionChannel=GOOGLE_PLAY", r.path)
        assertEquals("ET", r.getHeader("X-Qal-Network-Country"))
        assertEquals("ET", o.marketCountry)
        assertTrue(o.gatewayFlow)
        assertEquals(listOf("ARIFPAY_CHECKOUT", "CHAPA_NEW_METHOD"), o.paymentMethods.map { it.methodCode })
        assertTrue(o.paymentMethods[0].isDefault)
        assertEquals("SOMETHING_NEW", o.paymentMethods[1].methodType) // an enum value this build does not know
        val premium = o.products.single()
        assertEquals(Money(29900, "ETB", 2), premium.prices.first { it.billingPeriod == "MONTHLY" }.listPrice)
        assertEquals(Money(299000, "ETB", 2), premium.prices.first { it.billingPeriod == "ANNUAL" }.listPrice)
    }

    @Test fun createOrderSendsTheKeyTheMarketAndTheSalesChannelButNoPrice() = runBlocking {
        server.enqueue(json(201, ORDER.replace("STATUS", "AWAITING_PAYMENT")))
        val key = UUID.randomUUID()
        val order = billing.createOrder(key, "PLAN_PREMIUM", "MONTHLY", "ET")
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/billing/orders", r.path)
        assertEquals(key.toString(), r.getHeader("Idempotency-Key"))
        assertEquals("Bearer access-token", r.getHeader("Authorization"))
        val body = Json.parseToJsonElement(r.body.readUtf8()) as JsonObject
        assertEquals(setOf("productCode", "billingPeriod", "marketCountry", "salesChannel", "renewalPreference"), body.keys)
        assertEquals(JsonPrimitive("ANDROID_GOOGLE_PLAY"), body["salesChannel"])
        assertEquals(JsonPrimitive("MANUAL_RENEW"), body["renewalPreference"])
        assertEquals("AWAITING_PAYMENT", order.status)
        assertEquals(Money(29900, "ETB", 2), order.total)
    }

    @Test fun aCreditPackOrderOmitsTheRenewalPreferenceAndTheOrderReadsTheLot() = runBlocking {
        server.enqueue(json(201, ORDER.replace("STATUS", "PAID").replace("\"createdAt\"", "\"fulfilment\":{\"creditLotId\":\"$ORDER_ID\"},\"createdAt\"")))
        val order = billing.createOrder(UUID.randomUUID(), "CREDITS_500", "ONE_TIME", "ET", renewalPreference = null)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
        assertEquals(setOf("productCode", "billingPeriod", "marketCountry", "salesChannel"), body.keys)
        assertEquals(JsonPrimitive("ONE_TIME"), body["billingPeriod"])
        assertEquals(ORDER_ID, order.creditLotId)
    }

    @Test fun creditPackProductsCarryUnitsAndValidity() = runBlocking {
        val pack = """{"productCode":"CREDITS_500","productType":"AI_CREDIT_PACK","displayName":"500 AI credits","creditUnits":500,"creditValidityDays":365,
            "prices":[{"billingPeriod":"ONE_TIME","renewalModel":"NONE","listPrice":{"amountMinor":19900,"currency":"ETB","exponent":2},"taxBehavior":"INCLUSIVE"}]}"""
        server.enqueue(json(200, OPTIONS.replace("\"products\":[", "\"products\":[$pack,")))
        val p = billing.options().products.first { it.productType == "AI_CREDIT_PACK" }
        assertEquals(500L, p.creditUnits)
        assertEquals(365, p.creditValidityDays)
        assertEquals("ONE_TIME", p.prices.single().billingPeriod)
        server.enqueue(json(200, OPTIONS))
        assertNull(billing.options().products.single().creditUnits) // a plan carries none
    }

    @Test fun creditsAndTheLedgerParse() = runBlocking {
        server.enqueue(json(200, """{"balanceUnits":120.75,"nextExpiryAt":"2027-11-24T09:46:40Z","lots":[{"lotId":"a","originalUnits":100,"remainingUnits":100.5,"source":"PURCHASE","expiresAt":"2027-11-24T09:46:40Z"}]}"""))
        val c = billing.credits()
        assertEquals("/api/v1/me/credits", server.takeRequest().path)
        assertEquals(120.75, c.balanceUnits, 0.0)
        assertEquals(1_827_049_600_000L, c.nextExpiryAtMs)
        assertEquals(100.5, c.lots.single().remainingUnits, 0.0)

        server.enqueue(json(200, """{"items":[{"id":"t1","transactionType":"CONSUMPTION","deltaUnits":-0.25,"balanceAfter":20.5,"featureCode":"TRANSLATE","createdAt":"2026-11-19T09:05:00Z"},{"transactionType":"BROKEN"}],"nextCursor":"n1"}"""))
        val page = billing.creditTransactions("c0", 10)
        assertEquals("/api/v1/me/credits/transactions?limit=10&cursor=c0", server.takeRequest().path)
        assertEquals(listOf("t1"), page.items.map { it.id }) // a row without an id is dropped, not fatal
        assertEquals(-0.25, page.items[0].deltaUnits, 0.0)
        assertEquals("n1", page.nextCursor)
    }

    @Test fun theDirectBuildSellsOnItsOwnChannel() = runBlocking {
        val direct = client(DistributionChannel.DIRECT_DOWNLOAD)
        server.enqueue(json(200, OPTIONS))
        direct.options()
        assertTrue(server.takeRequest().path!!.endsWith("distributionChannel=DIRECT_DOWNLOAD"))
        server.enqueue(json(201, ORDER.replace("STATUS", "AWAITING_PAYMENT")))
        direct.createOrder(UUID.randomUUID(), "PLAN_PREMIUM", "ANNUAL", "ET")
        assertTrue(server.takeRequest().body.readUtf8().contains("\"salesChannel\":\"ANDROID_DIRECT\""))
    }

    @Test fun attemptReturnsTheCheckoutUrl() = runBlocking {
        server.enqueue(json(201, ATTEMPT))
        val orderId = UUID.fromString(ORDER_ID)
        val a = billing.createAttempt(orderId, UUID.randomUUID(), "ARIFPAY_CHECKOUT", "am")
        val r = server.takeRequest()
        assertEquals("/api/v1/billing/orders/$ORDER_ID/attempts", r.path)
        assertEquals("""{"methodCode":"ARIFPAY_CHECKOUT","locale":"am"}""", r.body.readUtf8())
        assertEquals("REDIRECT", a.nextActionType)
        assertEquals("https://checkout.arifpay.org/s/abc", a.checkoutUrl)
    }

    @Test fun theReturnSignalCarriesNoBearerFollowsNoRedirectAndNeverFails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "qalkeyboard://billing/return?orderId=$ORDER_ID"))
        billing.signalReturn(UUID.fromString(ATTEMPT_ID), "success")
        val r = server.takeRequest()
        assertEquals("/api/v1/billing/return/$ATTEMPT_ID?result=success", r.path)
        assertNull(r.getHeader("Authorization"))
        assertNull(r.getHeader("X-Qal-Network-Country"))
        assertEquals(1, server.requestCount)
        // An unexpected result value is dropped; a 404 or a dead server is swallowed.
        server.enqueue(MockResponse().setResponseCode(404))
        billing.signalReturn(UUID.fromString(ATTEMPT_ID), "<script>")
        assertEquals("/api/v1/billing/return/$ATTEMPT_ID", server.takeRequest().path)
        server.shutdown()
        billing.signalReturn(UUID.fromString(ATTEMPT_ID), null)
    }

    @Test fun problemAnswersBecomeApiErrors() = runBlocking {
        server.enqueue(json(403, """{"type":"about:blank","title":"t","status":403,"code":"BILLING_POLICY_DENIED","correlationId":"c"}"""))
        try {
            billing.createOrder(UUID.randomUUID(), "PLAN_PREMIUM", "MONTHLY", "ZZ")
            fail()
        } catch (e: ApiError.Http) {
            assertEquals("BILLING_POLICY_DENIED", e.code)
        }
    }

    @Test fun planSubscriptionsAndStorePurchase() = runBlocking {
        server.enqueue(json(200, """{"plan":{"planCode":"PREMIUM","displayName":"Premium","planRank":1,"source":"SUBSCRIPTION","effectiveUntil":"2026-12-20T09:00:00Z"},"features":[],"entitlementToken":{}}"""))
        val plan = billing.currentPlan()
        assertEquals("PREMIUM", plan.planCode)
        assertEquals(java.time.Instant.parse("2026-12-20T09:00:00Z").toEpochMilli(), plan.effectiveUntilMs)
        server.enqueue(json(200, """{"items":[{"subscriptionId":"$SUB_ID","planCode":"PREMIUM","status":"ACTIVE","sourceType":"PAYMENT","billingMode":"MANUAL_RENEW","currentPeriodEnd":"2026-12-20T09:00:00Z"}]}"""))
        assertEquals("ACTIVE", billing.subscriptions().single().status)
        server.takeRequest(); server.takeRequest()
        server.enqueue(json(200, """{"storePurchaseId":"$SUB_ID","status":"ACTIVE","environment":"SANDBOX"}"""))
        val key = UUID.randomUUID()
        val res = billing.reportStorePurchase(key, "premium_monthly", "secret-token", "com.qalsmartkeyboard.app")
        val r = server.takeRequest()
        assertEquals("/api/v1/billing/store-purchases", r.path)
        assertEquals(key.toString(), r.getHeader("Idempotency-Key"))
        val body = Json.parseToJsonElement(r.body.readUtf8()) as JsonObject
        assertEquals(JsonPrimitive("GOOGLE_PLAY"), body["provider"])
        assertEquals(JsonPrimitive("secret-token"), body["purchaseToken"])
        assertEquals("ACTIVE", res.status)
    }

    @Test fun storeCreditPacksCarryTheStoreIdentifiers() = runBlocking {
        val pack = """{"productCode":"CREDITS_100","productType":"AI_CREDIT_PACK","displayName":"100 AI credits","creditUnits":100,"creditValidityDays":365,
            "prices":[{"billingPeriod":"ONE_TIME","renewalModel":"STORE_MANAGED","listPrice":{"amountMinor":1,"currency":"USD","exponent":2},
            "storeProductId":"credits_100","storeOfferingId":"credits","storePackageId":"credits_100_pkg"}]}"""
        server.enqueue(json(200, OPTIONS.replace("\"products\":[", "\"products\":[$pack,")))
        val price = billing.options().products.first { it.productType == "AI_CREDIT_PACK" }.prices.single()
        assertEquals("STORE_MANAGED", price.renewalModel)
        assertEquals("credits_100", price.storeProductId)
        assertEquals("credits", price.storeOfferingId)
        assertEquals("credits_100_pkg", price.storePackageId)
        server.enqueue(json(200, OPTIONS))
        assertNull(billing.options().products.single().prices.first().storeOfferingId)
    }

    @Test fun aCreditPackReportCarriesTheTransactionIdAndTheChannelAndParsesTheGrant() = runBlocking {
        server.enqueue(json(200, """{"storePurchaseId":"$SUB_ID","status":"ACTIVE","environment":"SANDBOX","purchaseType":"CONSUMABLE","productCode":"CREDITS_100",
            "transactionId":"GPA.1234-5678","creditUnits":100,"creditsExpireAt":"2027-11-24T09:46:40Z","creditLotId":"$SUB_ID"}"""))
        val res = billing.reportStorePurchase(UUID.randomUUID(), "credits_100", "tok", "com.qalsmartkeyboard.app", transactionId = "GPA.1234-5678")
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
        assertEquals(JsonPrimitive("GPA.1234-5678"), body["transactionId"])
        assertEquals(JsonPrimitive("GOOGLE_PLAY"), body["distributionChannel"])
        assertEquals(JsonPrimitive("credits_100"), body["productId"])
        assertEquals("CONSUMABLE", res.purchaseType)
        assertEquals("CREDITS_100", res.productCode)
        assertEquals("GPA.1234-5678", res.transactionId)
        assertEquals(100L, res.creditUnits)
        assertEquals(SUB_ID, res.creditLotId)
        assertEquals(java.time.Instant.parse("2027-11-24T09:46:40Z").toEpochMilli(), res.creditsExpireAtMs)
        // A subscription report sends no transaction id; a pending consumable has no lot yet.
        server.enqueue(json(200, """{"storePurchaseId":"$SUB_ID","status":"PENDING","environment":"SANDBOX","purchaseType":"CONSUMABLE"}"""))
        val pending = billing.reportStorePurchase(UUID.randomUUID(), "premium_monthly", "tok", "pkg")
        val sub = Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
        assertNull(sub["transactionId"])
        assertEquals("PENDING", pending.status)
        assertNull(pending.creditLotId)
    }

    @Test fun aBuildWithoutAChannelCannotAskForOptions() = runBlocking {
        try {
            client(null).options()
            fail()
        } catch (e: IllegalStateException) {
            assertFalse(e.message.isNullOrEmpty())
        }
    }

    companion object {
        const val ORDER_ID = "0193a5c2-7a10-7d11-8e2f-1a2b3c4d5e6f"
        const val ATTEMPT_ID = "0193a5c2-7a10-7d11-8e2f-aaaaaaaaaaaa"
        const val SUB_ID = "0193a5c2-7a10-7d11-8e2f-bbbbbbbbbbbb"

        val OPTIONS = """{"marketCountry":"ET","marketResolution":{"source":"IP_EDGE","confidence":"HIGH","globalDefault":false},
            "clientPlatform":"ANDROID","distributionChannel":"GOOGLE_PLAY","purchaseFlow":"LOCAL_GATEWAY_IN_APP","purchaseUiAllowed":true,
            "products":[{"productCode":"PLAN_PREMIUM","productType":"SUBSCRIPTION","displayName":"Premium","planCode":"PREMIUM",
              "prices":[{"billingPeriod":"MONTHLY","renewalModel":"MANUAL_RENEW","listPrice":{"amountMinor":29900,"currency":"ETB","exponent":2},"taxBehavior":"INCLUSIVE"},
                        {"billingPeriod":"ANNUAL","renewalModel":"MANUAL_RENEW","listPrice":{"amountMinor":299000,"currency":"ETB","exponent":2},"taxBehavior":"INCLUSIVE"}]}],
            "paymentMethods":[{"methodCode":"ARIFPAY_CHECKOUT","providerCode":"ARIFPAY","displayName":"ArifPay","methodType":"HOSTED_CHECKOUT","requiresRedirect":true,"priority":1,"isDefault":true},
                              {"methodCode":"CHAPA_NEW_METHOD","providerCode":"CHAPA","displayName":"Chapa","methodType":"SOMETHING_NEW","priority":2,"isDefault":false}],
            "policyEvaluatedAt":"2026-11-20T09:00:00Z"}"""

        val ORDER = """{"orderId":"$ORDER_ID","orderReference":"EAK-ET-2611-7Q4M2X","orderType":"SUBSCRIPTION_PURCHASE","status":"STATUS",
            "productCode":"PLAN_PREMIUM","billingPeriod":"MONTHLY","renewalModel":"MANUAL_RENEW","marketCountry":"ET","salesChannel":"ANDROID_GOOGLE_PLAY",
            "taxBehavior":"INCLUSIVE","amounts":{"list":{"amountMinor":29900,"currency":"ETB","exponent":2},"discount":{"amountMinor":0,"currency":"ETB","exponent":2},
            "tax":{"amountMinor":3900,"currency":"ETB","exponent":2},"total":{"amountMinor":29900,"currency":"ETB","exponent":2}},
            "expiresAt":"2026-11-20T09:30:00Z","createdAt":"2026-11-20T09:00:00Z"}"""

        val ATTEMPT = """{"attemptId":"$ATTEMPT_ID","attemptNumber":1,"status":"REQUIRES_ACTION","methodCode":"ARIFPAY_CHECKOUT","providerCode":"ARIFPAY",
            "nextAction":{"type":"REDIRECT","checkoutUrl":"https://checkout.arifpay.org/s/abc"},"expiresAt":"2026-11-20T09:30:00Z","createdAt":"2026-11-20T09:00:00Z"}"""
    }
}
