package com.qalsmartkeyboard.ime.voice

import java.util.Locale

/**
 * How recognised text is committed (ADR 0029). Pure.
 *
 * - Whitespace is trimmed and runs of it collapse to one space; empty text inserts nothing.
 * - A leading space is added when the cursor follows a non-space character, unless that character opens a bracket or
 *   quote, or the text itself starts with closing punctuation (`.`, `,`, `።`, ...).
 * - English only: the first letter is capitalised where the field's auto-capitalisation would capitalise it
 *   ([capitalize], the editor's cursor caps mode). Other languages are inserted as recognised.
 * - No trailing space (the next dictation or typed word adds its own).
 */
object VoiceText {
    private const val OPENERS = "([{<“‘«‹¿¡/@#"
    private const val CLOSERS = ".,!?;:)]}>…%'’”»›።፣፤፥፦፧፨"
    private const val ETHIOPIC_WORDSPACE = '፡'
    private val SPACES = Regex("\\s+")

    fun prepare(raw: String, before: CharSequence?, english: Boolean, capitalize: Boolean): String {
        var text = raw.trim().replace(SPACES, " ")
        if (text.isEmpty()) return ""
        if (english && capitalize) text = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
        val last = before?.lastOrNull()
        val space = last != null && !last.isWhitespace() && last != ETHIOPIC_WORDSPACE && last !in OPENERS && text.first() !in CLOSERS
        return if (space) " $text" else text
    }

    /** The tag is an English one (capitalisation applies). */
    fun isEnglish(tag: String): Boolean = VoiceLanguages.languageOf(tag) == "en"
}
