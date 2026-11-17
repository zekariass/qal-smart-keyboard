package com.qalsmartkeyboard.ime

enum class ShiftState { OFF, ONCE, LOCKED }

/**
 * Keyboard state machine: current page, shift, typing mode, candidates and key handling. Every
 * key goes through the [composer] (the Rust core once loaded) and the resulting update through
 * the [TextSink]. Contains no View code and never logs or stores typed text (rule N2).
 */
class KeyboardController(private val sink: TextSink, private val onChanged: () -> Unit) {
    var profile: InputProfile = InputProfile.DEFAULT
        private set
    var page: Page = Page.LETTERS
        private set
    var shift: ShiftState = ShiftState.OFF
        private set

    /** The engine. Replaced once by the service when the core and its packs are loaded. */
    var composer: Composer = PassthroughComposer()
        set(value) {
            field = value
            if (mode !in value.availableModes) mode = TypingMode.ABC
            applyMode()
            onChanged()
        }

    /**
     * The mode the user picked for text fields (TEMPORARY A1.3 toggle; A1.5 persists it per
     * language). Fields that must stay Latin use [TypingMode.ABC] whatever this is.
     */
    var mode: TypingMode = TypingMode.ABC
        private set

    /** The mode in force for the current field: transliteration is never forced (SD 6.2, 7). */
    val effectiveMode: TypingMode
        get() = if (profile.allowsTransliteration) mode else TypingMode.ABC

    /** Candidates of the last update, for the strip. */
    var candidates: List<Suggestion> = emptyList()
        private set

    /** The strip is shown for phonetic modes only, and never in password fields. */
    val showCandidateStrip: Boolean
        get() = effectiveMode.phonetic && profile.kind != FieldKind.PASSWORD

    /** The active profile is DRAFT: the strip must say it is not verified (rule N9). */
    val candidatesAreDraft: Boolean
        get() = composer.isDraft(effectiveMode)

    val layout: KeyboardLayout get() = Layouts.forPage(page, profile)

    fun startField(newProfile: InputProfile) {
        profile = newProfile
        page = Layouts.startPage(newProfile)
        shift = ShiftState.OFF
        applyMode()
        refreshAutoCapital()
        onChanged()
    }

    /** The composition was given up (field change, cursor moved, keyboard hidden). */
    fun onCompositionAbandoned() {
        composer.reset()
        candidates = emptyList()
        refreshAutoCapital()
        onChanged()
    }

    fun refreshAutoCapital() {
        if (shift == ShiftState.LOCKED || page != Page.LETTERS) return
        // Phonetic schemes give capitals their own meaning (SERA-style), so only ABC auto-capitalises.
        val wants = effectiveMode == TypingMode.ABC && profile.capsFlags != 0 && sink.wantsAutoCapital(profile.capsFlags)
        shift = if (wants) ShiftState.ONCE else ShiftState.OFF
    }

    fun onKey(key: Key) {
        when (key.kind) {
            KeyKind.CHAR -> {
                if (key.isSpacer) return
                val upper = shift != ShiftState.OFF && page == Page.LETTERS
                send(ComposerKey.Text(if (upper) key.text.uppercase() else key.text))
                if (shift == ShiftState.ONCE) shift = ShiftState.OFF
                refreshAutoCapital()
            }
            KeyKind.SPACE -> { send(ComposerKey.Space); refreshAutoCapital() }
            KeyKind.BACKSPACE -> { send(ComposerKey.Backspace); refreshAutoCapital() }
            KeyKind.ENTER -> {
                when (val a = profile.enterAction) {
                    EnterAction.Newline -> send(ComposerKey.Enter)
                    EnterAction.EnterKey -> { send(ComposerKey.Commit); sink.sendEnterKey() }
                    is EnterAction.Editor -> { send(ComposerKey.Commit); sink.performEditorAction(a.actionId) }
                }
                refreshAutoCapital()
            }
            KeyKind.SHIFT -> shift = when (shift) {
                ShiftState.OFF -> ShiftState.ONCE
                ShiftState.ONCE -> ShiftState.LOCKED
                ShiftState.LOCKED -> ShiftState.OFF
            }
            KeyKind.MODE -> cycleMode()
            KeyKind.TO_SYMBOLS -> page = Page.SYMBOLS
            KeyKind.TO_SYMBOLS_2 -> page = Page.SYMBOLS_2
            KeyKind.TO_LETTERS -> { page = Page.LETTERS; refreshAutoCapital() }
        }
        onChanged()
    }

    /** A tap on candidate [index] of the strip. */
    fun selectCandidate(index: Int) {
        if (index !in candidates.indices) return
        apply(composer.selectCandidate(index))
        refreshAutoCapital()
        onChanged()
    }

    /** TEMPORARY (A1.3): the mode key cycles through the loaded modes (ABC, አማ, ትግ). */
    private fun cycleMode() {
        val modes = composer.availableModes
        if (!profile.allowsTransliteration || modes.size < 2) return
        send(ComposerKey.Commit)
        mode = modes[(modes.indexOf(mode) + 1) % modes.size]
        applyMode()
        refreshAutoCapital()
    }

    private fun applyMode() {
        candidates = emptyList()
        if (!composer.setMode(effectiveMode)) {
            mode = TypingMode.ABC
            composer.setMode(TypingMode.ABC)
        }
    }

    private fun send(key: ComposerKey) = apply(composer.onKey(key))

    private fun apply(update: ComposerUpdate) {
        sink.apply(update)
        candidates = update.candidates
    }
}
