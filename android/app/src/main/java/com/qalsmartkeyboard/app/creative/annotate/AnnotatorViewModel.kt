package com.qalsmartkeyboard.app.creative.annotate

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.qalsmartkeyboard.aiclient.FeatureAccess
import com.qalsmartkeyboard.app.creative.cards.CardOutcome
import com.qalsmartkeyboard.app.creative.cards.CardShare
import com.qalsmartkeyboard.app.creative.cards.ExportFormat
import com.qalsmartkeyboard.app.creative.cards.SaveResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What happened when the user asked to continue the stored draft. */
enum class DraftRestore { RESTORED, NONE, UNREADABLE }

/**
 * The image annotator's long-lived state (T8.7). It lives in the activity's view model store, so the picture and its
 * annotations survive a rotation and the detour to the plans screen. Everything stays on the device (R-8): the picture
 * is read from the user's own file and never written by the app; only flattened exports are saved (the gallery, in
 * no-backup storage) or staged for the share sheet; the editable draft is a small JSON of objects (no pixels).
 * Nothing is logged and nothing is uploaded.
 */
class AnnotatorViewModel(
    app: Application,
    val store: AnnotationStore = FileAnnotationStore(File(app.noBackupFilesDir, "annotations")),
    private val drafts: AnnotationDraftStore = AnnotationDraftStore(File(app.noBackupFilesDir, "annotator/draft.json")),
    private val exporter: AnnotationExporter = AnnotationExporter(),
) : AndroidViewModel(app) {
    /** Measures labels for the editor (main thread only: the export uses its own painter). */
    val painter = AnnotationPainter()
    val editor = AnnotatorEditor(painter)

    /** The working bitmap (the imported picture, downscaled within limits, upright, opaque); null before an import. */
    var base: Bitmap? by mutableStateOf(null)
        private set

    private var previewSource: Bitmap? = null

    /** What the editor shows under the annotations: a smaller copy of [base] with the redactions applied. */
    var preview: Bitmap? by mutableStateOf(null)
        private set

    /** The picture the user picked (read again to continue the draft). */
    var source: Uri? = null
        private set

    var gallery: List<SavedAnnotation> by mutableStateOf(emptyList())
        private set

    /** A draft from an earlier session is stored and no picture is open. */
    var draftWaiting: Boolean by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            CardShare.sweep(getApplication(), listOf(CardShare.DIR, SHARE_DIR))
            val waiting = drafts.exists()
            withContext(Dispatchers.Main) { if (base == null) draftWaiting = waiting }
        }
    }

    private val resolver get() = getApplication<Application>().contentResolver

    /** Reads the picked picture (locally, never uploaded) and starts annotating it. False when it is not a readable picture. */
    suspend fun import(uri: Uri): Boolean {
        val pic = withContext(Dispatchers.IO) { AnnotatorImages.load(resolver, uri) } ?: return false
        releaseSource()
        keepAccess(uri)
        show(pic.bitmap, uri)
        editor.start(pic.bitmap.width, pic.bitmap.height)
        draftWaiting = false
        saveDraft()
        return true
    }

    /** Continues the stored draft: reads its picture again. */
    suspend fun restoreDraft(): DraftRestore {
        val draft = withContext(Dispatchers.IO) { drafts.load() } ?: return DraftRestore.NONE
        val uri = Uri.parse(draft.source)
        val pic = withContext(Dispatchers.IO) { AnnotatorImages.load(resolver, uri) } ?: return DraftRestore.UNREADABLE
        if (pic.bitmap.width != draft.doc.width || pic.bitmap.height != draft.doc.height) return DraftRestore.UNREADABLE // the file changed
        show(pic.bitmap, uri)
        editor.restore(draft.doc, draft.format)
        draftWaiting = false
        refreshPreview()
        return DraftRestore.RESTORED
    }

    /** "Discard draft": the draft file goes and the picture is closed. The user's own picture is untouched. */
    suspend fun discardDraft() {
        withContext(Dispatchers.IO) { drafts.delete() }
        releaseSource()
        editor.close()
        base = null
        previewSource = null
        preview = null
        draftWaiting = false
    }

    /** Stores the current state as the draft (objects and the picture's reference only). */
    suspend fun saveDraft() {
        val d = editor.doc ?: return
        val src = source ?: return
        val draft = AnnotDraft(src.toString(), d, editor.format)
        withContext(Dispatchers.IO) { drafts.save(draft) }
    }

    private fun show(bmp: Bitmap, uri: Uri) {
        base = bmp
        source = uri
        val p = AnnotatorImages.preview(bmp)
        previewSource = p
        preview = p
    }

    /** Recomputes the redacted preview (off the main thread) after the redactions changed. */
    suspend fun refreshPreview() {
        val src = previewSource ?: return
        val d = editor.doc ?: return
        preview = if (d.redactions.isEmpty()) src else withContext(Dispatchers.Default) {
            try { exporter.redactedPreview(src, d) } catch (e: OutOfMemoryError) { src }
        }
    }

    /** Keeps read access to the picked picture so the draft can be continued later (picker URIs allow this). */
    private fun keepAccess(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not persistable: the draft can be continued while this process lives.
        }
    }

    private fun releaseSource() {
        val s = source ?: return
        source = null
        try {
            resolver.releasePersistableUriPermission(s, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
        }
    }

    /** Turns the blur and pixelate boxes this device may not use into solid boxes (free), as one undoable step. */
    fun solidifyLocked(access: FeatureAccess) {
        val d = editor.doc ?: return
        editor.replace(AnnotatorGate.solidifyLockedRedactions(d, access))
    }

    /** The flattened picture's bytes, or null (Locked) when it uses tools this device may not use. */
    private suspend fun export(access: FeatureAccess): Pair<ByteArray?, CardOutcome?> {
        val d = editor.doc ?: return null to CardOutcome.Failed
        val b = base ?: return null to CardOutcome.Failed
        if (!AnnotatorGate.canExport(d, access)) return null to CardOutcome.Locked
        val format = editor.format
        val bytes = withContext(Dispatchers.Default) {
            try { exporter.export(b, d, format) } catch (e: OutOfMemoryError) { null } catch (e: IllegalArgumentException) { null }
        } ?: return null to CardOutcome.Failed
        return bytes to null
    }

    /** "Save a copy": the flattened picture goes to the personal gallery (the draft stays as it is). */
    suspend fun saveCopy(access: FeatureAccess): CardOutcome {
        val (bytes, fail) = export(access)
        if (bytes == null) return fail ?: CardOutcome.Failed
        val format = editor.format
        return when (withContext(Dispatchers.IO) { store.save(bytes, format) }) {
            is SaveResult.Saved -> {
                editor.saved()
                refreshGallery()
                CardOutcome.Saved
            }
            SaveResult.Full -> CardOutcome.GalleryFull
            SaveResult.Failed -> CardOutcome.Failed
        }
    }

    /** Flattens the picture and stages it for the share sheet. */
    suspend fun stageForShare(access: FeatureAccess): CardOutcome {
        val (bytes, fail) = export(access)
        if (bytes == null) return fail ?: CardOutcome.Failed
        val format = editor.format
        val file = withContext(Dispatchers.IO) { CardShare.stage(getApplication(), bytes, format, SHARE_NAME, SHARE_DIR) } ?: return CardOutcome.Failed
        return CardOutcome.Shareable(file, format)
    }

    suspend fun refreshGallery() {
        gallery = withContext(Dispatchers.IO) { store.list() }
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { store.delete(id) }
        refreshGallery()
    }

    /** Stages a saved picture for the share sheet (as it was saved). */
    suspend fun stageSaved(item: SavedAnnotation): CardOutcome {
        val format = item.format ?: return CardOutcome.Failed
        val file = withContext(Dispatchers.IO) {
            runCatching { CardShare.stage(getApplication(), item.image.readBytes(), format, SHARE_NAME, SHARE_DIR) }.getOrNull()
        } ?: return CardOutcome.Failed
        return CardOutcome.Shareable(file, format)
    }

    fun format(f: ExportFormat) {
        editor.format = f
    }

    companion object {
        /** The share sheet's copy lives here (`xml/cards_file_paths.xml`; excluded from backups and device transfer). */
        const val SHARE_DIR = "annotate_share"
        private const val SHARE_NAME = "qal-annotated"

        val create: CreationExtras.() -> AnnotatorViewModel = {
            AnnotatorViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application)
        }
    }
}
