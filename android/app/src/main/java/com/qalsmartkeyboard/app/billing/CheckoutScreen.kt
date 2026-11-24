package com.qalsmartkeyboard.app.billing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.qalsmartkeyboard.app.R

/**
 * The provider's hosted checkout in a Chrome Custom Tab (ADR 0047 amendment M4.11). The provider's page asks for the
 * device location, which the app neither has nor may request, so the page runs in the browser, which handles that
 * prompt itself.
 *
 * - The tab opens once per [url] (also across rotation); with no Custom Tabs provider the page opens in the default
 *   browser; if nothing can open it the error state shows.
 * - While it is open this screen waits: "I've paid" and the app's resume after the tab was left call [onReturn] once
 *   (the server verifies and the model polls; nothing here marks anything paid), "Cancel payment" and Back call [onClose].
 */
@Composable
fun CheckoutScreen(url: String, onReturn: (String?) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val returnNow by rememberUpdatedState(onReturn)
    val closeNow by rememberUpdatedState(onClose)
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()
    var launched by rememberSaveable(url) { mutableStateOf(false) }
    var failed by rememberSaveable(url) { mutableStateOf(false) }
    var finished by rememberSaveable(url) { mutableStateOf(false) }

    fun open() {
        failed = !(CheckoutUrlPolicy.isAllowedStart(url) && launchCheckout(context, url, toolbarColor))
    }

    fun finish(returned: Boolean) {
        if (finished) return
        finished = true
        if (returned) returnNow(null) else closeNow()
    }

    LaunchedEffect(url) {
        if (!launched) {
            launched = true
            open()
        }
    }

    // The tab (or the browser) took the foreground and the user came back, by the done button's deep link or by closing it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> paused = launched && !failed
                Lifecycle.Event.ON_RESUME -> if (paused) {
                    paused = false
                    finish(returned = true)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    BackHandler { finish(returned = false) }

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (failed) {
                Text(stringResource(R.string.pay_checkout_error_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(stringResource(R.string.pay_checkout_error_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Button(onClick = { open() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pay_retry)) }
            } else {
                Text(stringResource(R.string.pay_checkout_waiting_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(stringResource(R.string.pay_checkout_waiting_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Button(onClick = { finish(returned = true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pay_checkout_paid)) }
                OutlinedButton(onClick = { open() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pay_checkout_reopen)) }
            }
            OutlinedButton(onClick = { finish(returned = false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pay_checkout_close)) }
        }
    }
}

/**
 * Opens [url] in a Custom Tab, or in the default browser when no Custom Tabs provider exists ([provider] is the
 * package that would serve it). False when nothing can open it.
 */
internal fun launchCheckout(
    context: Context,
    url: String,
    toolbarColor: Int,
    provider: String? = runCatching { CustomTabsClient.getPackageName(context, null) }.getOrNull(),
): Boolean {
    val uri = Uri.parse(url)
    return try {
        if (provider != null) {
            val tab = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build())
                .build()
            tab.intent.setPackage(provider)
            tab.launchUrl(context, uri)
        } else {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        }
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
