package com.qalsmartkeyboard.ime.draw

import com.qalsmartkeyboard.ime.FieldTyping
import com.qalsmartkeyboard.ime.TextSink
import com.qalsmartkeyboard.ime.ai.AiTextField
import com.qalsmartkeyboard.ime.share.ImageSender
import com.qalsmartkeyboard.ime.share.ImageShare
import com.qalsmartkeyboard.ime.share.ShareOutcome
import java.io.File

/** A short message the panel shows after an action (the view maps it to a string). */
enum class DrawStatus {
    SAVED, SAVE_FAILED, NOTHING_TO_SEND, INSERTED, SHARED, SEND_FAILED, DELETED, DUPLICATED, OPEN_FAILED,

    /** The gallery holds [DrawingStore.MAX_DRAWINGS] drawings: delete one to save a new one. */
    GALLERY_FULL,

    /** The drawing holds [DrawSession.MAX_OBJECTS] objects. */
    OBJECT_LIMIT,
}

enum class DrawMode { CANVAS, GALLERY }

/** What a touch on the canvas does. */
enum class DrawInput {
    /** Ink with the current tool and brush. */
    INK,

    /** A drag places the chosen shape (a tap places a default-sized one). */
    SHAPE,

    /** A tap places a text and starts typing it. */
    TEXT,

    /** A tap selects an object, a drag moves the selected one. */
    SELECT,
}

/** What the second row of the panel offers: the ink colours, the brushes, or the paper. */
enum class DrawOptions { COLOURS, BRUSHES, PAPER }

/**
 * The state of the Draw panel, free of Views (JVM-tested): the tool, brush, colour, width and paper choices, what a touch
 * does ([DrawInput]), the drawing being edited with its objects (text and shapes) and their selection, text typed through the
 * keyboard's own typing strip, the two "tap again to confirm" steps (clear canvas, delete drawing), save, insert / share, and
 * the paged gallery. The drawing lives in memory only until the user saves it; [close] drops what is being typed and
 * [discard] the drawing (the keyboard was hidden).
 *
 * **Eraser.** The eraser clears ink only. Text and shapes are not ink: they float above it, the eraser does not touch them, and
 * they are removed with [deleteSelected] (select, then Delete; undo brings them back).
 *
 * **Closing is silent.** The service closes controllers from inside its own refresh; a change notification raised while
 * closing would call that refresh again without end (the sticker maker's crash on the phone). [close] therefore clears
 * nothing through a path that calls [onChanged].
 */
class DrawController(
    val store: DrawingStore,
    private val exporter: DrawingExporter,
    private val imageShare: () -> ImageShare,
    private val onChanged: () -> Unit = {},
) {
    var tool: DrawTool = DrawTool.PEN
        private set
    var brush: Brush = Brush.PEN
        private set
    var colour: Int = InkDefaults.BLACK
        private set
    var widthIndex: Int = InkDefaults.DEFAULT_WIDTH_INDEX
        private set
    var mode: DrawMode = DrawMode.CANVAS
        private set
    var input: DrawInput = DrawInput.INK
        private set
    var options: DrawOptions = DrawOptions.COLOURS
        private set

    var shapeKind: ShapeKind = ShapeKind.RECTANGLE
        private set
    var shapeFilled: Boolean = false
        private set
    var textSize: TextSize = TextSize.MEDIUM
        private set
    var textBold: Boolean = false
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

    /** Every drawing of the gallery, newest first (read when it opens and after each change). */
    var gallery: List<SavedDrawing> = emptyList()
        private set

    /** The gallery page shown (0-based). */
    var galleryPage: Int = 0
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

    /** Pen, highlighter or eraser: ink with that tool (the pen keeps its brush). */
    fun selectTool(t: DrawTool) {
        tool = t
        toInk()
        touched()
    }

    /** A pen brush (marker, pencil, dashed, dotted, calligraphy, or the plain pen): ink with it, and back to the colours. */
    fun selectBrush(b: Brush) {
        tool = DrawTool.PEN
        brush = b
        options = DrawOptions.COLOURS
        toInk()
        touched()
    }

    /** The brush button of the tools row: ink again; a second tap shows or hides the list of brushes. */
    fun toggleBrushes() {
        if (input == DrawInput.INK && tool != DrawTool.ERASER) {
            options = if (options == DrawOptions.BRUSHES) DrawOptions.COLOURS else DrawOptions.BRUSHES
        } else {
            if (tool == DrawTool.ERASER) tool = DrawTool.PEN
            options = DrawOptions.BRUSHES
            toInk()
        }
        touched()
    }

    /** The paper button: the second row shows the backgrounds, or the colours again. */
    fun togglePaper() {
        options = if (options == DrawOptions.PAPER) DrawOptions.COLOURS else DrawOptions.PAPER
        touched()
    }

    /** Colour for the ink and for what is placed next; the selected object takes it too. */
    fun selectColour(c: Int) {
        colour = c
        inkIsAutomatic = false
        if (tool == DrawTool.ERASER && input == DrawInput.INK) tool = DrawTool.PEN // picking a colour means drawing with it
        restyleSelected { it.withColour(c) }
        touched()
    }

    /** Thickness for the ink and for shapes; the selected shape takes it too. */
    fun selectWidth(i: Int) {
        widthIndex = i.coerceIn(0, InkDefaults.WIDTHS.lastIndex)
        val w = widthUnits
        restyleSelected { if (it is ShapeObject) it.copy(widthDp = w) else it }
        touched()
    }

    fun setBackground(b: DrawBackground) {
        session.setBackground(b)
        if (inkIsAutomatic) colour = InkDefaults.inkFor(b)
        touched()
    }

    // ---- what a touch does ---------------------------------------------------------------------------------------

    private fun toInk() {
        input = DrawInput.INK
        session.select(null)
        endTypingSilently()
    }

    fun selectShape(kind: ShapeKind) {
        shapeKind = kind
        input = DrawInput.SHAPE
        options = DrawOptions.COLOURS
        session.select(null)
        touched()
    }

    fun setShapeFilled(filled: Boolean) {
        shapeFilled = filled
        restyleSelected { if (it is ShapeObject) it.copy(filled = filled) else it }
        touched()
    }

    /** The text tool: a tap on the canvas places a text there. */
    fun selectTextTool() {
        input = DrawInput.TEXT
        options = DrawOptions.COLOURS
        session.select(null)
        touched()
    }

    fun selectTextSize(s: TextSize) {
        textSize = s
        restyleSelected { if (it is TextObject) it.copy(size = s) else it }
        touched()
    }

    fun toggleBold() {
        textBold = !textBold
        val b = textBold
        restyleSelected { if (it is TextObject) it.copy(bold = b) else it }
        touched()
    }

    /** The select tool: a tap picks an object, a drag moves it; with nothing picked, the last object is selected. */
    fun selectSelectTool() {
        input = DrawInput.SELECT
        options = DrawOptions.COLOURS
        if (session.selected() == null) session.select(session.objects.lastOrNull()?.id)
        touched()
    }

    private fun restyleSelected(f: (DrawObject) -> DrawObject) {
        val s = session.selected() ?: return
        session.replaceObject(f(s))
    }

    // ---- objects -------------------------------------------------------------------------------------------------

    /** A shape dragged from ([x1], [y1]) to ([x2], [y2]) on the canvas (a tap places a default-sized one there); it is placed and selected. */
    fun placeShape(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val tiny = kotlin.math.hypot(x2 - x1, y2 - y1) < TAP_UNITS
        val linear = shapeKind == ShapeKind.LINE || shapeKind == ShapeKind.ARROW
        val box = if (tiny) {
            val hw = DEFAULT_SHAPE / 2f
            val hh = if (linear) 0f else DEFAULT_SHAPE * 0.35f
            floatArrayOf(x1 - hw, y1 - hh, x1 + hw, y1 + hh)
        } else {
            floatArrayOf(x1, y1, x2, y2)
        }
        val placed = session.addObject(ShapeObject(0, shapeKind, box[0], box[1], box[2], box[3], colour, widthUnits, shapeFilled))
        status = if (placed == null) DrawStatus.OBJECT_LIMIT else null
        if (placed != null) disarm()
        onChanged()
        return placed != null
    }

    /** A tap with the select tool: the object [id] is selected (null: none). */
    fun selectAt(id: Int?) {
        session.select(id)
        touched()
    }

    /** The selected object was dragged by ([dx], [dy]) units. */
    fun moveSelected(dx: Float, dy: Float) {
        val s = session.selected() ?: return
        val moved = s.moved(dx, dy)
        session.replaceObject(clampInside(moved))
        touched()
    }

    private fun clampInside(o: DrawObject): DrawObject {
        // Keep at least a corner of the object on the canvas so it can still be picked up.
        val b = o.bounds()
        val keep = 12f
        var dx = 0f
        var dy = 0f
        if (b.right < keep) dx = keep - b.right
        if (b.left > session.width - keep) dx = session.width - keep - b.left
        if (b.bottom < keep) dy = keep - b.bottom
        if (b.top > session.height - keep) dy = session.height - keep - b.top
        return if (dx == 0f && dy == 0f) o else o.moved(dx, dy)
    }

    /** Deletes the selected object (undo brings it back). */
    fun deleteSelected() {
        val s = session.selected() ?: return
        session.removeObject(s.id)
        touched()
    }

    val hasSelection: Boolean get() = session.selected() != null

    // ---- typing a text -------------------------------------------------------------------------------------------

    /** True while the keys type a text into [textField] (the panel gives way to the typing strip). */
    var typing: Boolean = false
        private set

    private var editingId: Int? = null
    private var pendingX = 0f
    private var pendingY = 0f
    private var closing = false

    /** What is being typed; the typing strip edits its buffer. */
    val textField = AiTextField(TextObject.MAX_CHARS, onChanged = { if (!closing) onChanged() }, capacity = TextObject.MAX_CHARS)

    /** A tap at ([x], [y]) with the text tool: typing starts for a new text there. */
    fun beginText(x: Float, y: Float) {
        if (session.objects.size >= DrawSession.MAX_OBJECTS) {
            status = DrawStatus.OBJECT_LIMIT
            onChanged()
            return
        }
        disarm()
        status = null
        editingId = null
        val box = textBox()
        pendingX = x.coerceIn(0f, maxOf(0f, session.width - box))
        pendingY = y.coerceIn(0f, maxOf(0f, session.height - textSize.units * 1.25f))
        textField.clear()
        typing = true
        onChanged()
    }

    /** Edit: typing starts for the selected text, with its text in the field. */
    fun editSelectedText() {
        val t = session.selected() as? TextObject ?: return
        disarm()
        status = null
        editingId = t.id
        textField.set(t.text)
        typing = true
        onChanged()
    }

    /** Typing is done: a new text with something in it is placed (selected), an edited one is replaced; the keys' panel is back. */
    fun endTyping() {
        if (!typing) return
        val text = textField.text.trim().take(TextObject.MAX_CHARS)
        val id = editingId
        typing = false
        editingId = null
        closing = true
        try {
            textField.clear()
        } finally {
            closing = false
        }
        if (id == null) {
            if (text.isNotEmpty()) {
                val placed = session.addObject(TextObject(0, text, pendingX, pendingY, textSize, colour, textBold, textBox()))
                if (placed == null) status = DrawStatus.OBJECT_LIMIT
            }
        } else {
            val old = session.find(id) as? TextObject
            if (old != null && text.isNotEmpty()) session.replaceObject(old.copy(text = text))
        }
        onChanged()
    }

    private fun textBox(): Float = minOf(TextObject.DEFAULT_BOX, (session.width * 0.8f).coerceAtLeast(60f))

    /** Typing ends without placing or changing anything; no change is announced. */
    private fun endTypingSilently() {
        if (!typing && textField.length == 0) return
        typing = false
        editingId = null
        closing = true
        try {
            textField.clear()
        } finally {
            closing = false
        }
    }

    private val typingAdapter = object : FieldTyping {
        override val sink: TextSink get() = textField.sink
        override val newlineOnEnter: Boolean get() = true
        override fun done() = endTyping()
    }

    /** For `KeyboardController.fieldTyping`: non-null while the keys type a text. */
    fun fieldTyping(): FieldTyping? = if (typing) typingAdapter else null

    /** The system Back: typing ends and its text is placed. False when nothing was being typed. */
    fun back(): Boolean {
        if (!typing) return false
        endTyping()
        return true
    }

    /**
     * The panel was left or the keyboard hidden: a text being typed is dropped. Silent: it calls no change notification (the
     * service calls this from inside its own refresh; a notification here would re-enter that refresh without end). The
     * drawing itself stays (see [discard]).
     */
    fun close() {
        endTypingSilently()
        clearArmed = false
        deleteArmedId = null
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

    /** Saves the drawing to the gallery (editable file and PNG). Returns its id, or null when it was empty, the gallery is full, or it failed. */
    fun save(): String? {
        disarm()
        val d = session.snapshot()
        if (d.isEmpty) return fail(DrawStatus.NOTHING_TO_SEND)
        val id = when (val r = store.trySave(currentId, d)) {
            is StoreResult.Saved -> r.id
            StoreResult.Full -> return fail(DrawStatus.GALLERY_FULL)
            StoreResult.Failed -> return fail(DrawStatus.SAVE_FAILED)
        }
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

    /** True when the gallery holds [DrawingStore.MAX_DRAWINGS] drawings: the panel says so, and a new one cannot be saved. */
    val galleryFull: Boolean get() = gallery.size >= DrawingStore.MAX_DRAWINGS

    val galleryPages: Int get() = maxOf(1, (gallery.size + GALLERY_PAGE - 1) / GALLERY_PAGE)

    /** The drawings of the page shown. */
    val pageItems: List<SavedDrawing>
        get() = gallery.drop(galleryPage * GALLERY_PAGE).take(GALLERY_PAGE)

    fun openGallery() {
        disarm()
        endTypingSilently()
        mode = DrawMode.GALLERY
        gallery = store.list()
        galleryPage = 0
        selectedId = null
        onChanged()
    }

    fun closeGallery() {
        mode = DrawMode.CANVAS
        selectedId = null
        deleteArmedId = null
        onChanged()
    }

    fun nextPage() = goToPage(galleryPage + 1)

    fun previousPage() = goToPage(galleryPage - 1)

    private fun goToPage(p: Int) {
        val to = p.coerceIn(0, galleryPages - 1)
        if (to == galleryPage) return
        galleryPage = to
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

    /** Opens a saved drawing for editing (ink and objects); false (and a message) when the file cannot be read. */
    fun edit(id: String): Boolean {
        disarm()
        val d = (store.load(id) as? DecodeResult.Ok)?.drawing
        if (d == null) {
            fail(DrawStatus.OPEN_FAILED)
            return false
        }
        endTypingSilently()
        current = DrawSession(d.width, d.height, d.background, d.strokes, d.meta, objects = d.objects)
        currentId = id
        inkIsAutomatic = true
        colour = InkDefaults.inkFor(d.background)
        input = DrawInput.INK
        options = DrawOptions.COLOURS
        mode = DrawMode.CANVAS
        selectedId = null
        status = null
        onChanged()
        return true
    }

    fun duplicate(id: String): String? {
        val copy = when (val r = store.tryDuplicate(id)) {
            is StoreResult.Saved -> r.id
            StoreResult.Full -> {
                fail(DrawStatus.GALLERY_FULL)
                return null
            }
            StoreResult.Failed -> {
                fail(DrawStatus.OPEN_FAILED)
                return null
            }
        }
        gallery = store.list()
        galleryPage = 0
        selectedId = copy
        status = DrawStatus.DUPLICATED
        onChanged()
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
        galleryPage = galleryPage.coerceIn(0, galleryPages - 1)
        selectedId = null
        status = DrawStatus.DELETED
        onChanged()
        return true
    }

    /** A new empty canvas (the gallery's "New"); the colour follows the white background. */
    fun newDrawing() {
        disarm()
        endTypingSilently()
        current = null
        currentId = null
        inkIsAutomatic = true
        colour = InkDefaults.BLACK
        input = DrawInput.INK
        options = DrawOptions.COLOURS
        mode = DrawMode.CANVAS
        status = null
        onChanged()
    }

    /** The keyboard was hidden or the field changed: the unsaved drawing is dropped (nothing is kept past the user's tap). Silent. */
    fun discard() {
        endTypingSilently()
        current = null
        currentId = null
        mode = DrawMode.CANVAS
        selectedId = null
        gallery = emptyList()
        galleryPage = 0
        status = null
        disarm()
        inkIsAutomatic = true
        colour = InkDefaults.BLACK
        tool = DrawTool.PEN
        brush = Brush.PEN
        input = DrawInput.INK
        options = DrawOptions.COLOURS
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

        /** A drag shorter than this (drawing units) is a tap. */
        const val TAP_UNITS = 8f

        /** The size of a shape placed by a tap. */
        const val DEFAULT_SHAPE = 80f

        /** Drawings per gallery page (3 x 3 thumbnails; only one page of bitmaps is alive at a time). */
        const val GALLERY_PAGE = 9
    }
}
