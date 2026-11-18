package com.qalsmartkeyboard.app.privacy

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.qalsmartkeyboard.app.auth.AccountStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The privacy screens on the JVM (Robolectric): state to text, and taps to callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h3000dp") // tall: lazy lists compose every row
class PrivacyScreensTest {
    @get:Rule val compose = createComposeRule()

    private val registered = AccountStatus.Registered
    private val signedOut = AccountStatus.SignedOut(null)

    private fun granted(code: String, on: Boolean) = ConsentRecord(code, "1", on, 1_795_000_000_000L)

    // ---- dashboard

    @Test fun dashboardSaysWhatStaysOnTheDeviceAndWhatIsSent() {
        compose.setContent { PrivacyDashboardScreen(registered, ConsentsState(), DeletionState(), false, {}, {}, {}) }
        compose.onNodeWithText("Stays on this device").assertExists()
        compose.onNodeWithText("Everything you type. Keystrokes and drafts are never sent, logged or used for statistics.").assertExists()
        compose.onNodeWithText("Sent to our servers").assertExists()
        compose.onNodeWithText("Only when you tap an AI action: the text of that action, and nothing else. It is processed and not stored, unless you turned on AI history.").assertExists()
    }

    @Test fun dashboardShowsConsentAndHistoryStatesAndOpensTheScreens() {
        val opened = mutableListOf<PrivacyRoute>()
        val state = ConsentsState(loaded = true, current = mapOf("AI_PROCESSING" to granted("AI_PROCESSING", true), "ANALYTICS" to granted("ANALYTICS", false)))
        compose.setContent { PrivacyDashboardScreen(registered, state, DeletionState(), true, { opened += it }, {}, {}) }
        compose.onNodeWithText("Manage consents").performClick()
        compose.onNodeWithText("AI history").performClick()
        compose.onNodeWithText("Export my data").performClick()
        compose.onNodeWithText("Delete my account").performClick()
        assertEquals(listOf(PrivacyRoute.CONSENTS, PrivacyRoute.HISTORY, PrivacyRoute.EXPORT, PrivacyRoute.DELETE), opened)
    }

    @Test fun dashboardForAGuestExplainsWhatApplies() {
        compose.setContent { PrivacyDashboardScreen(AccountStatus.Guest, ConsentsState(), DeletionState(), false, {}, {}, {}) }
        compose.onNodeWithText("You are using a guest session. You can export or delete its data. Dictionary sync is only for accounts with a phone number or Google sign-in.").assertExists()
        compose.onNodeWithText("Export my data").assertIsEnabled()
    }

    @Test fun dashboardWhenSignedOutOffersSignIn() {
        var taps = 0
        compose.setContent { PrivacyDashboardScreen(signedOut, ConsentsState(), DeletionState(), false, {}, { taps++ }, {}) }
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(1, taps)
        compose.onNodeWithText("Stays on this device").assertExists()
    }

    @Test fun dashboardShowsAPendingDeletionWithACancelEntry() {
        val pending = DeletionState(DeletionPhase.PENDING, DeletionInfo("i", "SCHEDULED", 1L, 1_796_198_400_000L, 0))
        val opened = mutableListOf<PrivacyRoute>()
        compose.setContent { PrivacyDashboardScreen(registered, ConsentsState(), pending, false, { opened += it }, {}, {}) }
        compose.onNodeWithText("Cancel deletion").performClick()
        assertEquals(listOf(PrivacyRoute.DELETE), opened)
    }

    @Test fun dashboardCrashReportSwitchSaysNoTypedTextAndWorksSignedOut() {
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            PrivacyDashboardScreen(signedOut, ConsentsState(), DeletionState(), false, {}, {}, {}, crashReportsOn = true, onCrashReports = { changes += it })
        }
        compose.onNodeWithText("Send crash reports — no typed text, ever").assertExists()
        compose.onNode(isToggleable()).assertIsEnabled().performClick()
        assertEquals(listOf(false), changes)
    }

    // ---- consents

    @Test fun termsAndPrivacyAreAcceptedAndRequiredWithoutASwitch() {
        val state = ConsentsState(loaded = true, current = mapOf("TERMS_ACCEPTANCE" to granted("TERMS_ACCEPTANCE", true)))
        compose.setContent { ConsentsScreen(registered, state, { _, _ -> }, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Terms of use").assertExists()
        compose.onNodeWithText("Privacy notice").assertExists()
        compose.onNodeWithText("Required to use an account").assertExists() // the privacy notice has no record yet
        assertEquals(2, nodeCount("This cannot be withdrawn. To stop, delete your account."))
        assertEquals(2, nodeCount("Read the text"))
    }

    @Test fun grantingIsOneTapWithdrawingAsksFirstAndExplainsTheEffect() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val state = ConsentsState(loaded = true, current = mapOf("AI_PROCESSING" to granted("AI_PROCESSING", true), "ANALYTICS" to granted("ANALYTICS", false)))
        compose.setContent { ConsentsScreen(registered, state, { c, g -> calls += c to g }, {}, {}, {}, {}, {}) }
        // Withdraw AI processing: confirmation with the effect text first.
        switches()[0].performClick() // the first SWITCH consent is AI processing
        compose.onNodeWithText("AI actions stop working until you allow this again. Typing is not affected.").assertExists()
        assertEquals(emptyList<Pair<String, Boolean>>(), calls)
        compose.onNodeWithText("Withdraw").performClick()
        assertEquals(listOf("AI_PROCESSING" to false), calls)
    }

    @Test fun dictionarySyncUsesTheEmbeddedCardNotASecondSwitch() {
        compose.setContent {
            ConsentsScreen(registered, ConsentsState(loaded = true), { _, _ -> }, {}, {}, {}, {}, {}, syncCard = {
                androidx.compose.material3.Text("A2.4 sync card")
            })
        }
        compose.onNodeWithText("A2.4 sync card").assertExists()
        assertEquals(ConsentCatalog.all.count { it.kind == ConsentKind.SWITCH }, switches().fetchSemanticsNodes().size)
    }

    @Test fun aWithdrawalShowsItsEffectAndForHistoryALinkToTheSavedItems() {
        var opened = 0
        val state = ConsentsState(loaded = true, justWithdrawn = "SAVE_AI_HISTORY")
        compose.setContent { ConsentsScreen(registered, state, { _, _ -> }, {}, { opened++ }, {}, {}, {}) }
        compose.onNodeWithText("Consent withdrawn").assertExists()
        compose.onNodeWithText("New AI results are no longer saved. Items already saved stay until you delete them in AI history.").assertExists()
        compose.onAllNodesWithText("AI history")[0].performClick()
        assertEquals(1, opened)
    }

    @Test fun signedOutConsentSwitchesAreOff() {
        compose.setContent { ConsentsScreen(signedOut, ConsentsState(), { _, _ -> }, {}, {}, {}, {}, {}) }
        for (i in 0 until switches().fetchSemanticsNodes().size) switches()[i].assertIsNotEnabled()
    }

    @Test fun consentHistoryListsDecisionsAndLoadsMore() {
        var more = 0
        val state = ConsentsState(historyLoaded = true, history = listOf(granted("ANALYTICS", true), granted("ANALYTICS", false)), historyNext = "c")
        compose.setContent { ConsentHistoryScreen(state, { more++ }, {}) }
        assertEquals(2, nodeCount("Usage statistics"))
        compose.onNodeWithText("Load more").performClick()
        assertEquals(1, more)
    }

    // ---- history

    @Test fun historyWarnsWhenThePlanHasNoHistorySync() {
        val state = HistoryState(loaded = true, enabled = true, consentGranted = true, plan = PlanInfo("Free", false))
        compose.setContent { HistoryScreen(registered, state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Your plan does not include history, so nothing will be saved until you have a plan that does.").assertExists()
        compose.onNodeWithText("See plans").assertExists()
    }

    @Test fun noWarningWhenThePlanHasHistoryOrIsUnknown() {
        compose.setContent {
            HistoryScreen(registered, HistoryState(loaded = true, enabled = true, consentGranted = true, plan = PlanInfo("Premium", true)), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
        compose.onNodeWithText("Your plan does not include history, so nothing will be saved until you have a plan that does.").assertDoesNotExist()
    }

    @Test fun turningOnWithoutHistorySyncAsksFirst() {
        val enabled = mutableListOf<Int>()
        val state = HistoryState(loaded = true, enabled = false, retentionDays = 30, plan = PlanInfo("Free", false))
        compose.setContent { HistoryScreen(registered, state, { enabled += it }, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        switches()[0].performClick()
        compose.onNodeWithText("Your plan has no history").assertExists()
        assertEquals(emptyList<Int>(), enabled)
        compose.onNodeWithText("Turn on anyway").performClick()
        assertEquals(listOf(30), enabled)
    }

    @Test fun turningOnWithHistorySyncIsImmediateAndTurningOffToo() {
        val log = mutableListOf<String>()
        compose.setContent {
            HistoryScreen(registered, HistoryState(loaded = true, enabled = false, plan = PlanInfo("Premium", true)), { log += "on$it" }, { log += "off" }, {}, {}, {}, {}, {}, {}, {}, {})
        }
        switches()[0].performClick()
        assertEquals(listOf("on30"), log)
    }

    @Test fun deleteAllNeedsAConfirmation() {
        var deleted = 0
        val state = HistoryState(loaded = true, itemsLoaded = true, items = listOf(HistoryEntry("a", "TRANSLATE", "en", "am", "hello", "ሰላም", false, 1L, 2L)))
        compose.setContent { HistoryScreen(registered, state, {}, {}, {}, {}, {}, {}, { deleted++ }, {}, {}, {}) }
        compose.onNodeWithText("hello").assertExists()
        compose.onNodeWithText("Delete all").performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText("Delete all saved history?").assertExists()
        compose.onAllNodesWithText("Delete all")[nodeCount("Delete all") - 1].performClick()
        assertEquals(1, deleted)
    }

    @Test fun historyItemsCanBeFavouritedAndDeleted() {
        val log = mutableListOf<String>()
        val state = HistoryState(loaded = true, itemsLoaded = true, items = listOf(HistoryEntry("a", "TRANSLATE", null, null, "hello", "ሰላም", false, 1L, null)))
        compose.setContent { HistoryScreen(registered, state, {}, {}, {}, {}, { log += "fav$it" }, { log += "del$it" }, {}, {}, {}, {}) }
        compose.onNodeWithText("Favourite").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf("fava", "dela"), log)
    }

    @Test fun retentionTakesOnlyOneTo365() {
        val saved = mutableListOf<Int>()
        compose.setContent { HistoryScreen(registered, HistoryState(loaded = true, retentionDays = 30), {}, {}, { saved += it }, {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("90 days").performClick()
        assertEquals(listOf(90), saved)
        compose.onNodeWithText("Days (1 to 365)").performTextReplacement("366")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Days (1 to 365)").performTextReplacement("0")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Days (1 to 365)").performTextReplacement("45")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals(listOf(90, 45), saved)
    }

    // ---- export

    @Test fun exportOffersARequestAndShowsProgressThenSave() {
        var requests = 0
        compose.setContent { ExportScreen(registered, ExportState(), { requests++ }, {}, {}, {}) }
        compose.onNodeWithText("Request export").performClick()
        assertEquals(1, requests)
    }

    @Test fun exportInProgressShowsAStatusAndNoRequestButton() {
        val export = DataExportInfo("e", ExportStatus.PROCESSING, 1L, null, null)
        compose.setContent { ExportScreen(registered, ExportState(ExportPhase.IN_PROGRESS, export, alreadyRunning = true), {}, {}, {}, {}) }
        compose.onNodeWithText("An export is already being prepared, so no new one was started.").assertExists()
        compose.onNodeWithText("This screen checks again every few seconds.").assertExists()
        compose.onNodeWithText("Request export").assertDoesNotExist()
    }

    @Test fun exportReadyShowsTheExpiryAndSavesToAChosenPlace() {
        val names = mutableListOf<String>()
        val export = DataExportInfo("e", ExportStatus.READY, 1_795_000_000_000L, 1L, 1_795_593_780_000L)
        compose.setContent { ExportScreen(registered, ExportState(ExportPhase.READY, export), {}, { names += it }, {}, {}) }
        compose.onNodeWithText("Your export is ready.").assertExists()
        compose.onNodeWithText("Save to…").performClick()
        assertEquals(listOf("qal-data-export-2026-11-18.json"), names)
    }

    @Test fun exportRateLimitDisablesTheRequestButton() {
        val state = ExportState(ExportPhase.FAILED, null, retryAtMs = 5_000_000L, message = PrivacyMessage.RateLimited(3600))
        compose.setContent { ExportScreen(registered, state, {}, {}, {}, {}, now = { 1_000_000L }) }
        compose.onNodeWithText("Request export").assertIsNotEnabled()
        compose.onNodeWithText("Too many requests. Try again in 1 h.").assertExists()
    }

    @Test fun exportForSignedOutOffersSignInAndNoRequest() {
        compose.setContent { ExportScreen(signedOut, ExportState(), {}, {}, {}, {}) }
        compose.onNodeWithText("Request export").assertIsNotEnabled()
        compose.onNodeWithText("Sign in").assertExists()
    }

    // ---- delete account

    @Test fun deleteNeedsTheWordAndOffersTheExportFirst() {
        val typed = mutableListOf<String>()
        var exports = 0
        compose.setContent { DeleteAccountScreen(registered, DeletionState(DeletionPhase.IDLE), { typed += it }, {}, { exports++ }, {}, {}, {}) }
        compose.onNodeWithText("You have 14 days to change your mind. AI features stop at once. After 14 days the deletion is final and your data cannot be recovered.").assertExists()
        compose.onNodeWithText("Download your data first").assertExists()
        compose.onAllNodesWithText("Export my data")[0].performClick()
        assertEquals(1, exports)
        compose.onAllNodesWithText("Delete my account")[1].assertIsNotEnabled() // [0] is the title
        compose.onNodeWithText("Type DELETE to confirm").performTextInput("delete")
        compose.onAllNodesWithText("Delete my account")[1].assertIsEnabled().performClick()
        assertEquals(listOf("delete"), typed)
    }

    @Test fun pendingDeletionShowsTheDateAndCancel() {
        var cancelled = 0
        val pending = DeletionState(DeletionPhase.PENDING, DeletionInfo("i", "SCHEDULED", 1L, 1_796_198_400_000L, 1))
        compose.setContent { DeleteAccountScreen(registered, pending, {}, { cancelled++ }, {}, {}, {}, {}) }
        compose.onNodeWithText("Until then you can cancel and keep your account. AI features are switched off.").assertExists()
        compose.onNodeWithText("You still have a store subscription. Cancel it in the store, or you will keep being charged.").assertExists()
        compose.onNodeWithText("Cancel deletion").performClick()
        assertEquals(1, cancelled)
    }

    @Test fun aStartedPurgeCannotBeCancelled() {
        val processing = DeletionState(DeletionPhase.PENDING, DeletionInfo("i", "PROCESSING", 1L, 1L, 0))
        compose.setContent { DeleteAccountScreen(registered, processing, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Cancel deletion").assertDoesNotExist()
        compose.onNodeWithText("The deletion has started and can no longer be cancelled.").assertExists()
    }

    @Test fun afterTheRequestTheSignedOutDeviceExplainsHowToCancel() {
        var done = 0
        val state = DeletionState(DeletionPhase.SCHEDULED_SIGNED_OUT, DeletionInfo("i", "SCHEDULED", 1L, 1_796_198_400_000L, 0))
        compose.setContent { DeleteAccountScreen(signedOut, state, {}, {}, {}, {}, { done++ }, {}) }
        compose.onNodeWithText("You were signed out on this device. To cancel, sign in again with the same phone number or Google account before that date, then open Privacy and Delete my account.").assertExists()
        compose.onNodeWithText("OK").performClick()
        assertEquals(1, done)
    }

    @Test fun guestDeletionExplainsWhyTheSessionStays() {
        compose.setContent { DeleteAccountScreen(AccountStatus.Guest, DeletionState(DeletionPhase.IDLE), {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("This is a guest session. It stays signed in on this device so you can cancel, because a guest cannot sign in again.").assertExists()
    }

    @Test fun signedOutCannotStartADeletion() {
        compose.setContent { DeleteAccountScreen(signedOut, DeletionState(DeletionPhase.UNKNOWN), {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Type DELETE to confirm").assertDoesNotExist()
        compose.onNodeWithText("Sign in").assertExists()
    }

    // ---- helpers

    private fun nodeCount(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun switches() = compose.onAllNodes(isToggleable())
}
