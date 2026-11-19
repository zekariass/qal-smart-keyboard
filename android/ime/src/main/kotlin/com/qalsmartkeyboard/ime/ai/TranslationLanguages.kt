package com.qalsmartkeyboard.ime.ai

import com.qalsmartkeyboard.ime.KeyboardLabels
import com.qalsmartkeyboard.ime.LanguageCatalog
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * One language translation offers (ADR 0040). Translation languages are not typing languages: [typing] only says the
 * language is also one of the keyboard's. [nativeName] is the autonym from the server's catalogue; the name in the UI
 * language comes from [AiLanguages.name] (the keyboard's own labels for its languages, else the platform's
 * `Locale.getDisplayName`, else [englishName]).
 */
data class TranslationLanguage(
    val code: String,
    val englishName: String,
    val nativeName: String,
    val rtl: Boolean = false,
    val asSource: Boolean = true,
    val asDestination: Boolean = true,
    val autoDetect: Boolean = true,
    val typing: Boolean = false,
)

/**
 * The translation language catalogue the client uses (ADR 0040): the server's `GET /ai/capabilities`
 * `translationLanguages` when it has been fetched (cached on the device), else [BUNDLED], the keyboard's five typing
 * languages, so first-run and offline behaviour is what it always was. `:ime` never fetches it: `:ai-client` hands it in
 * through [AiActions.translationCatalog] and the service publishes it in [TranslationLanguages.current].
 */
class TranslationCatalog(val languages: List<TranslationLanguage>, val fromServer: Boolean = false) {
    private val byCode = languages.associateBy { it.code }

    fun find(code: String?): TranslationLanguage? = code?.let(byCode::get)

    fun isKnown(code: String?): Boolean = find(code) != null

    /** Text in [code] can be translated (an explicit source). */
    fun isSource(code: String?): Boolean = find(code)?.asSource == true

    /** Text can be translated into [code]. */
    fun isDestination(code: String?): Boolean = find(code)?.asDestination == true

    val sources: List<TranslationLanguage> get() = languages.filter { it.asSource }
    val destinations: List<TranslationLanguage> get() = languages.filter { it.asDestination }

    override fun equals(other: Any?) = other is TranslationCatalog && other.languages == languages && other.fromServer == fromServer
    override fun hashCode() = languages.hashCode()
    override fun toString() = "TranslationCatalog(${languages.size} languages, fromServer=$fromServer)"

    companion object {
        /** The keyboard's typing languages (ADR 0031, Arabic ADR 0034): the offline and first-run list. */
        val BUNDLED = TranslationCatalog(
            listOf(
                TranslationLanguage("am", "Amharic", "አማርኛ", typing = true),
                TranslationLanguage("ti", "Tigrinya", "ትግርኛ", typing = true),
                TranslationLanguage("om", "Afaan Oromoo", "Afaan Oromoo", typing = true),
                TranslationLanguage("en", "English", "English", typing = true),
                TranslationLanguage("ar", "Arabic", "العربية", rtl = true, typing = true),
            ),
        )

        /** The server's list, or [BUNDLED] when it is empty (no provider active, or the plan has no translation). */
        fun of(languages: List<TranslationLanguage>, fromServer: Boolean = true): TranslationCatalog =
            if (languages.isEmpty()) BUNDLED else TranslationCatalog(languages, fromServer)
    }
}

/** The catalogue the keyboard's views read (set by the service from [AiActions.translationCatalog]). */
object TranslationLanguages {
    @Volatile var current: TranslationCatalog = TranslationCatalog.BUNDLED
}

/**
 * Search and order of the translate panel's long language lists (ADR 0040). Pure, so the lists are JVM-tested.
 *
 * A query matches a language when a word of its UI-language name, English name or autonym starts with it, or its code
 * does; case and accents are ignored ("francais" finds Français, "ara" finds العربية via "Arabic"). An empty query
 * matches everything.
 */
object TranslationLanguageSearch {
    private val MARKS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[\\s()\\-·,/]+")

    fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    fun matches(query: String, language: TranslationLanguage, uiName: String = uiName(language)): Boolean {
        val q = fold(query.trim())
        if (q.isEmpty()) return true
        if (fold(language.code).startsWith(q)) return true
        return listOf(uiName, language.englishName, language.nativeName).any { name ->
            val f = fold(name)
            f.startsWith(q) || f.split(SEPARATORS).any { it.startsWith(q) }
        }
    }

    /**
     * The list order: [first] (the current choice, the keyboard language, the recent ones, in that order) as given, then
     * the keyboard's typing languages in their usual order, then every other language by its UI-language name.
     */
    fun order(all: List<TranslationLanguage>, first: List<String>, locale: Locale = KeyboardLabels.current.locale): List<TranslationLanguage> {
        val byCode = all.associateBy { it.code }
        val head = (first + TranslationCatalog.BUNDLED.languages.map { it.code }).distinct().mapNotNull(byCode::get)
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        val names = all.associate { it.code to uiName(it) }
        val rest = all.filter { it !in head }.sortedWith { a, b -> collator.compare(names.getValue(a.code), names.getValue(b.code)) }
        return head + rest
    }

    /** [language]'s name in the UI language: the keyboard's labels for its own languages, else the platform's, else its English name. */
    fun uiName(language: TranslationLanguage): String =
        if (language.code in AiLanguages.ALL) AiLanguages.name(language.code)
        else PlatformLanguageNames.name(language.code, KeyboardLabels.current.locale) ?: language.englishName
}

/** The UI-language names of translation languages that are not keyboard languages (no hand-written strings: ADR 0032). */
internal object PlatformLanguageNames {
    /**
     * The platform's name of [code] in [ui] (`Locale.getDisplayName`), or null when the platform has none for that UI
     * language (it answers with the code itself or the English name: then the catalogue's English name reads better).
     */
    fun name(code: String, ui: Locale): String? {
        val locale = Locale.forLanguageTag(code)
        if (locale.language.isEmpty()) return null
        val shown = if (ui.language == "en") return null else locale.getDisplayName(ui)
        if (shown.isBlank() || shown.equals(code, ignoreCase = true) || shown == locale.getDisplayName(Locale.ENGLISH)) return null
        return shown.replaceFirstChar { if (it.isLowerCase()) it.titlecase(ui) else it.toString() }
    }
}

/** The name of a language the keyboard does not type: the platform's name in the UI language, else the English name. */
internal fun translationLanguageName(code: String): String {
    val known = TranslationLanguages.current.find(code) ?: TranslationCatalog.BUNDLED.find(code)
    PlatformLanguageNames.name(code, KeyboardLabels.current.locale)?.let { return it }
    return known?.englishName ?: LanguageCatalog.displayName(code)
}
