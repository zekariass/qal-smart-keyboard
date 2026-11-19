package com.qalsmartkeyboard.ime.wordart

/**
 * A small extended-grapheme-cluster splitter, pure Kotlin so the result does not depend on the JDK or ICU version
 * (`java.text.BreakIterator` only learned emoji ZWJ sequences recently). A cluster is a base code point plus its
 * combining marks (Mn, Me, Mc: this keeps an Ethiopic letter with U+135D-U+135F, Indic vowel signs and keycaps together),
 * variation selectors, emoji modifiers (skin tones), tag characters and ZWJ joined parts; two regional indicators form
 * one flag. Spacing styles insert their separator only between clusters, so nothing is ever torn apart.
 */
object Graphemes {
    private const val ZWJ = 0x200D

    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            val start = i
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isRegionalIndicator(cp) && i < n && isRegionalIndicator(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
            while (i < n) {
                val next = text.codePointAt(i)
                if (next == ZWJ) {
                    i += 1
                    if (i < n) i += Character.charCount(text.codePointAt(i))
                } else if (isExtender(next)) {
                    i += Character.charCount(next)
                } else break
            }
            out += text.substring(start, i)
        }
        return out
    }

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private fun isExtender(cp: Int): Boolean {
        if (cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F || cp in 0xE0100..0xE01EF) return true
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }
    }
}
