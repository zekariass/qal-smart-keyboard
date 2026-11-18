package com.qalsmartkeyboard.ime

/**
 * Where a key's label and its "DRAFT" tag (N9) go, so the two never overlap at any row height (pure,
 * JVM-tested). Coordinates are px relative to the key's top-left corner; x values are centres (the
 * surface draws both texts centred), y values are baselines.
 *
 * - **Stacked** (portrait rows): the tag above the label, the pair centred vertically.
 * - **Beside** (short landscape rows): label and tag on one line, the pair centred; the label shrinks
 *   (down to [MIN_LABEL_SCALE]) to leave room.
 * - **Hidden**: a key too small for either. The draft state is still in the key's spoken
 *   description and on the candidate strip's DRAFT tag.
 */
object DraftTagLayout {
    /** A text at the size it would be drawn: its advance [width] and the font's [ascent] (negative) and [descent]. */
    data class Text(val width: Float, val ascent: Float, val descent: Float) {
        val height: Float get() = descent - ascent
    }

    enum class Mode { STACKED, BESIDE, HIDDEN }

    data class Placement(
        val mode: Mode,
        val labelX: Float,
        val labelBaseline: Float,
        /** Multiply the label's text size by this (1 = unchanged). */
        val labelScale: Float,
        val tagX: Float,
        val tagBaseline: Float,
    ) {
        val showsTag: Boolean get() = mode != Mode.HIDDEN
    }

    /** Smallest the label may shrink to make room for the tag beside it. */
    const val MIN_LABEL_SCALE = 0.7f

    /**
     * [keyW] x [keyH] is the key, [label] and [tag] the texts at full size, [pad] the inner margin
     * kept free on every side, [gap] the space between label and tag.
     */
    fun place(keyW: Float, keyH: Float, label: Text, tag: Text, pad: Float, gap: Float): Placement {
        val innerW = keyW - 2 * pad
        val cx = keyW / 2
        // Stacked: both fit one above the other.
        val labelScaleStacked = if (label.width > innerW && label.width > 0f) innerW / label.width else 1f
        val stackH = tag.height + gap + label.height * labelScaleStacked
        if (stackH <= keyH - 2 * pad && tag.width <= innerW) {
            val top = (keyH - stackH) / 2
            return Placement(
                Mode.STACKED,
                labelX = cx,
                labelBaseline = top + tag.height + gap - label.ascent * labelScaleStacked,
                labelScale = labelScaleStacked,
                tagX = cx,
                tagBaseline = top - tag.ascent,
            )
        }
        // Beside: one line, centred; the label gives up width first.
        val roomForLabel = innerW - gap - tag.width
        val scale = when {
            label.width <= 0f -> 1f
            label.width <= roomForLabel -> 1f
            else -> roomForLabel / label.width
        }
        val fitsHeight = maxOf(label.height * scale, tag.height) <= keyH
        if (scale >= MIN_LABEL_SCALE && fitsHeight) {
            val lw = label.width * scale
            val left = (keyW - (lw + gap + tag.width)) / 2
            val cy = keyH / 2
            return Placement(
                Mode.BESIDE,
                labelX = left + lw / 2,
                labelBaseline = cy - (label.ascent + label.descent) * scale / 2,
                labelScale = scale,
                tagX = left + lw + gap + tag.width / 2,
                tagBaseline = cy - (tag.ascent + tag.descent) / 2,
            )
        }
        val s = if (label.width > innerW && label.width > 0f) innerW / label.width else 1f
        return Placement(Mode.HIDDEN, cx, keyH / 2 - (label.ascent + label.descent) * s / 2, s, cx, 0f)
    }

    /** The label's box (left, top, right, bottom) for a placement: used to prove there is no overlap. */
    fun labelBox(p: Placement, label: Text): FloatArray {
        val w = label.width * p.labelScale
        return floatArrayOf(p.labelX - w / 2, p.labelBaseline + label.ascent * p.labelScale, p.labelX + w / 2, p.labelBaseline + label.descent * p.labelScale)
    }

    /** The tag's box (left, top, right, bottom). */
    fun tagBox(p: Placement, tag: Text): FloatArray =
        floatArrayOf(p.tagX - tag.width / 2, p.tagBaseline + tag.ascent, p.tagX + tag.width / 2, p.tagBaseline + tag.descent)
}
