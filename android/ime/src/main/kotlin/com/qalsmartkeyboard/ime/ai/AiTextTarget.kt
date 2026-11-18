package com.qalsmartkeyboard.ime.ai

/**
 * What an AI action works on (A2.5), decided only when the user taps an action:
 *
 * 1. **The selection**, if the host has a non-empty one.
 * 2. Otherwise **the sentence before the cursor** (the open composition is committed first, so it
 *    is part of the text before the cursor): from just after the previous sentence terminator
 *    (`. ! ? … ። ፧ ፨` or a line break) to the cursor. If the cursor sits right after a terminator
 *    ("How are you?|"), that finished sentence is the target. Whitespace after the sentence is
 *    kept as it is. Text after the cursor is never read.
 *
 * At most [MAX_CHARS] UTF-16 units (the contract's `TransformRequest.text` limit); a longer
 * selection is refused ([AiFailure.TooLong]) rather than cut.
 */
sealed interface AiSource {
    val text: String

    /** The user's selection. */
    class Selection(override val text: String) : AiSource

    /** The sentence before the cursor and the whitespace between it and the cursor ([trailing]). */
    class Sentence(override val text: String, val trailing: String) : AiSource
}

object AiTextTarget {
    const val MAX_CHARS = 5000

    /** How much text before the cursor is read to find the sentence start. */
    const val LOOKBACK = 2000

    private val TERMINATORS = setOf('.', '!', '?', '…', '።', '፧', '፨', '\n', '\r')

    /** The sentence at the end of [before], or null if there is none. */
    fun sentenceBeforeCursor(before: String): AiSource.Sentence? {
        val core = before.trimEnd()
        if (core.isEmpty()) return null
        var i = core.length
        // Terminators right at the end belong to the sentence that just ended.
        while (i > 0 && core[i - 1] in TERMINATORS) i--
        while (i > 0 && core[i - 1] !in TERMINATORS) i--
        var start = i
        // The lookback window may start mid-word: drop the cut word fragment.
        if (start == 0 && before.length >= LOOKBACK) {
            val ws = core.indexOfFirst { it.isWhitespace() }
            if (ws >= 0) start = ws
        }
        while (start < core.length && core[start].isWhitespace()) start++
        val sentence = core.substring(start)
        if (sentence.none { it.isLetterOrDigit() }) return null
        return AiSource.Sentence(sentence, before.substring(core.length))
    }
}
