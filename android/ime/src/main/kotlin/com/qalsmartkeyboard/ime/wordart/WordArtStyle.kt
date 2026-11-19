package com.qalsmartkeyboard.ime.wordart

/** The chips of the Word Art screen. [POPULAR] lists every style flagged popular, the others list their own styles. */
enum class WordArtCategory(val label: String) {
    POPULAR("Popular"),
    BIRTHDAY("Birthday"),
    LOVE("Love"),
    WEDDING("Wedding"),
    CONGRATS("Congratulations"),
    THANKS("Thank you"),
    HOLIDAYS("Holidays and festivals"),
    ANNOUNCEMENTS("Announcements"),
    SOCIAL("Social captions"),
    NAMES("Names"),
    LETTERS("Letter styles"),
}

/** How a style works, which decides the scripts it can serve. */
enum class StyleKind {
    /** Replaces ASCII letters and digits with a decorative Unicode alphabet: Latin only. */
    LETTER,

    /** Adds a combining overlay (underline, strikethrough, ...) after each ASCII letter or digit: Latin only. */
    MARK,

    /** Puts a separator between grapheme clusters: every script except joining and complex-shaping ones. */
    SPACING,

    /** Wraps whole lines in ornaments, dividers or brackets: works for every script (frames never touch the letters). */
    COMPOSITION,
}

/**
 * One decorative style. [apply] is a pure string function of the text and its [ScriptInfo]; [support] says how much of the
 * text it can serve. The label is the English style name (also its spoken name); category titles are Android resources.
 */
class WordArtStyle(
    val id: String,
    val category: WordArtCategory,
    val label: String,
    val kind: StyleKind,
    val occasion: Occasion? = null,
    val popular: Boolean = false,
    /** True when [apply] also rewrites ASCII letters (a frame with a letter alphabet): the text is then not kept verbatim. */
    val altersLetters: Boolean = false,
    private val supportFn: (ScriptInfo) -> Support,
    private val fn: (String, ScriptInfo) -> String,
) {
    fun support(info: ScriptInfo): Support = supportFn(info)

    fun supportsScript(info: ScriptInfo): Boolean = supportFn(info) != Support.NONE

    /** Styles [text]. The text is returned unchanged when the style does not support it. */
    fun apply(text: String, info: ScriptInfo = ScriptInfo.of(text)): String =
        if (supportFn(info) == Support.NONE) text else fn(text, info)

    override fun toString() = "WordArtStyle($id)"
}
