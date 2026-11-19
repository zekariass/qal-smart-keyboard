package com.qalsmartkeyboard.ime.wordart

/**
 * A small extended-grapheme-cluster splitter, pure Kotlin so the result does not depend on the JDK or ICU version
 * (`java.text.BreakIterator` only learned emoji ZWJ sequences recently). A cluster is a base code point plus its
 * combining marks (Mn, Me, Mc: this keeps an Ethiopic letter with U+135D-U+135F, an Arabic letter with its harakat,
 * Indic vowel signs and keycaps together), variation selectors, emoji modifiers (skin tones), tag characters and ZWJ
 * joined parts; two regional indicators form one flag. A line break or another control character is always a cluster
 * of its own (UAX 29 GB4/GB5). Spacing styles insert their separator only between clusters, so nothing is ever torn
 * apart; the in-keyboard text editor (ADR 0038) moves its cursor and deletes by these clusters.
 */
object Graphemes {
    private const val ZWJ = 0x200D

    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val end = clusterEnd(text, i)
            out += text.substring(i, end)
            i = end
        }
        return out
    }

    /** The end (exclusive) of the cluster that starts at [start] (which must be a cluster start). */
    fun clusterEnd(text: CharSequence, start: Int): Int {
        val n = text.length
        if (start >= n) return n
        var i = start
        val cp = Character.codePointAt(text, i)
        i += Character.charCount(cp)
        if (cp == '\r'.code && i < n && text[i] == '\n') return i + 1
        if (isControl(cp)) return i
        if (isRegionalIndicator(cp) && i < n && isRegionalIndicator(Character.codePointAt(text, i))) i += Character.charCount(Character.codePointAt(text, i))
        while (i < n) {
            val next = Character.codePointAt(text, i)
            if (next == ZWJ) {
                i += 1
                if (i < n && !isControl(Character.codePointAt(text, i))) i += Character.charCount(Character.codePointAt(text, i))
            } else if (isExtender(next)) {
                i += Character.charCount(next)
            } else break
        }
        return i
    }

    private fun isControl(cp: Int) = Character.getType(cp).toByte() == Character.CONTROL

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private fun isExtender(cp: Int): Boolean {
        if (cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F || cp in 0xE0100..0xE01EF) return true
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }
    }
}
