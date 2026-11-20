package com.qalsmartkeyboard.app.billing

import android.content.Context

/** Direct flavour: no store SDK in this APK (ADR 0047); the local gateways' hosted checkout needs none. */
object StoreBillingFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): StoreBilling = StoreBilling.Unavailable
}
