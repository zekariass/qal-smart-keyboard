package com.qalsmartkeyboard.ime.draw

import com.qalsmartkeyboard.ime.share.ImageSender
import com.qalsmartkeyboard.ime.share.ImageShare
import com.qalsmartkeyboard.ime.share.ShareOutcome
import java.io.File

/** A short message the panel shows after an action (the view maps it to a string). */
enum class DrawStatus { SAVED, SAVE_FAILED, NOTHING_TO_SEND, INSERTED, SHARED, SEND_FAILED, DELETED, DUPLICATED, OPEN_FAILED }

enum class DrawMode { CANVAS, GALLERY }

/**
 * The state of the Draw panel, free of Views (JVM-tested): the tool, colour, width and background choices, the drawing being
 * edited, the two "tap again to confirm" steps (clear canvas, delete drawing), save, insert / share, and the gallery.
 * The drawing lives in memory only until the user saves it; [discard] drops it (the keyboard was hidden).
 */
class DrawController(
    val store: DrawingStore,
    private val exporter: DrawingExporter,
    private val imageShare: () -> ImageShare,
    private val onChanged: () -> Unit = {},
) {
    var tool: DrawTool = DrawTool.PEN
        private set
    var colour: Int = InkDefaults.BLACK
        private set
    var widthIndex: Int = InkDefaults.DEFAULT_WIDTH_INDEX
        private set
    var mode: DrawMode = DrawMode.CANVAS
        private set

    /** The saved drawing being edited (null for a new one). */
    var currentId: String? = null
        private set

    /** Clear canvas was tapped once and waits for the confirming second tap. */
    var clearArmed: Boolean = false
        private set

    /** Delete was tapped once for this gallery drawing. */
    var deleteArmedId: String? = null
        private set

    /** The gallery drawing whose actions are shown. */
    var selectedId: String? = null
        private set

    var gallery: List<SavedDrawing> = emptyList()
        private set

    var status: DrawStatus? = null
        private set

    /** True until the user picks a colour: then the ink follows the background's contrast. */
    private var inkIsAutomatic = true

    private var current: DrawSession? = null

    /** The session being drawn; created empty on first use. */
    val session: DrawSession
        get() = current ?: DrawSession(DEFAULT_W, DEFAULT_H).also { current = it }

    val widthUnits: Float get() = InkDefaults.WIDTHS[widthIndex]

    // ---- tool settings -------------------------------------------------------------------------------------------

    fun selectTool(t: DrawTool) {
        tool = t
        touched()
    }

    fun selectColour(c: Int) {
        colour = c
        inkIsAutomatic = false
        if (tool == DrawTool.ERASER) tool = DrawTool.PEN // picking a colour means drawing with it
        touched()
    }

    fun selectWidth(i: Int) {
        widthIndex = i.coerceIn(0, InkDefaults.WIDTHS.lastIndex)
        touched()
    }

    fun setBackground(b: DrawBackground) {
        session.setBackground(b)
        if (inkIsAutomatic) colour = InkDefaults.inkFor(b)
        touched()
    }

    // ---- editing -------------------------------------------------------------------------------------------------

    fun undo() {
        session.undo()
        touched()
    }

    fun redo() {
        session.redo()
        touched()
    }

    /** The first tap arms, the second clears (one undo brings it back); any other action disarms. Returns true when it cleared. */
    fun requestClear(): Boolean {
        if (!clearArmed) {
            clearArmed = true
            status = null
            onChanged()
            return false
        }
        clearArmed = false
        session.clear()
        onChanged()
        return true
    }

    /** A stroke was added by the canvas view. */
    fun strokeAdded() = touched()

    // ---- save, insert, share -------------------------------------------------------------------------------------

    /** Saves the drawing to the gallery (editable file and PNG). Returns its id, or null when it was empty or failed. */
    fun save(): String? {
        disarm()
        val d = session.snapshot()
        if (d.isEmpty) return fail(DrawStatus.NOTHING_TO_SEND)
        val id = store.save(currentId, d) ?: return fail(DrawStatus.SAVE_FAILED)
        currentId = id
        session.markSaved()
        val png = exporter.exportPng(d, ExportOptions(), store.pngFile(id))
        status = if (png) DrawStatus.SAVED else DrawStatus.SAVE_FAILED
        onChanged()
        return id
    }

    /** Inserts the canvas into the field when it takes PNGs, else opens the share sheet. */
    fun insert(description: String, shareTitle: String = description): ShareOutcome =
        send(currentPng()) { f -> ImageSender.insertOrShare(imageShare(), f, description, shareTitle) }

    fun share(title: String): ShareOutcome = send(currentPng()) { f -> ImageSender.shareOnly(imageShare(), f, title) }

    fun insertSaved(id: String, description: String, shareTitle: String = description): ShareOutcome =
        send(savedPng(id)) { f -> ImageSender.insertOrShare(imageShare(), f, description, shareTitle) }

    fun shareSaved(id: String, title: String): ShareOutcome = send(savedPng(id)) { f -> ImageSender.shareOnly(imageShare(), f, title) }

    private fun send(png: File?, how: (File?) -> ShareOutcome): ShareOutcome {
        disarm()
        val outcome = if (png == null) ShareOutcome.NO_IMAGE else how(png)
        status = when (outcome) {
            ShareOutcome.INSERTED -> DrawStatus.INSERTED
            ShareOutcome.SHARED -> DrawStatus.SHARED
            ShareOutcome.FAILED -> DrawStatus.SEND_FAILED
            ShareOutcome.NO_IMAGE -> DrawStatus.NOTHING_TO_SEND
        }
        onChanged()
        return outcome
    }

    private fun currentPng(): File? {
        val d = session.snapshot()
        if (d.isEmpty) return null
        val f = store.currentPngFile()
        return f.takeIf { exporter.exportPng(d, ExportOptions(), it) }
    }

    private fun savedPng(id: String): File? {
        val f = store.pngFile(id)
        if (f.isFile && f.length() > 0) return f
        val d = (store.load(id) as? DecodeResult.Ok)?.drawing ?: return null
        return f.takeIf { exporter.exportPng(d, ExportOptions(), it) }
    }

    // ---- gallery -------------------------------------------------------------------------------------------------

    fun openGallery() {
        disarm()
        mode = DrawMode.GALLERY
        gallery = store.list()
        selectedId = null
        onChanged()
    }

    fun closeGallery() {
        mode = DrawMode.CANVAS
        selectedId = null
        deleteArmedId = null
        onChanged()
    }

    fun select(id: String) {
        if (gallery.none { it.id == id }) return
        selectedId = if (selectedId == id) null else id
        deleteArmedId = null
        status = null
        onChanged()
    }

    /** Opens a saved drawing for editing; false (and a message) when the file cannot be read. */
    fun edit(id: String): Boolean {
        disarm()
        val d = (store.load(id) as? DecodeResult.Ok)?.drawing
        if (d == null) {
            fail(DrawStatus.OPEN_FAILED)
            return false
        }
        current = DrawSession(d.width, d.height, d.background, d.strokes, d.meta)
        currentId = id
        inkIsAutomatic = true
        colour = InkDefaults.inkFor(d.background)
        mode = DrawMode.CANVAS
        selectedId = null
        status = null
        onChanged()
        return true
    }

    fun duplicate(id: String): String? {
        val copy = store.duplicate(id)
        if (copy == null) {
            fail(DrawStatus.OPEN_FAILED)
        } else {
            gallery = store.list()
            selectedId = copy
            status = DrawStatus.DUPLICATED
            onChanged()
        }
        return copy
    }

    /** The first tap arms, the second deletes. Returns true when it deleted. */
    fun requestDelete(id: String): Boolean {
        if (deleteArmedId != id) {
            deleteArmedId = id
            status = null
            onChanged()
            return false
        }
        deleteArmedId = null
        store.delete(id)
        if (currentId == id) currentId = null
        gallery = store.list()
        selectedId = null
        status = DrawStatus.DELETED
        onChanged()
        return true
    }

    /** A new empty canvas (the gallery's "New"); the colour follows the white background. */
    fun newDrawing() {
        disarm()
        current = null
        currentId = null
        inkIsAutomatic = true
        colour = InkDefaults.BLACK
        mode = DrawMode.CANVAS
        status = null
        onChanged()
    }

    /** The keyboard was hidden or the field changed: the unsaved drawing is dropped (nothing is kept past the user's tap). */
    fun discard() {
        current = null
        currentId = null
        mode = DrawMode.CANVAS
        selectedId = null
        gallery = emptyList()
        status = null
        disarm()
        inkIsAutomatic = true
        colour = InkDefaults.BLACK
        tool = DrawTool.PEN
    }

    private fun touched() {
        disarm()
        status = null
        onChanged()
    }

    private fun disarm() {
        clearArmed = false
        deleteArmedId = null
    }

    private fun fail(s: DrawStatus): String? {
        status = s
        onChanged()
        return null
    }

    companion object {
        const val DEFAULT_W = 360f
        const val DEFAULT_H = 240f
    }
}
