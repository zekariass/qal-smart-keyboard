package com.qalsmartkeyboard.app.billing

import android.app.Activity

/**
 * Global store billing (Google Play through RevenueCat, ADR 0047), behind a small port so the `direct` build carries no
 * store SDK: the `play` flavour's `StoreBillingFactory` wraps `:billing-play`, the `direct` one returns [Unavailable].
 * The app never unlocks anything from a store answer: a purchase is reported to the backend, which verifies it and
 * grants the plan; the paywall then reads the plan the server shows.
 */
interface StoreBilling {
    /** The SDK is in this build and configured (a public key was set at build time). */
    val available: Boolean

    /** The store products of the current offering for [appUserId] (the backend's user id), in display order. */
    suspend fun offers(appUserId: String): List<StoreOffer>

    /**
     * The packages of the store offering [offeringId] for [appUserId] (T8.10: the `credits` offering holding the one-time
     * credit packs), each with the store's own formatted price. Empty when the offering is not there.
     */
    suspend fun offering(appUserId: String, offeringId: String): List<StoreOffer> = emptyList()

    /**
     * Launches the store's purchase sheet for package [offerId] over [host]'s activity: of the current offering, or of
     * [offeringId] when given (credit packs).
     */
    suspend fun purchase(host: PurchaseHost, appUserId: String, offerId: String, offeringId: String? = null): StorePurchaseOutcome

    /** The user signed out: forget the store identity. */
    fun signedOut() {}

    /** No store billing in this build (the `direct` flavour, or no key). */
    object Unavailable : StoreBilling {
        override val available = false
        override suspend fun offers(appUserId: String): List<StoreOffer> = emptyList()
        override suspend fun purchase(host: PurchaseHost, appUserId: String, offerId: String, offeringId: String?): StorePurchaseOutcome =
            StorePurchaseOutcome.Failed("UNAVAILABLE")
    }
}

/** The activity a store purchase sheet is shown over. */
fun interface PurchaseHost {
    fun activity(): Activity
}

/** One store product the user can buy; [price] is the store's own formatted price (the store charges it). */
data class StoreOffer(
    /** The package identifier inside the offering. */
    val id: String,
    val productId: String,
    /** `MONTHLY`, `ANNUAL` or `OTHER`. */
    val period: String,
    val title: String,
    val price: String,
)

sealed interface StorePurchaseOutcome {
    /**
     * The store charged the user; [purchaseToken] goes to the backend only and is never logged or stored. [transactionId]
     * is the store's order id (Play GPA.…), reported with a credit pack so the server grants exactly that transaction.
     */
    data class Purchased(
        val productId: String,
        val purchaseToken: String,
        val packageName: String,
        val transactionId: String? = null,
    ) : StorePurchaseOutcome {
        override fun toString() = "Purchased(productId=$productId)"
    }

    /** The store accepted the purchase but the payment is still pending (e.g. cash at a shop). */
    data object Pending : StorePurchaseOutcome

    /** The store still holds an earlier purchase of this one-time product that has not been confirmed yet. */
    data object AlreadyOwned : StorePurchaseOutcome

    data object Cancelled : StorePurchaseOutcome

    /** [code] is a short error code (never a message from the SDK, which may carry account details). */
    data class Failed(val code: String) : StorePurchaseOutcome
}
