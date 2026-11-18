package com.qalsmartkeyboard.ime.theme

/** Who may use a theme: everyone, or accounts with the `PREMIUM_THEMES` entitlement. */
enum class ThemeTier { FREE, PREMIUM }

/**
 * One keyboard theme: every colour and shape the views draw with, as plain values. The views read these
 * (never a resource lookup or an allocation on the draw path); the app's theme picker draws its miniature
 * previews from the same tokens, so a preview always shows what the keyboard will look like.
 *
 * Colours are ARGB ints. Adding a theme means adding an entry to [ThemeCatalog]; no view or layout changes.
 * `ThemeContrastTest` holds every entry to the contrast rules (WCAG 2.x relative luminance).
 *
 * @property id stable id, at most 40 characters, stored in the settings and synced as `keyboardTheme`
 * @property isLight the theme is a light one: picks the fallback (Classic Light / Dark) when it is not available
 *   and the dark/light appearance of the navigation bar icons
 * @property bgTop background colour (the top stop of the gradient when [bgBottom] differs)
 * @property bgBottom bottom stop of the vertical background gradient; equal to [bgTop] for a solid background
 * @property key fill of character keys and the space bar
 * @property special fill of function keys (shift, backspace, mode keys, ...)
 * @property pressed fill of a pressed key and of an active shift
 * @property accent fill of the Enter key and of the highlighted popup item
 * @property onAccent label colour on [accent]
 * @property accentText [accent] used as a label colour on [key] and on the popup panel (DRAFT tag, selected item)
 * @property keyText label colour on [key]
 * @property specialText label and icon colour on [special] keys
 * @property hintText secondary labels (phonetic hints, space bar, disabled text); readable on every fill
 * @property border key outline colour, drawn when [borderWidthDp] > 0
 * @property shadow colour of the one-step offset shadow under each key (alpha included), drawn when [shadowDyDp] > 0
 * @property stripBg candidate strip and AI toolbar background
 * @property stripText candidate and toolbar text
 * @property stripDivider divider between candidates
 * @property warn caution text (DRAFT tag, offline notice) on the strip and the background
 * @property popupBg long-press popup panel
 * @property popupText popup item text
 * @property popupBorder popup panel edge
 */
class KeyboardTheme(
    val id: String,
    val name: String,
    val tier: ThemeTier,
    val isLight: Boolean,
    val bgTop: Int,
    val bgBottom: Int,
    val key: Int,
    val special: Int,
    val pressed: Int,
    val accent: Int,
    val onAccent: Int,
    val accentText: Int,
    val keyText: Int,
    val specialText: Int,
    val hintText: Int,
    val border: Int,
    val borderWidthDp: Float,
    val shadow: Int,
    val shadowDyDp: Float,
    val cornerRadiusDp: Float,
    val stripBg: Int,
    val stripText: Int,
    val stripDivider: Int,
    val warn: Int,
    val popupBg: Int,
    val popupText: Int,
    val popupBorder: Int,
) {
    /** True when the background is a vertical gradient from [bgTop] to [bgBottom]. */
    val isGradient: Boolean get() = bgTop != bgBottom

    val isPremium: Boolean get() = tier == ThemeTier.PREMIUM

    val hasBorder: Boolean get() = borderWidthDp > 0f && (border ushr 24) != 0
    val hasShadow: Boolean get() = shadowDyDp > 0f && (shadow ushr 24) != 0

    /** Every colour the background is drawn with (one for a solid background, else both gradient stops). */
    val backgroundStops: IntArray get() = if (isGradient) intArrayOf(bgTop, bgBottom) else intArrayOf(bgTop)

    override fun toString() = "KeyboardTheme($id)"
}
