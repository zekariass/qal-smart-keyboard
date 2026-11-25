package com.qalsmartkeyboard.app.billing

import android.content.Context
import com.qalsmartkeyboard.app.BuildConfig
import com.qalsmartkeyboard.billing.play.RcOutcome
import com.qalsmartkeyboard.billing.play.RevenueCatStore

/** Play flavour: RevenueCat when the build has a public key (ADR 0047), else no store billing. */
object StoreBillingFactory {
    fun create(context: Context): StoreBilling {
        val key = BuildConfig.REVENUECAT_ANDROID_KEY
        return if (key.isBlank()) StoreBilling.Unavailable else RevenueCatStoreBilling(RevenueCatStore(context, key), context.packageName)
    }
}

/** Adapts `:billing-play`'s [RevenueCatStore] to the app's [StoreBilling] port. */
class RevenueCatStoreBilling(private val rc: RevenueCatStore, private val packageName: String) : StoreBilling {
    override val available: Boolean get() = rc.configured

    override suspend fun offers(appUserId: String): List<StoreOffer> =
        rc.offers(appUserId).map { StoreOffer(it.id, it.productId, it.period, it.title, it.price) }

    override suspend fun offering(appUserId: String, offeringId: String): List<StoreOffer> =
        rc.offering(appUserId, offeringId).map { StoreOffer(it.id, it.productId, it.period, it.title, it.price) }

    override suspend fun purchase(host: PurchaseHost, appUserId: String, offerId: String, offeringId: String?): StorePurchaseOutcome =
        when (val r = rc.purchase(host.activity(), appUserId, offerId, offeringId)) {
            is RcOutcome.Purchased -> StorePurchaseOutcome.Purchased(r.productId, r.purchaseToken, packageName, r.transactionId)
            RcOutcome.Pending -> StorePurchaseOutcome.Pending
            RcOutcome.AlreadyOwned -> StorePurchaseOutcome.AlreadyOwned
            RcOutcome.Cancelled -> StorePurchaseOutcome.Cancelled
            is RcOutcome.Failed -> StorePurchaseOutcome.Failed(r.code)
        }

    override fun signedOut() = rc.logOut()
}
