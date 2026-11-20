package com.qalsmartkeyboard.api.billing

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.ApiErrors
import com.qalsmartkeyboard.api.DistributionChannel
import com.qalsmartkeyboard.api.apiCall
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * The billing calls of the companion app (ADR 0045, ADR 0047) over the app's OkHttp stack: bearer, client headers,
 * the country hints (billing paths only) and the refresh authenticator. Failures are [ApiError]s; nothing here logs
 * (bodies carry purchase tokens and order references).
 *
 * The client never decides that a payment succeeded: it creates an order the server priced, starts an attempt, opens
 * the provider's page, and then only reads the order the server verified ([order]).
 */
class BillingClient(private val clients: ApiClients) {
    private val base = clients.config.baseUrl.trimEnd('/') + "/api/v1"

    /** This build's channel; a build without one cannot buy. */
    val channel: DistributionChannel?
        get() = clients.config.client.distributionChannel

    private fun requireChannel(): DistributionChannel =
        channel ?: throw IllegalStateException("this build has no distribution channel")

    /** `GET /billing/options`: the server resolves the market from the request (edge country + hints). */
    suspend fun options(): BillingOptions {
        val c = requireChannel()
        val doc = call(
            "GET", "/billing/options",
            query = mapOf("clientPlatform" to clients.config.client.platform, "distributionChannel" to c.apiValue),
        )
        return BillingJson.options(doc) ?: throw ApiError.Unexpected(200)
    }

    /**
     * `POST /billing/orders` with [idempotencyKey] (the same key and body return the same order: a retry after a lost
     * answer never creates a second one). [marketCountry] is the value [options] returned; the server prices it.
     */
    suspend fun createOrder(idempotencyKey: UUID, productCode: String, billingPeriod: String, marketCountry: String): PaymentOrder {
        val body = buildJsonObject {
            put("productCode", productCode)
            put("billingPeriod", billingPeriod)
            put("marketCountry", marketCountry)
            put("salesChannel", requireChannel().salesChannel)
            put("renewalPreference", "MANUAL_RENEW")
        }
        val doc = call("POST", "/billing/orders", body = body, idempotencyKey = idempotencyKey)
        return BillingJson.order(doc) ?: throw ApiError.Unexpected(201)
    }

    /** `POST /billing/orders/{id}/attempts`. While a hosted checkout of that method is open, the server returns it. */
    suspend fun createAttempt(orderId: UUID, idempotencyKey: UUID, methodCode: String, locale: String?): PaymentAttempt {
        val body = buildJsonObject {
            put("methodCode", methodCode)
            locale?.takeIf { it.isNotBlank() && it.length <= 35 }?.let { put("locale", it) }
        }
        val doc = call("POST", "/billing/orders/$orderId/attempts", body = body, idempotencyKey = idempotencyKey)
        return BillingJson.attempt(doc) ?: throw ApiError.Unexpected(201)
    }

    /** `GET /billing/orders/{id}`: the polling endpoint. Only `PAID` here means paid. */
    suspend fun order(orderId: UUID): PaymentOrder =
        BillingJson.order(call("GET", "/billing/orders/$orderId")) ?: throw ApiError.Unexpected(200)

    /** `GET /billing/orders/{id}/attempts/{attemptId}`: tells a failed or cancelled attempt from one still open. */
    suspend fun attempt(orderId: UUID, attemptId: UUID): PaymentAttempt =
        BillingJson.attempt(call("GET", "/billing/orders/$orderId/attempts/$attemptId")) ?: throw ApiError.Unexpected(200)

    /**
     * Tells the server the payer came back from the hosted checkout (`GET /billing/return/{attemptId}`, no bearer):
     * it queues one server-to-server verification (at most once per 30 s) and changes nothing else. The checkout
     * WebView stops at the return URL instead of loading it, so the app makes this call itself, on its own API host and
     * with the attempt id it created. Best effort: when it fails, the webhook or the reconciliation job settles the
     * payment instead.
     */
    suspend fun signalReturn(attemptId: UUID, result: String?) {
        val safe = result?.takeIf { it in RESULTS }
        try {
            apiCall {
                val url = "$base/billing/return/$attemptId".toHttpUrl().newBuilder()
                    .apply { safe?.let { addQueryParameter("result", it) } }.build()
                // No bearer and no redirects (the base client never follows them): the 303 points at the app's scheme.
                clients.anonymousClient.newCall(Request.Builder().url(url).get().build()).execute().use { }
            }
        } catch (_: ApiError) {
        }
    }

    /** The plan the server grants now (`GET /me/entitlements`, its `plan` member). */
    suspend fun currentPlan(): CurrentPlan =
        BillingJson.plan(call("GET", "/me/entitlements")) ?: throw ApiError.Unexpected(200)

    /** `GET /me/subscriptions` (first page). */
    suspend fun subscriptions(): List<Subscription> {
        val doc = call("GET", "/me/subscriptions", query = mapOf("limit" to "20"))
        return BillingJson.arr(doc as? JsonObject, "items").mapNotNull { BillingJson.subscription(it) }
    }

    /**
     * `POST /billing/store-purchases` (Google Play through RevenueCat, ADR 0047): the server verifies the purchase
     * with the store and grants the plan; the app then reads the plan the server shows. [purchaseToken] is never
     * logged or stored by this class.
     */
    suspend fun reportStorePurchase(idempotencyKey: UUID, productId: String, purchaseToken: String, packageName: String): StorePurchaseResult {
        val body = buildJsonObject {
            put("provider", "GOOGLE_PLAY")
            put("productId", productId)
            put("purchaseToken", purchaseToken)
            put("packageName", packageName)
        }
        val doc = call("POST", "/billing/store-purchases", body = body, idempotencyKey = idempotencyKey)
        return BillingJson.storeResult(doc) ?: throw ApiError.Unexpected(200)
    }

    private suspend fun call(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonElement? = null,
        idempotencyKey: UUID? = null,
    ): JsonElement? = apiCall {
        val url = (base + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url)
            .method(method, body?.let { Json.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON) })
            .header("Accept", "application/json")
            .apply { idempotencyKey?.let { header("Idempotency-Key", it.toString()) } }
            .build()
        clients.okHttpClient.newCall(request).execute().use { r ->
            val text = r.body?.string()
            if (!r.isSuccessful) throw ApiErrors.fromHttp(r.code, text, r.headers.toMultimap())
            if (r.code == 204 || text.isNullOrBlank()) return@apiCall null
            try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                throw ApiError.Unexpected(r.code, e)
            }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val RESULTS = setOf("success", "cancel", "error")
    }
}
