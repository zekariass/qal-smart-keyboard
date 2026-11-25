package com.qalsmartkeyboard.app.billing

import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.billing.BillingClient
import com.qalsmartkeyboard.api.billing.BillingOptions
import com.qalsmartkeyboard.api.billing.CreditSummary
import com.qalsmartkeyboard.api.billing.CreditTransaction
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
import kotlin.math.floor

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

/** One prepaid AI credit pack the market sells (T8.10): exactly the server's product, credits and money. */
data class CreditPack(
    val productCode: String,
    val name: String,
    val description: String?,
    val credits: Long,
    val validityDays: Int?,
    val price: Money,
    /** The market's formatted price; in a store market the **store's** formatted price (null: not known, a guest). */
    val displayPrice: String?,
    /** Store billing: the store (Play / RevenueCat) product id the server maps this pack to. */
    val storeProductId: String? = null,
    /** Store billing: the RevenueCat offering holding the pack's package (`credits`). */
    val storeOfferingId: String? = null,
    /** Store billing: the RevenueCat package identifier the app resolved in that offering (the store returned it). */
    val storePackageId: String? = null,
) {
    val key: String get() = "$productCode/$PERIOD"

    companion object {
        const val PERIOD = "ONE_TIME"
    }
}

/**
 * The credit balance shown on the paywall, rounded down to whole credits (the server's exact balance has 6 dp).
 * [expiringUnits] of it expire on [nextExpiryMs] (the soonest lot's date), when any lot expires.
 */
data class CreditsInfo(val balance: Long, val nextExpiryMs: Long?, val expiringUnits: Long) {
    companion object {
        fun of(s: CreditSummary): CreditsInfo {
            val next = s.nextExpiryAtMs ?: s.lots.mapNotNull { it.expiresAtMs }.minOrNull()
            val expiring = if (next == null) 0.0 else s.lots.filter { it.expiresAtMs == next }.sumOf { it.remainingUnits }
            val balance = floor(s.balanceUnits + 1e-9).toLong()
            return CreditsInfo(balance, next, floor(expiring + 1e-9).toLong().coerceAtMost(balance))
        }
    }
}

/** The "Credit history" page: ledger rows newest first, paged by cursor. */
data class CreditHistoryState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val items: List<CreditTransaction> = emptyList(),
    val nextCursor: String? = null,
    /** The last load failed (offline or server); [items] keep what was loaded. */
    val error: PlansMessage? = null,
)

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

    /** The provider's page is open in the browser (Custom Tab). */
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
    /** A credit-pack receipt (T8.10): the credits added; [validUntilMs] is then when they expire. */
    val credits: Long? = null,
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
    /** The store purchase is a credit pack (T8.10), not a plan. */
    val credits: Boolean = false,
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
    /** The credit packs this market sells (empty: no credits section); in the store flow only those the store returned. */
    val packs: List<CreditPack> = emptyList(),
    /** The balance, or null while unknown (not loaded, or the call failed). */
    val credits: CreditsInfo? = null,
    /** The pack whose checkout is starting (its button shows the spinner). */
    val buyingPack: String? = null,
    val history: CreditHistoryState = CreditHistoryState(),
    val selectedOffer: String? = null,
    val selectedMethod: String? = null,
    val checkout: CheckoutState = CheckoutState(),
    val message: PlansMessage? = null,
    /** A server notice to show as is (`BillingOptions.notice`, already localised by the server). */
    val notice: String? = null,
) {
    val selected: PlanOffer? get() = offers.firstOrNull { it.key == selectedOffer }

    /**
     * Only AI credits are for sale (no plan chooser): a gateway market that lists credit packs but no plans, or a
     * signed-in store buyer whose current store offering has no plans.
     */
    val creditsOnly: Boolean
        get() = phase == PlansPhase.READY && packs.isNotEmpty() &&
            if (storeFlow) buyer == BuyerKind.REGISTERED && storeOffers.isEmpty() else offers.isEmpty()
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
 * method and opens its `checkoutUrl` in a Custom Tab. The app coming back to the front ([onReturned]) or the
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
    /** Credit-pack purchases the store charged but the backend has not confirmed (shared across screens, memory only). */
    private val outbox: StoreCreditOutbox = StoreCreditOutbox(),
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

    /** The credit-pack purchase being confirmed (store flow), and the lots the account held before it. */
    private class CreditAttempt(val packCode: String, val baseline: Set<String>?, val unreported: UnreportedCredit?)
    private var attempt: CreditAttempt? = null

    /** The ids of the credit lots the last balance read showed. */
    private var knownLots: Set<String>? = null

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
        val credits = loadCredits()
        if (!opts.purchaseUiAllowed || opts.paymentMethods.isEmpty() || !(opts.gatewayFlow || opts.storeFlow)) {
            // A server notice on a closed market is the "no store in this build" answer (the direct APK in a store market).
            val reason = if (opts.notice != null && !store.available) UnavailableReason.STORE_NOT_IN_BUILD else UnavailableReason.MARKET
            _state.update { it.copy(phase = PlansPhase.NOT_AVAILABLE, unavailable = reason, current = current, credits = credits, offers = emptyList(), packs = emptyList(), methods = emptyList(), storeOffers = emptyList(), notice = opts.notice) }
            resumePending()
            return
        }
        if (opts.storeFlow) {
            loadStore(who, current, opts, credits)
        } else {
            val offers = offersOf(opts)
            val packs = packsOf(opts)
            if (offers.isEmpty() && packs.isEmpty()) {
                _state.update { it.copy(phase = PlansPhase.NOT_AVAILABLE, unavailable = UnavailableReason.MARKET, current = current, credits = credits, packs = emptyList(), notice = opts.notice) }
            } else {
                val methods = opts.paymentMethods.sortedWith(compareByDescending<PaymentMethod> { it.isDefault }.thenBy { it.priority ?: Int.MAX_VALUE })
                _state.update { s ->
                    s.copy(
                        phase = PlansPhase.READY,
                        unavailable = null,
                        storeFlow = false,
                        current = current,
                        credits = credits,
                        offers = offers,
                        packs = packs,
                        methods = methods,
                        selectedOffer = s.selectedOffer?.takeIf { k -> offers.any { it.key == k } } ?: offers.takeIf { it.isNotEmpty() }?.let(::defaultOffer),
                        selectedMethod = s.selectedMethod?.takeIf { c -> methods.any { it.methodCode == c } } ?: methods.first().methodCode,
                        notice = opts.notice,
                    )
                }
            }
        }
        resumePending()
        resumeCredits()
    }

    private suspend fun loadStore(who: BuyerKind, current: CurrentPlanInfo?, opts: BillingOptions, credits: CreditsInfo?) {
        val storeOffered = opts.paymentMethods.any { it.methodType == "APP_STORE" || it.providerCode == "REVENUECAT" }
        if (!storeOffered || !store.available) {
            _state.update {
                it.copy(phase = PlansPhase.NOT_AVAILABLE, unavailable = if (storeOffered) UnavailableReason.STORE_NOT_IN_BUILD else UnavailableReason.MARKET, current = current, credits = credits, packs = emptyList(), notice = opts.notice)
            }
            return
        }
        val uid = userId()
        val registered = who == BuyerKind.REGISTERED && uid != null
        // Store plans come from the RevenueCat offering; the server lists only the credit packs of a store market.
        val offers = if (registered) guarded { store.offers(uid!!) } ?: emptyList() else emptyList()
        val serverPacks = packsOf(opts).filter { it.storeProductId != null }
        // Never the server's placeholder price: a pack shows the store's price, and is hidden when the store has none.
        val packs = if (registered) storePacksOf(uid!!, serverPacks) else serverPacks.map { it.copy(displayPrice = null) }
        _state.update { s ->
            s.copy(
                phase = if (registered && offers.isEmpty() && packs.isEmpty()) PlansPhase.ERROR else PlansPhase.READY,
                storeFlow = true,
                unavailable = null,
                current = current,
                credits = credits,
                offers = emptyList(),
                packs = packs,
                methods = emptyList(),
                storeOffers = offers,
                selectedOffer = s.selectedOffer?.takeIf { k -> offers.any { it.id == k } } ?: offers.firstOrNull()?.id,
                notice = opts.notice,
            )
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The server's store packs the store actually sells (matched by product id), with the store's formatted price. */
    private suspend fun storePacksOf(uid: String, serverPacks: List<CreditPack>): List<CreditPack> {
        val offerings = serverPacks.map { it.storeOfferingId ?: DEFAULT_CREDITS_OFFERING }.distinct().associateWith { id ->
            guarded { store.offering(uid, id) } ?: emptyList()
        }
        return serverPacks.mapNotNull { p ->
            val offeringId = p.storeOfferingId ?: DEFAULT_CREDITS_OFFERING
            val list = offerings[offeringId].orEmpty()
            val match = list.firstOrNull { it.productId == p.storeProductId && (p.storePackageId == null || it.id == p.storePackageId) }
                ?: list.firstOrNull { it.productId == p.storeProductId }
            match?.let { p.copy(displayPrice = it.price, storeOfferingId = offeringId, storePackageId = it.id) }
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

    /** The balance (guests have one too), or the last known one when the call fails. */
    private suspend fun loadCredits(): CreditsInfo? = try {
        billing.credits().also { s -> knownLots = s.lots.map { it.lotId }.toSet() }.let { CreditsInfo.of(it) }
    } catch (e: ApiError) {
        _state.value.credits
    }

    private fun packsOf(o: BillingOptions): List<CreditPack> =
        o.products.filter { it.productType == "AI_CREDIT_PACK" }.mapNotNull { p ->
            val price = p.prices.firstOrNull { it.billingPeriod == CreditPack.PERIOD } ?: return@mapNotNull null
            val credits = p.creditUnits ?: return@mapNotNull null
            CreditPack(
                p.productCode, p.displayName, p.description, credits, p.creditValidityDays, price.listPrice, price.displayPrice,
                price.storeProductId, price.storeOfferingId, price.storePackageId,
            )
        }.sortedBy { it.credits }

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
        val offer = _state.value.selected ?: return
        begin(offer.key, offer.productCode, offer.period, "MANUAL_RENEW", null)
    }

    /**
     * Starts the hosted checkout of the credit pack [productCode] with the selected payment method (T8.10): one-time, no
     * renewal preference. The same order, attempt, Custom Tab and polling as a plan; a guest is asked to sign in.
     */
    suspend fun buyPack(productCode: String) {
        val pack = _state.value.packs.firstOrNull { it.productCode == productCode } ?: return
        begin(pack.key, pack.productCode, CreditPack.PERIOD, null, pack.productCode)
    }

    private suspend fun begin(key: String, productCode: String, period: String, renewal: String?, pack: String?) {
        val s = _state.value
        if (s.phase != PlansPhase.READY || s.storeFlow || s.checkout.step != CheckoutStep.IDLE) return
        if (buyer() != BuyerKind.REGISTERED) {
            _state.update { it.copy(message = PlansMessage.NEEDS_ACCOUNT) }
            return
        }
        val method = s.selectedMethod ?: return
        val market = options?.marketCountry ?: return
        _state.update { it.copy(checkout = CheckoutState(CheckoutStep.STARTING), buyingPack = pack, message = null) }
        try {
            val o = openOrder(key, productCode, period, renewal, market)
            startAttempt(o.orderId, method, productCode, period)
        } catch (e: ApiError) {
            onStartFailed(e)
        }
    }

    private suspend fun openOrder(key: String, productCode: String, period: String, renewal: String?, market: String): PaymentOrder {
        order?.let { o ->
            val fresh = o.status == "AWAITING_PAYMENT" && (o.expiresAtMs ?: Long.MAX_VALUE) > now() + ORDER_MARGIN_MS
            if (fresh && o.productCode == productCode && o.billingPeriod == period) return o
        }
        val idem = orderKey?.takeIf { it.first == key }?.second ?: newKey().also { orderKey = key to it }
        val o = billing.createOrder(idem, productCode, period, market, renewal)
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

    /** The payer came back from the hosted checkout (deep link, closed tab or "I've paid"): confirm with the server. */
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
        if (s.store && s.credits) {
            refreshCredits()
            return
        }
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
        val pack = _state.value.packs.firstOrNull { it.productCode == o.productCode }
        if (pack != null || o.creditLotId != null) {
            paidCredits(o, pack)
            return
        }
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

    /** A verified credit-pack order: the balance is read again (the server granted one lot) and the receipt shows the credits. */
    private suspend fun paidCredits(o: PaymentOrder, pack: CreditPack?) {
        val summary = try {
            billing.credits()
        } catch (e: ApiError) {
            null
        }
        val info = summary?.let { CreditsInfo.of(it) } ?: _state.value.credits
        val lot = summary?.lots?.firstOrNull { it.lotId == o.creditLotId }
        val validUntil = lot?.expiresAtMs ?: pack?.validityDays?.let { (o.paidAtMs ?: now()) + it * DAY_MS }
        _state.update {
            it.copy(
                credits = info,
                buyingPack = null,
                checkout = CheckoutState(
                    CheckoutStep.PAID,
                    orderId = o.orderId,
                    receipt = Receipt(o.orderReference, pack?.name, o.billingPeriod, o.total, null, o.paidAtMs, validUntil, credits = pack?.credits ?: lot?.originalUnits?.toLong()),
                ),
            )
        }
    }

    // ---------------------------------------------------------------- credit history

    /** Opens the "Credit history" page and loads its first page. */
    suspend fun openHistory() {
        _state.update { it.copy(history = CreditHistoryState(open = true, loading = true)) }
        loadHistoryPage(null)
    }

    /** The next page, when there is one and none is loading. */
    suspend fun loadMoreHistory() {
        val h = _state.value.history
        if (!h.open || h.loading) return
        if (h.nextCursor == null && h.error == null && h.items.isNotEmpty()) return
        _state.update { it.copy(history = h.copy(loading = true, error = null)) }
        loadHistoryPage(h.nextCursor)
    }

    fun closeHistory() = _state.update { it.copy(history = CreditHistoryState()) }

    private suspend fun loadHistoryPage(cursor: String?) {
        try {
            val page = billing.creditTransactions(cursor)
            _state.update { s ->
                if (!s.history.open) s
                else s.copy(history = s.history.copy(loading = false, error = null, items = if (cursor == null) page.items else s.history.items + page.items, nextCursor = page.nextCursor))
            }
        } catch (e: ApiError) {
            _state.update { s -> if (!s.history.open) s else s.copy(history = s.history.copy(loading = false, error = messageOf(e))) }
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
            StorePurchaseOutcome.AlreadyOwned, is StorePurchaseOutcome.Failed ->
                _state.update { it.copy(checkout = CheckoutState(), message = PlansMessage.STORE_FAILED) }
        }
    }

    // ---------------------------------------------------------------- store credit packs (T8.10)

    /**
     * Buys the credit pack [productCode] through the store (RevenueCat, `play` flavour only): the store sheet, then the
     * report of the store transaction to the backend, which verifies it with RevenueCat and grants one credit lot. Only
     * the server's answer (or the balance it then shows) adds credits. A report that fails or comes back pending is
     * repeated within a bounded window ([CREDIT_SETTLE_MS]) and again on the next Plans open ([outbox]); RevenueCat's
     * webhook grants it on its own as a safety net.
     */
    suspend fun buyPackFromStore(host: PurchaseHost, productCode: String) {
        val s = _state.value
        if (s.phase != PlansPhase.READY || !s.storeFlow || s.checkout.step != CheckoutStep.IDLE) return
        val uid = userId()
        if (buyer() != BuyerKind.REGISTERED || uid == null) {
            _state.update { it.copy(message = PlansMessage.NEEDS_ACCOUNT) }
            return
        }
        val pack = s.packs.firstOrNull { it.productCode == productCode } ?: return
        val packageId = pack.storePackageId ?: return
        _state.update { it.copy(checkout = CheckoutState(CheckoutStep.STARTING, store = true, credits = true), buyingPack = productCode, message = null) }
        // The lots held before the purchase: a lot that appears afterwards (the webhook granted it) is this purchase's.
        val baseline = try {
            billing.credits().lots.map { it.lotId }.toSet()
        } catch (e: ApiError) {
            knownLots
        }
        val outcome = try {
            store.purchase(host, uid, packageId, pack.storeOfferingId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StorePurchaseOutcome.Failed("EXCEPTION")
        }
        when (outcome) {
            is StorePurchaseOutcome.Purchased -> {
                val item = UnreportedCredit(productCode, outcome, newKey(), uid)
                outbox.add(item)
                attempt = CreditAttempt(productCode, baseline, item)
                settleCredits(CREDIT_SETTLE_MS)
            }
            // Play is still processing it, or an earlier purchase of the pack was not confirmed: it lands by itself.
            StorePurchaseOutcome.Pending, StorePurchaseOutcome.AlreadyOwned -> {
                attempt = CreditAttempt(productCode, baseline, null)
                _state.update { it.copy(buyingPack = null, checkout = CheckoutState(CheckoutStep.STILL_CONFIRMING, store = true, credits = true)) }
            }
            StorePurchaseOutcome.Cancelled -> _state.update { it.copy(buyingPack = null, checkout = CheckoutState()) }
            is StorePurchaseOutcome.Failed -> _state.update { it.copy(buyingPack = null, checkout = CheckoutState(), message = PlansMessage.STORE_FAILED) }
        }
    }

    /**
     * Reports the attempt's purchase and reads the balance on each step of [schedule] until the server grants the
     * credits (its answer carries the lot, or a new lot shows in the balance), then shows the receipt; when the window
     * ends the purchase stays "still confirming" (and in the [outbox]).
     */
    private suspend fun settleCredits(schedule: LongArray) {
        val a = attempt ?: return
        _state.update { it.copy(checkout = CheckoutState(CheckoutStep.CONFIRMING, store = true, credits = true)) }
        var failure: PlansMessage? = null
        for (wait in schedule) {
            if (wait > 0) pause(wait)
            if (_state.value.checkout.step != CheckoutStep.CONFIRMING) return // left meanwhile
            val item = a.unreported
            if (item != null) {
                try {
                    val p = item.purchase
                    val r = billing.reportStorePurchase(item.reportKey, p.productId, p.purchaseToken, p.packageName.ifEmpty { packageName }, p.transactionId)
                    failure = null
                    if (r.creditLotId != null) {
                        grantedCredits(a, r.creditLotId, r.creditUnits, r.creditsExpireAtMs, null)
                        return
                    }
                    if (r.status in DEAD_STORE) {
                        outbox.remove(item)
                        attempt = null
                        _state.update { it.copy(buyingPack = null, checkout = CheckoutState(), message = PlansMessage.STORE_FAILED) }
                        return
                    }
                } catch (e: ApiError) {
                    when {
                        e is ApiError.SignedOut -> {
                            _state.update { it.copy(checkout = CheckoutState(), phase = PlansPhase.SIGNED_OUT) }
                            return
                        }
                        e.code == "GUEST_NOT_ALLOWED" -> {
                            _state.update { it.copy(buyingPack = null, checkout = CheckoutState(), message = PlansMessage.NEEDS_ACCOUNT) }
                            return
                        }
                        e is ApiError.Network || e is ApiError.Timeout -> failure = PlansMessage.OFFLINE
                        // The server keeps a verified purchase it may not grant yet (market not open, RevenueCat not caught up).
                        e.code in KEPT_BY_SERVER -> failure = null
                        else -> failure = PlansMessage.SERVER
                    }
                }
            }
            val base = a.baseline
            if (base != null) {
                val summary = try {
                    billing.credits()
                } catch (e: ApiError) {
                    null
                }
                val lot = summary?.lots?.firstOrNull { it.lotId !in base }
                if (lot != null) {
                    grantedCredits(a, lot.lotId, null, lot.expiresAtMs, summary)
                    return
                }
            }
        }
        _state.update { it.copy(buyingPack = null, checkout = CheckoutState(CheckoutStep.STILL_CONFIRMING, store = true, credits = true), message = failure) }
    }

    private suspend fun grantedCredits(a: CreditAttempt, lotId: String?, units: Long?, expiresMs: Long?, known: CreditSummary?) {
        a.unreported?.let { outbox.remove(it) }
        attempt = null
        withContext(Dispatchers.IO) { runCatching { refresher.planChanged() } }
        val summary = known ?: try {
            billing.credits()
        } catch (e: ApiError) {
            null
        }
        summary?.let { s -> knownLots = s.lots.map { it.lotId }.toSet() }
        val lot = summary?.lots?.firstOrNull { it.lotId == lotId }
        val pack = _state.value.packs.firstOrNull { it.productCode == a.packCode }
        val credits = units ?: pack?.credits ?: lot?.originalUnits?.toLong()
        val validUntil = expiresMs ?: lot?.expiresAtMs ?: pack?.validityDays?.let { now() + it * DAY_MS }
        val info = summary?.let { CreditsInfo.of(it) } ?: _state.value.credits
        _state.update {
            it.copy(
                credits = info,
                buyingPack = null,
                message = null,
                checkout = CheckoutState(
                    CheckoutStep.PAID,
                    store = true,
                    credits = true,
                    receipt = Receipt(null, pack?.name, CreditPack.PERIOD, null, pack?.displayPrice, now(), validUntil, credits = credits),
                ),
            )
        }
    }

    /** The "Refresh" of a still-confirming credit purchase: one more report (when it was not accepted yet) and balance read. */
    private suspend fun refreshCredits() {
        val a = attempt ?: outbox.all().firstOrNull { it.userId == userId() }?.let { CreditAttempt(it.packCode, null, it) }?.also { attempt = it }
        if (a == null) {
            // Nothing of ours to confirm (e.g. the store was still processing it): read the balance again.
            _state.update { it.copy(checkout = CheckoutState()) }
            load()
            return
        }
        settleCredits(longArrayOf(0))
    }

    /** The plans screen opened again: credit purchases the backend has not confirmed yet are reported once more. */
    private suspend fun resumeCredits() {
        val s = _state.value
        if (!s.storeFlow || s.phase != PlansPhase.READY || s.buyer != BuyerKind.REGISTERED || s.checkout.step != CheckoutStep.IDLE) return
        val uid = userId()
        for (item in outbox.all().filter { it.userId == uid }) {
            attempt = CreditAttempt(item.packCode, null, item)
            settleCredits(longArrayOf(0))
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
        attempt = null
        outbox.clear()
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
        const val DAY_MS = 24 * 3600_000L

        const val ORDER_MARGIN_MS = 2 * 60_000L

        /** A pending checkout older than this is forgotten (the reconciliation job settles it server side). */
        const val PENDING_MAX_AGE_MS = 48 * 3600_000L

        val PERIOD_ORDER = listOf("MONTHLY", "ANNUAL", "QUARTERLY", "WEEKLY", "ONE_TIME", "LIFETIME")
        val FINAL_UNPAID = setOf("FAILED", "CANCELLED", "EXPIRED", "REFUNDED", "PARTIALLY_REFUNDED")
        val FAILED_ATTEMPT = setOf("FAILED", "CANCELLED", "EXPIRED")
        val LIVE_SUBSCRIPTION = setOf("ACTIVE", "GRACE_PERIOD", "PENDING")
        val LIVE_STORE = setOf("ACTIVE", "IN_GRACE_PERIOD")

        /** The RevenueCat offering that holds the credit packs, when the server names none. */
        const val DEFAULT_CREDITS_OFFERING = "credits"

        /**
         * Waits before each report and balance read after a credit-pack purchase (ms): about 50 s in all, then the
         * purchase stays "still confirming" and is reported again on the next open.
         */
        val CREDIT_SETTLE_MS = longArrayOf(0, 3_000, 5_000, 8_000, 12_000, 20_000)

        /** A store purchase that ended without a grant (refunded, revoked, expired before it was granted). */
        val DEAD_STORE = setOf("REFUNDED", "REVOKED", "EXPIRED", "CANCELLED")

        /** Answers meaning "verified, kept, granted later": the market is not open yet, or the store has not listed it yet. */
        val KEPT_BY_SERVER = setOf("BILLING_POLICY_DENIED", "PRODUCT_NOT_AVAILABLE", "STORE_PURCHASE_INVALID")
    }
}
