package com.qalsmartkeyboard.ime

/**
 * The arrangement of the Latin letter keys (ADR 0043), chosen per language: it applies to every Latin layout of that
 * language (English, Afaan Oromoo's Qubee, the Phonetic and English writing modes of Amharic and Tigrinya). Each one
 * is a layout people already type on elsewhere, so the choice carries their muscle memory; none is invented here.
 *
 * [rows] are the three letter rows, lower case, left to right. Every arrangement has the 26 letters exactly once
 * (Dvorak's top row also has `'` `,` `.`, where Dvorak typists expect them). Transliteration works by character, so
 * the phonetic engine, the hints and the dictionary behave the same on any arrangement.
 *
 * The names are proper names of the layouts and are not translated.
 */
enum class LatinLayout(val label: String, val rows: List<String>) {
    /** The default everywhere. */
    QWERTY("QWERTY", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),

    /** French and Belgian keyboards: A and Z swapped with Q and W, M at the end of the middle row. */
    AZERTY("AZERTY", listOf("azertyuiop", "qsdfghjklm", "wxcvbn")),

    /** German, Austrian, Swiss and most Central European keyboards: Z and Y swapped. */
    QWERTZ("QWERTZ", listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")),

    /** Dvorak Simplified Keyboard: the vowels and the most used consonants on the middle row. */
    DVORAK("Dvorak", listOf("',.pyfgcrl", "aoeuidhtns", "qjkxbmwvz")),

    /** Colemak: QWERTY with the most used letters moved to the middle row (Z X C V and most of the top row stay). */
    COLEMAK("Colemak", listOf("qwfpgjluy", "arstdhneio", "zxcvbkm")),
    ;

    /** Whether the arrangement already has an apostrophe key (Afaan Oromoo's hudhaa needs one on the letters page). */
    val hasApostrophe: Boolean get() = rows.any { '\'' in it }

    companion object {
        val DEFAULT = QWERTY

        fun fromName(name: String?): LatinLayout? = entries.firstOrNull { it.name == name }
    }
}

/**
 * How the direct Fidel grid of Amharic and Tigrinya is typed (ADR 0043), both from the same layout pack (whose
 * `interaction` declares LONG_PRESS and TWO_KEY, `docs/specs/keyboard-layout-v1.md`), so no new layout data is needed.
 */
enum class FidelVariant {
    /** The default (ADR 0007): tap a consonant, then a vowel-order key; long-press a consonant for all its forms. */
    TWO_KEY,

    /**
     * No vowel-order row: a tap types the sixth-order form (ስ, the consonant on its own and the most frequent order in
     * running text), long-press offers the other orders. One row fewer, so the keys are taller.
     */
    LONG_PRESS,
    ;

    companion object {
        val DEFAULT = TWO_KEY

        fun fromName(name: String?): FidelVariant? = entries.firstOrNull { it.name == name }
    }
}

/** One choice of the Layout panel (and of the companion app's language page). */
sealed interface KeyLayoutChoice {
    data class Latin(val layout: LatinLayout) : KeyLayoutChoice

    data class Fidel(val variant: FidelVariant) : KeyLayoutChoice
}

/** A layout choice and whether it is the one in force. */
data class LayoutOption(val choice: KeyLayoutChoice, val selected: Boolean)

object KeyLayouts {
    /** Whether [language] has Latin letter layouts whose arrangement can be chosen (all but Arabic, ADR 0034). */
    fun hasLatinLayouts(language: String): Boolean =
        LanguageCatalog.hasAbcLayout(language) || LanguageCatalog.nativeKinds(language).any { it.latin }

    /** Whether [language] has the Fidel grid (am, ti). */
    fun hasFidel(language: String): Boolean = LayoutKind.FIDEL in LanguageCatalog.nativeKinds(language)

    /**
     * The grid as the user types it: [FidelVariant.LONG_PRESS] drops the two-key entry (a tap types the default form)
     * when the layout pack offers long-press; otherwise the pack's own interaction stays.
     */
    fun apply(grid: FidelGrid, variant: FidelVariant): FidelGrid =
        if (variant == FidelVariant.LONG_PRESS && grid.longPress && grid.twoKey) grid.copy(twoKey = false) else grid

    /** The Fidel variants [grid] can be typed with (both only when the pack declares both interactions). */
    fun fidelVariants(grid: FidelGrid?): List<FidelVariant> =
        if (grid != null && grid.longPress && grid.twoKey) FidelVariant.entries else emptyList()
}
