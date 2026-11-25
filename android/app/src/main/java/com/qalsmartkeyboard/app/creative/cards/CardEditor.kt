package com.qalsmartkeyboard.app.creative.cards

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The editor's steps, in the order of the flow: template, text, background, style, export. */
enum class CardSection { TEMPLATE, TEXT, BACKGROUND, STYLE, EXPORT }

/**
 * The state of the card being edited (T8.7): the [CardDoc], the picked background photo (in memory, downscaled), which
 * gallery card it is (when it was opened or saved) and the open step. Plain state with no Android services, so its rules
 * (a template keeps text the user typed; limits; the photo flag) are JVM-tested. Compose reads the snapshot state.
 */
class CardEditor(initial: CardDoc) {
    var doc: CardDoc by mutableStateOf(initial)
        private set

    /** The picked background, downscaled; null for any other background. */
    var photo: Bitmap? by mutableStateOf(null)
        private set

    /** The photo is new since the last save (the store keeps its copy otherwise). */
    var photoDirty: Boolean = false
        private set

    /** The gallery card being edited; null for a card never saved. */
    var editingId: String? by mutableStateOf(null)
        private set

    var section: CardSection by mutableStateOf(CardSection.TEMPLATE)

    /** Something changed since the card was opened or saved. */
    var dirty: Boolean by mutableStateOf(false)
        private set

    private fun update(change: CardDoc.() -> CardDoc) {
        doc = doc.change()
        dirty = true
    }

    /**
     * Starts the card from [t]: its background, style and frame replace the current ones. The texts follow the template
     * only where the user has not written their own: a field that is empty or still the previous template's starting text
     * takes the new one's.
     */
    fun applyTemplate(t: CardTemplate, resolve: (Int) -> String) {
        val prev = CardTemplates.find(doc.templateId)?.sample
        fun pick(current: String, old: Int?, new: Int): String {
            val fresh = if (new == 0) "" else resolve(new)
            val untouched = current.isBlank() || (old != null && old != 0 && current == resolve(old))
            return if (untouched) fresh else current
        }
        val fresh = t.newDoc(resolve, doc.aspect, doc.format)
        doc = fresh.copy(
            headline = pick(doc.headline, prev?.headline, t.sample.headline),
            body = pick(doc.body, prev?.body, t.sample.body),
            from = pick(doc.from, prev?.from, t.sample.from),
        )
        if (doc.background !is CardBackground.Photo) clearPhoto()
        dirty = true
    }

    fun headline(text: String) = update { copy(headline = text.take(CardDoc.MAX_HEADLINE)) }
    fun body(text: String) = update { copy(body = text.take(CardDoc.MAX_BODY)) }
    fun from(text: String) = update { copy(from = text.take(CardDoc.MAX_FROM)) }

    fun background(b: CardBackground) {
        update { copy(background = b) }
        if (b !is CardBackground.Photo) clearPhoto()
    }

    /** Uses [bitmap] (already downscaled by [CardImages.loadPhoto]) as the background, keeping the current dimming. */
    fun usePhoto(bitmap: Bitmap) {
        val dim = (doc.background as? CardBackground.Photo)?.dimPercent ?: 30
        photo = bitmap
        photoDirty = true
        update { copy(background = CardBackground.Photo(null, dim)) }
    }

    fun dim(percent: Int) {
        val b = doc.background as? CardBackground.Photo ?: return
        update { copy(background = b.copy(dimPercent = percent.coerceIn(0, CardBackground.MAX_DIM))) }
    }

    fun font(f: CardFont) = update { copy(text = text.copy(font = f)) }
    fun sizeScale(s: Float) = update { copy(text = text.copy(sizeScale = s.coerceIn(CardTextStyle.MIN_SCALE, CardTextStyle.MAX_SCALE))) }
    fun color(c: Int) = update { copy(text = text.copy(color = c)) }
    fun align(a: CardTextAlign) = update { copy(text = text.copy(align = a)) }
    fun bold(on: Boolean) = update { copy(text = text.copy(bold = on)) }
    fun shadow(on: Boolean) = update { copy(text = text.copy(shadow = on)) }
    fun frame(f: CardFrame) = update { copy(frame = f) }
    fun aspect(a: CardAspect) = update { copy(aspect = a) }
    fun format(f: ExportFormat) = update { copy(format = f) }

    /** Opens a saved card (its [photo], when it has one, already loaded). */
    fun open(id: String, saved: CardDoc, photo: Bitmap?) {
        doc = saved
        replacePhoto(photo)
        photoDirty = false
        editingId = id
        dirty = false
        section = CardSection.TEXT
    }

    /** Starts a new card from [t]. */
    fun startNew(t: CardTemplate, resolve: (Int) -> String) {
        doc = t.newDoc(resolve)
        clearPhoto()
        editingId = null
        dirty = false
        section = CardSection.TEMPLATE
    }

    /** The card was saved as [id]: further saves replace it, and its photo is stored. */
    fun saved(id: String) {
        editingId = id
        photoDirty = false
        dirty = false
    }

    /** The gallery card being edited was deleted: what is on screen becomes a card that was never saved. */
    fun detach() {
        editingId = null
        photoDirty = photo != null
        dirty = true
    }

    private fun clearPhoto() = replacePhoto(null)

    private fun replacePhoto(next: Bitmap?) {
        photo = next
        photoDirty = false
    }
}
