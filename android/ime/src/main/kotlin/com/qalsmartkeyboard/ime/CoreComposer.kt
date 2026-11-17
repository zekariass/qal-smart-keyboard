package com.qalsmartkeyboard.ime

import uniffi.qal_keyboard_core.CandidateKind
import uniffi.qal_keyboard_core.CoreException
import uniffi.qal_keyboard_core.EditorUpdate
import uniffi.qal_keyboard_core.InputMode
import uniffi.qal_keyboard_core.KeyEvent
import uniffi.qal_keyboard_core.KeyboardEngine
import uniffi.qal_keyboard_core.TranslitProfileStatus

/**
 * [Composer] over the Rust core (SD 6.2 `CoreSession`). Build it after the packs are loaded:
 * the phonetic modes offered are those whose `TRANSLITERATION_RULES` pack the engine accepted.
 * All calls come from the main thread; the engine is internally synchronised, so pack loading on
 * another thread is safe.
 */
class CoreComposer(private val engine: KeyboardEngine) : Composer {
    private val draft = HashMap<TypingMode, Boolean>()

    override val availableModes: List<TypingMode> = buildList {
        add(TypingMode.ABC)
        for (m in TypingMode.entries.filter { it.phonetic }) {
            try {
                draft[m] = engine.translitProfile(m.languageCode).status != TranslitProfileStatus.APPROVED
                add(m)
            } catch (_: CoreException) {
                // No usable profile for this language: the mode is not offered.
            }
        }
    }

    override fun onKey(key: ComposerKey): ComposerUpdate = engine.onKey(
        when (key) {
            is ComposerKey.Text -> KeyEvent.Char(key.text)
            ComposerKey.Backspace -> KeyEvent.Backspace
            ComposerKey.Space -> KeyEvent.Space
            ComposerKey.Enter -> KeyEvent.Enter
            ComposerKey.Commit -> KeyEvent.Commit
        },
    ).toUpdate()

    override fun selectCandidate(index: Int): ComposerUpdate =
        if (index < 0) ComposerUpdate.NONE else engine.selectCandidate(index.toUInt()).toUpdate()

    override fun reset() {
        engine.reset()
    }

    override fun setMode(mode: TypingMode): Boolean {
        if (mode !in availableModes) return false
        return try {
            engine.setInputMode(mode.languageCode, if (mode.phonetic) InputMode.PHONETIC_LATIN else InputMode.ABC)
            true
        } catch (_: CoreException) {
            false
        }
    }

    override fun isDraft(mode: TypingMode): Boolean = draft[mode] ?: false

    private fun EditorUpdate.toUpdate() = ComposerUpdate(
        composing = composing,
        commit = commit,
        deleteBefore = deleteBefore.toInt(),
        candidates = candidates.map {
            Suggestion(
                it.text,
                when (it.kind) {
                    CandidateKind.TRANSLITERATION -> SuggestionKind.TRANSLITERATION
                    CandidateKind.COMPLETION -> SuggestionKind.COMPLETION
                    CandidateKind.CORRECTION -> SuggestionKind.CORRECTION
                    CandidateKind.PREDICTION -> SuggestionKind.PREDICTION
                },
            )
        },
    )
}
