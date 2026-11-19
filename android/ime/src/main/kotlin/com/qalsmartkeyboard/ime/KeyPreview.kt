package com.qalsmartkeyboard.ime

/**
 * Key press preview bubbles: which keys get one, what it shows, and where it goes. Pure functions
 * (no View code, no allocation beyond the result), so the geometry and the rules are unit tested.
 */
object KeyPreview {
    /**
     * The text the bubble shows for [key], or null when the key has none: only character keys (including
     * the Fidel consonant and order keys) preview; space, enter, shift, backspace, globe and the mode
     * and page keys never do. Shows exactly what the key types (shift upper-cases letters on the letters
     * page, as [KeyboardController.onKey] does); [orderFace] is the current face of a Fidel order key.
     * A key that types combining marks only (an Arabic harakah) shows its label, the mark on a dotted circle.
     */
    fun textFor(key: Key, shift: ShiftState, page: Page, orderFace: OrderFace?): String? = when (key.kind) {
        KeyKind.CHAR -> when {
            key.isSpacer || key.text.isBlank() -> null
            key.text.all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() } -> key.label
            shift != ShiftState.OFF && page == Page.LETTERS -> key.text.uppercase()
            else -> key.text
        }
        KeyKind.FIDEL_CONSONANT -> key.label.takeIf { it.isNotBlank() }
        KeyKind.FIDEL_VOWEL -> orderFace?.takeIf { it.active && it.label.isNotBlank() }?.label
        else -> null
    }

    /** Where the bubble goes: its top-left corner in the coordinates of the area it is drawn in. */
    class Placement(val left: Float, val top: Float)

    /**
     * The bubble of size [w] x [h] centred above the key spanning [keyLeft]..[keyRight] with top [keyTop],
     * [gap] clear of it. Clamped horizontally into 0..[areaWidth] so it is never cut off at a screen edge,
     * and vertically so it is never above [minTop] (the top of the drawable area): on the top key row the
     * bubble then rises into the transparent headroom the popups use, and where even that is too short it
     * stays inside the area, overlapping the key rather than being clipped.
     */
    fun place(keyLeft: Float, keyRight: Float, keyTop: Float, w: Float, h: Float, gap: Float, areaWidth: Float, minTop: Float): Placement {
        val centre = (keyLeft + keyRight) / 2f
        val left = (centre - w / 2f).coerceAtMost(areaWidth - w).coerceAtLeast(0f)
        val top = (keyTop - h - gap).coerceAtLeast(minTop)
        return Placement(left, top)
    }

    /** The bubble width for a key of width [keyWidth]: wider than the key (the finger covers it), never narrower than [minWidth]. */
    fun width(keyWidth: Float, minWidth: Float): Float = maxOf(keyWidth * 1.3f, minWidth)
}
