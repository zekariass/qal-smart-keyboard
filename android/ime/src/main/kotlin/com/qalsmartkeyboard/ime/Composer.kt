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

    /** Commit any composition as it is (before an editor action, a mode switch, ...). */
    data object Commit : ComposerKey
}

/**
 * TEMPORARY (A1.3) typing modes, cycled by the mode key so Amharic/Tigrinya phonetic input can be
 * exercised before A1.4 (layouts, language switching) and A1.5 (saved per-language mode) exist.
 */
enum class TypingMode(val label: String, val languageCode: String, val phonetic: Boolean) {
    ABC("ABC", "en", false),
    AM_PHONETIC("አማ", "am", true),
    TI_PHONETIC("ትግ", "ti", true),
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

    /** Modes whose data is loaded, in toggle order; always starts with [TypingMode.ABC]. */
    val availableModes: List<TypingMode>

    /** True when [mode]'s transliteration profile is not native-speaker approved (rule N9). */
    fun isDraft(mode: TypingMode): Boolean
}

/** ABC-only composer used until the core and its packs are loaded: text is committed as typed. */
class PassthroughComposer : Composer {
    override fun onKey(key: ComposerKey): ComposerUpdate = when (key) {
        is ComposerKey.Text -> ComposerUpdate(commit = key.text)
        ComposerKey.Space -> ComposerUpdate(commit = " ")
        ComposerKey.Enter -> ComposerUpdate(commit = "\n")
        ComposerKey.Backspace -> ComposerUpdate(deleteBefore = 1)
        ComposerKey.Commit -> ComposerUpdate.NONE
    }

    override fun selectCandidate(index: Int) = ComposerUpdate.NONE
    override fun reset() {}
    override fun setMode(mode: TypingMode) = mode == TypingMode.ABC
    override val availableModes = listOf(TypingMode.ABC)
    override fun isDraft(mode: TypingMode) = false
}
