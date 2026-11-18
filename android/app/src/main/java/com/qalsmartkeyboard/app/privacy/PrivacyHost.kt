package com.qalsmartkeyboard.app.privacy

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.qalsmartkeyboard.app.auth.AccountStatus

/**
 * Hosts the privacy area (A2.6): picks the screen of [route], loads what it shows, handles Back
 * (every screen goes back to the dashboard, the dashboard closes the area) and the document picker
 * for the export. The archive goes to the document the person picks (`ACTION_CREATE_DOCUMENT`, so
 * no storage permission is needed and the file lands where they chose); nothing is kept in the app.
 */
@Composable
fun PrivacyHost(
    route: PrivacyRoute,
    onRoute: (PrivacyRoute) -> Unit,
    onClose: () -> Unit,
    account: AccountStatus,
    viewModel: PrivacyViewModel,
    onSignIn: () -> Unit,
    onPlans: () -> Unit,
    modifier: Modifier = Modifier,
    syncCard: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val consents by viewModel.consents.state.collectAsState()
    val history by viewModel.history.state.collectAsState()
    val export by viewModel.export.state.collectAsState()
    val deletion by viewModel.deletion.state.collectAsState()
    val signedIn = account !is AccountStatus.SignedOut

    BackHandler { if (route == PrivacyRoute.DASHBOARD) onClose() else onRoute(PrivacyRoute.DASHBOARD) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            viewModel.saveExport(
                open = { openSink(context, uri) },
                discard = { discard(context, uri) },
            )
        }
    }

    LaunchedEffect(route, signedIn) {
        if (!signedIn) return@LaunchedEffect
        when (route) {
            PrivacyRoute.DASHBOARD -> viewModel.loadDashboard()
            PrivacyRoute.CONSENTS -> viewModel.loadConsents()
            PrivacyRoute.CONSENT_HISTORY -> viewModel.loadConsentHistory()
            PrivacyRoute.HISTORY -> viewModel.loadHistory()
            PrivacyRoute.EXPORT -> viewModel.openExport()
            PrivacyRoute.DELETE -> viewModel.loadDeletion()
        }
    }

    val back = { onRoute(PrivacyRoute.DASHBOARD) }
    when (route) {
        PrivacyRoute.DASHBOARD -> PrivacyDashboardScreen(account, consents, deletion, history.enabled && history.consentGranted == true, onRoute, onSignIn, onClose, modifier)
        PrivacyRoute.CONSENTS -> ConsentsScreen(
            account, consents,
            onSet = viewModel::setConsent,
            onDismissWithdrawn = viewModel.consents::dismissWithdrawn,
            onOpenHistory = { onRoute(PrivacyRoute.HISTORY) },
            onOpenConsentHistory = { onRoute(PrivacyRoute.CONSENT_HISTORY) },
            onSignIn = onSignIn,
            onBack = back,
            modifier = modifier,
            syncCard = syncCard,
        )
        PrivacyRoute.CONSENT_HISTORY -> ConsentHistoryScreen(consents, viewModel::moreConsentHistory, back, modifier)
        PrivacyRoute.HISTORY -> HistoryScreen(
            account, history,
            onEnable = viewModel::enableHistory,
            onDisable = viewModel::disableHistory,
            onRetention = viewModel::setRetention,
            onMore = viewModel::moreHistory,
            onFavorite = viewModel::toggleFavorite,
            onDelete = viewModel::deleteHistoryItem,
            onDeleteAll = viewModel::deleteAllHistory,
            onPlans = onPlans,
            onSignIn = onSignIn,
            onBack = back,
            modifier = modifier,
        )
        PrivacyRoute.EXPORT -> ExportScreen(
            account, export,
            onRequest = viewModel::requestExport,
            onSave = { name -> picker.launch(name) },
            onSignIn = onSignIn,
            onBack = back,
            modifier = modifier,
        )
        PrivacyRoute.DELETE -> DeleteAccountScreen(
            account, deletion,
            onRequest = { typed -> viewModel.requestDeletion(typed, deleteWord(context), account == AccountStatus.Guest) },
            onCancelDeletion = viewModel::cancelDeletion,
            onExport = { onRoute(PrivacyRoute.EXPORT) },
            onSignIn = onSignIn,
            onDone = { viewModel.deletion.acknowledge(); onClose() },
            onBack = back,
            modifier = modifier,
        )
    }
}

private fun deleteWord(context: Context): String = context.getString(com.qalsmartkeyboard.app.R.string.pv_delete_word)

private fun openSink(context: Context, uri: Uri): java.io.OutputStream? = context.contentResolver.openOutputStream(uri, "wt")

/** Removes a half-written document (best effort; a provider may refuse). */
private fun discard(context: Context, uri: Uri) {
    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
}
