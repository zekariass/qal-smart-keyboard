package com.qalsmartkeyboard.app.billing

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Message
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qalsmartkeyboard.app.R
import java.net.URI

/**
 * The provider's hosted checkout in a locked-down WebView (ADR 0047, ADR 0016):
 *
 * - JavaScript and DOM storage on (the provider pages need them); no file or content access, no
 *   `addJavascriptInterface`, no geolocation or camera/microphone grants, no file chooser, no new windows; mixed
 *   content never; Safe Browsing on; TLS errors cancel the load (the default).
 * - Every navigation goes through [CheckoutUrlPolicy]: https loads, the return URL and `qalkeyboard://billing/return`
 *   close the checkout ([onReturn]), app links go to other apps through [ExternalLinks], the rest is blocked.
 * - Back steps back inside the provider's pages, then closes ([onClose]: the payment may still have gone through, so
 *   the caller confirms the order either way). The WebView's cookies and storage are cleared when it closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(url: String, onReturn: (String?) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var loading by remember { mutableStateOf(true) }
    var host by remember { mutableStateOf(hostOf(url)) }
    var failed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var finished by remember { mutableStateOf(false) }
    val returnNow by rememberUpdatedState(onReturn)
    val closeNow by rememberUpdatedState(onClose)

    fun finish(result: String?, returned: Boolean) {
        if (finished) return
        finished = true
        webView?.stopLoading()
        if (returned) returnNow(result) else closeNow()
    }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack() && !failed) w.goBack() else finish(null, returned = false)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.pay_checkout_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (host.isNotEmpty()) {
                            Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { finish(null, returned = false) }) {
                        Icon(painterResource(R.drawable.ic_settings_close), contentDescription = stringResource(R.string.pay_checkout_close))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).also { w ->
                        configure(w)
                        w.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val target = request.url.toString()
                                return when (val d = CheckoutUrlPolicy.decide(target)) {
                                    is CheckoutNavigation.Return -> {
                                        finish(d.result, returned = true)
                                        true
                                    }
                                    CheckoutNavigation.Load -> {
                                        host = hostOf(target)
                                        false
                                    }
                                    is CheckoutNavigation.External -> {
                                        // Only the page itself may hand the payer to another app, never a frame in it.
                                        if (request.isForMainFrame && !ExternalLinks.open(ctx, d.url)) {
                                            ExternalLinks.fallbackUrl(d.url)?.let { view.loadUrl(it) }
                                        }
                                        true
                                    }
                                    CheckoutNavigation.Block -> true
                                }
                            }

                            override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                                // A backstop for navigations that skip shouldOverrideUrlLoading (a POST to the return URL).
                                val d = pageUrl?.let { CheckoutUrlPolicy.decide(it) }
                                if (d is CheckoutNavigation.Return) {
                                    view.stopLoading()
                                    finish(d.result, returned = true)
                                    return
                                }
                                if (d != null && d != CheckoutNavigation.Load && pageUrl != "about:blank") {
                                    view.stopLoading()
                                    return
                                }
                                loading = true
                                failed = false
                                pageUrl?.let { host = hostOf(it) }
                            }

                            override fun onPageFinished(view: WebView, pageUrl: String?) {
                                loading = false
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) {
                                    failed = true
                                    loading = false
                                }
                            }
                        }
                        w.webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) =
                                callback.invoke(origin, false, false)
                            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
                        }
                        w.loadUrl(url)
                        webView = w
                    }
                },
            )
            if (loading && !failed) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            if (failed) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.pay_checkout_error_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.pay_checkout_error_body), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { failed = false; webView?.reload() }) { Text(stringResource(R.string.pay_retry)) }
                    OutlinedButton(onClick = { finish(null, returned = false) }) { Text(stringResource(R.string.pay_checkout_close)) }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { w ->
                w.stopLoading()
                w.webChromeClient = null
                w.destroy()
            }
            webView = null
            // Nothing of the payer's session stays on the phone (the app has no other WebView).
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configure(w: WebView) {
    w.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        setGeolocationEnabled(false)
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        @Suppress("DEPRECATION")
        saveFormData = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
    }
    // Card 3-D Secure pages often run in a bank's frame: allow its cookies in this WebView only (cleared on close).
    CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
}

private fun hostOf(url: String): String = try {
    URI(url).host.orEmpty()
} catch (e: Exception) {
    ""
}
