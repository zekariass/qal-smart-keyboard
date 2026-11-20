package com.qalsmartkeyboard.app.billing

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** What the checkout WebView does with a navigation. */
sealed interface CheckoutNavigation {
    /**
     * The provider sent the payer back: close the checkout and confirm the order. [result] is `success`, `cancel`,
     * `error` or null, UX only (never proof of payment). [viaServer] is true for the app's own return URI, which the
     * server only redirects to after it saw the return.
     */
    data class Return(val result: String?, val viaServer: Boolean) : CheckoutNavigation

    /** An https page: the WebView loads it. */
    data object Load : CheckoutNavigation

    /** Another app should handle it (a wallet or bank app link, `intent:`, `tel:`, `mailto:`, `market:`). */
    data class External(val url: String) : CheckoutNavigation

    /** Never followed (`javascript:`, `file:`, `content:`, `data:`, plain `http:`, ...). */
    data object Block : CheckoutNavigation
}

/**
 * The URL policy of the hosted-checkout WebView (ADR 0047), pure so it is a JVM test:
 *
 * 1. The return URL `…/api/v1/billing/return/{attemptId}` (any host: the server's public origin may differ from the API
 *    host the app uses) and the app's return URI `qalkeyboard://billing/return?…` close the checkout. The app then
 *    signals the return itself to its own API host and polls the order; the WebView never loads the return URL.
 * 2. `https:` loads in the WebView. Plain `http:` never does (TLS only, ADR 0016).
 * 3. `intent:` and other app schemes (telebirr, bank apps, `tel:`, `mailto:`, `market:`) go to another app through a
 *    sanitised intent; the Android side refuses any target in this app.
 * 4. Everything that could read local data or run script in the page's origin is blocked.
 */
object CheckoutUrlPolicy {
    const val APP_RETURN_SCHEME = "qalkeyboard"
    private val RETURN_PATH = Regex("""^(?:/[^?#]*)?/api/v1/billing/return/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/?$""")
    private val SCHEME = Regex("^[a-z][a-z0-9+.-]{0,31}$")
    private val LEADING_SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val RESULTS = setOf("success", "cancel", "error")

    /** Schemes that are never followed, in the WebView or outside it. */
    private val BLOCKED = setOf(
        "javascript", "file", "content", "data", "blob", "about", "chrome", "chrome-extension", "view-source", "filesystem",
        "jar", "ftp", "ws", "wss", "android-app", "android-resource", "res", "asset", "http",
    )

    fun decide(url: String): CheckoutNavigation {
        val trimmed = url.trim()
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            // App links are not always strict RFC 3986 (an intent: URI with an unencoded character): decide on the
            // scheme alone, and only ever hand such a link to another app.
            val scheme = LEADING_SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: return CheckoutNavigation.Block
            return if (scheme in BLOCKED || scheme == "https" || scheme == APP_RETURN_SCHEME || !SCHEME.matches(scheme)) {
                CheckoutNavigation.Block
            } else {
                CheckoutNavigation.External(trimmed)
            }
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return CheckoutNavigation.Block
        if (scheme == APP_RETURN_SCHEME) {
            return if (uri.host.equals("billing", ignoreCase = true) && uri.path == "/return") {
                CheckoutNavigation.Return(result(uri.rawQuery), viaServer = true)
            } else {
                CheckoutNavigation.Block
            }
        }
        if ((scheme == "https" || scheme == "http") && uri.rawPath != null && RETURN_PATH.matches(uri.rawPath)) {
            return CheckoutNavigation.Return(result(uri.rawQuery), viaServer = false)
        }
        return when {
            scheme == "https" -> if (uri.host.isNullOrBlank()) CheckoutNavigation.Block else CheckoutNavigation.Load
            scheme in BLOCKED -> CheckoutNavigation.Block
            !SCHEME.matches(scheme) -> CheckoutNavigation.Block
            else -> CheckoutNavigation.External(trimmed)
        }
    }

    /** The first URL the checkout may open: the server's `checkoutUrl` must be an https page with a host. */
    fun isAllowedStart(url: String?): Boolean = url != null && decide(url) == CheckoutNavigation.Load

    private fun result(rawQuery: String?): String? =
        rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
            val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrNull(1) }
            if (k == "result") v else null
        }?.takeIf { it in RESULTS }
}
