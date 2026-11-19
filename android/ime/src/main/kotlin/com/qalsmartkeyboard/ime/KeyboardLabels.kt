package com.qalsmartkeyboard.ime

import android.content.Context
import java.util.Locale

/**
 * The UI words the keyboard's pure code needs (layouts, controller, [LanguageCatalog], the AI language names): key
 * descriptions for TalkBack, the spoken language and writing-mode names, the writing-mode selector's labels (ADR 0032).
 *
 * That code has no [Context], so the service (and the companion app's activity) loads the app-language resources into
 * [current] with [load]; until then, and in plain JVM tests, the English defaults apply. The English resource values
 * equal [ENGLISH] (UiLocalisationSmokeTest checks it). Typing languages are not affected: only the UI language is.
 */
data class KeyboardLabels(
    /** The UI locale: the platform's names of other languages and regions (voice typing's language list) use it. */
    val locale: Locale,
    val languageAm: String,
    val languageTi: String,
    val languageOm: String,
    val languageEn: String,
    /** The writing-mode selector's names (am/ti): Fidel stays ፊደል in every UI language. */
    val modePhonetic: String,
    val modeEnglish: String,
    val spokenFidel: String,
    val spokenFidelGrid: String,
    val spokenPhoneticKeys: String,
    val spokenAbcLatin: String,
    /** "%1$s, %2$s": language, writing mode. */
    val modeDescription: String,
    val keyDelete: String,
    val keyEmoji: String,
    val keySpace: String,
    /** "Space, %1$s %2$s": language, layout. */
    val keySpaceMode: String,
    val keyEthiopicNumeral: String,
    val keyEthiopicPunctuation: String,
    val keyPeriod: String,
    val keyEthiopicFullStop: String,
    val keyComma: String,
    val keyEthiopicComma: String,
    val keySwitchToLanguageKeys: String,
    val keySwitchToEnglishMode: String,
    val keySwitchToAbc: String,
    val keyNumbersSymbols: String,
    val keyMoreSymbols: String,
    val keyLetters: String,
    val keySwitchLanguage: String,
    val keyShift: String,
    val keyApostrophe: String,
    /** "Vowel order %1$d %2$s". */
    val keyVowelOrder: String,
    /** "Switch to %1$s" (the space-bar language picker). */
    val pickerSwitchTo: String,
) {
    fun language(code: String): String = when (code) {
        "am" -> languageAm
        "ti" -> languageTi
        "om" -> languageOm
        "en" -> languageEn
        else -> code
    }

    companion object {
        val ENGLISH = KeyboardLabels(
            locale = Locale.ENGLISH,
            languageAm = "Amharic",
            languageTi = "Tigrinya",
            languageOm = "Afaan Oromoo",
            languageEn = "English",
            modePhonetic = "Phonetic",
            modeEnglish = "English",
            spokenFidel = "Fidel",
            spokenFidelGrid = "Fidel grid",
            spokenPhoneticKeys = "phonetic Latin keys",
            spokenAbcLatin = "ABC Latin",
            modeDescription = "%1\$s, %2\$s",
            keyDelete = "Delete",
            keyEmoji = "Emoji",
            keySpace = "Space",
            keySpaceMode = "Space, %1\$s %2\$s",
            keyEthiopicNumeral = "Ethiopic numeral %1\$s",
            keyEthiopicPunctuation = "Ethiopic punctuation %1\$s",
            keyPeriod = "Period",
            keyEthiopicFullStop = "Ethiopic full stop",
            keyComma = "Comma",
            keyEthiopicComma = "Ethiopic comma",
            keySwitchToLanguageKeys = "Switch to %1\$s keys",
            keySwitchToEnglishMode = "Switch to English writing mode",
            keySwitchToAbc = "Switch to ABC Latin keys",
            keyNumbersSymbols = "Numbers and symbols",
            keyMoreSymbols = "More symbols",
            keyLetters = "Letters",
            keySwitchLanguage = "Switch language",
            keyShift = "Shift",
            keyApostrophe = "Apostrophe, hudhaa",
            keyVowelOrder = "Vowel order %1\$d %2\$s",
            pickerSwitchTo = "Switch to %1\$s",
        )

        /** The labels in use; replaced by [load]. */
        @Volatile
        var current: KeyboardLabels = ENGLISH
            private set

        /** Reads the labels from [c]'s resources (the app language). Main thread or any thread; cheap. */
        fun from(c: Context) = KeyboardLabels(
            locale = c.resources.configuration.locales[0] ?: Locale.ENGLISH,
            languageAm = c.getString(R.string.language_name_am),
            languageTi = c.getString(R.string.language_name_ti),
            languageOm = c.getString(R.string.language_name_om),
            languageEn = c.getString(R.string.language_name_en),
            modePhonetic = c.getString(R.string.kb_mode_phonetic),
            modeEnglish = c.getString(R.string.kb_mode_english),
            spokenFidel = c.getString(R.string.kb_spoken_fidel),
            spokenFidelGrid = c.getString(R.string.kb_spoken_fidel_grid),
            spokenPhoneticKeys = c.getString(R.string.kb_spoken_phonetic_keys),
            spokenAbcLatin = c.getString(R.string.kb_spoken_abc_latin),
            modeDescription = c.getString(R.string.kb_mode_description),
            keyDelete = c.getString(R.string.kb_key_delete),
            keyEmoji = c.getString(R.string.key_emoji_description),
            keySpace = c.getString(R.string.kb_key_space),
            keySpaceMode = c.getString(R.string.kb_key_space_mode),
            keyEthiopicNumeral = c.getString(R.string.kb_key_ethiopic_numeral),
            keyEthiopicPunctuation = c.getString(R.string.kb_key_ethiopic_punctuation),
            keyPeriod = c.getString(R.string.kb_key_period),
            keyEthiopicFullStop = c.getString(R.string.kb_key_ethiopic_full_stop),
            keyComma = c.getString(R.string.kb_key_comma),
            keyEthiopicComma = c.getString(R.string.kb_key_ethiopic_comma),
            keySwitchToLanguageKeys = c.getString(R.string.kb_key_switch_to_language_keys),
            keySwitchToEnglishMode = c.getString(R.string.kb_key_switch_to_english_mode),
            keySwitchToAbc = c.getString(R.string.kb_key_switch_to_abc),
            keyNumbersSymbols = c.getString(R.string.kb_key_numbers_symbols),
            keyMoreSymbols = c.getString(R.string.kb_key_more_symbols),
            keyLetters = c.getString(R.string.kb_key_letters),
            keySwitchLanguage = c.getString(R.string.kb_key_switch_language),
            keyShift = c.getString(R.string.kb_key_shift),
            keyApostrophe = c.getString(R.string.kb_key_apostrophe),
            keyVowelOrder = c.getString(R.string.kb_key_vowel_order),
            pickerSwitchTo = c.getString(R.string.kb_picker_switch_to),
        )

        fun load(c: Context) {
            current = from(c)
        }

        /** Back to English (tests that loaded another locale). */
        fun reset() {
            current = ENGLISH
        }
    }
}
