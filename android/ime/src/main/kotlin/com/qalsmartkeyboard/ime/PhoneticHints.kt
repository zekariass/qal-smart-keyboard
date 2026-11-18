package com.qalsmartkeyboard.ime

/**
 * The native character each QWERTY letter key produces on its own in Phonetic mode, drawn as a
 * small secondary label on the key. Derived from the engine itself ([derive], fed by
 * [CoreComposer.deriveHints] on the pack-loader thread) for the language's active profile, so am and
 * ti can differ and nothing is hard-coded; looked up from this cache when a layout is built, never
 * on the draw or key path. A key whose single press produces no Ethiopic text has no hint.
 */
class PhoneticHints(private val lower: Map<Char, String>, private val upper: Map<Char, String>) {
    /** Hint of the unshifted [letter] key ("" = none). */
    fun lower(letter: Char): String = lower[letter.lowercaseChar()].orEmpty()

    /** Hint of the shifted [letter] key ("" = none). */
    fun upper(letter: Char): String = upper[letter.lowercaseChar()].orEmpty()

    val isEmpty: Boolean get() = lower.isEmpty() && upper.isEmpty()

    companion object {
        const val LETTERS = "abcdefghijklmnopqrstuvwxyz"

        /**
         * Builds the hints by typing each letter alone, lower and upper case: [typeAlone] returns what
         * one key press produces in a fresh phonetic session (committed + composing text), or null.
         */
        fun derive(typeAlone: (String) -> String?): PhoneticHints {
            val lower = HashMap<Char, String>()
            val upper = HashMap<Char, String>()
            for (c in LETTERS) {
                hintOf(typeAlone(c.toString()))?.let { lower[c] = it }
                hintOf(typeAlone(c.uppercaseChar().toString()))?.let { upper[c] = it }
            }
            return PhoneticHints(lower, upper)
        }

        /** A usable hint: some Ethiopic text and no Latin letters left (an unmapped key echoes its letter). */
        internal fun hintOf(output: String?): String? {
            val t = output?.trim().orEmpty()
            if (t.isEmpty() || t.length > MAX_HINT_CHARS) return null
            if (t.any { it in 'a'..'z' || it in 'A'..'Z' }) return null
            return t.takeIf { s -> s.any { isEthiopic(it) } }
        }

        private const val MAX_HINT_CHARS = 2

        private fun isEthiopic(c: Char) = c in 'ሀ'..'᎟' || c in 'ⶀ'..'⷟' || c in '꬀'..'꬯'
    }
}
