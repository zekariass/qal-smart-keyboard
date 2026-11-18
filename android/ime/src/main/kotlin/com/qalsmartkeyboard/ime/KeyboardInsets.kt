package com.qalsmartkeyboard.ime

import kotlin.math.max
import kotlin.math.min

/** Padding in px that keeps every key clear of the navigation bar and display cutouts. */
data class InsetPadding(val left: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    companion object {
        val NONE = InsetPadding()
    }
}

/**
 * Pure inset and size rules of the input view (JVM-tested).
 *
 * Since Android 15 an IME targeting SDK 35+ is laid out edge to edge: its window reaches the bottom
 * of the screen, under the (transparent) navigation bar, and nothing pads the input view. The
 * keyboard therefore applies the navigation-bar insets itself (bottom with gesture or 3-button
 * navigation in portrait, a side with 3-button navigation in landscape) and the display-cutout sides
 * (landscape notches) as padding, while its background keeps running under those areas. On older
 * releases, or where the system already fits the window, the dispatched insets are zero (consumed by
 * the decor), so the same rule never pads twice.
 */
object KeyboardInsets {
    /**
     * [nav] and [cutout] are the navigation-bar and display-cutout insets as (left, top, right,
     * bottom) px. The top is ignored: the keyboard's top edge never meets a system bar.
     * [imeNavBarPx] is the height of the navigation-bar strip the system draws inside the IME window
     * itself (Android 13+: the IME back / switcher buttons). The window provides that strip's insets
     * to other windows, so it never receives them; on a landscape phone with 3-button navigation the
     * strip is at the bottom while the system bar is on the side and the bottom inset is 0.
     */
    fun padding(nav: IntArray, cutout: IntArray, imeNavBarPx: Int = 0): InsetPadding {
        require(nav.size == 4 && cutout.size == 4)
        return InsetPadding(
            left = max(nav[0], cutout[0]).coerceAtLeast(0),
            right = max(nav[2], cutout[2]).coerceAtLeast(0),
            bottom = maxOf(nav[3], cutout[3], imeNavBarPx).coerceAtLeast(0),
        )
    }

    /**
     * The window-relative top of the area the app must keep clear (content/visible insets): the
     * bottom of the transparent popup headroom. Null while the view is not laid out (keep the
     * system's values).
     */
    /**
     * Height of the transparent popup headroom: [maxPx] when the window has room, else what is
     * left of [availablePx] once the keyboard ([keyboardPx]) fits, never negative. The keyboard
     * itself always gets its full height (landscape phones are too short for both).
     */
    fun headroomPx(maxPx: Int, availablePx: Int, keyboardPx: Int): Int =
        (availablePx - keyboardPx).coerceIn(0, maxOf(0, maxPx))

    fun contentTop(rootHeight: Int, headroomTopInWindow: Int, headroomHeight: Int): Int? =
        if (rootHeight <= 0) null else headroomTopInWindow + headroomHeight
}

/** Key-row height rules: responsive to orientation and screen size, honouring the key-height setting. */
object KeyboardMetrics {
    /** Smallest row height (dp) a key may shrink to: still a usable touch target. */
    const val MIN_ROW_DP = 30f

    /** Landscape rows are shorter (wide, short screens). */
    const val LANDSCAPE_FACTOR = 0.75f

    /** At most this share of the screen height goes to the key rows (the app must stay usable). */
    const val PORTRAIT_SHARE = 0.48f
    const val LANDSCAPE_SHARE = 0.42f

    /**
     * Height (dp) of one key row. [rowDp] is the layout's row height already multiplied by the
     * user's key-height [scale]; the fit to the screen is computed on the unscaled height and the
     * scale applied after it, so the setting changes the keyboard in every orientation.
     */
    fun rowHeightDp(rowDp: Float, scale: Float, rows: Int, screenWidthDp: Int, screenHeightDp: Int): Float {
        val s = if (scale > 0f) scale else 1f
        val landscape = screenWidthDp > screenHeightDp
        var base = rowDp / s
        if (landscape) base *= LANDSCAPE_FACTOR
        if (rows > 0 && screenHeightDp > 0) {
            val budget = screenHeightDp * (if (landscape) LANDSCAPE_SHARE else PORTRAIT_SHARE)
            base = min(base, budget / rows)
        }
        return max(base * s, MIN_ROW_DP)
    }
}
