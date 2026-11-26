package com.qalsmartkeyboard.app.creative

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.qalsmartkeyboard.app.creative.annotate.AnnotationDraftStore
import com.qalsmartkeyboard.app.creative.annotate.AnnotatorViewModel
import com.qalsmartkeyboard.app.creative.annotate.FileAnnotationStore
import com.qalsmartkeyboard.app.creative.backup.BackupAccount
import com.qalsmartkeyboard.app.creative.backup.BackupConsentPort
import com.qalsmartkeyboard.app.creative.backup.BackupEngine
import com.qalsmartkeyboard.app.creative.backup.CreativeBackupModel
import com.qalsmartkeyboard.app.creative.backup.CreativeBackupViewModel
import com.qalsmartkeyboard.app.creative.backup.FakeLocal
import com.qalsmartkeyboard.app.creative.backup.FakeRemote
import com.qalsmartkeyboard.app.creative.backup.MemoryRecords
import com.qalsmartkeyboard.app.creative.cards.CardMakerViewModel
import com.qalsmartkeyboard.app.creative.cards.FileCardStore
import com.qalsmartkeyboard.app.creative.draw.DrawingsViewModel
import com.qalsmartkeyboard.ime.draw.DrawingStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The cloud backup card inside the Creative studio hub (T8.15 S3): where it sits, and the whole opt-in flow through the view model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2500dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreativeStudioBackupTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var dir: File
    private lateinit var cards: CardMakerViewModel
    private lateinit var drawings: DrawingsViewModel
    private val local = FakeLocal()
    private val remote = FakeRemote().also { it.consentGranted = false }
    private val records = MemoryRecords()
    private val consentCalls = mutableListOf<Boolean>()
    private var signIns = 0

    private val consent = object : BackupConsentPort {
        override suspend fun set(granted: Boolean) {
            consentCalls += granted
            remote.consentGranted = granted
            if (!granted) remote.items.clear()
        }
    }

    private lateinit var backup: CreativeBackupViewModel

    @Before fun setUp() {
        dir = Files.createTempDirectory("studio-backup-test").toFile()
        cards = CardMakerViewModel(app, store = FileCardStore(File(dir, "cards")))
        drawings = DrawingsViewModel(app, store = DrawingStore(File(dir, "drawings")))
        val engine = BackupEngine(local, remote, records, { "user-a" })
        backup = CreativeBackupViewModel(CreativeBackupModel(engine, remote, consent, Dispatchers.Unconfined))
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun showHub(account: BackupAccount) = compose.setContent {
        CreativeStudioScreen(
            cards = cards, onOpenTool = {}, onCardOpened = {}, onBack = {},
            annotations = AnnotatorViewModel(app, store = FileAnnotationStore(File(dir, "annotations")), drafts = AnnotationDraftStore(File(dir, "annotator/draft.json"))),
            drawings = drawings, backup = backup, account = account, onSignIn = { signIns++ },
        )
    }

    private fun seen(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun theCardSitsAboveYourDrawings() {
        showHub(BackupAccount.REGISTERED)
        compose.waitUntil(5_000) { seen("Turn on cloud backup") }
        val card = compose.onNodeWithText("Cloud backup of your drawings").performScrollTo().getUnclippedBoundsInRoot()
        val mine = compose.onNodeWithText("Your drawings").performScrollTo().getUnclippedBoundsInRoot()
        assertTrue("card ${card.top} above gallery ${mine.top}", card.top < mine.top)
    }

    @Test fun signedOutShowsSignInAndItOpensTheSignInFlow() {
        showHub(BackupAccount.SIGNED_OUT)
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        assertEquals(1, signIns)
        assertEquals(0, remote.lists)
    }

    @Test fun theWholeOptInFlowUploadsOnlyAfterTheConsentAndTheTapOnBackUpNow() {
        local.save("aaaaaa", 100)
        local.save("bbbbbb", 200)
        showHub(BackupAccount.REGISTERED)
        compose.waitUntil(5_000) { seen("Turn on cloud backup") }
        // 1. consent, with a confirmation: nothing is sent
        compose.onNodeWithText("Turn on cloud backup").performScrollTo().performClick()
        compose.onNodeWithText("Turn on cloud backup?").assertExists()
        compose.onNodeWithText("Turn on").performClick()
        compose.waitUntil(5_000) { seen("Back up now") }
        assertEquals(listOf(true), consentCalls)
        assertTrue(remote.puts.isEmpty())
        assertTrue(seen("Cloud backup is on. Nothing is sent until you tap Back up now."))
        // 2. the explicit tap
        compose.onNodeWithText("Back up now").performScrollTo().performClick()
        compose.waitUntil(5_000) { seen("Drawings backed up: 2") }
        assertEquals(setOf("aaaaaa", "bbbbbb"), remote.items.keys)
        compose.waitUntil(5_000) { seen("Drawings in the cloud: 2") }
        // 3. turning off says what it deletes, then the cloud is empty and the phone keeps its drawings
        compose.onNodeWithText("Turn off").performScrollTo().performClick()
        compose.onNodeWithText("Turn off cloud backup?").assertExists()
        compose.onAllNodesWithText("Turn off")[1].performClick()
        compose.waitUntil(5_000) { seen("every cloud copy was deleted") }
        assertEquals(listOf(true, false), consentCalls)
        assertTrue(remote.items.isEmpty())
        assertEquals(2, local.items.size)
        assertTrue(seen("Turn on cloud backup"))
    }

    @Test fun aRestoreFillsTheGalleryBelow() {
        remote.consentGranted = true
        showHub(BackupAccount.REGISTERED)
        compose.waitUntil(5_000) { seen("Back up now") }
        assertTrue(local.items.isEmpty())
        remote.otherDevice("aaaaaa", 100)
        compose.onNodeWithText("Restore from cloud").performScrollTo().performClick()
        compose.waitUntil(5_000) { seen("Drawings restored: 1") }
        assertEquals(setOf("aaaaaa"), local.items.keys)
    }

    @Test fun whenTheServerSwitchedTheFeatureOffTheCardIsNeutral() {
        remote.onSummary = { throw com.qalsmartkeyboard.app.creative.backup.BackupException(com.qalsmartkeyboard.app.creative.backup.BackupError.FeatureOff) }
        showHub(BackupAccount.REGISTERED)
        compose.waitUntil(5_000) { seen("not available right now") }
        assertTrue(!seen("Turn on cloud backup"))
    }
}
