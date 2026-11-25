package com.qalsmartkeyboard.app.creative

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.os.Bundle
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.creative.cards.CardFileProvider
import com.qalsmartkeyboard.app.creative.cards.CardMakerViewModel
import com.qalsmartkeyboard.app.creative.cards.CardShare
import com.qalsmartkeyboard.app.creative.cards.CardTemplates
import com.qalsmartkeyboard.app.creative.cards.ExportFormat
import com.qalsmartkeyboard.app.creative.cards.FileCardStore
import com.qalsmartkeyboard.app.creative.cards.SaveResult
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Creative studio hub (T8.7): the registry a third tool joins, the hub's tool cards, the personal gallery and sharing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreativeStudioTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var dir: File
    private lateinit var vm: CardMakerViewModel
    private val resolve: (Int) -> String = { "x" }

    @Before fun setUp() {
        dir = Files.createTempDirectory("studio-test").toFile()
        vm = CardMakerViewModel(app, store = FileCardStore(File(dir, "cards")))
        // The manifest's card FileProvider, registered the way the merged manifest declares it (also checks the paths file).
        val info = ProviderInfo().apply {
            authority = CardShare.authority(app)
            exported = false
            grantUriPermissions = true
            packageName = app.packageName
            metaData = Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.cards_file_paths) }
        }
        Robolectric.buildContentProvider(CardFileProvider::class.java).create(info)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun saveCard(template: String = "birthday"): String =
        (vm.store.save(null, CardTemplates.find(template)!!.newDoc(resolve), PNG, null) as SaveResult.Saved).id

    private fun showHub(onBack: () -> Unit = {}, onOpened: () -> Unit = {}) = compose.setContent {
        CreativeStudioScreen(cards = vm, onOpenTool = {}, onCardOpened = onOpened, onBack = onBack)
    }

    // ---- The registry -----------------------------------------------------------------------------------------------

    @Test fun theRegistryHoldsTheCardMakerAndFindsToolsById() {
        assertEquals(listOf("cards"), CreativeTools.all.map { it.id })
        assertEquals(CreativeTools.all.map { it.id }.size, CreativeTools.all.map { it.id }.toSet().size)
        assertNotNull(CreativeTools.find("cards"))
        assertEquals(null, CreativeTools.find("nope"))
        assertEquals(null, CreativeTools.find(null))
        for (t in CreativeTools.all) assertTrue(app.getString(t.title).isNotBlank() && app.getString(t.summary).isNotBlank())
    }

    // ---- The hub ----------------------------------------------------------------------------------------------------

    @Test fun theHubListsTheToolsAndAnEmptyGallery() {
        showHub()
        compose.onNodeWithText("Creative studio").assertExists()
        compose.onNodeWithText("Quote & greeting cards").assertExists()
        compose.onNodeWithText("Cards you save appear here.").performScrollTo().assertExists()
    }

    @Test fun savedCardsAreListedWithEditShareAndDelete() {
        saveCard()
        showHub()
        compose.waitUntil(5_000) { count("Edit") == 1 }
        compose.onNodeWithText("Edit").assertExists()
        compose.onNodeWithText("Share").assertExists()
        compose.onNodeWithText("Delete").assertExists()
    }

    private fun count(t: String) = compose.onAllNodesWithText(t).fetchSemanticsNodes().size

    @Test fun editingASavedCardOpensItInTheEditor() {
        val id = saveCard("thanks")
        var opened = 0
        showHub(onOpened = { opened++ })
        compose.waitUntil(5_000) { count("Edit") == 1 }
        compose.onNodeWithText("Edit").performClick()
        compose.waitUntil(5_000) { opened == 1 }
        assertEquals(id, vm.editor.editingId)
        assertEquals("thanks", vm.editor.doc.templateId)
        assertFalse(vm.editor.dirty)
    }

    @Test fun deletingNeedsConfirmationAndRemovesTheCard() {
        saveCard()
        showHub()
        compose.waitUntil(5_000) { count("Delete") == 1 }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this card?").assertExists()
        assertEquals(1, vm.store.count()) // not yet
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, vm.store.count())
        compose.onNodeWithText("Delete").performClick()
        // The dialog's confirm button is the second "Delete".
        compose.onAllNodesWithText("Delete")[1].performClick()
        compose.waitUntil(5_000) { vm.store.count() == 0 }
    }

    // ---- Sharing ----------------------------------------------------------------------------------------------------

    @Test fun sharingStagesAFileInTheFilesDirAndOffersTheShareSheetWithAReadGrant() {
        // androidx.core 1.16's FileProvider matches its roots with '/' separators, so on a Windows build machine (backslash
        // paths) no URI can be made in a JVM test; on Linux (CI) and on a device this is the real check.
        assumeTrue("FileProvider needs '/' paths", File.separatorChar == '/')
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val file = CardShare.stage(app, png, ExportFormat.PNG)!!
        assertEquals(File(app.filesDir, "card_share"), file.parentFile)
        assertTrue(file.name.endsWith(".png"))
        assertTrue(CardShare.share(app, file, ExportFormat.PNG, "Share card"))
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertTrue((send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertTrue(uri.toString(), uri.toString().startsWith("content://${app.packageName}.cards/"))
    }

    @Test fun onlyTheCardBeingSharedStaysInTheShareDirectory() {
        CardShare.stage(app, byteArrayOf(1), ExportFormat.PNG)
        val second = CardShare.stage(app, byteArrayOf(2), ExportFormat.JPEG)!!
        assertEquals(listOf(second.name), File(app.filesDir, "card_share").list()!!.toList())
        assertTrue(second.name.endsWith(".jpg"))
        assertEquals("image/jpeg", ExportFormat.JPEG.mime)
    }

    @Test fun theGalleryStoreLivesInTheNoBackupDirectoryByDefault() {
        val real = CardMakerViewModel(app)
        val id = (real.store.save(null, CardTemplates.default.newDoc(resolve), PNG, null) as SaveResult.Saved).id
        try {
            assertTrue(File(app.noBackupFilesDir, "cards/$id.json").isFile)
            assertTrue(File(app.noBackupFilesDir, "cards/$id.png").isFile)
        } finally {
            real.store.delete(id)
        }
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    }
}
