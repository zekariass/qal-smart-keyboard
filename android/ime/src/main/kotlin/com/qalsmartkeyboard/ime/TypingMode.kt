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

    /**
     * Latin passthrough in any language (core `Abc`). For am/ti this is the **English** writing mode
     * (remembered like Fidel and Phonetic); for om it is the temporary ABC toggle.
     */
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

/** Static facts about the four launch languages (SD 7.1). UI words come from [KeyboardLabels] (the app language, ADR 0032). */
object LanguageCatalog {
    /** Native (non-ABC) layouts per language, in long-press-globe cycle order; the first is the first-run default (ADR 0007: phonetic QWERTY). */
    fun nativeKinds(language: String): List<LayoutKind> = when (language) {
        "am", "ti" -> listOf(LayoutKind.PHONETIC, LayoutKind.FIDEL)
        "om" -> listOf(LayoutKind.QUBEE)
        "en" -> listOf(LayoutKind.ENGLISH)
        else -> emptyList()
    }

    /** Languages written in Ge'ez script: Ethiopic punctuation and numerals apply (SD 7.2). */
    fun isEthiopic(language: String) = language == "am" || language == "ti"

    /**
     * The writing modes the mode selector offers, in selector order: am/ti have Fidel, Phonetic and
     * English (= [LayoutKind.ABC], Latin with no transliteration); om and en have a single mode and no
     * selector. Language and writing mode are separate: the language stays am/ti in English mode.
     */
    fun writingModes(language: String): List<LayoutKind> =
        if (isEthiopic(language)) listOf(LayoutKind.FIDEL, LayoutKind.PHONETIC, LayoutKind.ABC) else emptyList()

    /** The selector's name of a writing mode (am/ti). */
    fun writingModeLabel(kind: LayoutKind) = when (kind) {
        LayoutKind.FIDEL -> "ፊደል"
        LayoutKind.PHONETIC -> KeyboardLabels.current.modePhonetic
        LayoutKind.ABC -> KeyboardLabels.current.modeEnglish
        else -> kindLabel(kind)
    }

    /** Spoken name of a writing mode (am/ti). */
    fun spokenWritingMode(kind: LayoutKind) = when (kind) {
        LayoutKind.FIDEL -> KeyboardLabels.current.spokenFidel
        LayoutKind.PHONETIC -> KeyboardLabels.current.modePhonetic
        LayoutKind.ABC -> KeyboardLabels.current.modeEnglish
        else -> spokenKind(kind)
    }

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

    /** The language's name in the UI language (spoken for accessibility, and the AI panels' language names). */
    fun spokenName(language: String) = KeyboardLabels.current.language(language)

    fun kindLabel(kind: LayoutKind) = when (kind) {
        LayoutKind.FIDEL -> "ፊደል"
        LayoutKind.PHONETIC -> KeyboardLabels.current.modePhonetic
        LayoutKind.QUBEE -> "Qubee"
        LayoutKind.ENGLISH -> "QWERTY"
        LayoutKind.ABC -> "ABC"
    }

    fun spokenKind(kind: LayoutKind) = when (kind) {
        LayoutKind.FIDEL -> KeyboardLabels.current.spokenFidelGrid
        LayoutKind.PHONETIC -> KeyboardLabels.current.spokenPhoneticKeys
        LayoutKind.QUBEE -> "Qubee"
        LayoutKind.ENGLISH -> "QWERTY"
        LayoutKind.ABC -> KeyboardLabels.current.spokenAbcLatin
    }

    /**
     * The mode indicator (SD 7.1, e.g. `አማ · Phonetic`): language tag, then the layout or writing
     * mode. am/ti name their English writing mode; elsewhere ABC names where the one-tap ABC toggle
     * goes back to (`OM · ABC→Qu`).
     */
    fun indicator(mode: TypingMode, backTo: LayoutKind?): String {
        val tag = shortLabel(mode.language)
        return when {
            mode.isAbc && isEthiopic(mode.language) -> "$tag · ${writingModeLabel(LayoutKind.ABC)}"
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

/**
 * The rest of the user's keyboard settings that the controller and surface use (A1.5). Reads must be
 * cheap and non-blocking (a cached snapshot); writes are fire-and-forget.
 */
interface ModePreferences {
    /** The layout to start a language on when it has no remembered one (the language's input-mode setting). */
    fun preferredKind(language: String): LayoutKind? = null

    /** The last native layout used in [language]. */
    fun lastNative(language: String): LayoutKind? = null

    fun setLastNative(language: String, kind: LayoutKind) {}

    /**
     * The writing mode last selected in [language] (am/ti: Fidel, Phonetic or English = ABC), or null
     * when none was saved yet (then [lastNative] and [preferredKind] decide).
     */
    fun writingMode(language: String): LayoutKind? = null

    fun setWritingMode(language: String, kind: LayoutKind) {}

    /** Whether the candidate strip is shown for [language]'s phonetic mode. */
    fun showCandidates(language: String): Boolean = true

    /** numeralStyle ETHIOPIC: offer Ethiopic numerals as digit alternatives. */
    val ethiopicNumerals: Boolean get() = false

    val hapticsEnabled: Boolean get() = true

    /** Key press preview bubbles (never in password fields, see [KeyboardController.keyPreviewsActive]). */
    val keyPreviewEnabled: Boolean get() = true

    /** Key height multiplier (1.0 = default). */
    val keyHeightScale: Float get() = 1f
}

/** In-memory preferences: used by tests and before settings load. */
open class InMemoryModePreferences : ModePreferences {
    private val last = HashMap<String, LayoutKind>()
    private val writing = HashMap<String, LayoutKind>()
    override fun lastNative(language: String) = last[language]
    override fun setLastNative(language: String, kind: LayoutKind) { last[language] = kind }
    override fun writingMode(language: String) = writing[language]
    override fun setWritingMode(language: String, kind: LayoutKind) { writing[language] = kind }
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
