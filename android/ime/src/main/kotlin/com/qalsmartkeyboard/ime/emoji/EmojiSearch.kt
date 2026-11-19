package com.qalsmartkeyboard.ime.emoji

/**
 * Keyword search over the CLDR names and keywords (English always, plus the keyboard's language when its
 * annotations are loaded). Every word of the query must match a term of the emoji. A term scores
 * 0 when it equals the word, 1 when it starts with it, 2 when one of its words starts with it, 3 when it
 * contains it (three letters or more); the emoji's score is the sum over the query words, its best
 * term per word, and results are ordered by score, then by their place in the panel. So prefix matches
 * always come before substring matches. A match on the emoji's name (the first term) beats the same kind
 * of match on a keyword, so searching "cat" lists the cat before the grinning cat. Pure; no allocation per term.
 */
object EmojiSearch {
    const val MAX_RESULTS = 60

    /**
     * The form terms and queries are compared in: lower case, every apostrophe variant as `'`. CLDR spells the Afaan
     * Oromoo hudhaa `’` (`boo’uu`) while the Qubee key types `'`, so without this `boo'uu` would never find its emoji.
     */
    fun fold(s: String): String = s.lowercase().replace('’', '\'').replace('ʼ', '\'')

    fun search(catalog: EmojiCatalog, query: String, language: String?, limit: Int = MAX_RESULTS): List<EmojiEntry> {
        val words = fold(query.trim()).split(' ', '\t').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val local = language?.takeIf { it != "en" }?.let { catalog.language(it) }
        val scored = ArrayList<Pair<EmojiEntry, Int>>()
        for (e in catalog.entries) {
            var total = 0
            var ok = true
            for (w in words) {
                var best = scoreTerms(catalog.englishTerms(e), w)
                if (local != null) best = minOf(best, scoreTerms(local.terms.getOrNull(e.index).orEmpty(), w))
                if (best == NONE) { ok = false; break }
                total += best
            }
            if (ok) scored += e to total
        }
        // sortedBy is stable: equal scores keep the panel order.
        return scored.sortedBy { it.second }.take(limit).map { it.first }
    }

    private const val NONE = 99

    private fun scoreTerms(terms: List<String>, w: String): Int {
        var best = NONE
        for ((i, t) in terms.withIndex()) {
            val kind = when {
                t == w -> 0
                t.startsWith(w) -> 1
                t.contains(" $w") -> 2
                w.length >= 3 && t.contains(w) -> 3
                else -> continue
            }
            val s = kind * 2 + (if (i == 0) 0 else 1) // the name first, keywords after
            if (s < best) {
                best = s
                if (best == 0) break
            }
        }
        return best
    }
}
