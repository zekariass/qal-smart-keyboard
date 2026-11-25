package com.qalsmartkeyboard.app.creative.cards

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.qalsmartkeyboard.aiclient.FeatureAccess
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What happened to a Save or Share the user asked for. */
sealed interface CardOutcome {
    /** The card has a premium part this device may not use: the screen sends the user to the plans. */
    data object Locked : CardOutcome

    data object Saved : CardOutcome

    /** The gallery holds [CardStore.MAX_ITEMS] cards. */
    data object GalleryFull : CardOutcome

    data object Failed : CardOutcome

    /** Ready to hand to the share sheet. */
    data class Shareable(val file: File, val format: ExportFormat) : CardOutcome
}

/**
 * The card maker's long-lived state (T8.7). It lives in the activity's view model store, so the draft survives a
 * rotation and a detour to the plans screen (a locked template sends the user there and back). It renders, saves and
 * stages files off the main thread; everything stays on the device (R-8): the store is app-private no-backup storage.
 */
class CardMakerViewModel(
    app: Application,
    val store: CardStore = FileCardStore(File(app.noBackupFilesDir, "cards")),
    private val renderer: CardRenderer = CardRenderer(),
) : AndroidViewModel(app) {
    val editor = CardEditor(CardTemplates.default.newDoc(resolve = { "" }))

    /** The saved cards, newest first; refreshed by [refreshGallery]. */
    var gallery: List<SavedCard> by mutableStateOf(emptyList())
        private set

    private var started = false

    /** Gives the first card its starting texts in the app language (once; later calls keep the draft). */
    fun ensureStarted(resolve: (Int) -> String) {
        if (started) return
        started = true
        editor.startNew(CardTemplates.default, resolve)
    }

    fun newCard(resolve: (Int) -> String) {
        started = true
        editor.startNew(CardTemplates.default, resolve)
    }

    suspend fun refreshGallery() {
        val list = withContext(Dispatchers.IO) { store.list() }
        gallery = list
    }

    /** Opens a saved card for editing. False when it is gone or damaged. */
    suspend fun open(id: String): Boolean {
        val (doc, photo) = withContext(Dispatchers.IO) {
            val d = store.load(id) ?: return@withContext null to null
            val p = store.photoFile(id)?.let { CardImages.thumbnail(it, CardImages.MAX_PHOTO_SIDE) }
            d to p
        }
        if (doc == null) return false
        started = true
        editor.open(id, doc, photo)
        return true
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { store.delete(id) }
        if (editor.editingId == id) editor.detach() // the card being edited was deleted: its next save is a new card
        refreshGallery()
    }

    /** Reads a picked background locally (never uploaded) and uses it. False when it is not a readable picture. */
    suspend fun usePhoto(uri: Uri): Boolean {
        val bmp = withContext(Dispatchers.IO) { CardImages.loadPhoto(getApplication<Application>().contentResolver, uri) } ?: return false
        editor.usePhoto(bmp)
        return true
    }

    /** The preview of the current card (a [scale] size of the export), rendered off the main thread. */
    suspend fun preview(doc: CardDoc, photo: Bitmap?, scale: Float): Bitmap = withContext(Dispatchers.Default) { renderer.render(doc, photo, scale) }

    /** A small picture of [t] with its starting texts, for the template picker. */
    suspend fun templateThumbnail(t: CardTemplate, resolve: (Int) -> String, scale: Float): Bitmap =
        withContext(Dispatchers.Default) { renderer.render(t.newDoc(resolve), null, scale) }

    /** The full-size card as the user's chosen file format. Null (Locked) when it has parts the device may not use. */
    private suspend fun export(access: FeatureAccess): ByteArray? {
        val doc = editor.doc
        if (!CardGate.canExport(doc, access)) return null
        return withContext(Dispatchers.Default) {
            val bmp = renderer.render(doc, editor.photo, 1f)
            try {
                CardImages.encode(bmp, doc.format)
            } finally {
                bmp.recycle()
            }
        }
    }

    suspend fun save(access: FeatureAccess): CardOutcome {
        val bytes = export(access) ?: return CardOutcome.Locked
        val doc = editor.doc
        val photoBytes = if (doc.background is CardBackground.Photo && editor.photoDirty) editor.photo?.let { p -> withContext(Dispatchers.Default) { CardImages.photoJpeg(p) } } else null
        val result = withContext(Dispatchers.IO) { store.save(editor.editingId, doc, bytes, photoBytes) }
        return when (result) {
            is SaveResult.Saved -> {
                editor.saved(result.id)
                refreshGallery()
                CardOutcome.Saved
            }
            SaveResult.Full -> CardOutcome.GalleryFull
            SaveResult.Failed -> CardOutcome.Failed
        }
    }

    /** Renders the card and stages the file for the share sheet (the cache directory the FileProvider exposes). */
    suspend fun stageForShare(access: FeatureAccess): CardOutcome {
        val bytes = export(access) ?: return CardOutcome.Locked
        val format = editor.doc.format
        val file = withContext(Dispatchers.IO) { CardShare.stage(getApplication(), bytes, format) } ?: return CardOutcome.Failed
        return CardOutcome.Shareable(file, format)
    }

    /** Stages a saved card's image for the share sheet (the image as it was saved). */
    suspend fun stageSaved(card: SavedCard): CardOutcome {
        val format = ExportFormat.entries.firstOrNull { card.image.name.endsWith("." + it.extension) } ?: return CardOutcome.Failed
        val file = withContext(Dispatchers.IO) {
            runCatching { CardShare.stage(getApplication(), card.image.readBytes(), format) }.getOrNull()
        } ?: return CardOutcome.Failed
        return CardOutcome.Shareable(file, format)
    }

    companion object {
        /** How the activity's view model store creates it (see `activityViewModel`). */
        val create: androidx.lifecycle.viewmodel.CreationExtras.() -> CardMakerViewModel = {
            CardMakerViewModel(this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application)
        }
    }
}
