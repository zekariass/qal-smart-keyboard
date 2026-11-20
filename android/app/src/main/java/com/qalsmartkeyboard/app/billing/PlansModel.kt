package com.qalsmartkeyboard.app.billing

import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.billing.BillingClient
import com.qalsmartkeyboard.api.billing.BillingOptions
import com.qalsmartkeyboard.api.billing.CurrentPlan
import com.qalsmartkeyboard.api.billing.Money
import com.qalsmartkeyboard.api.billing.PaymentMethod
import com.qalsmartkeyboard.api.billing.PaymentOrder
import com.qalsmartkeyboard.api.billing.Subscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID

/** Who may see what on the paywall. */
enum class BuyerKind { SIGNED_OUT, GUEST, REGISTERED }

enum class PlansPhase {
    LOADING,

    /** Nobody is signed in: the plans need an account (sign-in button). */
    SIGNED_OUT,

    /** No connection and nothing to show yet. */
    OFFLINE,

    /** The server answered with an error; retry. */
    ERROR,

    /** The server says nothing can be bought in this market with this build (or the build lacks the store). */
    NOT_AVAILABLE,

    READY,
}

/** Why [PlansPhase.NOT_AVAILABLE]. */
enum class UnavailableReason {
    /** The market/channel policy offers nothing (fail closed). */
    MARKET,

    /** Store billing is the way to buy here, but this build has no store SDK or key (the `direct` APK). */
    STORE_NOT_IN_BUILD,
}

/** A short message the paywall shows after an action; never server prose. */
enum class PlansMessage {
    OFFLINE,
    SERVER,
    NEEDS_ACCOUNT,

    /** 403 `BILLING_POLICY_DENIED` on order: the market changed under the user; the options were reloaded. */
    MARKET_CHANGED,
    METHOD_UNAVAILABLE,
    PROVIDER_UNAVAILABLE,
    PRODUCT_UNAVAILABLE,
    CHECKOUT_UNAVAILABLE,
    STORE_FAILED,
}

/** One product price the user can pick (gateway flow): exactly the server's money. */
data class PlanOffer(
    val productCode: String,
    val productName: String,
    val description: String?,
    val planCode: String?,
    /** `MONTHLY`, `ANNUAL`, ... */
    val period: String,
    val price: Money,
    val displayPrice: String?,
) {
    val key: String get() = "$productCode/$period"
}

/** What the current plan looks like (from `/me/entitlements` and `/me/subscriptions`). */
data class CurrentPlanInfo(
    val planCode: String,
    val name: String?,
    val source: String?,
    /** When the current access ends (the last stacked term), or null (free, or no end). */
    val untilMs: Long?,
    /** Bought with a manual-renew term (the user renews it himself). */
    val manualRenew: Boolean,
) {
    val isFree: Boolean get() = planCode == "FREE" || planCode == "GUEST"
}

enum class CheckoutStep {
    IDLE,

    /** Creating the order and the attempt. */
    STARTING,

    /** The provider's page is open in the WebView. */
    CHECKOUT,

    /** Polling the order (or reporting a store purchase). */
    CONFIRMING,

    /** No final answer within the polling window: "Still confirming, we'll update you" + refresh. */
    STILL_CONFIRMING,

    /** The payer left the checkout (or the provider said cancel/failed) and the order is still unpaid. */
    NOT_COMPLETED,

    /** The server verified the payment: receipt. */
    PAID,

    /** The order ended without payment (failed, cancelled, expired). */
    FAILED,
}

/** The receipt summary after a verified payment. */
data class Receipt(
    val orderReference: String?,
    val planName: String?,
    val period: String?,
    val amount: Money?,
    /** A store's own formatted price (store purchases). */
    val storePrice: String?,
    val paidAtMs: Long?,
    val validUntilMs: Long?,
)

data class CheckoutState(
    val step: CheckoutStep = CheckoutStep.IDLE,
    val checkoutUrl: String? = null,
    /** The order in progress (gateway flow). */
    val orderId: String? = null,
    val receipt: Receipt? = null,
    /** Final order status for [CheckoutStep.FAILED]: `FAILED`, `CANCELLED`, `EXPIRED`, ... */
    val finalStatus: String? = null,
    /** A store purchase waits for the server to confirm it. */
    val store: Boolean = false,
)

data class PlansState(
    val phase: PlansPhase = PlansPhase.LOADING,
    val buyer: BuyerKind = BuyerKind.SIGNED_OUT,
    val current: CurrentPlanInfo? = null,
    val unavailable: UnavailableReason? = null,
    /** True when buying goes through the store (RevenueCat) instead of a local gateway. */
    val storeFlow: Boolean = false,
    val offers: List<PlanOffer> = emptyList(),
    /** Payment options in the server's order: the default first. */
    val methods: List<PaymentMethod> = emptyList(),
    val storeOffers: List<StoreOffer> = emptyList(),
    val selectedOffer: String? = null,
    val selectedMethod: String? = null,
    val checkout: CheckoutState = CheckoutState(),
    val message: PlansMessage? = null,
    /** A server notice to show as is (`BillingOptions.notice`, already localised by the server). */
    val notice: String? = null,
) {
    val selected: PlanOffer? get() = offers.firstOrNull { it.key == selectedOffer }
    val canBuy: Boolean get() = phase == PlansPhase.READY && buyer == BuyerKind.REGISTERED && checkout.step == CheckoutStep.IDLE
}

/** Refreshes the keyboard's entitlement token after the server granted a plan (blocking; runs on [Dispatchers.IO]). */
fun interface PlanRefresher {
    fun planChanged()
}

/**
 * The paywall and checkout logic (ADR 0047), plain Kotlin over [BillingClient] so every state is a JVM test.
 *
 * Gateway flow (ArifPay, Chapa): [buy] creates a server-priced order (idempotency key), starts an attempt for the chosen
 * method and shows its `checkoutUrl` in the checkout WebView. The WebView reports the return URL ([onReturned]) or the
 * user closing it ([onCheckoutClosed]); the model then signals the return to the server and polls
 * `GET /billing/orders/{id}` with backoff until `PAID` or a final failure, or until [POLL_SCHEDULE_MS] runs out ("still
 * confirming"). Only the server's `PAID` counts: then the entitlement token is refreshed ([PlanRefresher]) so the
 * keyboard unlocks at once, and a receipt is shown. The order is kept in [pending] so a process death resumes polling.
 *
 * Store flow (RevenueCat, `play` only): [buyFromStore] runs the store sheet and reports the purchase to the backend;
 * only the server's verified answer (or the plan it then shows) unlocks anything.
 */
class PlansModel(
    private val billing: BillingClient,
    private val buyer: () -> BuyerKind,
    /** The backend user id (the access token's `sub`), the store's app user id. */
    private val userId: () -> String?,
    private val pending: PendingCheckoutStore,
    private val refresher: PlanRefresher,
    private val store: StoreBilling = StoreBilling.Unavailable,
    private val locale: () -> String? = { null },
    private val packageName: String = "",
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val newKey: () -> UUID = UUID::randomUUID,
) {
    private val _state = MutableStateFlow(PlansState())
    val state: StateFlow<PlansState> = _state.asStateFlow()

    private var options: BillingOptions? = null

    /** The open order of this selection, reused by a retry so a lost answer never creates a second order. */
    private var order: PaymentOrder? = null
    private var orderKey: Pair<String, UUID>? = null
    private var attemptKey: Pair<String, UUID>? = null

    /** A store purchase the backend has not confirmed yet (memory only; RevenueCat's webhook also reports it). */
    private var unreported: StorePurchaseOutcome.Purchased? = null
    private var storeReportKey: UUID? = null
    private var storeOfferShown: StoreOffer? = null

    // ---------------------------------------------------------------- loading

    /** Loads the plan, the options and (store flow) the store offers; then resumes a pending checkout. */
    suspend fun load() {
        val who = buyer()
        _state.update { it.copy(buyer = who, message = null, phase = if (it.phase == PlansPhase.READY) PlansPhase.READY else PlansPhase.LOADING) }
        if (who == BuyerKind.SIGNED_OUT) {
            options = null
            _state.update { PlansState(phase = PlansPhase.SIGNED_OUT, buyer = who) }
            return
        }
        val opts = try {
            billing.options()
        } catch (e: ApiError) {
            failLoad(e)
            return
        }
        options = opts
        val current = loadCurrent(who)
        if (!opts.purchaseUiAllowed || opts.paymentMethods.isEmpty() || !(opts.gatewayFlow || opts.storeFlow)) {
            _state.update { it.copy(phase = PlansPhase.NOT_AVAILABLE, unavailable = UnavailableReason.MARKET, current = current, offers = emptyList(), methods = emptyList(), notice = opts.notice) }
            resumePending()
            return
        }
        if (opts.storeFlow) {
            loadStore(who, current, opts)
        } else {
            val offers = offersOf(opts)
            if (offers.isEmpty()) {
                _state.update { it.copy(phase = PlansPhase.NOT_AVAILABLE, unavailable = UnavailableReason.MARKET, current = current, notice = opts.notice) }
            } else {
                val methods = opts.paymentMethods.sortedWith(compareByDescending<PaymentMethod> { it.isDefault }.thenBy { it.priority ?: Int.MAX_VALUE })
                _state.update { s ->
                    s.copy(
                        phase = PlansPhase.READY,
                        unavailable = null,
                        storeFlow = false,
                        current = current,
                        offers = offers,
                        methods = methods,
                        selectedOffer = s.selectedOffer?.takeIf { k -> offers.any { it.key == k } } ?: defaultOffer(offers),
                        selectedMethod = s.selectedMethod?.takeIf { c -> methods.any { it.methodCode == c } } ?: methods.first().methodCode,
                        notice = opts.notice,
                    )
                }
            }
        }
        resumePending()
    }

    private suspend fun loadStore(who: BuyerKind, current: CurrentPlanInfo?, opts: BillingOptions) {
        val storeOffered = opts.paymentMethods.any { it.methodType == "APP_STORE" || it.providerCode == "REVENUECAT" }
        if (!storeOffered || !store.available) {
            _state.update {
                it.copy(phase = PlansPhase.NOT_AVAILABLE, unavailable = if (storeOffered) UnavailableReason.STORE_NOT_IN_BUILD else UnavailableReason.MARKET, current = current, notice = opts.notice)
            }
            return
        }
        val uid = userId()
        val offers = if (who == BuyerKind.REGISTERED && uid != null) {
            try {
                store.offers(uid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        _state.update { s ->
            s.copy(
                phase = if (who == BuyerKind.REGISTERED && offers.isEmpty()) PlansPhase.ERROR else PlansPhase.READY,
                storeFlow = true,
                unavailable = null,
                current = current,
                offers = emptyList(),
                methods = emptyList(),
                storeOffers = offers,
                selectedOffer = s.selectedOffer?.takeIf { k -> offers.any { it.id == k } } ?: offers.firstOrNull()?.id,
                notice = opts.notice,
            )
        }
    }

    private fun failLoad(e: ApiError) {
        val phase = when {
            e is ApiError.SignedOut || e.status == 401 -> PlansPhase.SIGNED_OUT
            e is ApiError.Network || e is ApiError.Timeout -> PlansPhase.OFFLINE
            else -> PlansPhase.ERROR
        }
        _state.update { s -> if (s.phase == PlansPhase.READY && phase != PlansPhase.SIGNED_OUT) s.copy(message = messageOf(e)) else s.copy(phase = phase) }
    }

    private suspend fun loadCurrent(who: BuyerKind): CurrentPlanInfo? {
        val plan: CurrentPlan = try {
            billing.currentPlan()
        } catch (e: ApiError) {
            return _state.value.current
        }
        val subs: List<Subscription> = if (who == BuyerKind.REGISTERED) {
            try {
                billing.subscriptions()
            } catch (e: ApiError) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val live = subs.filter { it.status in LIVE_SUBSCRIPTION && it.planCode == plan.planCode }
        val until = live.mapNotNull { it.currentPeriodEndMs }.maxOrNull() ?: plan.effectiveUntilMs
        return CurrentPlanInfo(
            planCode = plan.planCode,
            name = plan.displayName,
            source = plan.source,
            untilMs = until,
            manualRenew = live.any { it.billingMode == "MANUAL_RENEW" },
        )
    }

    private fun offersOf(o: BillingOptions): List<PlanOffer> =
        o.products.filter { it.productType == "SUBSCRIPTION" }.flatMap { p ->
            p.prices.filter { it.renewalModel == "MANUAL_RENEW" }
                .sortedBy { PERIOD_ORDER.indexOf(it.billingPeriod).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .map { PlanOffer(p.productCode, p.displayName, p.description, p.planCode, it.billingPeriod, it.listPrice, it.displayPrice) }
        }

    private fun defaultOffer(offers: List<PlanOffer>): String = (offers.firstOrNull { it.period == "MONTHLY" } ?: offers.first()).key

    fun selectOffer(key: String) = _state.update { it.copy(selectedOffer = key, message = null) }

    fun selectMethod(code: String) = _state.update { it.copy(selectedMethod = code, message = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---------------------------------------------------------------- gateway checkout

    /** Starts the hosted checkout of the selected offer and method. */
    suspend fun buy() {
        val s = _state.value
        if (s.phase != PlansPhase.READY || s.storeFlow || s.checkout.step != CheckoutStep.IDLE) return
        if (buyer() != BuyerKind.REGISTERED) {
            _state.update { it.copy(message = PlansMessage.NEEDS_ACCOUNT) }
            return
        }
        val offer = s.selected ?: return
        val method = s.selectedMethod ?: return
        val market = options?.marketCountry ?: return
        _state.update { it.copy(checkout = CheckoutState(CheckoutStep.STARTING), message = null) }
        try {
            val o = openOrder(offer, market)
            startAttempt(o.orderId, method, offer.productCode, offer.period)
        } catch (e: ApiError) {
            onStartFailed(e)
        }
    }

    private suspend fun openOrder(offer: PlanOffer, market: String): PaymentOrder {
        order?.let { o ->
            val fresh = o.status == "AWAITING_PAYMENT" && (o.expiresAtMs ?: Long.MAX_VALUE) > now() + ORDER_MARGIN_MS
            if (fresh && o.productCode == offer.productCode && o.billingPeriod == offer.period) return o
        }
        val key = orderKey?.takeIf { it.first == offer.key }?.second ?: newKey().also { orderKey = offer.key to it }
        val o = billing.createOrder(key, offer.productCode, offer.period, market)
        order = o
        return o
    }

    private suspend fun startAttempt(orderId: String, method: String, productCode: String, period: String) {
        val id = UUID.fromString(orderId)
        val key = attemptKey?.takeIf { it.first == "$orderId/$method" }?.second ?: newKey().also { attemptKey = "$orderId/$method" to it }
        pending.save(PendingCheckout(orderId, null, method, productCode, period, now()))
        val a = billing.createAttempt(id, key, method, locale())
        attemptKey = null
        pending.save(PendingCheckout(orderId, a.attemptId, method, productCode, period, now()))
        when {
            a.status in FAILED_ATTEMPT -> {
                _state.update { it.copy(checkout = CheckoutState(CheckoutStep.NOT_COMPLETED, orderId = orderId)) }
            }
            a.nextActionType == "REDIRECT" && CheckoutUrlPolicy.isAllowedStart(a.checkoutUrl) -> {
                _state.update { it.copy(checkout = CheckoutState(CheckoutStep.CHECKOUT, checkoutUrl = a.checkoutUrl, orderId = orderId)) }
            }
            a.nextActionType == "REDIRECT" -> {
                // Not an https page with a host: never opened.
                _state.update { it.copy(checkout = CheckoutState(CheckoutStep.NOT_COMPLETED, orderId = orderId), message = PlansMessage.CHECKOUT_UNAVAILABLE) }
            }
            else -> {
                _state.update { it.copy(checkout = CheckoutState(CheckoutStep.CONFIRMING, orderId = orderId)) }
                poll()
            }
        }
    }

    private suspend fun onStartFailed(e: ApiError) {
        val code = e.code
        val msg = messageOf(e)
        if (code == "ORDER_EXPIRED" || code == "ORDER_NOT_PAYABLE") {
            order = null
            orderKey = null
        }
        val p = pending.load()
        if (p != null && p.attemptId == null) pending.clear()
        _state.update { it.copy(checkout = CheckoutState(), message = msg) }
        if (code == "BILLING_POLICY_DENIED" || code == "PRODUCT_NOT_AVAILABLE") {
            order = null
            orderKey = null
            load()
            _state.update { it.copy(message = msg) }
        }
    }

    /** The checkout WebView reached the return URL (or the app's return URI): confirm with the server. */
    suspend fun onReturned(result: String?) {
        val p = pending.load() ?: return
        pending.save(p.copy(returned = true, result = result))
        _state.update { it.copy(checkout = CheckoutState(CheckoutStep.CONFIRMING, orderId = p.orderId)) }
        p.attemptId?.let { billing.signalReturn(UUID.fromString(it), result) }
        poll()
    }

    /** The user closed the checkout without a return: the payment may still have gone through, so check. */
    suspend fun onCheckoutClosed() {
        val p = pending.load() ?: run {
            _state.update { it.copy(checkout = CheckoutState()) }
            return
        }
        _state.update { it.copy(checkout = CheckoutState(CheckoutStep.CONFIRMING, orderId = p.orderId)) }
        poll(PlansModel.CLOSED_POLL_SCHEDULE_MS)
    }

    /** Opens the provider page of the pending order again (the server returns the open checkout of that method). */
    suspend fun reopenCheckout() {
        val p = pending.load() ?: return
        val step = _state.value.checkout.step
        if (step != CheckoutStep.NOT_COMPLETED && step != CheckoutStep.STILL_CONFIRMING) return
        _state.update { it.copy(checkout = CheckoutState(CheckoutStep.STARTING, orderId = p.orderId), message = null) }
        try {
            startAttempt(p.orderId, p.methodCode, p.productCode, p.billingPeriod)
        } catch (e: ApiError) {
            if (e.code == "ORDER_EXPIRED" || e.code == "ORDER_NOT_PAYABLE") {
                pending.clear()
                order = null
                _state.update { it.copy(checkout = CheckoutState(), message = messageOf(e)) }
            } else {
                _state.update { it.copy(checkout = CheckoutState(CheckoutStep.NOT_COMPLETED, orderId = p.orderId), message = messageOf(e)) }
            }
        }
    }

    /** Polls again after "still confirming" or "not completed" (the user's refresh). */
    suspend fun refreshCheckout() {
        val s = _state.value.checkout
        if (s.store) {
            reportStore()
            return
        }
        if (s.step != CheckoutStep.STILL_CONFIRMING && s.step != CheckoutStep.NOT_COMPLETED) return
        val p = pending.load() ?: return
        _state.update { it.copy(checkout = CheckoutState(CheckoutStep.CONFIRMING, orderId = p.orderId)) }
        poll()
    }

    /** Leaves a finished (paid, failed) or abandoned checkout: back to the plans. Polling of an unpaid order stops. */
    fun closeCheckout() {
        val step = _state.value.checkout.step
        if (step == CheckoutStep.NOT_COMPLETED) {
            // The user gave up on this order: a retry starts a new attempt on it while it is payable.
            pending.clear()
        }
        _state.update { it.copy(checkout = CheckoutState()) }
    }

    /** After process death (or the screen opened again): a pending order is confirmed before anything else. */
    suspend fun resumePending() {
        if (_state.value.checkout.step != CheckoutStep.IDLE) return
        val p = pending.load() ?: return
        if (now() - p.startedAtMs > PENDING_MAX_AGE_MS) {
            pending.clear()
            return
        }
        _state.update { it.copy(checkout = CheckoutState(CheckoutStep.CONFIRMING, orderId = p.orderId)) }
        poll()
    }

    private suspend fun poll(schedule: LongArray = POLL_SCHEDULE_MS) {
        val p = pending.load() ?: return
        val orderId = UUID.fromString(p.orderId)
        for (wait in schedule) {
            if (wait > 0) pause(wait)
            if (_state.value.checkout.step != CheckoutStep.CONFIRMING) return // closed or replaced meanwhile
            val o = try {
                billing.order(orderId)
            } catch (e: ApiError) {
                if (e is ApiError.SignedOut) {
                    _state.update { it.copy(checkout = CheckoutState(), phase = PlansPhase.SIGNED_OUT) }
                    return
                }
                if (e.status == 404) {
                    pending.clear()
                    _state.update { it.copy(checkout = CheckoutState(), message = PlansMessage.SERVER) }
                    return
                }
                continue // offline or a server hiccup: keep trying within the window
            }
            order = o
            when (o.status) {
                "PAID" -> {
                    paid(o)
                    return
                }
                in FINAL_UNPAID -> {
                    pending.clear()
                    order = null
                    orderKey = null
                    _state.update { it.copy(checkout = CheckoutState(CheckoutStep.FAILED, orderId = o.orderId, finalStatus = o.status)) }
                    return
                }
            }
            // Still awaiting payment: a failed or cancelled attempt ends the wait (the order stays payable).
            val attemptId = p.attemptId
            if (attemptId != null && (p.returned || wait >= ATTEMPT_CHECK_FROM_MS)) {
                val a = try {
                    billing.attempt(orderId, UUID.fromString(attemptId))
                } catch (e: ApiError) {
                    null
                }
                if (a != null && a.status in FAILED_ATTEMPT) {
                    _state.update { it.copy(checkout = CheckoutState(CheckoutStep.NOT_COMPLETED, orderId = o.orderId)) }
                    return
                }
            }
        }
        val latest = pending.load() ?: p
        val gaveUp = !latest.returned || latest.result == "cancel" || latest.result == "error"
        _state.update {
            it.copy(checkout = CheckoutState(if (gaveUp) CheckoutStep.NOT_COMPLETED else CheckoutStep.STILL_CONFIRMING, orderId = p.orderId))
        }
    }

    private suspend fun paid(o: PaymentOrder) {
        pending.clear()
        order = null
        orderKey = null
        withContext(Dispatchers.IO) { runCatching { refresher.planChanged() } }
        val current = loadCurrent(buyer())
        val offer = _state.value.offers.firstOrNull { it.productCode == o.productCode && it.period == o.billingPeriod }
        val subEnd = current?.untilMs
        _state.update {
            it.copy(
                current = current ?: it.current,
                checkout = CheckoutState(
                    CheckoutStep.PAID,
                    orderId = o.orderId,
                    receipt = Receipt(o.orderReference, offer?.productName ?: current?.name, o.billingPeriod, o.total, null, o.paidAtMs, subEnd),
                ),
            )
        }
    }

    // ---------------------------------------------------------------- store checkout

    /** Buys the selected store offer (RevenueCat), then lets the server verify it. */
    suspend fun buyFromStore(host: PurchaseHost) {
        val s = _state.value
        if (s.phase != PlansPhase.READY || !s.storeFlow || s.checkout.step != CheckoutStep.IDLE) return
        val uid = userId()
        if (buyer() != BuyerKind.REGISTERED || uid == null) {
            _state.update { it.copy(message = PlansMessage.NEEDS_ACCOUNT) }
            return
        }
        val offer = s.storeOffers.firstOrNull { it.id == s.selectedOffer } ?: return
        _state.update { it.copy(checkout = CheckoutState(CheckoutStep.STARTING, store = true), message = null) }
        val outcome = try {
            store.purchase(host, uid, offer.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StorePurchaseOutcome.Failed("EXCEPTION")
        }
        when (outcome) {
            is StorePurchaseOutcome.Purchased -> {
                unreported = outcome
                storeReportKey = newKey()
                storeOfferShown = offer
                reportStore()
            }
            StorePurchaseOutcome.Pending ->
                _state.update { it.copy(checkout = CheckoutState(CheckoutStep.STILL_CONFIRMING, store = true)) }
            StorePurchaseOutcome.Cancelled -> _state.update { it.copy(checkout = CheckoutState()) }
            is StorePurchaseOutcome.Failed -> _state.update { it.copy(checkout = CheckoutState(), message = PlansMessage.STORE_FAILED) }
        }
    }

    private suspend fun reportStore() {
        _state.update { it.copy(checkout = CheckoutState(CheckoutStep.CONFIRMING, store = true)) }
        val purchase = unreported
        val confirmed = if (purchase != null) {
            try {
                val r = billing.reportStorePurchase(storeReportKey ?: newKey().also { storeReportKey = it }, purchase.productId, purchase.purchaseToken, purchase.packageName.ifEmpty { packageName })
                r.status in LIVE_STORE
            } catch (e: ApiError) {
                false
            }
        } else {
            false
        }
        // The server may also have learned of it from the store itself (RevenueCat webhook): the plan decides.
        withContext(Dispatchers.IO) { runCatching { refresher.planChanged() } }
        val current = loadCurrent(buyer())
        if (confirmed || (current != null && !current.isFree)) {
            unreported = null
            storeReportKey = null
            val offer = storeOfferShown
            _state.update {
                it.copy(
                    current = current ?: it.current,
                    checkout = CheckoutState(
                        CheckoutStep.PAID,
                        store = true,
                        receipt = Receipt(null, offer?.title ?: current?.name, offer?.period, null, offer?.price, now(), current?.untilMs),
                    ),
                )
            }
        } else {
            _state.update { it.copy(current = current ?: it.current, checkout = CheckoutState(CheckoutStep.STILL_CONFIRMING, store = true)) }
        }
    }

    /** The account went away (sign-out): nothing of the old buyer stays. */
    fun signedOut() {
        pending.clear()
        order = null
        orderKey = null
        attemptKey = null
        unreported = null
        store.signedOut()
        _state.value = PlansState(phase = PlansPhase.SIGNED_OUT)
    }

    private fun messageOf(e: ApiError): PlansMessage = when {
        e is ApiError.Network || e is ApiError.Timeout -> PlansMessage.OFFLINE
        e.code == "GUEST_NOT_ALLOWED" -> PlansMessage.NEEDS_ACCOUNT
        e.code == "BILLING_POLICY_DENIED" -> PlansMessage.MARKET_CHANGED
        e.code == "PAYMENT_METHOD_UNAVAILABLE" -> PlansMessage.METHOD_UNAVAILABLE
        e.code == "PROVIDER_UNAVAILABLE" -> PlansMessage.PROVIDER_UNAVAILABLE
        e.code == "PRODUCT_NOT_AVAILABLE" || e.code == "ORDER_EXPIRED" || e.code == "ORDER_NOT_PAYABLE" -> PlansMessage.PRODUCT_UNAVAILABLE
        else -> PlansMessage.SERVER
    }

    companion object {
        /**
         * Waits before each order read after a return (ms): quick at first (the server verifies on the return), then
         * slower; about three minutes in all before "still confirming".
         */
        val POLL_SCHEDULE_MS = longArrayOf(0, 1_500, 2_000, 3_000, 4_000, 5_000, 5_000, 8_000, 10_000, 10_000, 15_000, 15_000, 20_000, 20_000, 30_000, 30_000)

        /** After the user closed the checkout without a return: a shorter check (about 30 s) before "not completed". */
        val CLOSED_POLL_SCHEDULE_MS = longArrayOf(0, 1_500, 3_000, 5_000, 8_000, 12_000)

        /** Without a return, attempts are only read once the wait has grown (a fresh checkout reads as pending). */
        const val ATTEMPT_CHECK_FROM_MS = 5_000L

        /** An order this close to its expiry is not reused for a new attempt. */
        const val ORDER_MARGIN_MS = 2 * 60_000L

        /** A pending checkout older than this is forgotten (the reconciliation job settles it server side). */
        const val PENDING_MAX_AGE_MS = 48 * 3600_000L

        val PERIOD_ORDER = listOf("MONTHLY", "ANNUAL", "QUARTERLY", "WEEKLY", "ONE_TIME", "LIFETIME")
        val FINAL_UNPAID = setOf("FAILED", "CANCELLED", "EXPIRED", "REFUNDED", "PARTIALLY_REFUNDED")
        val FAILED_ATTEMPT = setOf("FAILED", "CANCELLED", "EXPIRED")
        val LIVE_SUBSCRIPTION = setOf("ACTIVE", "GRACE_PERIOD", "PENDING")
        val LIVE_STORE = setOf("ACTIVE", "IN_GRACE_PERIOD")
    }
}
