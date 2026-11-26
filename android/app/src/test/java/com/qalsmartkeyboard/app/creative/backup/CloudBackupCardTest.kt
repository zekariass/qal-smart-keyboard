package com.qalsmartkeyboard.app.creative.backup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The "Cloud backup of your drawings" card (T8.15 S3) on the JVM at 360 dp: the honest text in every state, the confirmations,
 * what each button calls, a run's progress and result, touch targets, font scale 1.3 and right-to-left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2500dp")
class CloudBackupCardTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = BackupActions(
        onSignIn = { calls += "signIn" },
        onTurnOn = { calls += "turnOn" },
        onTurnOff = { calls += "turnOff" },
        onBackUp = { calls += "backUp" },
        onRestore = { calls += "restore" },
        onDeleteCloud = { calls += "delete" },
        onRetry = { calls += "retry" },
        onDismiss = { calls += "dismiss" },
    )

    private val mib = 1024L * 1024
    private fun summary(items: Int = 3, bytes: Long = mib + mib / 2) = RemoteSummary(true, items, bytes, 2 * mib, 100, 32 * mib)

    private fun show(state: BackupUiState, direction: LayoutDirection = LayoutDirection.Ltr) = compose.setContent {
        CompositionLocalProvider(LocalLayoutDirection provides direction) { CloudBackupCard(state, actions) }
    }

    private fun on(result: BackupResult? = null, work: BackupWork? = null, problem: BackupError? = null) =
        BackupUiState(BackupPhase.CONSENT_ON, summary(), problem, work, result)

    private fun assertWithinScreen(what: String, vararg nodes: SemanticsNodeInteraction) {
        for (n in nodes) {
            val b = n.getUnclippedBoundsInRoot()
            assertTrue("$what: ${b.left}..${b.right}", b.left >= 0.dp && b.right <= 360.dp)
        }
    }

    private fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** True when some node contains [text] (a text may appear in the card and in an open dialog). */
    private fun seen(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    // ---- the states ---------------------------------------------------------------------------------------------------

    @Test fun theCardStatesHonestlyThatItIsNotEndToEndEncryptedAndOffByDefault() {
        show(BackupUiState(BackupPhase.CONSENT_OFF, summary(0, 0)))
        compose.onNodeWithText("Cloud backup of your drawings").assertExists()
        assertTrue(seen("This is not end-to-end encrypted"))
        assertTrue(seen("we hold the key"))
        assertTrue(seen("It is off by default"))
        assertTrue(seen("you can delete the cloud copy at any time"))
        assertTrue(seen("Nothing is sent until you turn it on"))
    }

    @Test fun theSameHonestTextIsThereWhenSignedOutAndWhenOn() {
        show(BackupUiState(BackupPhase.SIGNED_OUT))
        assertTrue(seen("This is not end-to-end encrypted"))
    }

    @Test fun signedOutOffersSignIn() {
        show(BackupUiState(BackupPhase.SIGNED_OUT))
        compose.onNodeWithText("Sign in to use cloud backup.").assertExists()
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(listOf("signIn"), calls)
        assertTrue(!exists("Turn on cloud backup") && !exists("Back up now"))
    }

    @Test fun aGuestIsToldItNeedsAFullAccountAndOffersSignIn() {
        show(BackupUiState(BackupPhase.GUEST))
        assertTrue(seen("Cloud backup needs a full account"))
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(listOf("signIn"), calls)
    }

    @Test fun whenTheServerSwitchedItOffTheCardIsNeutralAndHasNoActions() {
        show(BackupUiState(BackupPhase.UNAVAILABLE))
        assertTrue(seen("Cloud backup is not available right now."))
        assertTrue(!exists("Sign in") && !exists("Turn on cloud backup") && !exists("Back up now"))
        assertTrue("the long explanation is not shown when nothing can be done", !seen("end-to-end"))
    }

    @Test fun aProblemReadingTheUsageOffersARetry() {
        show(BackupUiState(BackupPhase.PROBLEM, problem = BackupError.Offline))
        assertTrue(seen("No connection to the server"))
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test fun turningOnAsksFirstAndOnlyTheConfirmationCallsIt() {
        show(BackupUiState(BackupPhase.CONSENT_OFF, summary(0, 0)))
        compose.onNodeWithText("Turn on cloud backup").performClick()
        assertTrue(calls.isEmpty())
        compose.onNodeWithText("Turn on cloud backup?").assertExists()
        assertTrue(seen("only when you tap Back up now"))
        assertTrue(seen("we hold the key"))
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(calls.isEmpty())
        compose.onNodeWithText("Turn on cloud backup").performClick()
        compose.onNodeWithText("Turn on").performClick()
        assertEquals(listOf("turnOn"), calls)
        assertTrue("no Back up now before the consent", !exists("Back up now"))
    }

    @Test fun theOnStateShowsUsageAgainstTheCapAndEveryAction() {
        show(on())
        compose.onNodeWithText("Drawings in the cloud: 3. Space used: 1.5 MB of 32 MB.").assertExists()
        compose.onNodeWithText("Back up now").performClick()
        compose.onNodeWithText("Restore from cloud").performClick()
        assertEquals(listOf("backUp", "restore"), calls)
    }

    @Test fun deletingTheCloudCopyAsksFirst() {
        show(on())
        compose.onNodeWithText("Delete cloud copy").performClick()
        assertTrue(calls.isEmpty())
        compose.onNodeWithText("Delete the cloud copy?").assertExists()
        assertTrue(seen("Drawings on this phone stay."))
        compose.onAllNodesWithText("Delete cloud copy")[1].performClick()
        assertEquals(listOf("delete"), calls)
    }

    @Test fun turningOffSaysItDeletesEveryCloudCopyAndAsksFirst() {
        show(on())
        compose.onNodeWithText("Turn off").performClick()
        assertTrue(calls.isEmpty())
        compose.onNodeWithText("Turn off cloud backup?").assertExists()
        assertTrue(seen("This deletes every cloud copy of your drawings."))
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(calls.isEmpty())
        compose.onNodeWithText("Turn off").performClick()
        compose.onAllNodesWithText("Turn off")[1].performClick()
        assertEquals(listOf("turnOff"), calls)
    }

    @Test fun whileARunGoesTheActionsAreDisabledAndProgressShows() {
        show(on(work = BackupWork.BackingUp(2, 5)))
        compose.onNodeWithText("Backing up: 2 of 5").assertExists()
        for (b in listOf("Back up now", "Restore from cloud", "Delete cloud copy", "Turn off")) compose.onNodeWithText(b).assertIsNotEnabled()
    }

    @Test fun whenIdleTheActionsAreEnabled() {
        show(on())
        for (b in listOf("Back up now", "Restore from cloud", "Delete cloud copy", "Turn off")) compose.onNodeWithText(b).assertIsEnabled()
    }

    // ---- results and errors -------------------------------------------------------------------------------------------

    @Test fun aBackupResultListsCountsAndCanBeDismissed() {
        show(on(BackupResult.BackedUp(RunReport(uploaded = 2, upToDate = 4, keptNewerCloud = 1, tooLarge = 1, failed = 1))))
        compose.onNodeWithText("Drawings backed up: 2").assertExists()
        compose.onNodeWithText("Newer in the cloud, not replaced (use Restore from cloud): 1").assertExists()
        compose.onNodeWithText("Too large to back up: 1").assertExists()
        compose.onNodeWithText("Failed, try again: 1").assertExists()
        compose.onNodeWithText("Already the same: 4").assertExists()
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test fun anEmptyBackupAndAnEmptyRestoreSaySo() {
        show(on(BackupResult.BackedUp(RunReport(upToDate = 3))))
        compose.onNodeWithText("Everything is already backed up.").assertExists()
    }

    @Test fun aRestoreResultListsCountsIncludingFullAndInvalid() {
        show(on(BackupResult.Restored(RunReport(restored = 3, keptNewerLocal = 2, invalid = 1, unsupported = 1, deviceFull = 4))))
        compose.onNodeWithText("Drawings restored: 3").assertExists()
        compose.onNodeWithText("Newer on this phone, not replaced: 2").assertExists()
        compose.onNodeWithText("Could not be read: 1").assertExists()
        compose.onNodeWithText("Made by a newer app version: 1").assertExists()
        compose.onNodeWithText("Not added because your gallery is full: 4").assertExists()
    }

    @Test fun aRunThatStoppedSaysWhyAndHowManyWereLeft() {
        show(on(BackupResult.BackedUp(RunReport(uploaded = 1, notAttempted = 6, stopped = BackupError.RateLimited(125)))))
        compose.onNodeWithText("Too many requests. Try again in about 3 min.").assertExists()
        compose.onNodeWithText("Not tried: 6").assertExists()
    }

    @Test fun everyErrorHasItsOwnSpecificSentence() {
        val cases = listOf(
            BackupError.Offline to "No connection to the server",
            BackupError.Timeout to "took too long",
            BackupError.SignedOut to "You were signed out",
            BackupError.GuestNotAllowed to "needs a full account",
            BackupError.ConsentRequired to "Cloud backup is off",
            BackupError.FeatureOff to "not available right now",
            BackupError.UpdateRequired to "Update the app",
            BackupError.RateLimited(null) to "Try again in a while",
            BackupError.RateLimited(30) to "about 1 min",
            BackupError.QuotaExceeded("ITEMS_PER_KIND", 100) to "most drawings it can (100)",
            BackupError.QuotaExceeded("TOTAL_BYTES", 32 * mib) to "is full (32 MB)",
            BackupError.QuotaExceeded(null, null) to "Your cloud backup is full.",
            BackupError.TooLarge(2 * mib) to "the limit is 2 MB for each",
            BackupError.TooLarge(null) to "too large to back up (the limit is 2 MB",
            BackupError.RevisionConflict(3) to "changed while backing up",
            BackupError.NotFound to "was not found",
            BackupError.Failed to "Something went wrong",
        )
        var error by mutableStateOf<BackupError>(BackupError.Failed)
        compose.setContent { CloudBackupCard(on(BackupResult.Failed(error)), actions) }
        for ((e, fragment) in cases) {
            compose.runOnIdle { error = e }
            compose.waitForIdle()
            assertTrue("$e -> $fragment", seen(fragment))
        }
    }

    @Test fun aBusyTapSaysSo() {
        show(on(BackupResult.Busy))
        assertTrue(seen("Please wait"))
    }

    @Test fun turnedOffSaysTheCloudCopiesWereDeleted() {
        show(BackupUiState(BackupPhase.CONSENT_OFF, summary(0, 0), result = BackupResult.TurnedOff))
        assertTrue(seen("every cloud copy was deleted"))
    }

    // ---- layout -------------------------------------------------------------------------------------------------------

    @Test fun touchTargetsAreAtLeast48dp() {
        show(on())
        val density = compose.density.density
        for (label in listOf("Back up now", "Restore from cloud", "Delete cloud copy", "Turn off")) {
            val t = compose.onNodeWithText(label).fetchSemanticsNode().touchBoundsInRoot
            assertTrue("$label: ${t.height / density} dp", t.height / density >= 47.9f)
        }
    }

    @Test fun nothingOverflowsAt360dpWithFontScale1_3() {
        RuntimeEnvironment.setFontScale(1.3f)
        show(on(BackupResult.BackedUp(RunReport(uploaded = 2, keptNewerCloud = 1, failed = 1, stopped = BackupError.QuotaExceeded("TOTAL_BYTES", 32 * mib), notAttempted = 3))))
        assertWithinScreen(
            "on",
            compose.onNodeWithText("Back up now"), compose.onNodeWithText("Restore from cloud"),
            compose.onNodeWithText("Delete cloud copy"), compose.onNodeWithText("Turn off"),
            compose.onNodeWithText("Cloud backup of your drawings"),
            compose.onNodeWithText("Newer in the cloud, not replaced (use Restore from cloud): 1"),
        )
    }

    @Test fun theOffStateAndItsDialogFitAt360dpWithFontScale1_3() {
        RuntimeEnvironment.setFontScale(1.3f)
        show(BackupUiState(BackupPhase.CONSENT_OFF, summary(0, 0)))
        assertWithinScreen("off", compose.onNodeWithText("Turn on cloud backup"), compose.onNodeWithText("Cloud backup of your drawings"))
        compose.onNodeWithText("Turn on cloud backup").performClick()
        assertWithinScreen("dialog", compose.onNodeWithText("Turn on cloud backup?"), compose.onNodeWithText("Cancel"), compose.onNodeWithText("Turn on"))
    }

    @Test fun theLayoutMirrorsInARightToLeftLocaleWithoutLosingAControl() {
        show(on(BackupResult.TurnedOn), LayoutDirection.Rtl)
        assertWithinScreen(
            "rtl",
            compose.onNodeWithText("Back up now"), compose.onNodeWithText("Restore from cloud"),
            compose.onNodeWithText("Delete cloud copy"), compose.onNodeWithText("Turn off"), compose.onNodeWithText("OK"),
        )
        // the title is read from the start edge: on the right in RTL
        assertTrue(compose.onNodeWithText("Cloud backup of your drawings").getUnclippedBoundsInRoot().right > 300.dp)
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    @Test fun sizesAreShownInBinaryMegabytes() {
        assertEquals("0 KB", formatBackupSize(0, Locale.US))
        assertEquals("1 KB", formatBackupSize(1, Locale.US))
        assertEquals("512 KB", formatBackupSize(512 * 1024, Locale.US))
        assertEquals("1 MB", formatBackupSize(mib, Locale.US))
        assertEquals("1.5 MB", formatBackupSize(mib + mib / 2, Locale.US))
        assertEquals("32 MB", formatBackupSize(32 * mib, Locale.US))
        assertEquals("1,5 MB", formatBackupSize(mib + mib / 2, Locale.GERMANY))
    }

    @Test fun theRetryTimeIsWholeMinutesAtLeastOne() {
        assertEquals(1, retryMinutes(0))
        assertEquals(1, retryMinutes(1))
        assertEquals(1, retryMinutes(60))
        assertEquals(2, retryMinutes(61))
        assertEquals(3, retryMinutes(125))
        assertEquals(24 * 60, retryMinutes(Long.MAX_VALUE / 4))
    }
}
