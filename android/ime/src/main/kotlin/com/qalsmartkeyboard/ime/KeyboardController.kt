package com.qalsmartkeyboard.ime

enum class ShiftState { OFF, ONCE, LOCKED }

/**
 * Keyboard state machine: current page, shift, language and layout, candidates and key handling.
 * Every key goes through the [composer] (the Rust core once loaded) and the resulting update through
 * the [TextSink]. Contains no View code and never logs or stores typed text (rule N2).
 *
 * Switching (A1.4, SD 7.1): the globe taps to the next enabled language and long-presses to cycle
 * the current language's layouts; the space bar long-press picks a language; the ABC key toggles
 * between Latin passthrough and the language's last native layout. Every switch first commits the
 * open composition as it is, so no text is lost or typed twice.
 */
class KeyboardController(
    private val sink: TextSink,
    /** The languages the user enabled, in switching order (A1.5's settings store supplies this). */
    private val languages: EnabledLanguages = DefaultEnabledLanguages,
    /** Remembered layouts, numeral style, haptics, key height (A1.5). */
    val prefs: ModePreferences = InMemoryModePreferences(),
    private val onChanged: () -> Unit,
) {
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
            gridCache.clear()
            cachedLayout = null
            applyMode()
            onChanged()
        }

    /** The language being typed (first run: the first enabled language). */
    var language: String = languages.enabled().firstOrNull() ?: "en"
        private set

    /** The layout the user picked inside [language]; ABC is the one-tap toggle. */
    private var kind: LayoutKind =
        prefs.lastNative(language) ?: prefs.preferredKind(language) ?: LanguageCatalog.nativeKinds(language).firstOrNull() ?: LayoutKind.ABC

    /**
     * The user's mode, falling back to ABC while its data is not loaded (before the core is ready,
     * or when a pack was rejected). Fields that must stay Latin use [effectiveMode].
     */
    val mode: TypingMode
        get() = TypingMode(language, kind).let { if (composer.supports(it)) it else TypingMode.abc(language) }

    /** The mode in force for the current field: transliteration is never forced (SD 6.2, 7). */
    val effectiveMode: TypingMode
        get() = if (profile.allowsTransliteration) mode else TypingMode.abc(language)

    /** Candidates of the last update, for the strip. */
    var candidates: List<Suggestion> = emptyList()
        private set

    /** A composition (e.g. a pending Fidel consonant) is open: the vowel-order keys are live. */
    var composing: Boolean = false
        private set

    /** The strip is shown for phonetic modes only, and never in password fields. */
    val showCandidateStrip: Boolean
        get() = effectiveMode.phonetic && profile.kind != FieldKind.PASSWORD && prefs.showCandidates(language)

    /** The active profile or layout is DRAFT: the UI must say it is not verified (rule N9). */
    val candidatesAreDraft: Boolean
        get() = composer.isDraft(effectiveMode)

    /** Spoken form of the current language and layout, announced to accessibility services on a switch. */
    val modeDescription: String
        get() = effectiveMode.let { "${LanguageCatalog.spokenName(it.language)}, ${LanguageCatalog.spokenKind(it.kind)}" }

    /** The mode indicator for the space bar (SD 7.1), e.g. `አማ · ABC→አ`. */
    val indicator: String
        get() {
            val m = effectiveMode
            return LanguageCatalog.indicator(m, if (m.isAbc) nativeToReturnTo() else null)
        }

    /**
     * The settings changed (A1.5): drop cached layouts, and if the current language was disabled
     * move to the first available one. Safe to call at any time.
     */
    private var settled = false

    fun onPreferencesChanged() {
        cachedLayout = null
        val all = availableLanguages()
        val first = languages.enabled().firstOrNull()
        // The first settings load decides the starting language; later edits only move away from a disabled one.
        val moveToFirst = !settled && first != null && first != language
        settled = true
        if (moveToFirst && first != null) {
            send(ComposerKey.Commit)
            language = first
            kind = prefs.lastNative(first) ?: prefs.preferredKind(first) ?: LanguageCatalog.nativeKinds(first).firstOrNull() ?: LayoutKind.ABC
            afterSwitch()
        } else if (all.isNotEmpty() && language !in all && language !in languages.enabled()) {
            send(ComposerKey.Commit)
            language = all.first()
            kind = nativeToReturnTo(language) ?: LayoutKind.ABC
            afterSwitch()
        }
        onChanged()
    }

    private val gridCache = HashMap<String, FidelGrid?>()

    private fun grid(lang: String): FidelGrid? =
        if (gridCache.containsKey(lang)) gridCache[lang] else composer.fidelGrid(lang).also { gridCache[lang] = it }

    private data class LayoutKey(
        val page: Page, val profile: InputProfile, val mode: TypingMode, val langs: List<String>,
        val ethiopicNumerals: Boolean, val heightScale: Float,
    )

    private var cachedLayout: Pair<LayoutKey, KeyboardLayout>? = null

    val layout: KeyboardLayout
        get() {
            val m = effectiveMode
            val key = LayoutKey(page, profile, m, availableLanguages(), prefs.ethiopicNumerals, prefs.keyHeightScale)
            cachedLayout?.takeIf { it.first == key }?.let { return it.second }
            val ctx = LayoutContext(m, grid(m.language), indicator, languagePicker(), composer.isDraft(m), prefs.ethiopicNumerals)
            val base = Layouts.forPage(page, profile, ctx)
            val scaled = KeyboardLayout(base.page, base.rows, base.rowHeightDp * prefs.keyHeightScale)
            return scaled.also { cachedLayout = key to it }
        }

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
        composing = false
        refreshAutoCapital()
        onChanged()
    }

    fun refreshAutoCapital() {
        if (shift == ShiftState.LOCKED || page != Page.LETTERS) return
        // Phonetic schemes give capitals their own meaning (SERA-style), so only Latin passthrough
        // layouts (ABC, Qubee, English) auto-capitalise.
        val m = effectiveMode
        val wants = m.kind.latin && !m.phonetic && profile.capsFlags != 0 && sink.wantsAutoCapital(profile.capsFlags)
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
            KeyKind.FIDEL_CONSONANT -> send(ComposerKey.FidelConsonant(key.text))
            KeyKind.FIDEL_VOWEL -> send(ComposerKey.FidelVowel(key.order))
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
            KeyKind.GLOBE -> { nextLanguage(); return }
            KeyKind.ABC_TOGGLE -> { toggleAbc(); return }
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

    // ------------------------------------------------------------------ language and layout switching

    /** Enabled languages that have at least one layout whose data is loaded, in switching order. */
    fun availableLanguages(): List<String> = languages.enabled().filter { l ->
        LanguageCatalog.nativeKinds(l).any { composer.supports(TypingMode(l, it)) }
    }

    /** The space-bar language picker: one entry per available language. */
    fun languagePicker(): List<PopupItem> = availableLanguages().map {
        PopupItem(
            LanguageCatalog.displayName(it),
            language = it,
            description = "Switch to ${LanguageCatalog.spokenName(it)}",
            selected = it == language,
        )
    }

    private fun nativeToReturnTo(lang: String = language): LayoutKind? =
        prefs.lastNative(lang)?.takeIf { composer.supports(TypingMode(lang, it)) }
            ?: prefs.preferredKind(lang)?.takeIf { composer.supports(TypingMode(lang, it)) }
            ?: LanguageCatalog.nativeKinds(lang).firstOrNull { composer.supports(TypingMode(lang, it)) }

    /** Ends the open composition (committed as it is) before the mode changes: nothing is lost or typed twice. */
    private fun finishComposition() {
        send(ComposerKey.Commit)
    }

    private fun setKind(newKind: LayoutKind) {
        kind = newKind
        if (newKind != LayoutKind.ABC) prefs.setLastNative(language, newKind)
    }

    private fun afterSwitch() {
        page = Page.LETTERS
        shift = ShiftState.OFF
        applyMode()
        refreshAutoCapital()
    }

    /** Switches to [lang] (a no-op if it is not available), landing on its last native layout. */
    fun switchLanguage(lang: String) {
        if (!profile.allowsTransliteration || lang == language || lang !in availableLanguages()) return
        finishComposition()
        language = lang
        kind = nativeToReturnTo(lang) ?: LayoutKind.ABC
        afterSwitch()
        onChanged()
    }

    /** Globe tap: the next available language. */
    fun nextLanguage() {
        val all = availableLanguages()
        if (all.isEmpty()) return
        switchLanguage(all[(all.indexOf(language) + 1) % all.size])
    }

    /** Globe long-press: cycles the current language's layouts (SD 7.1), ending on ABC. */
    fun cycleLayout() {
        if (!profile.allowsTransliteration) return
        val kinds = LanguageCatalog.nativeKinds(language).filter { composer.supports(TypingMode(language, it)) } + LayoutKind.ABC
        if (kinds.size < 2) return
        finishComposition()
        setKind(kinds[(kinds.indexOf(mode.kind) + 1) % kinds.size])
        afterSwitch()
        onChanged()
    }

    /** One-tap ABC toggle (SD 7.1): passthrough <-> the language's last native layout. */
    fun toggleAbc() {
        if (!profile.allowsTransliteration) return
        if (mode.isAbc) {
            val back = nativeToReturnTo() ?: return
            finishComposition()
            setKind(back)
        } else {
            finishComposition()
            setKind(LayoutKind.ABC)
        }
        afterSwitch()
        onChanged()
    }

    /** A long-press action key (the globe) was held. */
    fun onLongPress(key: Key) {
        if (key.kind == KeyKind.GLOBE) cycleLayout()
    }

    /** A popup choice was picked: a language of the picker, or text committed as typed. */
    fun onPopupSelected(item: PopupItem) {
        val lang = item.language
        if (lang != null) {
            switchLanguage(lang)
            return
        }
        send(ComposerKey.Text(item.text))
        refreshAutoCapital()
        onChanged()
    }

    private fun applyMode() {
        candidates = emptyList()
        composing = false
        val m = effectiveMode
        if (!composer.setMode(m)) composer.setMode(TypingMode.abc(m.language))
    }

    private fun send(key: ComposerKey) = apply(composer.onKey(key))

    private fun apply(update: ComposerUpdate) {
        sink.apply(update)
        candidates = update.candidates
        composing = update.composing.isNotEmpty()
    }
}
