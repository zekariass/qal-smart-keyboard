package com.qalsmartkeyboard.app.billing

import android.content.Context
import com.qalsmartkeyboard.aiclient.SessionAccessInfo
import com.qalsmartkeyboard.api.billing.BillingClient
import com.qalsmartkeyboard.app.ai.AiGraph
import com.qalsmartkeyboard.app.auth.AccountStatus
import com.qalsmartkeyboard.app.auth.AuthGraph
import java.io.File
import java.util.Locale

/**
 * Process-wide wiring of the paywall (ADR 0047): the companion app's API client (with the flavour's channel and the
 * country hints, see [AuthGraph]), the pending-checkout file, the flavour's store billing and the keyboard's
 * entitlement refresh.
 */
class BillingGraph private constructor(context: Context) {
    private val app = context.applicationContext
    private val auth = AuthGraph.get(app)
    private val sessionInfo = SessionAccessInfo(auth.session)

    val client = BillingClient(auth.clients)

    val pending: PendingCheckoutStore = FilePendingCheckoutStore(File(app.noBackupFilesDir, "billing/pending-checkout.json"))

    /** RevenueCat on the `play` flavour with a key; [StoreBilling.Unavailable] otherwise. Built on first use. */
    val store: StoreBilling by lazy { StoreBillingFactory.create(app) }

    /** The keyboard's entitlement token and plan hints, fetched again past the client throttles (blocking). */
    val refresher = PlanRefresher { AiGraph.get(app).entitlements.planChanged() }

    val session get() = auth.session

    fun buyer(): BuyerKind = when (auth.repository.currentStatus()) {
        is AccountStatus.SignedOut -> BuyerKind.SIGNED_OUT
        AccountStatus.Guest -> BuyerKind.GUEST
        AccountStatus.Registered -> BuyerKind.REGISTERED
    }

    /** The backend user id (`sub` of the access token): the store's app user id. Never logged. */
    fun userId(): String? = sessionInfo.subject()

    fun newModel(): PlansModel = PlansModel(
        billing = client,
        buyer = ::buyer,
        userId = ::userId,
        pending = pending,
        refresher = refresher,
        store = store,
        locale = { Locale.getDefault().toLanguageTag() },
        packageName = app.packageName,
    )

    companion object {
        @Volatile private var instance: BillingGraph? = null

        fun get(context: Context): BillingGraph =
            instance ?: synchronized(this) { instance ?: BillingGraph(context.applicationContext).also { instance = it } }
    }
}
