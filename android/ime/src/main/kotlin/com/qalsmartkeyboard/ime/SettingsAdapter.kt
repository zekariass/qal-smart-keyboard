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

    override val ethiopicNumerals: Boolean get() = settings().numeralStyle == NumeralStyle.ETHIOPIC
    override val hapticsEnabled: Boolean get() = settings().hapticsEnabled
    override val keyPreviewEnabled: Boolean get() = settings().keyPreviewEnabled
    override val keyHeightScale: Float get() = settings().keyHeightScale
}
