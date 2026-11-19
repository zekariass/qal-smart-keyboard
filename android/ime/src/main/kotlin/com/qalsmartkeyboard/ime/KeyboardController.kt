package com.qalsmartkeyboard.ime

import com.qalsmartkeyboard.ime.emoji.EmojiController
import com.qalsmartkeyboard.ime.emoji.EmojiExecutor
import com.qalsmartkeyboard.ime.emoji.EmojiHost
import com.qalsmartkeyboard.ime.emoji.EmojiLoader
import com.qalsmartkeyboard.ime.emoji.EmojiStore
import com.qalsmartkeyboard.ime.userdict.WordLearner
import com.qalsmartkeyboard.ime.userdict.WordLearningSink

enum class ShiftState { OFF, ONCE, LOCKED }

/**
 * A text field outside the host editor that the keys type into: a field of an AI panel (ADR 0031), with the same
 * redirect as the emoji search and Word Art. Nothing typed there reaches the host field or the personal dictionary.
 */
interface FieldTyping {
    val sink: TextSink

    /** Enter adds a line break (multi-line fields) instead of ending the typing. */
    val newlineOnEnter: Boolean

    /** Typing ends (Enter on a one-line field, the emoji key): the open composition was already committed into [sink]. */
    fun done()
}

/**
 * Keyboard state machine: current page, shift, language and layout, candidates and key handling.
 * Every key goes through the [composer] (the Rust core once loaded) and the resulting update through
 * the [TextSink]. Contains no View code and never logs or stores typed text (rule N2).
 *
 * Switching (A1.4, SD 7.1): the globe taps to the next enabled language and long-presses to cycle
 * the current language's layouts; the space bar long-press picks a language; the ABC key toggles
 * between Latin passthrough and the language's last native layout. am/ti also have a writing-mode
 * selector (Fidel, Phonetic, English = ABC; [writingModeOptions]) whose choice is remembered per
 * language. Every switch first commits the open composition as it is, so no text is lost or typed twice.
 */
class KeyboardController(
    private val sink: TextSink,
    /** The languages the user enabled, in switching order (A1.5's settings store supplies this). */
    private val languages: EnabledLanguages = DefaultEnabledLanguages,
    /** Remembered layouts, numeral style, haptics, key height (A1.5). */
    val prefs: ModePreferences = InMemoryModePreferences(),
    /** Where learned and explicitly added words go (A1.8). */
    private val dictionary: WordLearningSink = WordLearningSink.None,
    /** Emoji panel (ADR 0028): its local recents store, its lazy data loader (null: no panel) and where it loads. */
    emojiStore: EmojiStore = EmojiStore.None,
    emojiLoader: EmojiLoader? = null,
    emojiExecutor: EmojiExecutor = EmojiExecutor.Inline,
    /** Puts text on the clipboard for Word Art's Copy (the service owns the ClipboardManager); false: not possible. */
    private val clipboard: (String) -> Boolean = { false },
    private val onChanged: () -> Unit,
) {
    private val learner = WordLearner(dictionary)

    /**
     * The emoji panel. While it is searching, the composer's output goes to its query instead of the host field
     * ([apply]), and nothing typed there is learned.
     */
    val emoji: EmojiController = EmojiController(
        object : EmojiHost {
            override fun commitComposition() = send(ComposerKey.Commit)
            override fun resetComposition() {
                composer.reset()
                learner.reset()
                candidates = emptyList()
                composing = false
                pendingSeries = null
            }
            override fun commitToField(text: String) {
                sink.apply(ComposerUpdate(commit = text))
                learner.reset() // an emoji ends the word
            }
            override val language: String get() = this@KeyboardController.language
            override fun selectedText(): String? = sink.selectedText()
            override fun copyToClipboard(text: String): Boolean = clipboard(text)
        },
        emojiStore, emojiLoader, emojiExecutor,
    ) { onChanged() }

    /** While it answers a [FieldTyping], the keys type into that AI panel field instead of the host (set by the service). */
    var fieldTyping: () -> FieldTyping? = { null }

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
            orderRows.clear()
            pendingSeries = null
            cachedLayout = null
            applyMode()
            onChanged()
        }

    /** The language being typed (first run: the first enabled language). */
    var language: String = languages.enabled().firstOrNull() ?: "en"
        private set

    /** The layout the user picked inside [language]; ABC is the one-tap toggle (am/ti: the English writing mode). */
    private var kind: LayoutKind = startKind(language)

    /** The user switched language or layout in this keyboard session (the first settings load then leaves the choice alone). */
    private var userSwitched = false

    /**
     * The layout a language opens on before its data is known: the saved writing mode (am/ti), else
     * the last native layout, else the language's input-mode setting, else its first native layout.
     */
    private fun startKind(lang: String): LayoutKind =
        prefs.writingMode(lang) ?: prefs.lastNative(lang) ?: prefs.preferredKind(lang)
            ?: LanguageCatalog.nativeKinds(lang).firstOrNull() ?: LayoutKind.ABC

    /** The layout a switch to [lang] lands on: its saved writing mode if usable, else its last native layout. */
    private fun landingKind(lang: String): LayoutKind =
        prefs.writingMode(lang)?.takeIf { it == LayoutKind.ABC || composer.supports(TypingMode(lang, it)) }
            ?: nativeToReturnTo(lang) ?: LayoutKind.ABC

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

    /**
     * The consonant family (`FidelKey.series_id`) whose default form is the pending composition of the
     * two-key Fidel layout, or null. It drives the vowel-order row and the selected-consonant highlight.
     */
    var pendingSeries: String? = null
        private set

    private val orderRows = HashMap<String, FidelOrderRow?>()

    private fun orderRow(): FidelOrderRow? {
        val lang = effectiveMode.language
        return if (orderRows.containsKey(lang)) orderRows[lang] else (grid(lang)?.let { FidelOrderRow(it) }).also { orderRows[lang] = it }
    }

    /** The face of the vowel-order [key] in the current state (family forms while a consonant is pending, else neutral). */
    fun orderFace(key: Key): OrderFace =
        orderRow()?.face(pendingSeries, key.order) ?: OrderFace(key.order, key.label, active = false)

    /** [key] is the consonant key of the pending family (drawn highlighted, spoken as selected). */
    fun isSelectedConsonant(key: Key): Boolean = key.kind == KeyKind.FIDEL_CONSONANT && key.text == pendingSeries

    /** Key press previews apply: the setting is on and the field is not a password field (never echo what is typed there). */
    val keyPreviewsActive: Boolean
        get() = prefs.keyPreviewEnabled && profile.kind != FieldKind.PASSWORD

    /** The strip is shown for phonetic modes only, and never in password fields. */
    val showCandidateStrip: Boolean
        get() = effectiveMode.phonetic && profile.kind != FieldKind.PASSWORD && prefs.showCandidates(language)

    /** The active profile or layout is DRAFT: the UI must say it is not verified (rule N9). */
    val candidatesAreDraft: Boolean
        get() = composer.isDraft(effectiveMode)

    /** Spoken form of the current language and layout, announced to accessibility services on a switch. */
    val modeDescription: String
        get() = effectiveMode.let {
            val kindName = if (LanguageCatalog.isEthiopic(it.language)) LanguageCatalog.spokenWritingMode(it.kind) else LanguageCatalog.spokenKind(it.kind)
            KeyboardLabels.current.modeDescription.format(LanguageCatalog.spokenName(it.language), kindName)
        }

    /**
     * The writing modes the selector offers in this field (am/ti: Fidel, Phonetic, English; only
     * those whose data is loaded). Empty = no selector: om/en, fields that stay Latin (password,
     * email, ...) and while the core is loading.
     */
    val writingModeOptions: List<LayoutKind>
        get() {
            if (!profile.allowsTransliteration) return emptyList()
            val all = LanguageCatalog.writingModes(language).filter { it == LayoutKind.ABC || composer.supports(TypingMode(language, it)) }
            return if (all.size >= 2) all else emptyList()
        }

    /** The active writing mode (what the selector shows as selected). */
    val writingMode: LayoutKind
        get() = effectiveMode.kind

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

    /** The UI language's labels were (re)loaded ([KeyboardLabels]): key descriptions and the space bar are built again. */
    fun onLabelsChanged() {
        cachedLayout = null
    }

    fun onPreferencesChanged() {
        cachedLayout = null
        val all = availableLanguages()
        val first = languages.enabled().firstOrNull()
        // The first settings load decides the starting language and its saved writing mode (the
        // controller may have been built from the defaults); later edits only move away from a disabled one.
        val firstLoad = !settled && !userSwitched
        val moveToFirst = firstLoad && first != null && first != language
        settled = true
        if (moveToFirst && first != null) {
            send(ComposerKey.Commit)
            language = first
            kind = startKind(first)
            afterSwitch()
        } else if (all.isNotEmpty() && language !in all && language !in languages.enabled()) {
            send(ComposerKey.Commit)
            language = all.first()
            kind = landingKind(language)
            afterSwitch()
        } else if (firstLoad && startKind(language) != kind) {
            send(ComposerKey.Commit)
            kind = startKind(language)
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
            val hints = if (m.kind == LayoutKind.PHONETIC) composer.phoneticHints(m.language) else null
            val ctx = LayoutContext(m, grid(m.language), indicator, languagePicker(), composer.isDraft(m), prefs.ethiopicNumerals, hints)
            val base = Layouts.forPage(page, profile, ctx)
            val scaled = KeyboardLayout(base.page, base.rows, base.rowHeightDp * prefs.keyHeightScale)
            return scaled.also { cachedLayout = key to it }
        }

    fun startField(newProfile: InputProfile) {
        emoji.close()
        profile = newProfile
        page = Layouts.startPage(newProfile)
        shift = ShiftState.OFF
        applyMode()
        refreshAutoCapital()
        onChanged()
    }

    /** Commits the open composition as typed (the AI toolbar needs final text in the editor before it reads it). */
    fun commitComposition() {
        send(ComposerKey.Commit)
        onChanged()
    }

    /**
     * Voice typing (ADR 0029): commits recognised [text] to the field, after finishing the open composition as typed.
     * The text goes straight to the sink, never through the composer or the word learner: dictated words are not
     * learned into the personal dictionary (privacy, and recognition errors would poison it), and they end the word.
     */
    fun commitVoiceText(text: String) {
        send(ComposerKey.Commit)
        if (text.isNotEmpty()) sink.apply(ComposerUpdate(commit = text))
        learner.reset()
        refreshAutoCapital()
        onChanged()
    }

    /** The field's auto-capitalisation would capitalise a letter at the cursor now (voice typing capitalises English there). */
    fun wantsCapitalAtCursor(): Boolean = profile.capsFlags != 0 && sink.wantsAutoCapital(profile.capsFlags)

    /** The composition was given up (field change, cursor moved, keyboard hidden). */
    fun onCompositionAbandoned() {
        composer.reset()
        learner.reset()
        candidates = emptyList()
        composing = false
        pendingSeries = null
        refreshAutoCapital()
        onChanged()
    }

    fun refreshAutoCapital() {
        if (shift == ShiftState.LOCKED || page != Page.LETTERS) return
        // Phonetic schemes give capitals their own meaning (SERA-style), so only Latin passthrough
        // layouts (ABC, Qubee, English) auto-capitalise.
        val m = effectiveMode
        val wants = m.kind.latin && !m.phonetic && profile.capsFlags != 0 && emoji.redirect == null && fieldTyping() == null &&
            sink.wantsAutoCapital(profile.capsFlags)
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
            KeyKind.FIDEL_CONSONANT -> {
                send(ComposerKey.FidelConsonant(key.text))
                // The core now shows this family's default form as the composition; an unknown id leaves none open.
                pendingSeries = if (composing) key.text else null
            }
            KeyKind.FIDEL_VOWEL -> if (pendingSeries != null && orderFace(key).active) send(ComposerKey.FidelVowel(key.order))
            KeyKind.SPACE -> { send(ComposerKey.Space); refreshAutoCapital() }
            KeyKind.BACKSPACE -> { send(ComposerKey.Backspace); refreshAutoCapital() }
            KeyKind.ENTER -> if (fieldTyping() != null) {
                val f = fieldTyping()
                if (f != null && f.newlineOnEnter) send(ComposerKey.Enter) else { send(ComposerKey.Commit); f?.done() }
            } else if (emoji.typingWordArt) { send(ComposerKey.Commit); emoji.endWordArtTyping() } else if (emoji.searching) send(ComposerKey.Commit) else {
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
            KeyKind.EMOJI -> if (fieldTyping() != null) { send(ComposerKey.Commit); fieldTyping()?.done() } else if (emoji.searching) emoji.closeSearch() else if (emoji.typingWordArt) emoji.endWordArtTyping() else emoji.open(profile.allowsEmoji)
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

    /**
     * Long-press on candidate [index]: adds its text to the personal dictionary of the current
     * language (A1.8). False (nothing added) in fields that do not learn, or if the text is not an
     * acceptable word of the language.
     */
    fun addCandidateToDictionary(index: Int): Boolean {
        val text = candidates.getOrNull(index)?.text ?: return false
        val m = effectiveMode
        if (!profile.learnsWords || !WordLearner.isLearnable(m.language, text)) return false
        return dictionary.add(m.language, text)
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
            description = KeyboardLabels.current.pickerSwitchTo.format(LanguageCatalog.spokenName(it)),
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
        userSwitched = true
        if (newKind != LayoutKind.ABC) prefs.setLastNative(language, newKind)
        // am/ti remember the selected writing mode, English (ABC) included; om keeps ABC temporary.
        if (newKind in LanguageCatalog.writingModes(language)) prefs.setWritingMode(language, newKind)
    }

    private fun afterSwitch() {
        emoji.onLanguageChanged()
        page = Page.LETTERS
        shift = ShiftState.OFF
        applyMode()
        refreshAutoCapital()
    }

    /** Switches to [lang] (a no-op if it is not available), landing on its saved writing mode or last native layout. */
    fun switchLanguage(lang: String) {
        if (!profile.allowsTransliteration || lang == language || lang !in availableLanguages()) return
        finishComposition()
        language = lang
        userSwitched = true
        kind = landingKind(lang)
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

    /**
     * The writing-mode selector picked [newKind] (am/ti). The open composition is committed first;
     * the choice is remembered for the language. A mode that is not offered is ignored.
     */
    fun selectWritingMode(newKind: LayoutKind) {
        if (newKind !in writingModeOptions || newKind == mode.kind) return
        finishComposition()
        setKind(newKind)
        afterSwitch()
        onChanged()
    }

    /**
     * One-tap ABC toggle (SD 7.1): passthrough <-> the language's last native layout. In am/ti this is
     * the English writing mode and back to the last of Fidel/Phonetic.
     */
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
        learner.reset()
        candidates = emptyList()
        composing = false
        pendingSeries = null
        val m = effectiveMode
        if (!composer.setMode(m)) composer.setMode(TypingMode.abc(m.language))
    }

    private fun send(key: ComposerKey) = apply(composer.onKey(key))

    private fun apply(update: ComposerUpdate) {
        // While the emoji search or the Word Art text field is open the keys type into it, not into the host field, and nothing is learned.
        // An AI panel field (ADR 0031) takes the keys the same way.
        val redirect = emoji.redirect ?: fieldTyping()?.sink
        val searching = redirect != null
        (redirect ?: sink).apply(update)
        val m = effectiveMode
        // A Latin word typed in ABC is not an Amharic or Tigrinya word (SD 7.4: learn per language and script).
        learner.observe(update, m.language, !searching && profile.learnsWords && !(m.isAbc && LanguageCatalog.isEthiopic(m.language)))
        candidates = update.candidates
        composing = update.composing.isNotEmpty()
        // Anything that commits or cancels the composition (space, another consonant's commit, backspace, an order key,
        // a mode switch) ends the family selection; the consonant branch of [onKey] sets it again.
        if (!composing) pendingSeries = null
    }
}
