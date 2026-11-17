package com.qalsmartkeyboard.ime

/** Why a candidate is offered (mirrors the core's `CandidateKind`). */
enum class SuggestionKind { TRANSLITERATION, COMPLETION, CORRECTION, PREDICTION }

/** One entry of the candidate strip. */
data class Suggestion(val text: String, val kind: SuggestionKind)

/**
 * What the editor must do after a key (mirrors the core's `EditorUpdate`): delete [deleteBefore]
 * grapheme clusters before the cursor (never UTF-16 units), commit [commit], then show
 * [composing] as the composing text (empty = no composition).
 */
data class ComposerUpdate(
    val composing: String = "",
    val commit: String = "",
    val deleteBefore: Int = 0,
    val candidates: List<Suggestion> = emptyList(),
) {
    companion object {
        val NONE = ComposerUpdate()
    }
}

/** Keys the composer understands (mirrors the subset of the core's `KeyEvent` the surface sends). */
sealed interface ComposerKey {
    data class Text(val text: String) : ComposerKey
    data object Backspace : ComposerKey
    data object Space : ComposerKey
    data object Enter : ComposerKey

    /** Direct Fidel, two-key entry: a consonant key (`FidelKey.series_id`). */
    data class FidelConsonant(val seriesId: String) : ComposerKey

    /** Direct Fidel, two-key entry: a vowel-order key (1..9). */
    data class FidelVowel(val order: Int) : ComposerKey

    /** Commit any composition as it is (before an editor action, a mode switch, ...). */
    data object Commit : ComposerKey
}

/**
 * The language engine as the keyboard sees it (SD 6.2 `CoreSession`). Implemented over the Rust
 * core by [CoreComposer]; [PassthroughComposer] covers the moments before the core is ready.
 * Implementations never log or store typed text (rule N2).
 */
interface Composer {
    fun onKey(key: ComposerKey): ComposerUpdate
    fun selectCandidate(index: Int): ComposerUpdate

    /** Drops any composition without committing it (the editor keeps what it shows). */
    fun reset()

    /** Switches mode, dropping any composition (commit it first). False if [mode] is unavailable. */
    fun setMode(mode: TypingMode): Boolean

    /** True when [mode]'s data is loaded (ABC is always supported). */
    fun supports(mode: TypingMode): Boolean

    /** True when [mode]'s data (layout or profile) is not native-speaker approved (rule N9). */
    fun isDraft(mode: TypingMode): Boolean

    /** The direct-Fidel layout data of [language] (also source of punctuation and numerals), if loaded. */
    fun fidelGrid(language: String): FidelGrid?
}

/** ABC-only composer used until the core and its packs are loaded: text is committed as typed. */
class PassthroughComposer : Composer {
    override fun onKey(key: ComposerKey): ComposerUpdate = when (key) {
        is ComposerKey.Text -> ComposerUpdate(commit = key.text)
        ComposerKey.Space -> ComposerUpdate(commit = " ")
        ComposerKey.Enter -> ComposerUpdate(commit = "\n")
        ComposerKey.Backspace -> ComposerUpdate(deleteBefore = 1)
        ComposerKey.Commit -> ComposerUpdate.NONE
        is ComposerKey.FidelConsonant, is ComposerKey.FidelVowel -> ComposerUpdate.NONE
    }

    override fun selectCandidate(index: Int) = ComposerUpdate.NONE
    override fun reset() {}
    override fun setMode(mode: TypingMode) = supports(mode)
    override fun supports(mode: TypingMode) = mode.kind == LayoutKind.ABC
    override fun isDraft(mode: TypingMode) = false
    override fun fidelGrid(language: String): FidelGrid? = null
}
