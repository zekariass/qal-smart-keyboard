package com.qalsmartkeyboard.app.billing

import java.net.URI
import java.util.Locale

/**
 * The start-URL policy of the hosted checkout (ADR 0047, ADR 0016), pure so it is a JVM test: the server's
 * `checkoutUrl` must be an `https:` page with a host before it is opened in the browser. Plain `http:` and every other
 * scheme never opens.
 */
object CheckoutUrlPolicy {
    /** The scheme of the app's return URI `qalkeyboard://billing/return` ([BillingReturnActivity]). */
    const val APP_RETURN_SCHEME = "qalkeyboard"

    fun isAllowedStart(url: String?): Boolean {
        if (url == null) return false
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return false
        }
        return uri.scheme?.lowercase(Locale.ROOT) == "https" && !uri.host.isNullOrBlank()
    }
}
