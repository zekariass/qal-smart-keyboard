package com.qalsmartkeyboard.ime.wordart

/** What the user chose besides the text: the greeting language (am, ti, om, en) and whether to add the built-in phrase. */
data class WordArtOptions(val language: String = "en", val greeting: Boolean = false)

/** A rendered style: [text] to insert or copy, [support] for the list, [plain] (the unstyled text) for screen readers. */
class Rendered(val text: String, val support: Support, val plain: String, val draftPhrase: Boolean)

/**
 * The Word Art engine's entry point (pure Kotlin; no Android classes, no allocation beyond the strings it builds).
 *
 * Rendering order: the optional greeting phrase and the user's text form the *body* (the phrase on its own first line), then
 * the style is applied to the whole body. A letter style therefore also restyles a Latin phrase, while a Ge'ez phrase passes
 * through it unchanged; compositions wrap both.
 */
object WordArt {
    /** Every style, in list order. */
    val styles: List<WordArtStyle> get() = WordArtCatalog.all

    private val byId: Map<String, WordArtStyle> by lazy { WordArtCatalog.all.associateBy { it.id } }

    fun style(id: String): WordArtStyle? = byId[id]

    val categories: List<WordArtCategory> get() = WordArtCategory.entries

    /** The styles of [category]; Popular gathers every style flagged popular. */
    fun stylesIn(category: WordArtCategory): List<WordArtStyle> =
        if (category == WordArtCategory.POPULAR) styles.filter { it.popular } else styles.filter { it.category == category }

    /** The placeholder shown in the previews while nothing is typed. */
    fun sample(language: String): String = Greetings.sample(language)

    /** The text a style is applied to: the greeting line (when asked for and the style has an occasion) plus [text]. */
    fun body(style: WordArtStyle, text: String, options: WordArtOptions): String {
        val clean = text.trim()
        val occasion = style.occasion
        val phrase = if (options.greeting && occasion != null) Greetings.phrase(occasion, options.language) else null
        return when {
            phrase == null -> clean
            clean.isEmpty() -> phrase.text
            else -> phrase.text + "\n" + clean
        }
    }

    /** True when the greeting line would be a DRAFT phrase for [style] (shown as a hint in the card). */
    fun phraseIsDraft(style: WordArtStyle, options: WordArtOptions): Boolean {
        val occasion = style.occasion ?: return false
        return options.greeting && Greetings.phrase(occasion, options.language)?.draft == true
    }

    /** Renders [style] for [text]; when the style does not support the body, the output is the body unchanged. */
    fun render(style: WordArtStyle, text: String, options: WordArtOptions = WordArtOptions()): Rendered {
        val body = body(style, text, options)
        val info = ScriptInfo.of(body)
        return Rendered(style.apply(body, info), style.support(info), body.replace('\n', ' '), phraseIsDraft(style, options))
    }
}
