package com.qalsmartkeyboard.billing.play

import android.app.Activity
import android.content.Context
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.PackageType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.models.PurchaseState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One package of the current RevenueCat offering. [price] is Google Play's formatted price. */
data class RcOffer(val id: String, val productId: String, val period: String, val title: String, val price: String)

sealed interface RcOutcome {
    data class Purchased(val productId: String, val purchaseToken: String) : RcOutcome {
        override fun toString() = "Purchased(productId=$productId)"
    }
    data object Pending : RcOutcome
    data object Cancelled : RcOutcome
    data class Failed(val code: String) : RcOutcome
}

/**
 * RevenueCat for the `play` flavour (ADR 0047). Configured lazily, on the first paywall that offers store billing, with
 * the build's **public** Android key and the backend's user id as RevenueCat app user id (so RevenueCat's webhooks name
 * the same user the backend knows). Nothing here decides entitlements: the purchase token goes to the backend, which
 * verifies it.
 *
 * Privacy: the SDK logs at ERROR only; automatic device-identifier collection, in-app messages and diagnostics are off.
 */
class RevenueCatStore(private val context: Context, private val apiKey: String) {
    private val mutex = Mutex()

    /** A public key is set (`goog_...`). */
    val configured: Boolean get() = apiKey.isNotBlank()

    private suspend fun instance(appUserId: String): Purchases = mutex.withLock {
        check(configured) { "RevenueCat is not configured in this build" }
        if (!Purchases.isConfigured) {
            Purchases.logLevel = LogLevel.ERROR
            Purchases.configure(
                PurchasesConfiguration.Builder(context.applicationContext, apiKey)
                    .appUserID(appUserId)
                    .showInAppMessagesAutomatically(false)
                    .diagnosticsEnabled(false)
                    .automaticDeviceIdentifierCollectionEnabled(false)
                    .build(),
            )
        }
        val p = Purchases.sharedInstance
        if (p.appUserID != appUserId) p.awaitLogIn(appUserId)
        p
    }

    suspend fun offers(appUserId: String): List<RcOffer> {
        val offering = instance(appUserId).awaitOfferings().current ?: return emptyList()
        return offering.availablePackages.map { pkg ->
            RcOffer(
                id = pkg.identifier,
                productId = pkg.product.id,
                period = when (pkg.packageType) {
                    PackageType.MONTHLY -> "MONTHLY"
                    PackageType.ANNUAL -> "ANNUAL"
                    else -> "OTHER"
                },
                title = pkg.product.title,
                price = pkg.product.price.formatted,
            )
        }
    }

    suspend fun purchase(activity: Activity, appUserId: String, packageId: String): RcOutcome {
        val purchases = instance(appUserId)
        val pkg = purchases.awaitOfferings().current?.availablePackages?.firstOrNull { it.identifier == packageId }
            ?: return RcOutcome.Failed("PACKAGE_NOT_FOUND")
        return try {
            val result = purchases.awaitPurchase(PurchaseParams.Builder(activity, pkg).build())
            val tx = result.storeTransaction
            when (tx.purchaseState) {
                PurchaseState.PENDING -> RcOutcome.Pending
                else -> RcOutcome.Purchased(tx.productIds.firstOrNull() ?: pkg.product.id, tx.purchaseToken)
            }
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) RcOutcome.Cancelled else outcomeOf(e)
        } catch (e: PurchasesException) {
            outcomeOf(e)
        }
    }

    private fun outcomeOf(e: PurchasesException): RcOutcome =
        if (e.code.name == "PaymentPendingError") RcOutcome.Pending else RcOutcome.Failed(e.code.name)

    /** The user signed out: RevenueCat forgets the identity (it would otherwise keep it until the next log-in). */
    fun logOut() {
        if (configured && Purchases.isConfigured && !Purchases.sharedInstance.isAnonymous) Purchases.sharedInstance.logOut()
    }
}
