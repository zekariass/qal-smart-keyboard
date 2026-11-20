package com.qalsmartkeyboard.ime

import com.qalsmartkeyboard.data.InputMode
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.NumeralStyle

/**
 * Bridges the settings store (A1.5) to the controller: the enabled languages and every other
 * preference are read from [settings], a cached snapshot (never a disk read on the key path), and
 * the last native layout per language is written back through [saveLastMode].
 */
class SettingsPreferences(
    private val settings: () -> KeyboardSettings,
    /** Saves the selected writing mode of am/ti (`KeyboardSettings.writingModes`). */
    private val saveWritingMode: (language: String, mode: String) -> Unit = { _, _ -> },
    /** Saves a change of the settings made from the keyboard (ADR 0043: the Layout panel's choice). */
    private val saveSettings: (transform: (KeyboardSettings) -> KeyboardSettings) -> Unit = {},
    /** Last (a trailing lambda): saves the last native layout (`KeyboardSettings.lastModes`). */
    private val saveLastMode: (language: String, mode: String) -> Unit = { _, _ -> },
) : EnabledLanguages, ModePreferences {

    override fun enabled(): List<String> = settings().enabledLanguages

    override fun preferredKind(language: String): LayoutKind? =
        when (settings().language(language)?.inputMode) {
            InputMode.DIRECT_SCRIPT_LAYOUT -> LayoutKind.FIDEL
            InputMode.PHONETIC_LATIN_TO_NATIVE -> LayoutKind.PHONETIC
            else -> null // passthrough: the language's native Latin layout (Qubee/English) or ABC
        }?.takeIf { it in LanguageCatalog.nativeKinds(language) }

    override fun lastNative(language: String): LayoutKind? =
        settings().lastModes[language]?.let { n -> LayoutKind.entries.firstOrNull { it.name == n } }
            ?.takeIf { it != LayoutKind.ABC && it in LanguageCatalog.nativeKinds(language) }

    override fun setLastNative(language: String, kind: LayoutKind) {
        if (settings().lastModes[language] != kind.name) saveLastMode(language, kind.name)
    }

    override fun writingMode(language: String): LayoutKind? =
        settings().writingModes[language]?.let { n -> LayoutKind.entries.firstOrNull { it.name == n } }
            ?.takeIf { it in LanguageCatalog.writingModes(language) }

    override fun setWritingMode(language: String, kind: LayoutKind) {
        if (kind !in LanguageCatalog.writingModes(language)) return
        if (settings().writingModes[language] != kind.name) saveWritingMode(language, kind.name)
    }

    override fun showCandidates(language: String): Boolean =
        settings().language(language)?.showTransliterationCandidates ?: true

    override val nextWordPredictions: Boolean get() = settings().nextWordPredictions

    override fun autocorrect(language: String): Boolean = when (language) {
        "en" -> settings().autocorrectEnglish
        "om" -> settings().autocorrectOromo
        else -> false
    }

    override val learnWords: Boolean get() = settings().learnWords

    /** ADR 0043 / 0044: the effective style is ETHIOPIC only when the user picked it (Western first by default). */
    override val ethiopicNumerals: Boolean get() = settings().effectiveNumeralStyle == NumeralStyle.ETHIOPIC
    /**
     * Layout choices saved from the keyboard but not yet back from the store (value, and the stored value when it was
     * made): the keys use them at once, the store answers a moment later. Dropped as soon as the stored value changes
     * (our write landed, or the app changed it meanwhile). Main thread only, like every other read here.
     */
    private val pendingLatin = HashMap<String, Pair<String, String?>>()
    private val pendingFidel = HashMap<String, Pair<String, String?>>()

    private fun read(pending: HashMap<String, Pair<String, String?>>, stored: Map<String, String>, language: String): String? {
        val (value, base) = pending[language] ?: return stored[language]
        if (stored[language] != base) {
            pending.remove(language)
            return stored[language]
        }
        return value
    }

    override fun latinLayout(language: String): LatinLayout =
        LatinLayout.fromName(read(pendingLatin, settings().latinLayouts, language)) ?: LatinLayout.DEFAULT

    override fun setLatinLayout(language: String, layout: LatinLayout) {
        if (latinLayout(language) == layout) return
        pendingLatin[language] = layout.name to settings().latinLayouts[language]
        saveSettings { it.copy(latinLayouts = it.latinLayouts + (language to layout.name)) }
    }

    override fun fidelVariant(language: String): FidelVariant =
        FidelVariant.fromName(read(pendingFidel, settings().fidelLayouts, language)) ?: FidelVariant.DEFAULT

    override fun setFidelVariant(language: String, variant: FidelVariant) {
        if (fidelVariant(language) == variant) return
        pendingFidel[language] = variant.name to settings().fidelLayouts[language]
        saveSettings { it.copy(fidelLayouts = it.fidelLayouts + (language to variant.name)) }
    }

    override val numberRow: Boolean get() = settings().numberRow
    override val hapticsEnabled: Boolean get() = settings().hapticsEnabled
    override val keyPreviewEnabled: Boolean get() = settings().keyPreviewEnabled
    override val keyHeightScale: Float get() = settings().keyHeightScale
}
