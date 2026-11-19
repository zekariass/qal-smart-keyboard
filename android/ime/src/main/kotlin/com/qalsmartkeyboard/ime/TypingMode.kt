package com.qalsmartkeyboard.ime

/** Which layout/engine behaviour is active inside a language (SD 7.1). */
enum class LayoutKind(val phonetic: Boolean, val latin: Boolean) {
    /** Direct Fidel grid (core `DirectFidel`): am, ti. */
    FIDEL(phonetic = false, latin = false),

    /** Latin QWERTY feeding the phonetic engine with the candidate strip: am, ti. */
    PHONETIC(phonetic = true, latin = true),

    /** Latin with the hudhaa key (core `Qubee`): om. Typed text is committed as typed; the word being typed gets dictionary suggestions. */
    QUBEE(phonetic = false, latin = true),

    /** Latin passthrough (core `English`): en. */
    ENGLISH(phonetic = false, latin = true),

    /** The Arabic letter layout, passthrough in logical order (core `Arabic`, ADR 0034): ar. No shift, no candidates. */
    ARABIC(phonetic = false, latin = false),

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

/**
 * Static facts about the typing languages: the four launch languages (SD 7.1) and Arabic (ADR 0034). UI words come from
 * [KeyboardLabels] (the app language, ADR 0032).
 */
object LanguageCatalog {
    /** Native (non-ABC) layouts per language, in long-press-globe cycle order; the first is the first-run default (ADR 0007: phonetic QWERTY). */
    fun nativeKinds(language: String): List<LayoutKind> = when (language) {
        "am", "ti" -> listOf(LayoutKind.PHONETIC, LayoutKind.FIDEL)
        "om" -> listOf(LayoutKind.QUBEE)
        "en" -> listOf(LayoutKind.ENGLISH)
        "ar" -> listOf(LayoutKind.ARABIC)
        else -> emptyList()
    }

    /** Languages written right to left (ADR 0034): text is committed in logical order and the editor lays it out. */
    fun isRtl(language: String) = language == "ar"

    /**
     * Whether the language has the Latin ABC layout as a writing choice in text fields (the one-tap ABC key and the
     * last stop of the globe long-press cycle). Arabic has none, like Gboard: Latin text is one globe tap away in
     * English, and a Latin "Arabic" layout would only mislabel the text (ADR 0034).
     */
    fun hasAbcLayout(language: String) = language != "ar"

    /**
     * Languages whose words the personal dictionary learns (A1.8): those with a core dictionary, and Arabic, whose
     * suggestions are the user's own words only (no licensed Arabic list, N10; ADR 0039).
     */
    fun learnsWords(language: String) = language in com.qalsmartkeyboard.ime.userdict.UserDictionary.LANGUAGES

    /**
     * Modes with a candidate strip (ADR 0039): am/ti Phonetic (transliteration alternatives and dictionary words) and
     * Fidel (completions of the word typed so far), Qubee and English (dictionary words, autocorrection), Arabic (the
     * user's own words), each with next-word predictions. ABC (Latin text in another language) has none.
     */
    fun offersSuggestions(mode: TypingMode) = !mode.isAbc

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
        "ar" -> "ع"
        else -> language.uppercase()
    }

    /** Name shown in the language picker. */
    fun displayName(language: String) = when (language) {
        "am" -> "አማርኛ"
        "ti" -> "ትግርኛ"
        "om" -> "Afaan Oromoo"
        "en" -> "English"
        "ar" -> "العربية"
        else -> language
    }

    /** The language's name in the UI language (spoken for accessibility, and the AI panels' language names). */
    fun spokenName(language: String) = KeyboardLabels.current.language(language)

    fun kindLabel(kind: LayoutKind) = when (kind) {
        LayoutKind.FIDEL -> "ፊደል"
        LayoutKind.PHONETIC -> KeyboardLabels.current.modePhonetic
        LayoutKind.QUBEE -> "Qubee"
        LayoutKind.ENGLISH -> "QWERTY"
        LayoutKind.ARABIC -> "العربية"
        LayoutKind.ABC -> "ABC"
    }

    fun spokenKind(kind: LayoutKind) = when (kind) {
        LayoutKind.FIDEL -> KeyboardLabels.current.spokenFidelGrid
        LayoutKind.PHONETIC -> KeyboardLabels.current.spokenPhoneticKeys
        LayoutKind.QUBEE -> "Qubee"
        LayoutKind.ENGLISH -> "QWERTY"
        LayoutKind.ARABIC -> KeyboardLabels.current.spokenArabicKeys
        LayoutKind.ABC -> KeyboardLabels.current.spokenAbcLatin
    }

    /**
     * The mode indicator (SD 7.1, e.g. `አማ · Phonetic`): language tag, then the layout or writing
     * mode. am/ti name their English writing mode; elsewhere ABC names where the one-tap ABC toggle
     * goes back to (`OM · ABC→Qu`). Arabic shows `ع · العربية` (drawn right to left as one Arabic run).
     */
    fun indicator(mode: TypingMode, backTo: LayoutKind?, latin: LatinLayout = LatinLayout.DEFAULT): String {
        val tag = shortLabel(mode.language)
        return when {
            // ADR 0043: English names its key arrangement (EN · QWERTY, EN · Dvorak).
            mode.kind == LayoutKind.ENGLISH -> "$tag · ${latin.label}"
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
        "ar" -> "ع"
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

    /** Next-word predictions after a word (ADR 0039). */
    val nextWordPredictions: Boolean get() = true

    /** Autocorrection in [language] (ADR 0039): English on, Afaan Oromoo off until its lexicon is reviewed; others never. */
    fun autocorrect(language: String): Boolean = language == "en"

    /** Words typed are learned into the personal dictionary ("Learn from my typing", ADR 0039). */
    val learnWords: Boolean get() = true

    /**
     * numeralStyle ETHIOPIC (ADR 0043): am/ti typed natively (Fidel, Phonetic) put Ethiopic numerals on the digit keys,
     * Western digits on long-press. The product default (Ethiopic) comes from the settings ([SettingsPreferences]); this
     * in-memory default stays Western so the plain layout tests keep ASCII digit keys.
     */
    val ethiopicNumerals: Boolean get() = false

    /** ADR 0043: the Latin key arrangement of [language] (all its Latin layouts). */
    fun latinLayout(language: String): LatinLayout = LatinLayout.DEFAULT

    fun setLatinLayout(language: String, layout: LatinLayout) {}

    /** ADR 0043: how [language]'s Fidel grid is typed. */
    fun fidelVariant(language: String): FidelVariant = FidelVariant.DEFAULT

    fun setFidelVariant(language: String, variant: FidelVariant) {}

    /** ADR 0043: the digit row above the letters (off: digits on long-press of the top letter row, and on `?123`). */
    val numberRow: Boolean get() = true

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
    private val latin = HashMap<String, LatinLayout>()
    private val fidel = HashMap<String, FidelVariant>()
    override fun latinLayout(language: String) = latin[language] ?: LatinLayout.DEFAULT
    override fun setLatinLayout(language: String, layout: LatinLayout) { latin[language] = layout }
    override fun fidelVariant(language: String) = fidel[language] ?: FidelVariant.DEFAULT
    override fun setFidelVariant(language: String, variant: FidelVariant) { fidel[language] = variant }
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
