package com.qalsmartkeyboard.ime.ai

import com.qalsmartkeyboard.ime.LanguageCatalog

/** The languages the AI panels offer as source and output (the keyboard's launch languages, ADR 0031). */
object AiLanguages {
    val ALL: List<String> = listOf("am", "ti", "om", "en")

    /** The language's name in the UI language ("Amharic", "አማርኛ" in Amharic, ADR 0032), as the AI panels show it. */
    fun name(code: String): String = LanguageCatalog.spokenName(code)

    /** A plain-language destination (no profile). */
    fun plain(code: String) = AiDestination("lang:$code", name(code), code)

    /** The default destination for text in [source]: English for am/ti/om, Amharic for English (ADR 0031). */
    fun defaultTarget(source: String): String = if (source == "en") "am" else "en"
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

    /** The name the user signs e-mails with ("" when never given). */
    val senderName: String

    fun rememberSenderName(name: String)

    /** Kept in memory only (tests, and before the settings load). */
    class InMemory(override val preferredTarget: String? = null) : AiLanguagePrefs {
        val pairs = LinkedHashMap<String, String>()
        override var senderName: String = ""
        override fun lastTarget(source: String) = pairs[source]
        override fun rememberTarget(source: String, destinationId: String) { pairs[source] = destinationId }
        override fun rememberSenderName(name: String) { senderName = name }
    }
}

/**
 * The translation direction control's state (ADR 0031): `[ Auto · Amharic ⇄ English ]  Translate`.
 *
 * - **Source:** "Auto" by default: the server detects the language, and the keyboard language is shown as the likely
 *   one ([keyboardLanguage]). Picking a language makes it explicit (sent as `sourceLanguage`).
 * - **Destination**, in order: the destination the user picked in this field; the enabled A2.4 rule of the app; the
 *   destination last picked for this source language (remembered on the device); the preferred destination from
 *   settings; English for am/ti/om and Amharic for English. A candidate in the source language itself is skipped.
 * - **Swap** makes the destination's language the explicit source and the old source the destination, and remembers
 *   that pair.
 *
 * Pure state: no Android, no text. A new field starts again from Auto and the defaults.
 */
class TranslationDirection(
    private val prefs: AiLanguagePrefs,
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
        prefs.preferredTarget?.takeIf { it in AiLanguages.ALL && it != src }?.let { return AiLanguages.plain(it) }
        return AiLanguages.plain(AiLanguages.defaultTarget(src))
    }

    private fun resolve(id: String): AiDestination? {
        if (id.startsWith("lang:")) return id.removePrefix("lang:").takeIf { it in AiLanguages.ALL }?.let(AiLanguages::plain)
        return destinations().firstOrNull { it.id == id }
    }

    /** [language] null: back to Auto. A destination in the new source language falls back to the default. */
    fun chooseSource(language: String?) {
        manualSource = language?.takeIf { it in AiLanguages.ALL }
        if (chosen?.language == source) chosen = null
    }

    fun chooseDestination(d: AiDestination) {
        chosen = d
        prefs.rememberTarget(source, d.id)
    }

    /** ⇄: the destination's language becomes the (explicit) source, the old source the destination. */
    fun swap() {
        val oldSource = source
        val newSource = destination.language
        manualSource = newSource
        val d = AiLanguages.plain(oldSource)
        chosen = d
        prefs.rememberTarget(newSource, d.id)
    }

    /** The source label: "Auto · Amharic" while Auto, else "Amharic". */
    fun sourceLabel(auto: String): String = if (isAuto) "$auto · ${AiLanguages.name(source)}" else AiLanguages.name(source)
}
