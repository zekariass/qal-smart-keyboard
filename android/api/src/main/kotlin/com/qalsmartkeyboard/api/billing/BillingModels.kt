package com.qalsmartkeyboard.api.billing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/*
 * The billing documents of the contract (ADR 0045), read by hand like `:sync` and the privacy screens: status and type
 * values stay strings, so a value a newer server adds never breaks the paywall (the generated models would fail the
 * whole answer on an unknown enum value). Money is integer minor units with the currency's exponent, exactly as the
 * server priced it; the app formats it and never computes a price.
 */

/** `Money`: `29900`/`ETB`/`2` is 299.00 ETB. */
data class Money(val amountMinor: Long, val currency: String, val exponent: Int)

/** `Price` of a product: one billing period. */
data class Price(
    val billingPeriod: String,
    val renewalModel: String,
    val listPrice: Money,
    val taxBehavior: String?,
    /** The server's localised display string, when it sends one. */
    val displayPrice: String?,
    val storeProductId: String?,
)

/** `Product`. */
data class Product(
    val productCode: String,
    val productType: String,
    val displayName: String,
    val description: String?,
    val planCode: String?,
    /** `AI_CREDIT_PACK` only (T8.10): the credits the pack grants and how many days they stay valid. */
    val creditUnits: Long? = null,
    val creditValidityDays: Int? = null,
    val prices: List<Price>,
)

/** `PaymentMethodOption`: public codes only. The server orders them by priority; [isDefault] marks the first. */
data class PaymentMethod(
    val methodCode: String,
    val providerCode: String,
    val displayName: String,
    val methodType: String,
    val requiresRedirect: Boolean?,
    val priority: Int?,
    val isDefault: Boolean,
)

/** `BillingOptions`: what this market and build may buy, and how (ADR 0045). */
data class BillingOptions(
    val marketCountry: String,
    /** True when the market fell back to the global default (`ZZ`). */
    val globalDefault: Boolean?,
    /** `LOCAL_GATEWAY_IN_APP`, `EXTERNAL_WEB_CHECKOUT`, `STORE_BILLING`, `NONE`, ... */
    val purchaseFlow: String,
    val purchaseUiAllowed: Boolean,
    val products: List<Product>,
    val paymentMethods: List<PaymentMethod>,
    val notice: String?,
) {
    val gatewayFlow: Boolean get() = purchaseFlow == FLOW_LOCAL_GATEWAY || purchaseFlow == FLOW_EXTERNAL_WEB
    val storeFlow: Boolean get() = purchaseFlow == FLOW_STORE

    companion object {
        const val FLOW_LOCAL_GATEWAY = "LOCAL_GATEWAY_IN_APP"
        const val FLOW_EXTERNAL_WEB = "EXTERNAL_WEB_CHECKOUT"
        const val FLOW_STORE = "STORE_BILLING"
    }
}

/** `PaymentOrder`, the members the app shows or polls. */
data class PaymentOrder(
    val orderId: String,
    val orderReference: String,
    /** `CREATED`, `AWAITING_PAYMENT`, `PAID`, `FAILED`, `CANCELLED`, `EXPIRED`, `PARTIALLY_REFUNDED`, `REFUNDED`. */
    val status: String,
    val productCode: String,
    val billingPeriod: String?,
    val renewalModel: String?,
    val marketCountry: String?,
    val total: Money?,
    val expiresAtMs: Long?,
    val paidAtMs: Long?,
    val subscriptionId: String?,
    val receiptId: String?,
    /** The credit lot a verified credit-pack order granted (T8.10). */
    val creditLotId: String? = null,
)

/** `PaymentAttempt`. [checkoutUrl] is the provider's hosted page when [nextActionType] is `REDIRECT`. */
data class PaymentAttempt(
    val attemptId: String,
    val attemptNumber: Int,
    val status: String,
    val methodCode: String,
    val providerCode: String,
    val nextActionType: String?,
    val checkoutUrl: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val expiresAtMs: Long?,
)

/** `Subscription`. */
data class Subscription(
    val subscriptionId: String,
    val planCode: String,
    val status: String,
    val sourceType: String?,
    val billingMode: String?,
    val providerCode: String?,
    val currentPeriodStartMs: Long?,
    val currentPeriodEndMs: Long?,
)

/** The `plan` member of `GET /me/entitlements`: the plan the server grants right now, from any source. */
data class CurrentPlan(
    val planCode: String,
    val displayName: String?,
    val source: String?,
    val effectiveUntilMs: Long?,
)

/** `StorePurchaseResult` (`POST /billing/store-purchases`). */
data class StorePurchaseResult(
    val storePurchaseId: String?,
    val status: String,
    val expiresAtMs: Long?,
    val subscription: Subscription?,
)

/** One spendable lot of `GET /me/credits`. */
data class CreditLot(val lotId: String, val originalUnits: Double, val remainingUnits: Double, val source: String, val expiresAtMs: Long?)

/** `CreditSummary` (`GET /me/credits`): [balanceUnits] is exact (6 dp); show it rounded down. */
data class CreditSummary(val balanceUnits: Double, val nextExpiryAtMs: Long?, val lots: List<CreditLot>)

/** `CreditTransaction`: one ledger row. `transactionType`: PURCHASE, GRANT, CONSUMPTION, REFUND, EXPIRY, ADJUSTMENT, REVERSAL. */
data class CreditTransaction(
    val id: String,
    val transactionType: String,
    val deltaUnits: Double,
    val balanceAfter: Double,
    val featureCode: String?,
    val createdAtMs: Long?,
)

/** A page of the ledger, newest first; [nextCursor] is passed back for the next one. */
data class CreditTransactionPage(val items: List<CreditTransaction>, val nextCursor: String?)

internal object BillingJson {
    fun double(o: JsonObject?, name: String): Double? = (o?.get(name) as? JsonPrimitive)?.doubleOrNull

    fun str(o: JsonObject?, name: String): String? = (o?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    fun long(o: JsonObject?, name: String): Long? = (o?.get(name) as? JsonPrimitive)?.longOrNull
    fun int(o: JsonObject?, name: String): Int? = (o?.get(name) as? JsonPrimitive)?.intOrNull
    fun bool(o: JsonObject?, name: String): Boolean? = (o?.get(name) as? JsonPrimitive)?.booleanOrNull
    fun obj(o: JsonObject?, name: String): JsonObject? = o?.get(name) as? JsonObject
    fun arr(o: JsonObject?, name: String): List<JsonObject> = (o?.get(name) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    fun time(o: JsonObject?, name: String): Long? = str(o, name)?.let {
        try {
            OffsetDateTime.parse(it).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    fun money(o: JsonObject?): Money? {
        val amount = long(o, "amountMinor") ?: return null
        val currency = str(o, "currency") ?: return null
        val exponent = int(o, "exponent") ?: return null
        if (amount < 0 || exponent !in 0..4) return null
        return Money(amount, currency, exponent)
    }

    fun price(o: JsonObject): Price? {
        return Price(
            billingPeriod = str(o, "billingPeriod") ?: return null,
            renewalModel = str(o, "renewalModel") ?: return null,
            listPrice = money(obj(o, "listPrice")) ?: return null,
            taxBehavior = str(o, "taxBehavior"),
            displayPrice = str(o, "displayPrice"),
            storeProductId = str(o, "storeProductId"),
        )
    }

    fun product(o: JsonObject): Product? {
        return Product(
            productCode = str(o, "productCode") ?: return null,
            productType = str(o, "productType") ?: return null,
            displayName = str(o, "displayName") ?: return null,
            description = str(o, "description"),
            planCode = str(o, "planCode"),
            creditUnits = long(o, "creditUnits"),
            creditValidityDays = int(o, "creditValidityDays"),
            prices = arr(o, "prices").mapNotNull { price(it) },
        )
    }

    fun method(o: JsonObject): PaymentMethod? {
        return PaymentMethod(
            methodCode = str(o, "methodCode") ?: return null,
            providerCode = str(o, "providerCode") ?: return null,
            displayName = str(o, "displayName") ?: return null,
            methodType = str(o, "methodType") ?: return null,
            requiresRedirect = bool(o, "requiresRedirect"),
            priority = int(o, "priority"),
            isDefault = bool(o, "isDefault") ?: false,
        )
    }

    fun options(e: JsonElement?): BillingOptions? {
        val o = e as? JsonObject ?: return null
        return BillingOptions(
            marketCountry = str(o, "marketCountry") ?: return null,
            globalDefault = bool(obj(o, "marketResolution"), "globalDefault"),
            purchaseFlow = str(o, "purchaseFlow") ?: return null,
            purchaseUiAllowed = bool(o, "purchaseUiAllowed") ?: false,
            products = arr(o, "products").mapNotNull { product(it) },
            paymentMethods = arr(o, "paymentMethods").mapNotNull { method(it) },
            notice = str(o, "notice"),
        )
    }

    fun order(e: JsonElement?): PaymentOrder? {
        val o = e as? JsonObject ?: return null
        val fulfilment = obj(o, "fulfilment")
        return PaymentOrder(
            orderId = str(o, "orderId") ?: return null,
            orderReference = str(o, "orderReference") ?: return null,
            status = str(o, "status") ?: return null,
            productCode = str(o, "productCode") ?: return null,
            billingPeriod = str(o, "billingPeriod"),
            renewalModel = str(o, "renewalModel"),
            marketCountry = str(o, "marketCountry"),
            total = money(obj(obj(o, "amounts"), "total")),
            expiresAtMs = time(o, "expiresAt"),
            paidAtMs = time(o, "paidAt"),
            subscriptionId = str(fulfilment, "subscriptionId"),
            receiptId = str(fulfilment, "receiptId"),
            creditLotId = str(fulfilment, "creditLotId"),
        )
    }

    fun attempt(e: JsonElement?): PaymentAttempt? {
        val o = e as? JsonObject ?: return null
        val next = obj(o, "nextAction")
        return PaymentAttempt(
            attemptId = str(o, "attemptId") ?: return null,
            attemptNumber = int(o, "attemptNumber") ?: 1,
            status = str(o, "status") ?: return null,
            methodCode = str(o, "methodCode") ?: return null,
            providerCode = str(o, "providerCode") ?: return null,
            nextActionType = str(next, "type"),
            checkoutUrl = str(next, "checkoutUrl"),
            failureCode = str(o, "failureCode"),
            failureMessage = str(o, "failureMessage"),
            expiresAtMs = time(o, "expiresAt"),
        )
    }

    fun subscription(o: JsonObject?): Subscription? {
        return Subscription(
            subscriptionId = str(o, "subscriptionId") ?: return null,
            planCode = str(o, "planCode") ?: return null,
            status = str(o, "status") ?: return null,
            sourceType = str(o, "sourceType"),
            billingMode = str(o, "billingMode"),
            providerCode = str(o, "providerCode"),
            currentPeriodStartMs = time(o, "currentPeriodStart"),
            currentPeriodEndMs = time(o, "currentPeriodEnd"),
        )
    }

    fun plan(e: JsonElement?): CurrentPlan? {
        val plan = obj(e as? JsonObject, "plan") ?: return null
        return CurrentPlan(
            planCode = str(plan, "planCode") ?: return null,
            displayName = str(plan, "displayName"),
            source = str(plan, "source"),
            effectiveUntilMs = time(plan, "effectiveUntil"),
        )
    }

    fun storeResult(e: JsonElement?): StorePurchaseResult? {
        val o = e as? JsonObject ?: return null
        return StorePurchaseResult(
            storePurchaseId = str(o, "storePurchaseId"),
            status = str(o, "status") ?: return null,
            expiresAtMs = time(o, "expiresAt"),
            subscription = subscription(obj(o, "subscription")),
        )
    }

    fun credits(e: JsonElement?): CreditSummary? {
        val o = e as? JsonObject ?: return null
        return CreditSummary(
            balanceUnits = double(o, "balanceUnits")?.takeIf { it >= 0 } ?: return null,
            nextExpiryAtMs = time(o, "nextExpiryAt"),
            lots = arr(o, "lots").mapNotNull { l ->
                CreditLot(
                    lotId = str(l, "lotId") ?: return@mapNotNull null,
                    originalUnits = double(l, "originalUnits") ?: return@mapNotNull null,
                    remainingUnits = double(l, "remainingUnits") ?: return@mapNotNull null,
                    source = str(l, "source") ?: "",
                    expiresAtMs = time(l, "expiresAt"),
                )
            },
        )
    }

    fun creditPage(e: JsonElement?): CreditTransactionPage? {
        val o = e as? JsonObject ?: return null
        return CreditTransactionPage(
            items = arr(o, "items").mapNotNull { t ->
                CreditTransaction(
                    id = str(t, "id") ?: return@mapNotNull null,
                    transactionType = str(t, "transactionType") ?: return@mapNotNull null,
                    deltaUnits = double(t, "deltaUnits") ?: return@mapNotNull null,
                    balanceAfter = double(t, "balanceAfter") ?: 0.0,
                    featureCode = str(t, "featureCode"),
                    createdAtMs = time(t, "createdAt"),
                )
            },
            nextCursor = str(o, "nextCursor"),
        )
    }
}
