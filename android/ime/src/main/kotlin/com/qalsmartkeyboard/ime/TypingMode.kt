package com.qalsmartkeyboard.ime

/** Which layout/engine behaviour is active inside a language (SD 7.1). */
enum class LayoutKind(val phonetic: Boolean, val latin: Boolean) {
    /** Direct Fidel grid (core `DirectFidel`): am, ti. */
    FIDEL(phonetic = false, latin = false),

    /** Latin QWERTY feeding the phonetic engine with the candidate strip: am, ti. */
    PHONETIC(phonetic = true, latin = true),

    /** Latin with the hudhaa key, passthrough (core `Qubee`): om. */
    QUBEE(phonetic = false, latin = true),

    /** Latin passthrough (core `English`): en. */
    ENGLISH(phonetic = false, latin = true),

    /** Temporary Latin passthrough in any language (core `Abc`). */
    ABC(phonetic = false, latin = true),
}

/** The language the keyboard is typing and how. */
data class TypingMode(val language: String, val kind: LayoutKind) {
    val phonetic: Boolean get() = kind.phonetic
    val isAbc: Boolean get() = kind == LayoutKind.ABC

    companion object {
        fun abc(language: String) = TypingMode(language, LayoutKind.ABC)
    }
}

/** Static facts about the four launch languages (SD 7.1). Names/labels are DRAFT UI strings. */
object LanguageCatalog {
    /** Native (non-ABC) layouts per language, in long-press-globe cycle order. */
    fun nativeKinds(language: String): List<LayoutKind> = when (language) {
        "am", "ti" -> listOf(LayoutKind.FIDEL, LayoutKind.PHONETIC)
        "om" -> listOf(LayoutKind.QUBEE)
        "en" -> listOf(LayoutKind.ENGLISH)
        else -> emptyList()
    }

    /** Languages written in Ge'ez script: Ethiopic punctuation and numerals apply (SD 7.2). */
    fun isEthiopic(language: String) = language == "am" || language == "ti"

    /** Short tag for the mode indicator, e.g. `አማ`. */
    fun shortLabel(language: String) = when (language) {
        "am" -> "አማ"
        "ti" -> "ትግ"
        "om" -> "OM"
        "en" -> "EN"
        else -> language.uppercase()
    }

    /** Name shown in the language picker. */
    fun displayName(language: String) = when (language) {
        "am" -> "አማርኛ"
        "ti" -> "ትግርኛ"
        "om" -> "Afaan Oromoo"
        "en" -> "English"
        else -> language
    }

    /** Spoken name for accessibility. */
    fun spokenName(language: String) = when (language) {
        "am" -> "Amharic"
        "ti" -> "Tigrinya"
        "om" -> "Afaan Oromoo"
        "en" -> "English"
        else -> language
    }

    fun kindLabel(kind: LayoutKind) = when (kind) {
        LayoutKind.FIDEL -> "ፊደል"
        LayoutKind.PHONETIC -> "QWERTY"
        LayoutKind.QUBEE -> "Qubee"
        LayoutKind.ENGLISH -> "QWERTY"
        LayoutKind.ABC -> "ABC"
    }

    fun spokenKind(kind: LayoutKind) = when (kind) {
        LayoutKind.FIDEL -> "Fidel grid"
        LayoutKind.PHONETIC -> "phonetic Latin keys"
        LayoutKind.QUBEE -> "Qubee"
        LayoutKind.ENGLISH -> "QWERTY"
        LayoutKind.ABC -> "ABC Latin"
    }

    /**
     * The mode indicator (SD 7.1, e.g. `አማ · ABC→አ`): language tag, then the layout; in ABC the
     * arrow names where the one-tap ABC toggle goes back to.
     */
    fun indicator(mode: TypingMode, backTo: LayoutKind?): String {
        val tag = shortLabel(mode.language)
        return when {
            mode.isAbc && backTo != null && mode.language != "en" -> "$tag · ABC→${nativeGlyph(mode.language)}"
            mode.isAbc -> "$tag · ABC"
            else -> "$tag · ${kindLabel(mode.kind)}"
        }
    }

    /** One glyph naming the language's native mode on the ABC toggle key. */
    fun nativeGlyph(language: String) = when (language) {
        "am", "ti" -> "አ"
        "om" -> "Qu"
        else -> "EN"
    }
}

/**
 * The languages the user enabled, in switching order. A1.5's settings store implements this; until
 * then [DefaultEnabledLanguages] is used.
 */
fun interface EnabledLanguages {
    fun enabled(): List<String>
}

object DefaultEnabledLanguages : EnabledLanguages {
    override fun enabled() = listOf("am", "ti", "om", "en")
}

/** Direct-Fidel layout data, copied from the core's `FidelLayout` so the UI model does not depend on FFI types. */
data class GridForm(val order: Int, val label: String, val text: String)

data class GridKey(val seriesId: String, val label: String, val tapText: String, val forms: List<GridForm>)

data class GridVowel(val order: Int, val label: String)

data class FidelGrid(
    val language: String,
    val layoutId: String,
    val draft: Boolean,
    val longPress: Boolean,
    val twoKey: Boolean,
    val defaultOrder: Int,
    val rows: List<List<GridKey>>,
    val vowelKeys: List<GridVowel>,
    val punctuation: List<String>,
    /** ASCII digit -> Ethiopic numeral alternatives. */
    val digits: Map<String, List<String>>,
)
