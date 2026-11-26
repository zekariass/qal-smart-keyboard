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
import com.qalsmartkeyboard.app.creative.annotate.AnnotationDraftStore
import com.qalsmartkeyboard.app.creative.annotate.AnnotatorViewModel
import com.qalsmartkeyboard.app.creative.annotate.FileAnnotationStore
import com.qalsmartkeyboard.app.creative.cards.CardFileProvider
import com.qalsmartkeyboard.app.creative.cards.CardMakerViewModel
import com.qalsmartkeyboard.app.creative.cards.CardShare
import com.qalsmartkeyboard.app.creative.cards.CardTemplates
import com.qalsmartkeyboard.app.creative.cards.ExportFormat
import com.qalsmartkeyboard.app.creative.cards.FileCardStore
import com.qalsmartkeyboard.app.creative.cards.SaveResult
import com.qalsmartkeyboard.app.creative.draw.DrawingsViewModel
import com.qalsmartkeyboard.ime.draw.Drawing
import com.qalsmartkeyboard.ime.draw.DrawingStore
import com.qalsmartkeyboard.ime.draw.Pt
import com.qalsmartkeyboard.ime.draw.ShapeKind
import com.qalsmartkeyboard.ime.draw.ShapeObject
import com.qalsmartkeyboard.ime.draw.Stroke
import kotlinx.coroutines.runBlocking
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
    private lateinit var annotations: AnnotatorViewModel
    private lateinit var drawings: DrawingsViewModel
    private val resolve: (Int) -> String = { "x" }

    @Before fun setUp() {
        dir = Files.createTempDirectory("studio-test").toFile()
        vm = CardMakerViewModel(app, store = FileCardStore(File(dir, "cards")))
        annotations = AnnotatorViewModel(
            app,
            store = FileAnnotationStore(File(dir, "annotations")),
            drafts = AnnotationDraftStore(File(dir, "annotator/draft.json")),
        )
        drawings = DrawingsViewModel(app, store = DrawingStore(File(dir, "drawings")))
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
        CreativeStudioScreen(cards = vm, onOpenTool = {}, onCardOpened = onOpened, onBack = onBack, annotations = annotations, drawings = drawings)
    }

    // ---- The registry -----------------------------------------------------------------------------------------------

    @Test fun theRegistryHoldsTheCardMakerAndTheAnnotatorAndFindsToolsById() {
        assertEquals(listOf("cards", "annotator"), CreativeTools.all.map { it.id })
        assertNotNull(CreativeTools.find("annotator"))
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
        compose.onNodeWithText("Image annotator").assertExists()
        compose.onNodeWithText("Cards you save appear here.").performScrollTo().assertExists()
        compose.onNodeWithText("Your annotated pictures").performScrollTo().assertExists()
        compose.onNodeWithText("Pictures you save appear here.").performScrollTo().assertExists()
    }

    @Test fun savedAnnotatedPicturesAreListedWithShareAndDeleteButNoEdit() {
        // A saved annotated picture is flattened: it can be shared or deleted, never re-opened for editing.
        annotations.store.save(PNG, ExportFormat.PNG)
        showHub()
        compose.waitUntil(5_000) { count("Delete") == 1 }
        assertEquals(1, count("Share"))
        assertEquals(0, count("Edit"))
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete this picture?").assertExists()
        compose.onAllNodesWithText("Delete")[1].performClick()
        compose.waitUntil(5_000) { annotations.store.count() == 0 }
    }

    @Test fun theAnnotatorStagesItsShareCopyInItsOwnExcludedDirectory() {
        val file = CardShare.stage(app, byteArrayOf(1, 2), ExportFormat.PNG, "qal-annotated", AnnotatorViewModel.SHARE_DIR)!!
        assertEquals(File(app.filesDir, "annotate_share"), file.parentFile)
        // The provider's paths and the backup rules name the directory (it is exposed only for the share, never backed up).
        val paths = app.resources.getXml(R.xml.cards_file_paths).let { p ->
            val names = ArrayList<String>()
            while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (p.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && p.name == "files-path") names += p.getAttributeValue(null, "path")
            }
            names
        }
        assertEquals(listOf("card_share/", "annotate_share/", "drawing_share/"), paths)
        for (rules in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val excluded = ArrayList<String>()
            val p = app.resources.getXml(rules)
            while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (p.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && p.name == "exclude") excluded += p.getAttributeValue(null, "path")
            }
            assertTrue("$rules: $excluded", excluded.contains("annotate_share") && excluded.contains("card_share") && excluded.contains("drawing_share"))
        }
    }

    @Test fun staleShareCopiesAreSweptButAFreshOneStays() {
        val old = CardShare.stage(app, byteArrayOf(1), ExportFormat.PNG, "qal-annotated", AnnotatorViewModel.SHARE_DIR)!!
        val now = System.currentTimeMillis()
        old.setLastModified(now - CardShare.STALE_MILLIS - 60_000)
        val fresh = CardShare.stage(app, byteArrayOf(2), ExportFormat.JPEG, "qal-card")!!
        CardShare.sweep(app, listOf(CardShare.DIR, AnnotatorViewModel.SHARE_DIR), now = now)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
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

    // ---- Your drawings (T8.15) -------------------------------------------------------------------------------------

    private val emptyText = "Drawings you save in the keyboard's Draw tool appear here. To change one, open it from Gallery in the keyboard."

    private fun saveDrawing(): String = drawings.store.save(
        null,
        Drawing(
            120f, 80f,
            strokes = listOf(Stroke(listOf(Pt(5f, 5f), Pt(90f, 60f)), 0xFF000000.toInt(), 4f)),
            objects = listOf(ShapeObject(0, ShapeKind.RECTANGLE, 10f, 10f, 80f, 50f)),
        ),
    )!!

    @Test fun theDrawingsSectionSaysDrawingsAreEditedInTheKeyboardWhenEmpty() {
        showHub()
        compose.onNodeWithText("Your drawings").performScrollTo().assertExists()
        compose.onNodeWithText(emptyText).performScrollTo().assertExists()
    }

    @Test fun savedDrawingsAreListedWithShareAndDeleteButNoEdit() {
        saveDrawing()
        saveDrawing()
        showHub()
        compose.waitUntil(5_000) { count("Delete") == 2 }
        assertEquals(2, count("Share"))
        assertEquals("drawings are edited in the keyboard", 0, count("Edit"))
        assertEquals(0, count(emptyText))
        // Each row has its picture: an exported PNG that exists (made again here when the keyboard's copy is missing).
        runBlocking { drawings.refreshGallery() }
        assertEquals(2, drawings.gallery.size)
        assertTrue(drawings.gallery.all { it.image.isFile && it.image.length() > 0 })
    }

    @Test fun deletingADrawingAsksFirstThenRemovesItFromTheSharedStore() {
        val id = saveDrawing()
        showHub()
        compose.waitUntil(5_000) { count("Delete") == 1 }
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete this drawing?").assertExists()
        assertEquals(1, drawings.store.count())
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, drawings.store.count())
        compose.onNodeWithText("Delete").performClick()
        compose.onAllNodesWithText("Delete")[1].performClick()
        compose.waitUntil(5_000) { drawings.store.count() == 0 }
        assertFalse(drawings.store.pngFile(id).exists())
    }

    @Test fun aMissingImageIsRenderedFromTheEditableDrawing() {
        val id = saveDrawing()
        assertFalse(drawings.store.pngFile(id).exists())
        runBlocking { drawings.refreshGallery() }
        assertTrue(drawings.store.pngFile(id).isFile)
        assertEquals(id, drawings.gallery.single().id)
    }

    @Test fun sharingADrawingStagesACopyOfItsPngInTheDrawingShareDirectory() {
        saveDrawing()
        runBlocking { drawings.refreshGallery() }
        val item = drawings.gallery.single()
        val outcome = runBlocking { drawings.stageSaved(item) }
        val shareable = outcome as com.qalsmartkeyboard.app.creative.cards.CardOutcome.Shareable
        assertEquals(File(app.filesDir, DrawingsViewModel.SHARE_DIR), shareable.file.parentFile)
        assertFalse("a copy, never the gallery's own file", shareable.file == item.image)
        assertTrue(shareable.file.name.endsWith(".png"))
        assertEquals(item.image.readBytes().toList(), shareable.file.readBytes().toList())
        // The staged copy is swept when it is stale, like the cards'.
        val now = System.currentTimeMillis()
        shareable.file.setLastModified(now - CardShare.STALE_MILLIS - 60_000)
        CardShare.sweep(app, listOf(DrawingsViewModel.SHARE_DIR), now = now)
        assertFalse(shareable.file.exists())
    }

    @Test fun theDrawGalleryIsExcludedFromAutoBackupAndDeviceTransfer() {
        val p = app.resources.getXml(R.xml.data_extraction_rules)
        var section = ""
        val perSection = HashMap<String, MutableList<String>>()
        while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (p.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            if (p.name == "cloud-backup" || p.name == "device-transfer") section = p.name
            if (p.name == "exclude") perSection.getOrPut(section) { ArrayList() } += p.getAttributeValue(null, "domain") + ":" + p.getAttributeValue(null, "path")
        }
        assertTrue(perSection["cloud-backup"]!!.contains("file:drawings"))
        assertTrue(perSection["device-transfer"]!!.contains("file:drawings"))
        // The older rules file (Android 11 and below) names it too.
        val old = ArrayList<String>()
        val q = app.resources.getXml(R.xml.backup_rules)
        while (q.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (q.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && q.name == "exclude") old += q.getAttributeValue(null, "domain") + ":" + q.getAttributeValue(null, "path")
        }
        assertTrue(old.toString(), old.contains("file:drawings"))
    }

    @Test fun theDefaultDrawingsStoreIsTheKeyboardsOwnDirectory() {
        // One package, one store, one file format: the hub reads what the keyboard's panel saved.
        val real = DrawingsViewModel(app)
        val id = DrawingStore(File(app.filesDir, "drawings")).save(null, Drawing(50f, 50f, objects = listOf(ShapeObject(0, ShapeKind.LINE, 0f, 0f, 20f, 20f))))!!
        try {
            runBlocking { real.refreshGallery() }
            assertTrue(real.gallery.any { it.id == id })
        } finally {
            real.store.delete(id)
        }
    }

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
