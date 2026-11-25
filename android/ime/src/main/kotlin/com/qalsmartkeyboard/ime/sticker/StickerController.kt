package com.qalsmartkeyboard.ime.sticker

import com.qalsmartkeyboard.ime.FieldTyping
import com.qalsmartkeyboard.ime.TextSink
import com.qalsmartkeyboard.ime.ai.AiLink
import com.qalsmartkeyboard.ime.ai.AiTextField
import com.qalsmartkeyboard.ime.draw.Drawing
import com.qalsmartkeyboard.ime.share.ImageSender
import com.qalsmartkeyboard.ime.share.ImageShare
import com.qalsmartkeyboard.ime.share.ShareOutcome
import java.io.File

/** Writes a sticker as a WebP file. An interface so the panel and the tests can swap it. */
interface StickerExporter {
    /** Renders [spec] to [dest] (parent directories are created); false when it could not be written. */
    fun exportWebp(spec: StickerSpec, dest: File): Boolean
}

enum class StickerScreen { STRIP, MAKE }

/** Where a new sticker comes from. */
enum class StickerSource { TEXT, EMOJI, DRAWING }

/** The text fields of the maker the keys can type into. */
enum class StickerField { TEXT, CAPTION }

/** A short message the panel shows after an action (the view maps it to a string). */
enum class StickerStatus { SAVED, SAVE_FAILED, FULL, NOTHING_TO_MAKE, NO_DRAWING, INSERTED, SHARED, SEND_FAILED, DELETED, LOCKED, WORKING }

/** A sticker on the strip: one of the user's own, or one of a bundled pack. */
sealed interface StickerRef {
    data class Mine(val id: String) : StickerRef
    data class Pack(val pack: StickerPackId, val stickerId: String) : StickerRef
}

/**
 * The state of the sticker panel, free of Views (JVM-tested): the strip (the user's own stickers and the bundled packs, locked
 * ones included), the maker (text, emoji and caption, or a drawing), the typed fields, save, insert / share, delete with a
 * second tap, and the gate of the premium packs ([access]).
 *
 * R-8: nothing here touches the network, logs or stores a text; the typed text lives in memory until the panel closes, and
 * a made sticker is a file in the app's private directory that leaves it only by an explicit insert or share. The host
 * field is never read. Bitmap work (rendering and encoding) runs through [background]; results come back through [post].
 */
class StickerController(
    val store: StickerStore,
    private val exporter: StickerExporter,
    private val imageShare: () -> ImageShare,
    /** The plan's say on the bundled packs, read at each tap and render (cached state, no call). */
    private val access: () -> PackAccess,
    /** The drawing in progress in Draw & Sketch (the keyboard's in-memory canvas), or null. */
    private val drawing: () -> Drawing? = { null },
    /** The companion app's screens ("See plans", sign in). */
    private val openLink: (AiLink) -> Unit = {},
    private val background: (Runnable) -> Unit = { it.run() },
    private val post: (Runnable) -> Unit = { it.run() },
    private val onChanged: () -> Unit = {},
) {
    var screen: StickerScreen = StickerScreen.STRIP
        private set

    /** The strip's tab: the user's own stickers (null) or a bundled pack. */
    var tab: StickerPackId? = null
        private set

    /** The user's own stickers, newest first (read when the panel opens and after each change). */
    var mine: List<SavedSticker> = emptyList()
        private set

    /** The sticker whose actions are shown (long press). */
    var selected: StickerRef? = null
        private set

    /** Delete was tapped once for this sticker. */
    var deleteArmedId: String? = null
        private set

    var status: StickerStatus? = null
        private set

    /** A sticker is being rendered or written. */
    var busy: Boolean = false
        private set

    // ---- maker state ---------------------------------------------------------------------------------------------

    var source: StickerSource = StickerSource.TEXT
        private set

    val text = AiTextField(StickerFormat.MAX_TEXT, onChanged = { changed(clearStatus = false) }, capacity = StickerFormat.MAX_TEXT)
    val caption = AiTextField(StickerFormat.MAX_CAPTION, onChanged = { changed(clearStatus = false) }, capacity = StickerFormat.MAX_CAPTION)

    /** The field the keys type into right now, or null. */
    var typing: StickerField? = null
        private set

    var shape: StickerShape = StickerShape.NONE
        private set
    var fillIndex: Int = 1
        private set
    var size: StickerTextSize = StickerTextSize.MEDIUM
        private set
    var emoji: String = EMOJIS[0]
        private set

    /** The drawing source's paper: false = transparent background (the default), true = the drawing's own paper colour. */
    var drawingPaper: Boolean = false
        private set

    private var inkPicked: Int? = null

    /** Ink colour (text and caption): the picked one, else black on a transparent sticker and the contrast of the fill on a shaped one. */
    val ink: Int
        get() = inkPicked?.let { StickerColours.INK[it] }
            ?: if (shape == StickerShape.NONE) StickerColours.INK[0] else StickerColours.contrastOn(StickerColours.FILLS[fillIndex])

    /** The picked swatch (null = automatic). */
    val inkIndex: Int? get() = inkPicked

    val typingField: AiTextField?
        get() = when (typing) {
            StickerField.TEXT -> text
            StickerField.CAPTION -> caption
            null -> null
        }

    /** The access to the bundled packs right now. */
    val packAccess: PackAccess get() = access()

    /** Called after an image was committed into the host field: the service closes the panel. */
    var onInserted: () -> Unit = {}

    // ---- opening, closing ----------------------------------------------------------------------------------------

    /** The panel opened: the strip with the user's own stickers. */
    fun open() {
        screen = StickerScreen.STRIP
        tab = null
        selected = null
        deleteArmedId = null
        status = null
        typing = null
        mine = store.list()
        onChanged()
    }

    /**
     * The panel closed, the keyboard was hidden or the field changed: nothing typed or chosen is kept. [keepMaker] (the user
     * went to Draw to make a drawing for a sticker) keeps the maker's source and style choices, never the typed texts.
     */
    fun close(keepMaker: Boolean = false) {
        screen = StickerScreen.STRIP
        tab = null
        selected = null
        deleteArmedId = null
        status = null
        typing = null
        busy = false
        mine = emptyList()
        text.clear()
        caption.clear()
        if (keepMaker) return
        source = StickerSource.TEXT
        shape = StickerShape.NONE
        fillIndex = 1
        size = StickerTextSize.MEDIUM
        emoji = EMOJIS[0]
        drawingPaper = false
        inkPicked = null
    }

    fun discard() = close(keepMaker = false)

    // ---- the strip -----------------------------------------------------------------------------------------------

    fun selectTab(t: StickerPackId?) {
        tab = t
        selected = null
        status = null
        changed()
    }

    /** Long press: the sticker's actions (insert, share, delete) are shown; again, or another sticker, moves or hides them. */
    fun select(ref: StickerRef) {
        selected = if (selected == ref) null else ref
        status = null
        changed()
    }

    fun clearSelection() {
        selected = null
        changed()
    }

    /** "See plans" (or "Sign in"): opens the companion app; the keyboard stays as it is. */
    fun seePlans() = openLink(access().link)

    /** The bundled packs the plan does not open show a lock; making stickers of your own is never locked. */
    fun locked(): Boolean = !access().open

    /** Tap on a sticker: it goes into the field (or the share sheet when the field does not take WebP). */
    fun insert(ref: StickerRef, description: String, shareTitle: String = description) =
        deliver(ref) { f -> ImageSender.insertOrShare(imageShare(), f, StickerFormat.MIME, description, shareTitle) }

    fun share(ref: StickerRef, title: String) = deliver(ref) { f -> ImageSender.shareOnly(imageShare(), f, StickerFormat.MIME, title) }

    /** The first tap arms, the second deletes. Returns true when it deleted. */
    fun requestDelete(id: String): Boolean {
        if (deleteArmedId != id) {
            status = null
            deleteArmedId = id
            onChanged()
            return false
        }
        deleteArmedId = null
        store.delete(id)
        mine = store.list()
        selected = null
        status = StickerStatus.DELETED
        onChanged()
        return true
    }

    private fun deliver(ref: StickerRef, how: (File?) -> ShareOutcome) {
        if (ref is StickerRef.Pack && !access().open) {
            status = StickerStatus.LOCKED
            onChanged()
            return
        }
        deleteArmedId = null
        resolve(ref) { file ->
            val outcome = if (file == null) ShareOutcome.NO_IMAGE else how(file)
            status = when (outcome) {
                ShareOutcome.INSERTED -> StickerStatus.INSERTED
                ShareOutcome.SHARED -> StickerStatus.SHARED
                ShareOutcome.FAILED, ShareOutcome.NO_IMAGE -> StickerStatus.SEND_FAILED
            }
            onChanged()
            if (outcome == ShareOutcome.INSERTED) onInserted()
        }
    }

    /** The WebP file of [ref] (a bundled sticker is rendered on first use, off the main thread), or null. */
    private fun resolve(ref: StickerRef, then: (File?) -> Unit) {
        when (ref) {
            is StickerRef.Mine -> then(store.file(ref.id).takeIf { StickerStore.validId(ref.id) && it.isFile && it.length() > 0 })
            is StickerRef.Pack -> {
                val sticker = StickerPacks.find(ref.pack.key, ref.stickerId)
                if (sticker == null) {
                    then(null)
                    return
                }
                val file = store.packFile(ref.pack, ref.stickerId)
                if (file.isFile && file.length() > 0) {
                    then(file)
                    return
                }
                busy = true
                status = StickerStatus.WORKING
                onChanged()
                background(
                    Runnable {
                        val tmp = File(file.parentFile, file.name + ".tmp")
                        val ok = exporter.exportWebp(sticker.spec, tmp) && tmp.isFile && tmp.length() > 0 &&
                            (!file.exists() || file.delete()) && tmp.renameTo(file)
                        if (!ok) tmp.delete()
                        post(
                            Runnable {
                                busy = false
                                then(file.takeIf { ok })
                            },
                        )
                    },
                )
            }
        }
    }

    // ---- the maker -----------------------------------------------------------------------------------------------

    /** "Make": the maker opens on [src]. */
    fun startMake(src: StickerSource = source) {
        screen = StickerScreen.MAKE
        source = src
        selected = null
        deleteArmedId = null
        typing = null
        status = null
        onChanged()
    }

    fun selectSource(src: StickerSource) {
        source = src
        typing = null
        changed()
    }

    /** Back to the strip (the maker's choices are kept until the panel closes). */
    fun backToStrip() {
        screen = StickerScreen.STRIP
        typing = null
        status = null
        onChanged()
    }

    fun selectShape(s: StickerShape) {
        shape = s
        changed()
    }

    fun selectFill(i: Int) {
        fillIndex = i.coerceIn(0, StickerColours.FILLS.lastIndex)
        changed()
    }

    /** Picks a swatch for the text and caption. */
    fun selectInk(i: Int) {
        inkPicked = i.coerceIn(0, StickerColours.INK.lastIndex)
        changed()
    }

    fun selectSize(s: StickerTextSize) {
        size = s
        changed()
    }

    fun selectEmoji(e: String) {
        emoji = e
        changed()
    }

    fun setDrawingPaper(on: Boolean) {
        drawingPaper = on
        changed()
    }

    /** The drawing has something to make a sticker of. */
    val hasDrawing: Boolean get() = drawing()?.isEmpty == false

    /** The sticker the maker would save right now, or null when there is nothing to make. */
    fun spec(): StickerSpec? = when (source) {
        StickerSource.TEXT -> text.text.trim().takeIf { it.isNotEmpty() }
            ?.let { TextStickerSpec(it, ink, shape, StickerColours.FILLS[fillIndex], size) }
        StickerSource.EMOJI -> EmojiStickerSpec(emoji, caption.text.trim(), ink, shape, StickerColours.FILLS[fillIndex])
        StickerSource.DRAWING -> drawing()?.takeIf { !it.isEmpty }?.let { DrawingStickerSpec(it, drawingPaper) }
    }

    /** True when saving would be refused because the personal pack is full. */
    val full: Boolean get() = store.count() >= store.max

    /** Saves the sticker of the maker to the personal pack (render and encode off the main thread). */
    fun make() {
        if (busy) return
        val sp = spec()
        if (sp == null) {
            status = if (source == StickerSource.DRAWING) StickerStatus.NO_DRAWING else StickerStatus.NOTHING_TO_MAKE
            onChanged()
            return
        }
        if (full) {
            status = StickerStatus.FULL
            onChanged()
            return
        }
        busy = true
        status = StickerStatus.WORKING
        typing = null
        onChanged()
        background(
            Runnable {
                val result = store.add(sp.kind) { f -> exporter.exportWebp(sp, f) }
                post(
                    Runnable {
                        busy = false
                        status = when (result) {
                            is AddResult.Added -> StickerStatus.SAVED
                            AddResult.Full -> StickerStatus.FULL
                            AddResult.Failed -> StickerStatus.SAVE_FAILED
                        }
                        if (result is AddResult.Added) {
                            mine = store.list()
                            screen = StickerScreen.STRIP
                            tab = null
                            text.clear()
                            caption.clear()
                        }
                        onChanged()
                    },
                )
            },
        )
    }

    // ---- typing --------------------------------------------------------------------------------------------------

    /** The keys type into [field] (the panel gives way to the typing strip above the keys). */
    fun beginTyping(field: StickerField) {
        typing = field
        status = null
        onChanged()
    }

    /** Typing ended: the maker is back. The caller commits the open composition first (it goes into the field). */
    fun endTyping() {
        if (typing == null) return
        typing = null
        onChanged()
    }

    private val typingAdapter = object : FieldTyping {
        override val sink: TextSink get() = typingField?.sink ?: NoSink
        override val newlineOnEnter: Boolean get() = typing == StickerField.TEXT
        override fun done() = endTyping()
    }

    /** For `KeyboardController.fieldTyping`: non-null while the keys type into a maker field. */
    fun fieldTyping(): FieldTyping? = if (typing != null) typingAdapter else null

    /** The system Back: typing → maker, maker → strip, a selection → none. False when nothing was open (the panel closes). */
    fun back(): Boolean {
        if (typing != null) {
            endTyping()
            return true
        }
        if (screen == StickerScreen.MAKE) {
            backToStrip()
            return true
        }
        if (selected != null) {
            clearSelection()
            return true
        }
        return false
    }

    private fun changed(clearStatus: Boolean = true) {
        deleteArmedId = null
        if (clearStatus && status != StickerStatus.WORKING) status = null
        onChanged()
    }

    private object NoSink : TextSink {
        override fun apply(update: com.qalsmartkeyboard.ime.ComposerUpdate) {}
        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    companion object {
        /** The maker's emoji choices (the system's emoji font draws them; no emoji art is bundled). */
        val EMOJIS: List<String> = listOf(
            "😀", "😂", "😍", "😎", "🥳", "😢", "😡", "🤔", "👍", "🙏", "👏", "🔥",
            "❤️", "💯", "🎉", "🎂", "🌼", "☕", "🌍", "✨", "💪", "🙌", "😴", "🤗",
        )
    }
}
