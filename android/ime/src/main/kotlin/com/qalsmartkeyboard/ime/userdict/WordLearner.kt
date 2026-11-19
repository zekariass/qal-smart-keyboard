package com.qalsmartkeyboard.ime.userdict

import com.qalsmartkeyboard.ime.ComposerUpdate

/** Where learned words go (the [UserDictionary]; a fake in tests). */
fun interface WordLearningSink {
    /**
     * One finished, learnable typed [word] of [language]; [previous] is the committed word right
     * before it, if any. The sink decides when it counts: [UserDictionary] learns a word on its
     * second sighting (ADR 0007 D18), so a single typo is never learned.
     */
    fun learn(language: String, word: String, previous: String?)

    /** Explicit "add to dictionary". True if it was added. */
    fun add(language: String, word: String): Boolean = false

    companion object {
        val None = WordLearningSink { _, _, _ -> }
    }
}

/**
 * Turns the text the keyboard commits into learned words (A1.8, SD 7.4). Pure and JVM-testable.
 *
 * Rules:
 * - Only text committed through the keys is observed, and only while [observe] is told that
 *   learning is on for the field and mode. In password, no-personalized-learning (incognito),
 *   email, URI, number and phone fields it is never on, and nothing is buffered there.
 * - A word counts once it is finished: a space (or the Ethiopic wordspace) after it. Trailing and
 *   leading sentence punctuation is stripped first. A word still being typed when the field is
 *   left is dropped ([reset]).
 * - A token learns only if, after stripping, it is letters of the language's script (Ethiopic for
 *   am/ti, Arabic letters and marks for ar, Latin for om/en; an inner apostrophe is allowed in Latin), 2 to [MAX_WORD] characters, and not an
 *   all-capitals acronym. Digits, `@`, `/` and the like inside a token reject the whole token
 *   (so addresses and handles are not learned word by word).
 * - Deleting text (a backspace that reaches committed text) drops the open word and the previous
 *   word: what the user is correcting is not learned.
 * - The previous word is only kept across a plain space; sentence punctuation clears it.
 * - Every finished, learnable word is passed to the sink; the sink, not this class, holds the
 *   sighting count, because the count must survive a restart.
 * - Words typed in ABC mode are not learned for the Ethiopic languages (a Latin word is not an
 *   Amharic word).
 */
class WordLearner(private val sink: WordLearningSink) {
    private val token = StringBuilder()
    private var overlong = false
    private var previous: String? = null

    fun reset() {
        token.setLength(0)
        overlong = false
        previous = null
    }

    /**
     * @param language the language of the mode in force.
     * @param learning false in fields that must not learn, or in a mode whose text is not that language.
     */
    fun observe(update: ComposerUpdate, language: String, learning: Boolean) {
        if (!learning || update.deleteBefore > 0) {
            reset()
            return
        }
        for (c in update.commit.codePoints().toArray()) {
            if (Character.isWhitespace(c) || c == ETHIOPIC_WORDSPACE) {
                finishToken(language, plainBoundary = true)
            } else if (token.length < MAX_TOKEN) {
                token.appendCodePoint(c)
            } else {
                overlong = true
            }
        }
    }

    private fun finishToken(language: String, plainBoundary: Boolean) {
        if (token.isEmpty() && !overlong) return
        val raw = token.toString()
        val tooLong = overlong
        token.setLength(0)
        overlong = false

        var start = 0
        var end = raw.length
        while (start < end && isEdgePunctuation(raw.codePointAt(start))) start += Character.charCount(raw.codePointAt(start))
        while (end > start && isEdgePunctuation(raw.codePointBefore(end))) end -= Character.charCount(raw.codePointBefore(end))
        val trailingPunct = end < raw.length
        val word = raw.substring(start, end)

        val learned = !tooLong && isLearnable(language, word)
        if (learned) sink.learn(language, word, previous)
        previous = if (learned && plainBoundary && !trailingPunct) word else null
    }

    companion object {
        const val MAX_WORD = 48
        private const val MAX_TOKEN = 64
        private const val ETHIOPIC_WORDSPACE = 0x1361

        /** Punctuation that may hug a word: quotes, brackets and the sentence marks of Latin and Ethiopic text. */
        private fun isEdgePunctuation(c: Int): Boolean = when (Character.getType(c).toByte()) {
            Character.START_PUNCTUATION, Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION, Character.DASH_PUNCTUATION,
            Character.CONNECTOR_PUNCTUATION -> true
            else -> false
        }

        /** True when [word] may enter the user dictionary of [language]. */
        fun isLearnable(language: String, word: String): Boolean {
            val cps = word.codePoints().toArray()
            if (cps.size !in 2..MAX_WORD) return false
            val ethiopic = language == "am" || language == "ti"
            val arabic = language == "ar"
            var letters = 0
            var upper = 0
            for ((i, c) in cps.withIndex()) {
                val type = Character.getType(c).toByte()
                val ok = when {
                    type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK -> i > 0
                    isApostrophe(c) -> !ethiopic && !arabic && i > 0 && i < cps.lastIndex
                    Character.isLetter(c) -> when {
                        ethiopic -> isEthiopic(c)
                        arabic -> isArabic(c)
                        else -> isLatin(c)
                    }
                    else -> false
                }
                if (!ok) return false
                if (Character.isLetter(c)) {
                    letters++
                    if (Character.isUpperCase(c)) upper++
                }
            }
            return letters >= 2 && !(upper == letters && !ethiopic && !arabic)
        }

        private fun isApostrophe(c: Int) = c == '\''.code || c == 0x2019 || c == 0x02BC

        private fun isEthiopic(c: Int) =
            c in 0x1200..0x139F || c in 0x2D80..0x2DDF || c in 0xAB00..0xAB2F || c in 0x1E7E0..0x1E7FF

        private fun isArabic(c: Int) = Character.UnicodeScript.of(c) == Character.UnicodeScript.ARABIC

        private fun isLatin(c: Int) = Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN
    }
}
