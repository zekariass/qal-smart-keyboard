package com.qalsmartkeyboard.ime.wordart

/**
 * An ASCII letter and digit substitution (one code point to one code point). Everything else (spaces, punctuation, accented
 * Latin, Ethiopic, Arabic, emoji, ...) passes through untouched: the decorative alphabets of Unicode exist for A-Z, a-z and
 * 0-9 only, so they are never applied to other scripts.
 */
class LetterMap internal constructor(private val table: IntArray) {
    fun map(text: String): String {
        val sb = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            sb.appendCodePoint(if (cp < 128) table[cp] else cp)
        }
        return sb.toString()
    }

    /** The image of one ASCII character (the character itself when the map leaves it alone). */
    operator fun get(c: Char): Int = if (c.code < 128) table[c.code] else c.code

    /** True when [map] would change nothing for [c]. */
    fun fixes(c: Char): Boolean = get(c) == c.code
}

/** The Latin letter styles (Unicode Mathematical Alphanumeric Symbols and friends), exceptions for reserved holes included. */
object LetterMaps {
    private fun build(
        upper: Int? = null, lower: Int? = null, digits: Int? = null,
        upperEx: Map<Char, Int> = emptyMap(), lowerEx: Map<Char, Int> = emptyMap(),
        lowerToUpper: Boolean = false, upperToLower: Boolean = false,
        zeroTo: Int? = null,
        extra: IntArray.() -> Unit = {},
    ): LetterMap {
        val t = IntArray(128) { it }
        if (upper != null) for (i in 0 until 26) t['A'.code + i] = upper + i
        if (lower != null) for (i in 0 until 26) t['a'.code + i] = lower + i
        if (lowerToUpper && upper != null) for (i in 0 until 26) t['a'.code + i] = upper + i
        if (upperToLower && lower != null) for (i in 0 until 26) t['A'.code + i] = lower + i
        for ((c, v) in upperEx) t[c.code] = v
        for ((c, v) in lowerEx) t[c.code] = v
        if (digits != null) for (i in 0..9) t['0'.code + i] = digits + i
        if (zeroTo != null) t['0'.code] = zeroTo
        t.extra()
        return LetterMap(t)
    }

    val BOLD = build(0x1D400, 0x1D41A, 0x1D7CE)
    val ITALIC = build(0x1D434, 0x1D44E, lowerEx = mapOf('h' to 0x210E))
    val BOLD_ITALIC = build(0x1D468, 0x1D482, 0x1D7CE)

    val SCRIPT = build(
        0x1D49C, 0x1D4B6,
        upperEx = mapOf('B' to 0x212C, 'E' to 0x2130, 'F' to 0x2131, 'H' to 0x210B, 'I' to 0x2110, 'L' to 0x2112, 'M' to 0x2133, 'R' to 0x211B),
        lowerEx = mapOf('e' to 0x212F, 'g' to 0x210A, 'o' to 0x2134),
    )
    val BOLD_SCRIPT = build(0x1D4D0, 0x1D4EA, 0x1D7CE)

    val FRAKTUR = build(
        0x1D504, 0x1D51E,
        upperEx = mapOf('C' to 0x212D, 'H' to 0x210C, 'I' to 0x2111, 'R' to 0x211C, 'Z' to 0x2128),
    )
    val BOLD_FRAKTUR = build(0x1D56C, 0x1D586, 0x1D7CE)

    val DOUBLE_STRUCK = build(
        0x1D538, 0x1D552, 0x1D7D8,
        upperEx = mapOf('C' to 0x2102, 'H' to 0x210D, 'N' to 0x2115, 'P' to 0x2119, 'Q' to 0x211A, 'R' to 0x211D, 'Z' to 0x2124),
    )

    val SANS = build(0x1D5A0, 0x1D5BA, 0x1D7E2)
    val SANS_BOLD = build(0x1D5D4, 0x1D5EE, 0x1D7EC)
    val SANS_ITALIC = build(0x1D608, 0x1D622, 0x1D7E2)
    val SANS_BOLD_ITALIC = build(0x1D63C, 0x1D656, 0x1D7EC)
    val MONOSPACE = build(0x1D670, 0x1D68A, 0x1D7F6)

    /** Circled letters and the digits 0-9 (the circled numbers 1-9 and 0 are separate blocks). */
    val CIRCLED = build(0x24B6, 0x24D0) { for (i in 1..9) this['0'.code + i] = 0x2460 + i - 1; this['0'.code] = 0x24EA }

    /** Negative circled: the block has capitals only, so lower case maps to the same glyphs. */
    val NEGATIVE_CIRCLED = build(0x1F150, 0x1F150, lowerToUpper = true) { for (i in 1..9) this['0'.code + i] = 0x2776 + i - 1; this['0'.code] = 0x24FF }

    val SQUARED = build(0x1F130, 0x1F130, lowerToUpper = true)
    val NEGATIVE_SQUARED = build(0x1F170, 0x1F170, lowerToUpper = true)

    /** Parenthesised: the block has lower case only (capitals map to the same glyphs); digits 1-9 only. */
    val PARENTHESIZED = build(null, 0x249C, upperToLower = true) { for (i in 1..9) this['0'.code + i] = 0x2474 + i - 1 }

    val FULLWIDTH = build(0xFF21, 0xFF41, 0xFF10)

    /** Small capitals: lower case only (capitals stay); x has no small capital in Unicode and q uses the nearest, so both are approximate. */
    val SMALL_CAPS = build {
        val small = intArrayOf(
            0x1D00, 0x0299, 0x1D04, 0x1D05, 0x1D07, 0xA730, 0x0262, 0x029C, 0x026A, 0x1D0A, 0x1D0B, 0x029F, 0x1D0D,
            0x0274, 0x1D0F, 0x1D18, 0x01EB, 0x0280, 0xA731, 0x1D1B, 0x1D1C, 0x1D20, 0x1D21, 'x'.code, 0x028F, 0x1D22,
        )
        for (i in 0 until 26) this['a'.code + i] = small[i]
    }

    /** Combining overlays (applied after each ASCII letter or digit). */
    const val COMBINING_UNDERLINE = 0x0332
    const val COMBINING_DOUBLE_UNDERLINE = 0x0333
    const val COMBINING_STRIKETHROUGH = 0x0336
    const val COMBINING_SLASH = 0x0338
    const val COMBINING_OVERLINE = 0x0305
}
