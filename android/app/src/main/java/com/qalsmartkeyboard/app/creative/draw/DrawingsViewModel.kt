package com.qalsmartkeyboard.app.creative.draw

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.qalsmartkeyboard.app.creative.cards.CardOutcome
import com.qalsmartkeyboard.app.creative.cards.CardShare
import com.qalsmartkeyboard.app.creative.cards.ExportFormat
import com.qalsmartkeyboard.ime.draw.DecodeResult
import com.qalsmartkeyboard.ime.draw.DrawingExporter
import com.qalsmartkeyboard.ime.draw.DrawingStore
import com.qalsmartkeyboard.ime.draw.ExportOptions
import com.qalsmartkeyboard.ime.draw.PngDrawingExporter
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One saved drawing of the keyboard's Draw gallery as the studio shows it: its picture (an exported PNG) and its date. */
data class SavedDrawingItem(val id: String, val modifiedMillis: Long, val image: File)

/**
 * "Your drawings" in the Creative studio (T8.15): the drawings saved in the keyboard's Draw tool, listed from the very same
 * [DrawingStore] directory (`filesDir/drawings/`; the keyboard and this app are one package, so one store, one file
 * format, no copy) to be shared as a PNG or deleted. Editing happens in the keyboard's panel, not here.
 *
 * R-8: nothing is uploaded; a share copies the PNG to `files/drawing_share/` (excluded from backups, swept when it is stale)
 * and hands it to the system share sheet on the user's tap. The drawings themselves are excluded from Auto Backup by the app's
 * backup rules. A drawing whose exported image is missing (a copy restored without it) is rendered again here, off the main
 * thread, from the editable file.
 */
class DrawingsViewModel(
    app: Application,
    val store: DrawingStore = DrawingStore(File(app.filesDir, "drawings")),
    private val exporter: DrawingExporter = PngDrawingExporter(),
) : AndroidViewModel(app) {
    var gallery: List<SavedDrawingItem> by mutableStateOf(emptyList())
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) { CardShare.sweep(getApplication(), listOf(SHARE_DIR)) }
    }

    /** Reads the store again (off the main thread). */
    suspend fun refreshGallery() {
        gallery = withContext(Dispatchers.IO) {
            store.list().mapNotNull { d -> imageOf(d.id)?.let { SavedDrawingItem(d.id, d.modifiedMillis, it) } }
        }
    }

    /** The exported PNG of [id], rendered first when it is missing; null when the drawing cannot be read. */
    private fun imageOf(id: String): File? {
        val png = store.pngFile(id)
        if (png.isFile && png.length() > 0) return png
        val d = (store.load(id) as? DecodeResult.Ok)?.drawing ?: return null
        return png.takeIf { exporter.exportPng(d, ExportOptions(), it) }
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { store.delete(id) }
        refreshGallery()
    }

    /** Copies the drawing's PNG to the share folder for the share sheet. */
    suspend fun stageSaved(item: SavedDrawingItem): CardOutcome {
        val file = withContext(Dispatchers.IO) {
            try {
                CardShare.stage(getApplication(), item.image.readBytes(), ExportFormat.PNG, SHARE_NAME, SHARE_DIR)
            } catch (e: IOException) {
                null
            }
        } ?: return CardOutcome.Failed
        return CardOutcome.Shareable(file, ExportFormat.PNG)
    }

    companion object {
        /** The share sheet's copy lives here (`xml/cards_file_paths.xml`; excluded from backups and device transfer). */
        const val SHARE_DIR = "drawing_share"
        private const val SHARE_NAME = "qal-drawing"

        val create: CreationExtras.() -> DrawingsViewModel = {
            DrawingsViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application)
        }
    }
}
