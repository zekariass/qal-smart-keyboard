package com.qalsmartkeyboard.ime.voice

import com.qalsmartkeyboard.ime.KeyboardLabels
import com.qalsmartkeyboard.ime.LanguageCatalog
import java.util.Locale

/**
 * Keyboard language to speech-recognition language (BCP-47) mapping, tag normalisation and matching (ADR 0029).
 * Pure and JVM-testable.
 *
 * Preference order per keyboard language: am `am-ET`; ti `ti-ET` then `ti-ER`; om `om-ET`; en the device locale's
 * English (e.g. `en-GB`) when the device is set to English, then `en-US`; ar (ADR 0034) the device's Arabic region
 * (e.g. `ar-EG`) when the device is set to Arabic, then `ar-SA` (Modern Standard Arabic on most speech services). A tag
 * the user chose for the keyboard language comes before all of these. None of the Ethiopian tags is assumed to work: support is asked of the
 * recogniser at run time ([VoiceSupport]).
 */
object VoiceLanguages {
    /** The keyboard languages, in the picker's order. */
    val KEYBOARD_LANGUAGES: List<String> = listOf("am", "ti", "om", "en", "ar")

    /**
     * Keyboard languages the picker lists only when a speech service can take them, or while they are typed (Arabic,
     * ADR 0034): the four launch languages are always listed, with their support.
     */
    val LISTED_WHEN_AVAILABLE: Set<String> = setOf("ar")

    /** The default tags of [keyboardLanguage], most preferred first. */
    fun defaults(keyboardLanguage: String, deviceLocale: Locale): List<String> = when (keyboardLanguage) {
        "am" -> listOf("am-ET")
        "ti" -> listOf("ti-ET", "ti-ER")
        "om" -> listOf("om-ET")
        "en" -> {
            val region = deviceLocale.country
            if (deviceLocale.language == "en" && region.isNotEmpty()) listOf("en-$region", "en-US").distinct() else listOf("en-US")
        }
        "ar" -> {
            val region = deviceLocale.country
            if (deviceLocale.language == "ar" && region.isNotEmpty()) listOf("ar-$region", "ar-SA").distinct() else listOf("ar-SA")
        }
        else -> listOf(normalize(keyboardLanguage))
    }

    /** The tags to try for [keyboardLanguage]: the user's [chosen] tag first (if any), then [defaults]. */
    fun preference(keyboardLanguage: String, chosen: String?, deviceLocale: Locale): List<String> =
        (listOfNotNull(chosen?.let { normalize(it) }) + defaults(keyboardLanguage, deviceLocale)).distinct()

    /**
     * Canonical form of a tag as recognisers spell it: `_` becomes `-`, the language is lower case, a script title
     * case, a region upper case (`am_et` -> `am-ET`, `zh-hant-tw` -> `zh-Hant-TW`). Unknown subtags keep their case.
     */
    fun normalize(tag: String): String {
        val parts = tag.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        return parts.mapIndexed { i, p ->
            when {
                i == 0 -> p.lowercase(Locale.ROOT)
                p.length == 4 && p.all { it.isLetter() } -> p.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
                p.length == 2 && p.all { it.isLetter() } -> p.uppercase(Locale.ROOT)
                p.length == 3 && p.all { it.isDigit() } -> p
                else -> p
            }
        }.joinToString("-")
    }

    /** The language subtag (`am-ET` -> `am`). */
    fun languageOf(tag: String): String = normalize(tag).substringBefore('-')

    /** The keyboard language a tag belongs to, or null for any other language. */
    fun keyboardLanguageOf(tag: String): String? = languageOf(tag).takeIf { it in KEYBOARD_LANGUAGES }

    /** [wanted] exactly as one of [available] (normalised), or null. */
    fun exact(wanted: String, available: Set<String>): String? = normalize(wanted).takeIf { it in available }

    /**
     * A tag of [available] with the same language as [wanted] when the exact one is missing: the bare language
     * (`am`) first, then the alphabetically first regional one (stable).
     */
    fun sameLanguage(wanted: String, available: Set<String>): String? {
        val lang = languageOf(wanted)
        if (lang in available) return lang
        return available.filter { languageOf(it) == lang }.minOrNull()
    }

    /** The language's name without the region (`ti-ER` -> Tigrinya), for messages. */
    fun languageName(tag: String): String {
        val lang = languageOf(tag)
        return if (lang in KEYBOARD_LANGUAGES) LanguageCatalog.spokenName(lang) else Locale.forLanguageTag(lang).getDisplayLanguage(uiLocale()).ifEmpty { lang }
    }

    /** The UI language (ADR 0032): the platform names other languages and regions in it. */
    private fun uiLocale(): Locale = KeyboardLabels.current.locale

    /** The name shown for [tag]: our names for the keyboard languages, the platform's name otherwise (in the UI language); region in brackets. */
    fun displayName(tag: String): String {
        val t = normalize(tag)
        val locale = Locale.forLanguageTag(t)
        val lang = languageOf(t)
        val ui = uiLocale()
        val name = if (lang in KEYBOARD_LANGUAGES) {
            LanguageCatalog.spokenName(lang)
        } else {
            locale.getDisplayLanguage(ui).ifEmpty { lang }
        }
        val details = listOf(locale.getDisplayScript(ui), locale.getDisplayCountry(ui)).filter { it.isNotEmpty() }
        return if (details.isEmpty()) name else "$name (${details.joinToString(", ")})"
    }
}
