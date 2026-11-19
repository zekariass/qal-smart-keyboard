package com.qalsmartkeyboard.ime.wordart

/** What a Word Art style can do for a text (the list shows `NONE` styles disabled, with a reason). */
enum class Support {
    /** The whole text is styled. */
    FULL,

    /** Only the part the style can handle (the Latin letters and digits) is styled; the rest passes through unchanged. */
    PARTIAL,

    /** The style would change nothing (or would damage the text): it is not offered. */
    NONE,
}

/** The dominant script of a text, for display and tests. */
enum class Script { EMPTY, LATIN, ETHIOPIC, RTL_JOINING, RTL, OTHER, MIXED }

/**
 * Which scripts a text contains, counted per code point (pure Kotlin: no Android classes, so the engine runs in plain JVM tests).
 *
 *  - [asciiAlnum]: A-Z, a-z, 0-9: the only characters the Unicode "decorative letter" blocks (Mathematical Alphanumerics,
 *    fullwidth, circled, small capitals) and the combining overlays are applied to.
 *  - [ethiopic]: Ethiopic (Ge'ez: Amharic, Tigrinya) code points, punctuation and numerals included.
 *  - [joining]: scripts whose letters join or reshape by context (Arabic, Syriac, Mongolian, N'Ko, ...); per-letter marks or
 *    spacing would break the shaping.
 *  - [complex]: other scripts with clusters or conjuncts (Indic, Thai, Khmer, Myanmar, Tibetan, ...); never spaced or marked.
 *  - [rtl]: strong right-to-left characters (Arabic, Hebrew, ...): frames wrap such a line in bidi isolates.
 *  - [otherLetters]: letters of any script that is not Latin or Ethiopic.
 */
class ScriptInfo(
    val asciiAlnum: Int,
    val latinLetters: Int,
    val ethiopic: Int,
    val joining: Int,
    val complex: Int,
    val rtl: Int,
    val otherLetters: Int,
    /** Letters whose strong direction is right-to-left (a subset of [otherLetters]). */
    val rtlLetters: Int,
    /** Letters that are not plain ASCII (accented Latin, Ethiopic, Arabic, CJK, ...). */
    val nonAsciiLetters: Int,
) {
    val hasLatin: Boolean get() = asciiAlnum > 0
    val hasEthiopic: Boolean get() = ethiopic > 0
    val hasJoining: Boolean get() = joining > 0
    val hasRtl: Boolean get() = rtl > 0

    /** Spacing letters apart or marking them would break the shaping of this text. */
    val shapingSensitive: Boolean get() = joining > 0 || complex > 0

    /** Every letter is a plain ASCII Latin letter (digits, spaces, punctuation and emoji do not count against it). */
    val onlyAsciiLetters: Boolean get() = nonAsciiLetters == 0

    val primary: Script
        get() {
            val latin = asciiAlnum > 0 || latinLetters > 0
            val kinds = listOf(latin, ethiopic > 0, rtl > 0 || joining > 0, otherLetters - rtlLetters > 0 || complex > 0)
            if (kinds.count { it } > 1) return Script.MIXED
            return when {
                latin -> Script.LATIN
                ethiopic > 0 -> Script.ETHIOPIC
                joining > 0 -> Script.RTL_JOINING
                rtl > 0 -> Script.RTL
                otherLetters > 0 -> Script.OTHER
                else -> Script.EMPTY
            }
        }

    companion object {
        val EMPTY = ScriptInfo(0, 0, 0, 0, 0, 0, 0, 0, 0)

        private val JOINING = setOf(
            Character.UnicodeScript.ARABIC, Character.UnicodeScript.SYRIAC, Character.UnicodeScript.MONGOLIAN,
            Character.UnicodeScript.NKO, Character.UnicodeScript.MANDAIC, Character.UnicodeScript.PHAGS_PA,
            Character.UnicodeScript.ADLAM, Character.UnicodeScript.HANIFI_ROHINGYA,
        )
        private val COMPLEX = setOf(
            Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI,
            Character.UnicodeScript.GUJARATI, Character.UnicodeScript.ORIYA, Character.UnicodeScript.TAMIL,
            Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM,
            Character.UnicodeScript.SINHALA, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
            Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR, Character.UnicodeScript.TIBETAN,
        )

        fun of(text: CharSequence): ScriptInfo {
            var ascii = 0; var latin = 0; var eth = 0; var join = 0; var cx = 0; var rtl = 0; var other = 0; var rtlLetters = 0; var nonAscii = 0
            var i = 0
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                i += Character.charCount(cp)
                if (cp < 0x80) {
                    if (cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code) { ascii++; latin++ } else if (cp in '0'.code..'9'.code) ascii++
                    continue
                }
                val script = try { Character.UnicodeScript.of(cp) } catch (e: IllegalArgumentException) { continue }
                val isLetter = Character.isLetter(cp)
                when {
                    script == Character.UnicodeScript.ETHIOPIC -> { eth++; if (isLetter) nonAscii++ }
                    script == Character.UnicodeScript.LATIN -> if (isLetter) { latin++; nonAscii++ }
                    script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED || script == Character.UnicodeScript.UNKNOWN -> {
                        // Arabic-Indic digits and the like carry no letter; a strong RTL mark still makes the line right-to-left.
                    }
                    else -> {
                        if (script in JOINING) join++
                        if (script in COMPLEX) cx++
                        if (isLetter) { other++; nonAscii++ }
                    }
                }
                val dir = Character.getDirectionality(cp)
                if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                    rtl++
                    if (isLetter) rtlLetters++
                }
            }
            return ScriptInfo(ascii, latin, eth, join, cx, rtl, other, rtlLetters, nonAscii)
        }
    }
}
