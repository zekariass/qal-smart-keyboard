package com.qalsmartkeyboard.app.billing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URISyntaxException
import java.util.Locale

/**
 * Hands a checkout page's app link (a telebirr or bank app, `intent:`, `tel:`, `mailto:`, `market:`) to another app,
 * the way Chrome does it safely (ADR 0047): the intent may only reach activities that declared themselves
 * `BROWSABLE`, carries no explicit component or selector, no flags (so no URI permission grants) and no clip data, and
 * never targets this app or a local-data scheme.
 */
object ExternalLinks {
    private val BLOCKED_DATA_SCHEMES = setOf("file", "content", "javascript", "data", "blob", "about", CheckoutUrlPolicy.APP_RETURN_SCHEME)

    /** The sanitised intent for [url], or null when it must not be launched. */
    fun intentFor(url: String, ownPackage: String): Intent? {
        val intent = try {
            if (url.lowercase(Locale.ROOT).startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
        } catch (e: URISyntaxException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
        if (intent.`package` == ownPackage || intent.component?.packageName == ownPackage) return null
        val scheme = intent.data?.scheme?.lowercase(Locale.ROOT)
        if (scheme != null && scheme in BLOCKED_DATA_SCHEMES) return null
        intent.component = null
        intent.selector = null
        intent.clipData = null
        intent.flags = 0
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        return intent
    }

    /** An `intent:` link's `browser_fallback_url`, when it is an https page the checkout may load. */
    fun fallbackUrl(url: String): String? {
        if (!url.lowercase(Locale.ROOT).startsWith("intent:")) return null
        val fallback = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
        } catch (e: URISyntaxException) {
            null
        } catch (e: RuntimeException) {
            null
        }
        return fallback?.takeIf { CheckoutUrlPolicy.decide(it) == CheckoutNavigation.Load }
    }

    /** Starts [url] in another app; false when nothing could take it (the caller may load [fallbackUrl]). */
    fun open(context: Context, url: String): Boolean {
        val intent = intentFor(url, context.packageName) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
