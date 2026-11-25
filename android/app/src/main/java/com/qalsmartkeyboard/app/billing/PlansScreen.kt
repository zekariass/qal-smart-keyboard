package com.qalsmartkeyboard.app.billing

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import com.qalsmartkeyboard.api.billing.CreditTransaction
import java.text.NumberFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.api.billing.PaymentMethod
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.settings.SettingsHeader
import com.qalsmartkeyboard.app.settings.SettingsNote
import com.qalsmartkeyboard.app.settings.SettingsScaffold
import com.qalsmartkeyboard.app.settings.SettingsValueRow

/** What the plans screen can ask its host to do. */
data class PlansActions(
    val back: () -> Unit = {},
    val signIn: () -> Unit = {},
    val retry: () -> Unit = {},
    val selectOffer: (String) -> Unit = {},
    val selectMethod: (String) -> Unit = {},
    val buy: () -> Unit = {},
    /** Buy the credit pack with this product code (T8.10). */
    val buyPack: (String) -> Unit = {},
    val openHistory: () -> Unit = {},
    val loadMoreHistory: () -> Unit = {},
    val closeHistory: () -> Unit = {},
    /** The credits section has been scrolled into view (a "Buy credits" deep link): forget the request. */
    val creditsFocused: () -> Unit = {},
    val buyFromStore: () -> Unit = {},
    val reopen: () -> Unit = {},
    val refresh: () -> Unit = {},
    val closeCheckout: () -> Unit = {},
    /** Leave after a purchase: back to where the user came from. */
    val done: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

/**
 * The plans screen with its view model: the paywall, or the provider's checkout while one is open. [onDone] leaves
 * after a purchase (the host returns to where the user came from: the keyboard, the themes, ...).
 */
@Composable
fun PlansRoute(
    vm: PlansViewModel,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** Scroll to the "AI credits" section once it shows (opened from the keyboard's "Buy credits"). */
    focusCredits: Boolean = false,
    onCreditsFocused: () -> Unit = {},
) {
    val state by vm.model.state.collectAsState()
    val activity = LocalContext.current as? Activity
    val url = state.checkout.checkoutUrl
    if (state.checkout.step == CheckoutStep.CHECKOUT && url != null) {
        CheckoutScreen(url, onReturn = vm::onReturned, onClose = vm::onCheckoutClosed, modifier = modifier)
        return
    }
    val finished = state.checkout.step == CheckoutStep.PAID || state.checkout.step == CheckoutStep.FAILED
    val leave = {
        if (state.history.open) {
            vm.closeHistory()
        } else {
            if (finished) vm.closeCheckout()
            onBack()
        }
    }
    BackHandler(onBack = leave)
    PlansScreen(
        state,
        PlansActions(
            back = leave,
            signIn = onSignIn,
            retry = vm::load,
            selectOffer = vm::selectOffer,
            selectMethod = vm::selectMethod,
            buy = vm::buy,
            buyPack = { code -> if (state.storeFlow) activity?.let { a -> vm.buyPackFromStore({ a }, code) } else vm.buyPack(code) },
            openHistory = vm::openHistory,
            loadMoreHistory = vm::loadMoreHistory,
            closeHistory = vm::closeHistory,
            creditsFocused = onCreditsFocused,
            buyFromStore = { activity?.let { a -> vm.buyFromStore { a } } },
            reopen = vm::reopenCheckout,
            refresh = vm::refreshCheckout,
            closeCheckout = vm::closeCheckout,
            done = {
                vm.closeCheckout()
                onDone()
            },
            dismissMessage = vm::dismissMessage,
        ),
        modifier,
        focusCredits = focusCredits,
    )
}

/**
 * The paywall (ADR 0047), in the settings' visual language (ADR 0036): the current plan, the server's products and
 * prices, the payment options in the server's order (the default first), the terms, one primary button. Every state
 * has its own page: loading, signed out, offline, error, not available, and the checkout's confirming, still
 * confirming, not completed, paid (receipt) and failed.
 */
@Composable
fun PlansScreen(state: PlansState, actions: PlansActions, modifier: Modifier = Modifier, focusCredits: Boolean = false) {
    if (state.history.open && state.checkout.step == CheckoutStep.IDLE) {
        CreditHistoryScreen(state.history, actions, modifier)
        return
    }
    SettingsScaffold(title = stringResource(if (state.creditsOnly) R.string.pay_credits_title else R.string.plans_title), onBack = actions.back, modifier = modifier) {
        when (state.checkout.step) {
            CheckoutStep.CONFIRMING -> Confirming()
            CheckoutStep.STILL_CONFIRMING -> StillConfirming(state, actions)
            CheckoutStep.NOT_COMPLETED -> NotCompleted(state, actions)
            CheckoutStep.PAID -> Paid(state, actions)
            CheckoutStep.FAILED -> Failed(state, actions)
            else -> when (state.phase) {
                PlansPhase.LOADING -> Loading()
                PlansPhase.SIGNED_OUT -> Notice(
                    stringResource(R.string.pay_signed_out_title), stringResource(R.string.pay_signed_out_body),
                    stringResource(R.string.pay_sign_in), actions.signIn,
                )
                PlansPhase.OFFLINE -> Notice(
                    stringResource(R.string.pay_offline_title), stringResource(R.string.pay_offline_body),
                    stringResource(R.string.pay_retry), actions.retry,
                )
                PlansPhase.ERROR -> Notice(
                    stringResource(R.string.pay_error_title), stringResource(R.string.pay_error_body),
                    stringResource(R.string.pay_retry), actions.retry,
                )
                PlansPhase.NOT_AVAILABLE -> {
                    CurrentPlan(state)
                    state.credits?.let { CreditBalance(it) }
                    val noStore = state.unavailable == UnavailableReason.STORE_NOT_IN_BUILD
                    Notice(
                        stringResource(if (noStore) R.string.pay_unavailable_store_title else R.string.pay_unavailable_title),
                        stringResource(if (noStore) R.string.pay_unavailable_store_body else R.string.pay_unavailable_body),
                        null, null,
                    )
                    // The server's own notice says the same as the localised "no store in this build" page.
                    if (!noStore) state.notice?.let { SettingsNote(it) }
                }
                PlansPhase.READY -> Ready(state, actions, focusCredits)
            }
        }
    }
}

@Composable
private fun ColumnScope.Loading() {
    val label = stringResource(R.string.pay_loading)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp).semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}

/** A titled message with an optional action, centred in the page. */
@Composable
private fun Notice(title: String, body: String, action: String?, onAction: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun CurrentPlan(state: PlansState) {
    val current = state.current ?: return
    SettingsHeader(stringResource(R.string.pay_current_header))
    val name = current.name ?: if (current.isFree) stringResource(R.string.pay_plan_free) else current.planCode
    val until = current.untilMs?.takeIf { !current.isFree }?.let { stringResource(R.string.pay_plan_until, MoneyFormat.date(it)) }
    ListItem(
        headlineContent = { Text(name, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Column {
                if (until != null) Text(until)
                if (current.manualRenew) Text(stringResource(R.string.pay_plan_manual), style = MaterialTheme.typography.bodySmall)
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    )
}

@Composable
private fun ColumnScope.Ready(state: PlansState, actions: PlansActions, focusCredits: Boolean) {
    val creditsOnly = state.creditsOnly
    if (!creditsOnly || state.current?.isFree == false) CurrentPlan(state)
    state.notice?.let { SettingsNote(it) }
    if (state.storeFlow) {
        if (state.storeOffers.isNotEmpty()) {
            SettingsHeader(stringResource(R.string.pay_choose_header))
            Column(Modifier.selectableGroup()) {
                state.storeOffers.forEach { o ->
                    ChoiceRow(
                        title = periodName(o.period) ?: o.title,
                        summary = if (periodName(o.period) != null) o.title else null,
                        trailing = o.price,
                        selected = o.id == state.selectedOffer,
                        onSelect = { actions.selectOffer(o.id) },
                    )
                }
            }
            SettingsNote(stringResource(R.string.pay_terms_store))
        }
    } else {
        if (state.offers.isNotEmpty()) SettingsHeader(stringResource(R.string.pay_choose_header))
        state.offers.groupBy { it.productCode }.values.forEach { group ->
            val product = group.first()
            if (state.offers.map { it.productCode }.distinct().size > 1) {
                Text(
                    product.productName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).semantics { heading() },
                )
            }
            product.description?.let { SettingsNote(it) }
            Column(Modifier.selectableGroup()) {
                group.forEach { o ->
                    ChoiceRow(
                        title = periodName(o.period) ?: o.period,
                        summary = periodTerm(o.period),
                        trailing = o.displayPrice ?: MoneyFormat.format(o.price),
                        selected = o.key == state.selectedOffer,
                        onSelect = { actions.selectOffer(o.key) },
                    )
                }
            }
        }
        SettingsHeader(stringResource(R.string.pay_with_header))
        Column(Modifier.selectableGroup()) {
            state.methods.forEach { m ->
                ChoiceRow(
                    title = m.displayName,
                    summary = methodSummary(m),
                    trailing = null,
                    selected = m.methodCode == state.selectedMethod,
                    onSelect = { actions.selectMethod(m.methodCode) },
                )
            }
        }
        if (state.offers.isNotEmpty()) SettingsNote(stringResource(R.string.pay_terms))
    }
    state.message?.let { Message(it, actions) }
    if (creditsOnly) {
        // Credits only: no plan to pay for; the packs below carry their own buttons.
        CreditsSection(state, actions, focusCredits)
        return
    }
    Spacer(Modifier.height(8.dp))
    val starting = state.checkout.step == CheckoutStep.STARTING
    val label = when {
        state.buyer != BuyerKind.REGISTERED -> stringResource(R.string.pay_sign_in_to_buy)
        state.storeFlow -> stringResource(R.string.pay_buy_store)
        else -> state.selected?.let { stringResource(R.string.pay_buy, it.displayPrice ?: MoneyFormat.format(it.price)) } ?: stringResource(R.string.pay_buy_store)
    }
    if (state.buyer != BuyerKind.REGISTERED) SettingsNote(stringResource(R.string.pay_guest_note))
    Button(
        onClick = when {
            state.buyer != BuyerKind.REGISTERED -> actions.signIn
            state.storeFlow -> actions.buyFromStore
            else -> actions.buy
        },
        enabled = !starting && (state.buyer != BuyerKind.REGISTERED || state.selectedOffer != null),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("pay_button"),
    ) {
        if (starting) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(label)
    }
    if (state.packs.isNotEmpty()) CreditsSection(state, actions, focusCredits)
}

// ---------------------------------------------------------------- AI credits (T8.10)

/** The balance row: "120", and "100 expire on 24 Nov 2027" under it when a lot expires. */
@Composable
private fun CreditBalance(c: CreditsInfo) {
    val expiry = c.nextExpiryMs?.let { ms ->
        stringResource(if (c.expiringUnits >= c.balance) R.string.pay_credits_expire_all else R.string.pay_credits_expire_some, c.expiringUnits, MoneyFormat.date(ms))
    }
    val number = NumberFormat.getIntegerInstance().format(c.balance)
    ListItem(
        headlineContent = { Text(stringResource(R.string.pay_credits_balance)) },
        supportingContent = expiry?.let { { Text(it) } },
        trailingContent = { Text(number, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.testTag("credits_balance").semantics(mergeDescendants = true) {},
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.CreditsSection(state: PlansState, actions: PlansActions, focus: Boolean) {
    val requester = remember { BringIntoViewRequester() }
    if (focus) {
        LaunchedEffect(Unit) {
            requester.bringIntoView()
            actions.creditsFocused()
        }
    }
    SettingsHeader(stringResource(R.string.pay_credits_header), Modifier.bringIntoViewRequester(requester).testTag("credits_header"))
    state.credits?.let { CreditBalance(it) }
    val starting = state.checkout.step == CheckoutStep.STARTING
    state.packs.forEach { p ->
        // A store market never shows the server's placeholder price: the store's own, or none (a guest has not met the store).
        val price = p.displayPrice ?: if (state.storeFlow) null else MoneyFormat.format(p.price)
        val buying = starting && state.buyingPack == p.productCode
        val terms = p.validityDays?.let {
            stringResource(R.string.pay_pack_terms, pluralStringResource(R.plurals.pay_valid_days, it, it))
        } ?: stringResource(R.string.pay_pack_terms_short)
        ListItem(
            headlineContent = { Text(p.name, fontWeight = FontWeight.Medium) },
            supportingContent = {
                Column {
                    Text(pluralStringResource(R.plurals.pay_credits_count, p.credits.toInt(), p.credits.toInt()))
                    p.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(terms, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = if (state.buyer == BuyerKind.REGISTERED) ({ actions.buyPack(p.productCode) }) else actions.signIn,
                        enabled = !starting,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("pack_buy_${p.productCode}"),
                    ) {
                        if (buying) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(if (state.buyer == BuyerKind.REGISTERED && price != null) stringResource(R.string.pay_pack_buy, price) else stringResource(R.string.pay_sign_in_to_buy))
                    }
                }
            },
            trailingContent = price?.let { { Text(it, style = MaterialTheme.typography.titleMedium) } },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.testTag("pack_${p.productCode}"),
        )
    }
    if (state.buyer != BuyerKind.REGISTERED) SettingsNote(stringResource(R.string.pay_credits_guest_note))
    SettingsNote(stringResource(if (state.storeFlow) R.string.pay_credits_terms_store else R.string.pay_credits_terms))
    TextButton(
        onClick = actions.openHistory,
        modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp).testTag("credit_history_open"),
    ) { Text(stringResource(R.string.pay_credits_history)) }
}

private val TYPE_LABEL = mapOf(
    "PURCHASE" to R.string.pay_ledger_purchase,
    "GRANT" to R.string.pay_ledger_grant,
    "CONSUMPTION" to R.string.pay_ledger_consumption,
    "REFUND" to R.string.pay_ledger_refund,
    "EXPIRY" to R.string.pay_ledger_expiry,
    "ADJUSTMENT" to R.string.pay_ledger_adjustment,
    "REVERSAL" to R.string.pay_ledger_reversal,
)

/** Credits are fractional (6 dp); the ledger shows at most two decimals. */
private fun units(v: Double, signed: Boolean): String {
    val nf = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2; minimumFractionDigits = 0 }
    val s = nf.format(v)
    return if (signed && v > 0) "+$s" else s
}

/** "Credit history": the ledger, newest first, with a Load more button while the server has another page. */
@Composable
private fun CreditHistoryScreen(h: CreditHistoryState, actions: PlansActions, modifier: Modifier) {
    SettingsScaffold(title = stringResource(R.string.pay_credits_history), onBack = actions.closeHistory, modifier = modifier) {
        when {
            h.items.isEmpty() && h.loading -> Loading()
            h.items.isEmpty() && h.error != null -> Notice(
                stringResource(if (h.error == PlansMessage.OFFLINE) R.string.pay_offline_title else R.string.pay_error_title),
                stringResource(if (h.error == PlansMessage.OFFLINE) R.string.pay_offline_body else R.string.pay_error_body),
                stringResource(R.string.pay_retry), actions.openHistory,
            )
            h.items.isEmpty() -> Notice(stringResource(R.string.pay_ledger_empty_title), stringResource(R.string.pay_ledger_empty_body), null, null)
            else -> {
                h.items.forEach { t -> LedgerRow(t) }
                if (h.error != null) SettingsNote(stringResource(if (h.error == PlansMessage.OFFLINE) R.string.pay_msg_offline else R.string.pay_msg_server))
                if (h.nextCursor != null || h.error != null) {
                    if (h.loading) {
                        Loading()
                    } else {
                        OutlinedButton(
                            onClick = actions.loadMoreHistory,
                            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp).testTag("credit_history_more"),
                        ) { Text(stringResource(R.string.pay_ledger_more)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerRow(t: CreditTransaction) {
    val label = stringResource(TYPE_LABEL[t.transactionType] ?: R.string.pay_ledger_other)
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Column {
                t.createdAtMs?.let { Text(MoneyFormat.date(it)) }
                Text(stringResource(R.string.pay_ledger_balance_after, units(t.balanceAfter, false)), style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = { Text(units(t.deltaUnits, true), style = MaterialTheme.typography.titleMedium) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    )
}

@Composable
private fun ChoiceRow(title: String, summary: String?, trailing: String?, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = trailing?.let { { Text(it, style = MaterialTheme.typography.titleMedium) } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}

@Composable
private fun periodName(period: String): String? = when (period) {
    "MONTHLY" -> stringResource(R.string.pay_period_monthly)
    "ANNUAL" -> stringResource(R.string.pay_period_annual)
    else -> null
}

@Composable
private fun periodTerm(period: String): String? = when (period) {
    "MONTHLY" -> stringResource(R.string.pay_term_monthly)
    "ANNUAL" -> stringResource(R.string.pay_term_annual)
    else -> null
}

@Composable
private fun methodSummary(m: PaymentMethod): String? {
    val parts = listOfNotNull(
        if (m.isDefault) stringResource(R.string.pay_method_recommended) else null,
        if (m.methodType == "HOSTED_CHECKOUT") stringResource(R.string.pay_method_hosted) else null,
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun Message(m: PlansMessage, actions: PlansActions) {
    val text = stringResource(
        when (m) {
            PlansMessage.OFFLINE -> R.string.pay_msg_offline
            PlansMessage.SERVER -> R.string.pay_msg_server
            PlansMessage.NEEDS_ACCOUNT -> R.string.pay_msg_needs_account
            PlansMessage.MARKET_CHANGED -> R.string.pay_msg_market_changed
            PlansMessage.METHOD_UNAVAILABLE -> R.string.pay_msg_method_unavailable
            PlansMessage.PROVIDER_UNAVAILABLE -> R.string.pay_msg_provider_unavailable
            PlansMessage.PRODUCT_UNAVAILABLE -> R.string.pay_msg_product_unavailable
            PlansMessage.CHECKOUT_UNAVAILABLE -> R.string.pay_msg_checkout_unavailable
            PlansMessage.STORE_FAILED -> R.string.pay_msg_store_failed
        },
    )
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            TextButton(onClick = actions.dismissMessage) { Text(stringResource(R.string.pay_dismiss)) }
        }
    }
}

// ---------------------------------------------------------------- checkout status pages

@Composable
private fun StatusPage(title: String, body: String?, icon: Int?, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Column(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        content()
    }
}

@Composable
private fun Confirming() {
    StatusPage(stringResource(R.string.pay_confirming_title), stringResource(R.string.pay_confirming_body), null) {
        CircularProgressIndicator()
    }
}

@Composable
private fun StillConfirming(state: PlansState, actions: PlansActions) {
    StatusPage(
        stringResource(R.string.pay_still_title),
        stringResource(
            when {
                state.checkout.store && state.checkout.credits -> R.string.pay_store_credits_still_body
                state.checkout.store -> R.string.pay_store_still_body
                else -> R.string.pay_still_body
            },
        ),
        R.drawable.ic_settings_info,
    ) {
        state.message?.let { Message(it, actions) }
        Button(onClick = actions.refresh, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pay_refresh)) }
        if (!state.checkout.store) {
            OutlinedButton(onClick = actions.reopen, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pay_reopen)) }
        }
        TextButton(onClick = actions.back) { Text(stringResource(R.string.pay_done)) }
    }
}

@Composable
private fun NotCompleted(state: PlansState, actions: PlansActions) {
    StatusPage(stringResource(R.string.pay_not_completed_title), stringResource(R.string.pay_not_completed_body), R.drawable.ic_settings_info) {
        Button(onClick = actions.reopen, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pay_retry)) }
        OutlinedButton(onClick = actions.refresh, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pay_check_again)) }
        TextButton(onClick = actions.closeCheckout) { Text(stringResource(R.string.pay_back_to_plans)) }
        state.message?.let { Message(it, actions) }
    }
}

@Composable
private fun Paid(state: PlansState, actions: PlansActions) {
    val r = state.checkout.receipt
    val plan = r?.planName ?: state.current?.name
    if (r?.credits != null) {
        StatusPage(stringResource(R.string.pay_credits_paid_title), stringResource(R.string.pay_credits_paid_body), R.drawable.ic_settings_check)
    } else {
        StatusPage(
            if (plan != null) stringResource(R.string.pay_paid_title, plan) else stringResource(R.string.pay_paid_title_generic),
            stringResource(R.string.pay_paid_body),
            R.drawable.ic_settings_check,
        )
    }
    if (r != null) {
        SettingsHeader(stringResource(R.string.pay_receipt_header))
        r.orderReference?.let { SettingsValueRow(stringResource(R.string.pay_receipt_order), it) }
        val period = r.period?.let { periodName(it) }
        if (r.credits != null) {
            SettingsValueRow(stringResource(R.string.pay_receipt_credits), NumberFormat.getIntegerInstance().format(r.credits))
        } else {
            listOfNotNull(r.planName, period).takeIf { it.isNotEmpty() }?.let { SettingsValueRow(stringResource(R.string.pay_receipt_plan), it.joinToString(" · ")) }
        }
        (r.amount?.let { MoneyFormat.format(it) } ?: r.storePrice)?.let { SettingsValueRow(stringResource(R.string.pay_receipt_amount), it) }
        r.paidAtMs?.let { SettingsValueRow(stringResource(R.string.pay_receipt_paid_on), MoneyFormat.date(it)) }
        r.validUntilMs?.let { SettingsValueRow(stringResource(if (r.credits != null) R.string.pay_receipt_credits_expire else R.string.pay_receipt_valid_until), MoneyFormat.date(it)) }
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = actions.done, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp)) {
        Text(stringResource(R.string.pay_done))
    }
}

@Composable
private fun Failed(state: PlansState, actions: PlansActions) {
    val body = stringResource(
        when (state.checkout.finalStatus) {
            "FAILED" -> R.string.pay_failed_failed
            "EXPIRED" -> R.string.pay_failed_expired
            "CANCELLED" -> R.string.pay_failed_cancelled
            else -> R.string.pay_failed_other
        },
    )
    StatusPage(stringResource(R.string.pay_failed_title), body, R.drawable.ic_settings_info) {
        Button(onClick = actions.closeCheckout, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pay_back_to_plans)) }
    }
}
