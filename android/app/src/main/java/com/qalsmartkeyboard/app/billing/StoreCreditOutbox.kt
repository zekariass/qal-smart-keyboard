package com.qalsmartkeyboard.app.billing

import java.util.UUID

/**
 * A credit-pack purchase the store charged but the backend has not confirmed yet (T8.10). [reportKey] is the
 * idempotency key of its report; the server is idempotent on the store transaction id as well.
 */
class UnreportedCredit(
    val packCode: String,
    val purchase: StorePurchaseOutcome.Purchased,
    val reportKey: UUID,
    /** The account that bought it: another account never reports it. */
    val userId: String,
) {
    override fun toString() = "UnreportedCredit(packCode=$packCode)"
}

/**
 * The credit-pack purchases to report again on the next Plans open. Memory only and process wide (the purchase token is
 * never written to disk): when the process dies first, RevenueCat's webhook still lets the server grant the credits.
 */
class StoreCreditOutbox {
    private val items = ArrayList<UnreportedCredit>()

    @Synchronized fun add(item: UnreportedCredit) {
        items += item
    }

    @Synchronized fun remove(item: UnreportedCredit) {
        items.remove(item)
    }

    @Synchronized fun all(): List<UnreportedCredit> = items.toList()

    @Synchronized fun clear() = items.clear()
}
