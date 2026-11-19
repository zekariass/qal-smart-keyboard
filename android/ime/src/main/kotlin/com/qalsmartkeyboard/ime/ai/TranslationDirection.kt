package com.qalsmartkeyboard.ime.ai

import com.qalsmartkeyboard.ime.LanguageCatalog

/**
 * Language names and the keyboard's own languages for the AI panels. The keyboard's typing languages ([ALL]: am, ti,
 * om, en, ar; ADR 0031, ADR 0034) are what the LLM features write in (e-mail, rewrite, summaries). Translation offers the
 * longer, server-driven list of [TranslationLanguages] (ADR 0040), which starts with these five.
 */
object AiLanguages {
    val ALL: List<String> = listOf("am", "ti", "om", "en", "ar")

    /**
     * The language's name in the UI language ("Amharic", "አማርኛ" in Amharic, ADR 0032), as the AI panels show it: the
     * keyboard's own labels for its languages, else the platform's name in the UI language, else the catalogue's
     * English name (ADR 0040: no hand-written string per translation language).
     */
    fun name(code: String): String = if (code in ALL) LanguageCatalog.spokenName(code) else translationLanguageName(code)

    /** A plain-language destination (no profile). */
    fun plain(code: String) = AiDestination("lang:$code", name(code), code)

    /** The default destination for text in [source]: English for am/ti/om/ar (and any other language), Amharic for English (ADR 0031). */
    fun defaultTarget(source: String): String = if (source == "en") "am" else "en"

    /**
     * The language's name in itself ("አማርኛ", "Afaan Oromoo", "العربية", "Français"): the translate panel's language list
     * shows both (ADR 0037). From the catalogue's autonym (ADR 0040).
     */
    fun selfName(code: String): String =
        TranslationLanguages.current.find(code)?.nativeName?.takeIf { it.isNotBlank() }
            ?: TranslationCatalog.BUNDLED.find(code)?.nativeName
            ?: LanguageCatalog.displayName(code)

    /** Written right to left (Arabic, ADR 0034; Hebrew, Persian, Urdu, ... ADR 0040): shown right-aligned. */
    fun isRtl(code: String?): Boolean =
        code != null && (TranslationLanguages.current.find(code)?.rtl ?: LanguageCatalog.isRtl(code))

    /** [name] wrapped in Unicode isolates when it is right to left, so "Detect · العربية" keeps its order (ADR 0040). */
    fun isolated(name: String, rtl: Boolean): String = if (rtl) "⁧$name⁩" else name
}

/**
 * The device-local AI choices the keyboard remembers (ADR 0031), implemented by the service over the settings store.
 * Nothing here is synced; none of it is user text except [senderName] (the user's own name for e-mail sign-offs).
 */
interface AiLanguagePrefs {
    /** The preferred translation destination from settings, or null for the automatic default ([AiLanguages.defaultTarget]). */
    val preferredTarget: String?

    /** The destination id last picked for text in [source] ("lang:en" or a profile's local id), or null. */
    fun lastTarget(source: String): String?

    fun rememberTarget(source: String, destinationId: String)

    /**
     * Languages used recently in translation (plain destinations and their sources), most recent first: the language
     * lists put them near the top (ADR 0040). Default: none.
     */
    fun recentLanguages(): List<String> = emptyList()

    /** The name the user signs e-mails with ("" when never given). */
    val senderName: String

    fun rememberSenderName(name: String)

    /** Kept in memory only (tests, and before the settings load). */
    class InMemory(override val preferredTarget: String? = null) : AiLanguagePrefs {
        val pairs = LinkedHashMap<String, String>()
        override var senderName: String = ""
        override fun lastTarget(source: String) = pairs[source]
        override fun rememberTarget(source: String, destinationId: String) {
            pairs.remove(source)
            pairs[source] = destinationId
        }
        override fun recentLanguages(): List<String> = recentFrom(pairs)
        override fun rememberSenderName(name: String) { senderName = name }
    }

    companion object {
        /** Recent languages from the remembered pairs (insertion order, newest last): each plain destination, then its source. */
        fun recentFrom(pairs: Map<String, String>): List<String> =
            pairs.entries.reversed().flatMap { (src, id) -> listOfNotNull(id.takeIf { it.startsWith("lang:") }?.removePrefix("lang:"), src) }.distinct()
    }
}

/**
 * The translation direction (ADR 0031, shown by the translate panel of ADR 0037: `[Detect · Amharic ▾] ⇄ [English ▾]`).
 * The one source of truth for where a translation goes: the translate panel and AI Assist's Translate both read it.
 *
 * - **Source:** "Detect" (Auto) by default: the server detects the language, and the keyboard language is shown as the
 *   likely one ([keyboardLanguage]) until a translation reports the detected one. Picking a language makes it explicit
 *   (sent as `sourceLanguage`).
 * - **Destination**, in order: the destination the user picked in this field; the enabled A2.4 rule of the app; the
 *   destination last picked for this source language (remembered on the device); the preferred destination from
 *   settings; English for am/ti/om and Amharic for English. A candidate in the source language itself is skipped.
 * - **Swap** makes the destination's language the explicit source and the old source the destination, and remembers
 *   that pair.
 * - **Languages** (ADR 0040) come from the translation catalogue ([catalog]): the server's list when fetched, else the
 *   five keyboard languages. A language the catalogue does not offer in a role is never chosen for it.
 *
 * Pure state: no Android, no text. A new field starts again from Auto and the defaults.
 */
class TranslationDirection(
    private val prefs: AiLanguagePrefs,
    private val catalog: () -> TranslationCatalog = { TranslationLanguages.current },
    private val destinations: () -> List<AiDestination>,
) {
    /** The keyboard language, the likely source while the source is Auto. */
    var keyboardLanguage: String = "en"

    /** The explicitly chosen source, or null for Auto. */
    var manualSource: String? = null
        private set

    private var chosen: AiDestination? = null
    private var appRule: AiDestination? = null

    val isAuto: Boolean get() = manualSource == null

    /** The source language: the explicit one, else the keyboard language (the likely source). */
    val source: String get() = manualSource ?: keyboardLanguage.takeIf { it in AiLanguages.ALL } ?: "en"

    val destination: AiDestination get() = chosen?.takeIf { it.language != source } ?: defaultFor(source)

    /** A new field: Auto source, the app's rule (if any), no explicit choice. */
    fun start(keyboardLanguage: String, appRuleDestination: AiDestination?) {
        this.keyboardLanguage = keyboardLanguage
        manualSource = null
        chosen = null
        appRule = appRuleDestination
    }

    /** The destination for text in [src] when nothing was picked in this field. */
    fun defaultFor(src: String): AiDestination {
        appRule?.takeIf { it.language != src }?.let { return it }
        prefs.lastTarget(src)?.let { id -> resolve(id)?.takeIf { it.language != src }?.let { return it } }
        prefs.preferredTarget?.takeIf { catalog().isDestination(it) && it != src }?.let { return AiLanguages.plain(it) }
        return AiLanguages.plain(AiLanguages.defaultTarget(src))
    }

    private fun resolve(id: String): AiDestination? {
        if (id.startsWith("lang:")) return id.removePrefix("lang:").takeIf { catalog().isDestination(it) }?.let(AiLanguages::plain)
        return destinations().firstOrNull { it.id == id }
    }

    /**
     * [language] null: back to Auto. A language the catalogue offers no source for is ignored. A destination in the new
     * source language falls back to the default.
     */
    fun chooseSource(language: String?) {
        manualSource = language?.takeIf { catalog().isSource(it) }
        if (chosen?.language == source) chosen = null
    }

    fun chooseDestination(d: AiDestination) {
        chosen = d
        prefs.rememberTarget(source, d.id)
    }

    /**
     * ⇄: the destination's language becomes the (explicit) source, the old source the destination. While the source is
     * Auto, the old source is the language the server [detected] in the last translation (if any), else the likely one.
     */
    fun swap(detected: String? = null) {
        val oldSource = likelySource(detected)
        val newSource = destination.language
        manualSource = newSource
        val d = AiLanguages.plain(oldSource)
        chosen = d
        prefs.rememberTarget(newSource, d.id)
    }

    /** The source the user sees: the explicit one; while Auto, the [detected] language if known, else the keyboard language. */
    fun likelySource(detected: String? = null): String =
        if (isAuto) detected?.takeIf { catalog().isKnown(it) } ?: source else source

    /** The source label: "Auto · Amharic" (or "Detect · Amharic") while Auto, else "Amharic". */
    fun sourceLabel(auto: String, detected: String? = null): String {
        val name = AiLanguages.name(likelySource(detected))
        return if (isAuto) "$auto · $name" else name
    }

    /** The languages the lists put first: the current one, the keyboard language, then the recent ones. */
    private fun leading(current: String?): List<String> =
        (listOf(current, keyboardLanguage) + prefs.recentLanguages()).filterNotNull().distinct()

    /**
     * The source list (ADR 0037, ADR 0040): Auto (null, "Detect language") first, then the current language, the keyboard
     * language and the recent ones, then the keyboard's other languages, then every other source of the catalogue by
     * name. With a [query] (the list's search), only the languages it matches, and Auto only while it is empty.
     */
    fun sourceChoices(query: String = ""): List<String?> {
        val ordered = TranslationLanguageSearch.order(catalog().sources, leading(manualSource))
            .filter { TranslationLanguageSearch.matches(query, it) }.map { it.code }
        return if (query.isBlank()) listOf<String?>(null) + ordered else ordered
    }

    /**
     * The destination list: the current destination first, then the plain languages (the source language left out) in
     * the order of [sourceChoices], then the user's destination profiles (ADR 0012); nothing twice. A [query] keeps the
     * languages it matches and the profiles whose label or language it matches.
     */
    fun destinationChoices(query: String = ""): List<AiDestination> {
        val current = destination
        val src = source
        val plain = TranslationLanguageSearch.order(catalog().destinations.filter { it.code != src }, leading(current.language))
            .filter { TranslationLanguageSearch.matches(query, it) }.map { AiLanguages.plain(it.code) }
        val q = TranslationLanguageSearch.fold(query.trim())
        val profiles = destinations().filter { it.isProfile && it.language != src }.filter { p ->
            q.isEmpty() || TranslationLanguageSearch.fold(p.label).contains(q) ||
                catalog().find(p.language)?.let { TranslationLanguageSearch.matches(query, it) } == true
        }
        val head = if (q.isEmpty()) listOf(current) else emptyList()
        return (head + plain + profiles).distinctBy { it.id }
    }
}
