package com.qalsmartkeyboard.ime

import uniffi.qal_keyboard_core.CandidateKind
import uniffi.qal_keyboard_core.CoreException
import uniffi.qal_keyboard_core.EditorUpdate
import uniffi.qal_keyboard_core.FidelLayout
import uniffi.qal_keyboard_core.InputMode
import uniffi.qal_keyboard_core.KeyEvent
import uniffi.qal_keyboard_core.KeyboardEngine
import uniffi.qal_keyboard_core.PredictionOptions
import uniffi.qal_keyboard_core.ReleaseChannel
import uniffi.qal_keyboard_core.ResourceType
import uniffi.qal_keyboard_core.TranslitProfileStatus

/**
 * [Composer] over the Rust core (SD 6.2 `CoreSession`). Build it after the packs are loaded:
 * the phonetic modes offered are those whose `TRANSLITERATION_RULES` pack the engine accepted.
 * All calls come from the main thread; the engine is internally synchronised, so pack loading on
 * another thread is safe.
 */
class CoreComposer(
    private val engine: KeyboardEngine,
    /** Phonetic key hints per language, derived off the main thread by [deriveHints] before the engine is handed over. */
    private val hints: Map<String, PhoneticHints> = emptyMap(),
) : Composer {
    /** Languages that can have layout or profile packs (Arabic, ADR 0034, needs none). */
    private val languages = listOf("am", "ti", "om", "en")
    private val draft = HashMap<TypingMode, Boolean>()
    private val grids = HashMap<String, FidelGrid>()
    private val phoneticOk = HashSet<String>()

    init {
        // Qubee and English suggestions come from the om / en DICTIONARY pack: DRAFT unless that pack was released on the
        // STABLE channel (N9). Arabic suggestions are the user's own words only (ADR 0039): not DRAFT data.
        val packs = engine.loadedPacks()
        for ((lang, kind) in listOf("om" to LayoutKind.QUBEE, "en" to LayoutKind.ENGLISH)) {
            draft[TypingMode(lang, kind)] = packs.none {
                it.languageCode == lang && it.resourceType == ResourceType.DICTIONARY && it.releaseChannel == ReleaseChannel.STABLE
            }
        }
        for (lang in languages) {
            try {
                grids[lang] = engine.fidelLayout(lang).toGrid()
            } catch (_: CoreException) {
                // No usable layout pack for this language: the Fidel grid is not offered.
            }
            try {
                val phonetic = TypingMode(lang, LayoutKind.PHONETIC)
                draft[phonetic] = engine.translitProfile(lang).status != TranslitProfileStatus.APPROVED
                phoneticOk += lang
            } catch (_: CoreException) {
                // No usable profile: the phonetic layout is not offered.
            }
        }
    }

    override fun supports(mode: TypingMode): Boolean = when (mode.kind) {
        LayoutKind.ABC -> true
        LayoutKind.FIDEL -> mode.language in grids
        LayoutKind.PHONETIC -> mode.language in phoneticOk
        LayoutKind.QUBEE -> mode.language == "om"
        LayoutKind.ENGLISH -> mode.language == "en"
        LayoutKind.ARABIC -> mode.language == "ar"
    }

    override fun fidelGrid(language: String): FidelGrid? = grids[language]

    override fun phoneticHints(language: String): PhoneticHints? = hints[language]

    override fun onKey(key: ComposerKey): ComposerUpdate = engine.onKey(
        when (key) {
            is ComposerKey.Text -> KeyEvent.Char(key.text)
            ComposerKey.Backspace -> KeyEvent.Backspace
            ComposerKey.Space -> KeyEvent.Space
            ComposerKey.Enter -> KeyEvent.Enter
            ComposerKey.Commit -> KeyEvent.Commit
            is ComposerKey.FidelConsonant -> KeyEvent.FidelConsonant(key.seriesId)
            is ComposerKey.FidelVowel -> KeyEvent.FidelVowel(key.order.toUByte())
        },
    ).toUpdate()

    override fun selectCandidate(index: Int): ComposerUpdate =
        if (index < 0) ComposerUpdate.NONE else engine.selectCandidate(index.toUInt()).toUpdate()

    override fun reset() {
        engine.reset()
    }

    override fun setPredictionOptions(options: PredictionSettings) =
        engine.setPredictionOptions(PredictionOptions(options.suggestions, options.nextWord, options.autocorrect))

    override fun setCursorContext(before: String, after: String): ComposerUpdate =
        engine.setCursorContext(before, after).toUpdate()

    override fun setMode(mode: TypingMode): Boolean {
        if (!supports(mode)) return false
        return try {
            engine.setInputMode(
                mode.language,
                when (mode.kind) {
                    LayoutKind.ABC -> InputMode.ABC
                    LayoutKind.FIDEL -> InputMode.DIRECT_FIDEL
                    LayoutKind.PHONETIC -> InputMode.PHONETIC_LATIN
                    LayoutKind.QUBEE -> InputMode.QUBEE
                    LayoutKind.ENGLISH -> InputMode.ENGLISH
                    LayoutKind.ARABIC -> InputMode.ARABIC
                },
            )
            true
        } catch (_: CoreException) {
            false
        }
    }

    override fun isDraft(mode: TypingMode): Boolean = when (mode.kind) {
        LayoutKind.FIDEL -> grids[mode.language]?.draft ?: false
        else -> draft[mode] ?: false
    }

    companion object {
        /**
         * Feeds every letter key alone (lower and upper case) into a fresh phonetic session of each
         * language whose profile [engine] accepted, and returns what each produced (PhoneticHints).
         * Call it on the loader thread **before** the engine is used for typing: it changes the
         * engine's mode and leaves it reset (the composer sets the real mode afterwards). Languages
         * without a usable profile get no entry; nothing typed here is ever kept or logged.
         */
        fun deriveHints(engine: KeyboardEngine, languages: List<String> = listOf("am", "ti")): Map<String, PhoneticHints> {
            val out = HashMap<String, PhoneticHints>()
            for (lang in languages) {
                try {
                    engine.setInputMode(lang, InputMode.PHONETIC_LATIN)
                    val h = PhoneticHints.derive { key ->
                        engine.reset()
                        val u = engine.onKey(KeyEvent.Char(key))
                        u.commit + u.composing
                    }
                    if (!h.isEmpty) out[lang] = h
                } catch (_: CoreException) {
                    // No usable profile for this language: no hints.
                } finally {
                    engine.reset()
                }
            }
            return out
        }
    }

    private fun FidelLayout.toGrid() = FidelGrid(
        language = languageCode,
        layoutId = layoutId,
        draft = status != "APPROVED",
        longPress = longPress,
        twoKey = twoKey,
        defaultOrder = defaultOrder.toInt(),
        rows = rows.map { row ->
            row.map { k -> GridKey(k.seriesId, k.label, k.tapText, k.forms.map { GridForm(it.order.toInt(), it.label, it.text) }) }
        },
        vowelKeys = vowelKeys.map { GridVowel(it.order.toInt(), it.label) },
        punctuation = punctuation,
        digits = digits.associate { it.digit to it.alternatives },
    )

    private fun EditorUpdate.toUpdate() = ComposerUpdate(
        composing = composing,
        commit = commit,
        deleteBefore = deleteBefore.toInt(),
        autocorrectIndex = autocorrectIndex?.toInt() ?: -1,
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
