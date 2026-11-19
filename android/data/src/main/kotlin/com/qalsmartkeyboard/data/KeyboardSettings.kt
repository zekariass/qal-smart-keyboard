package com.qalsmartkeyboard.data

/** Input modes of a typing language; names match the contract's `InputMode` enum (SD 7.1, 10.2). */
enum class InputMode { DIRECT_SCRIPT_LAYOUT, PHONETIC_LATIN_TO_NATIVE, LATIN_PASSTHROUGH }

/** Contract `TypingLanguage.conversionTrigger`. Stored and synced; the engine applies it in a later step. */
enum class ConversionTrigger { LIVE_COMPOSING, WORD_BOUNDARY, MANUAL }

/** Contract `TypingLanguage.unknownTokenBehavior`. Stored and synced; the engine applies it in a later step. */
enum class UnknownTokenBehavior { PRESERVE_LATIN, SUGGEST_ONLY }

/**
 * Contract preferences `numeralStyle`. It only affects Amharic and Tigrinya typed natively (Fidel or Phonetic, ADR 0043):
 * ETHIOPIC = the digit keys type ፩…፱ and ፲ (Western digits on long-press), WESTERN = they type 1…0. Number, phone and
 * date fields, English (ABC) and the other languages always type Western digits.
 */
enum class NumeralStyle { WESTERN, ETHIOPIC }

/**
 * Keyboard theme ids. The contract's `keyboardTheme` is a free string (max 40) and carries the id of a theme from
 * the IME's catalogue (`classic_light`, `midnight`, ...). `:data` does not know the catalogue (the IME depends on it,
 * not the other way round): it only keeps ids well formed and migrates the old enum names. The IME resolves an id it
 * does not know to the system theme.
 */
object ThemeIds {
    /** Follows the device's dark mode (Classic Light / Classic Dark). The default. */
    const val SYSTEM = "system"
    const val CLASSIC_LIGHT = "classic_light"
    const val CLASSIC_DARK = "classic_dark"

    const val MAX_LENGTH = 40
    private val WELL_FORMED = Regex("[a-z][a-z0-9_]{0,${MAX_LENGTH - 1}}")

    /** Old `ThemeMode` names (SYSTEM, LIGHT, DARK) become ids; a malformed value becomes [SYSTEM]. */
    fun normalize(raw: String?): String = when {
        raw == null -> SYSTEM
        raw == "SYSTEM" -> SYSTEM
        raw == "LIGHT" -> CLASSIC_LIGHT
        raw == "DARK" -> CLASSIC_DARK
        WELL_FORMED.matches(raw) -> raw
        else -> SYSTEM
    }
}

/** Language-pack release channel (A2.3); names match the contract's `ReleaseChannel`. STABLE unless a tester opts in. */
enum class PackChannel { STABLE, BETA, INTERNAL }

/** Static facts about the typing languages (SD 7.1, ADR 0034) that validation needs. */
object LanguageRules {
    /** Typing languages in their default order: the four launch languages, then Arabic (ADR 0034). */
    val KNOWN: List<String> = listOf("am", "ti", "om", "en", "ar")

    /**
     * Languages a user turns on themselves (ADR 0034): off on first run, and appended switched off to an existing
     * user's list (by [KeyboardSettings.sanitized]). The launch languages start on.
     */
    val OPT_IN: Set<String> = setOf("ar")

    fun isKnown(code: String) = code in KNOWN

    /**
     * Allowed modes; the first is the default (am/ti: phonetic QWERTY, Fidel one globe long-press away; ADR 0007).
     * Arabic has one mode, its letter layout. The contract stores it as LATIN_PASSTHROUGH, the name it gives every
     * mode that commits keys as they are (the backend allows only that for a non-Ge'ez language); the keyboard's
     * Arabic layout is chosen by the language, not by this value.
     */
    fun allowedModes(code: String): List<InputMode> = when (code) {
        "am", "ti" -> listOf(InputMode.PHONETIC_LATIN_TO_NATIVE, InputMode.DIRECT_SCRIPT_LAYOUT, InputMode.LATIN_PASSTHROUGH)
        "om", "en", "ar" -> listOf(InputMode.LATIN_PASSTHROUGH)
        else -> emptyList()
    }

    fun defaultMode(code: String): InputMode = allowedModes(code).firstOrNull() ?: InputMode.LATIN_PASSTHROUGH

    /**
     * Languages whose single layout suggests words while typing (ADR 0039): Afaan Oromoo (Qubee) and English (dictionary
     * words), Arabic (the user's own words). Their suggestion strip is switched by the same per-language
     * [TypingLanguageSetting.showTransliterationCandidates] as phonetic am/ti.
     */
    fun hasWordSuggestions(code: String) = code == "om" || code == "en" || code == "ar"
}

/**
 * One entry of the user's ordered typing-language list. Maps 1:1 to the contract's `TypingLanguage`
 * (`/me/typing-languages`, SD 10.2): [languageCode], [enabled], [inputMode],
 * [transliterationProfileCode], [conversionTrigger], [showTransliterationCandidates],
 * [unknownTokenBehavior]. `sortOrder` is the list position (see [KeyboardSettings.toSyncItems]) and
 * `isPrimary` the first enabled entry.
 *
 * A null [transliterationProfileCode] means "the newest active profile of this language".
 */
data class TypingLanguageSetting(
    val languageCode: String,
    val enabled: Boolean = true,
    val inputMode: InputMode = LanguageRules.defaultMode(languageCode),
    val transliterationProfileCode: String? = null,
    val conversionTrigger: ConversionTrigger = ConversionTrigger.LIVE_COMPOSING,
    val showTransliterationCandidates: Boolean = true,
    val unknownTokenBehavior: UnknownTokenBehavior = UnknownTokenBehavior.PRESERVE_LATIN,
)

/** Contract-shaped view of one language for the future A2.x sync (no JSON here). */
data class TypingLanguageSyncItem(
    val languageCode: String,
    val enabled: Boolean,
    val isPrimary: Boolean,
    val sortOrder: Int,
    val inputMode: InputMode,
    val transliterationProfileCode: String?,
    val conversionTrigger: ConversionTrigger,
    val showTransliterationCandidates: Boolean,
    val unknownTokenBehavior: UnknownTokenBehavior,
)

/**
 * All local keyboard settings. Always valid when built through [sanitized] (the repository does).
 * [languages] holds every known language, enabled or not, in the user's order, so a disabled
 * language keeps its place.
 */
data class KeyboardSettings(
    val languages: List<TypingLanguageSetting> = defaultLanguages(),
    /** The style the user last picked; only in force when [numeralStyleChosen] (see [effectiveNumeralStyle]). Synced. */
    val numeralStyle: NumeralStyle = NumeralStyle.WESTERN,
    /**
     * ADR 0043: the user picked [numeralStyle] themselves (the settings page, or another device through sync). Until then the
     * keyboard uses [DEFAULT_NUMERAL_STYLE]. Device-local. Files written before this key: true only for a stored ETHIOPIC
     * (never the default, so always a choice); a stored WESTERN cannot be told from the old default and counts as not chosen.
     */
    val numeralStyleChosen: Boolean = false,
    /** Id of the chosen keyboard theme ([ThemeIds]); stored under `theme`, synced as `keyboardTheme`. */
    val theme: String = ThemeIds.SYSTEM,
    /** Contract `hapticsEnabled`. */
    val hapticsEnabled: Boolean = true,
    /** Key press preview bubble above the pressed key (never shown in password fields). Device-local. */
    val keyPreviewEnabled: Boolean = true,
    /** Key height as a percentage of the default row height. */
    val keyHeightPercent: Int = KEY_HEIGHT_DEFAULT,
    /** Last-used native layout per language, as the IME's `LayoutKind` name (the IME interprets it). */
    val lastModes: Map<String, String> = emptyMap(),
    /**
     * The writing mode the user last selected per language (am/ti: Fidel, Phonetic or English), as the
     * IME's `LayoutKind` name (`ABC` is the English writing mode). Unlike [lastModes] it may be `ABC`,
     * so the keyboard reopens in the mode the user left. A missing entry (settings saved before this
     * field existed) falls back to [lastModes], then to the language's input mode. Device-local.
     */
    val writingModes: Map<String, String> = emptyMap(),
    /** A2.3: allow language-pack updates on a metered connection. Default off (SD: Wi-Fi-only by default). Local only. */
    val packUpdatesOnMobileData: Boolean = false,
    /** A2.3: channel asked of `GET /language-resources`. Local only. */
    val packChannel: PackChannel = PackChannel.STABLE,
    /**
     * ADR 0024 (ADR 0007 D47): send content-free crash reports. On by default because reports carry no typed text;
     * the privacy dashboard turns it off. Local only, not synced, and "Reset to defaults" keeps it.
     */
    val crashReports: Boolean = true,
    /** Emoji panel: recently used emoji, newest first (at most [EMOJI_RECENT_MAX]). Device-local: never synced, never logged. */
    val recentEmoji: List<String> = emptyList(),
    /** Emoji panel: the skin-tone form last chosen per base emoji (base to variant). Device-local. */
    val emojiTones: Map<String, String> = emptyMap(),
    /** Voice typing (ADR 0029): the microphone in the keyboard bar. On by default. Device-local, not synced. */
    val voiceTypingEnabled: Boolean = true,
    /**
     * Voice typing: the recognition language (a BCP-47 tag, e.g. `ti-ER`) the user last chose per keyboard language.
     * A missing entry means the keyboard language's default tag. Device-local, never synced.
     */
    val voiceLanguages: Map<String, String> = emptyMap(),
    /** Voice typing: the one-time "your phone's speech service turns voice into text" note was shown. Device-local. */
    val voiceNoticeShown: Boolean = false,
    /**
     * AI translation (ADR 0031): the preferred destination language (a translation language of the catalogue, ADR 0040:
     * am, ti, om, en, ar or any other the server offers), or null for the automatic default (English for am/ti/om text,
     * Amharic for English text). Device-local, not synced.
     */
    val aiPreferredTarget: String? = null,
    /** AI translation: the destination last picked per source language (source code to destination id). Device-local. */
    val aiTranslatePairs: Map<String, String> = emptyMap(),
    /** Write Email with AI: the name the user signs e-mails with (remembered on this device only, never synced). */
    val emailSenderName: String = "",
    /**
     * Clipboard panel (ADR 0035): remember text copied while the keyboard runs (in memory only, at most 20 clips, each for
     * an hour, never persisted). On by default; off = the panel offers only the current clip. Device-local, not synced.
     */
    val clipboardHistoryEnabled: Boolean = true,
    /** ADR 0039: next-word predictions after a word (bigram pack + the user's own word pairs). On by default. Device-local. */
    val nextWordPredictions: Boolean = true,
    /** ADR 0039: autocorrection while typing English (Space applies a confident correction, Backspace undoes). On by default. */
    val autocorrectEnglish: Boolean = true,
    /**
     * ADR 0039: autocorrection while typing Afaan Oromoo. **Off** by default until the om lexicon (a ~90-word DRAFT seed) is
     * reviewed: with so few words most real words would be "corrected". Device-local.
     */
    val autocorrectOromo: Boolean = false,
    /** ADR 0039: learn new words from typing into the personal dictionary (never in password or incognito fields). On by default. */
    val learnWords: Boolean = true,
    /** ADR 0043: the digit row above the letters (Gboard's "Number row"). On by default. Device-local. */
    val numberRow: Boolean = true,
    /**
     * ADR 0043: the Latin key arrangement per language, as the IME's `LatinLayout` name (`AZERTY`, `DVORAK`, ...). A missing
     * entry is QWERTY. It applies to all the language's Latin layouts (Phonetic, Qubee, English, ABC). Device-local.
     */
    val latinLayouts: Map<String, String> = emptyMap(),
    /** ADR 0043: how the Fidel grid is typed per language (am/ti), as the IME's `FidelVariant` name. Missing = two-key. Device-local. */
    val fidelLayouts: Map<String, String> = emptyMap(),
) {
    /** Enabled language codes in the user's order. */
    val enabledLanguages: List<String> get() = languages.filter { it.enabled }.map { it.languageCode }

    fun language(code: String): TypingLanguageSetting? = languages.firstOrNull { it.languageCode == code }

    val keyHeightScale: Float get() = keyHeightPercent / 100f

    /** The numeral style in force (ADR 0043): the user's choice, else the default (Ethiopic). */
    val effectiveNumeralStyle: NumeralStyle get() = if (numeralStyleChosen) numeralStyle else DEFAULT_NUMERAL_STYLE

    /** The list as `/me/typing-languages` items (sortOrder 10, 20, ...; primary = first enabled). */
    fun toSyncItems(): List<TypingLanguageSyncItem> {
        val primary = languages.firstOrNull { it.enabled }?.languageCode
        return languages.mapIndexed { i, l ->
            TypingLanguageSyncItem(
                l.languageCode, l.enabled, l.languageCode == primary, (i + 1) * 10, l.inputMode,
                l.transliterationProfileCode.takeIf { l.inputMode == InputMode.PHONETIC_LATIN_TO_NATIVE },
                l.conversionTrigger, l.showTransliterationCandidates, l.unknownTokenBehavior,
            )
        }
    }

    /**
     * Returns a valid copy: unknown or duplicate languages dropped, missing known languages appended
     * (disabled), modes the language cannot use reset to its default, profile codes kept only for
     * phonetic mode and only when well formed, at least one language enabled, key height clamped.
     * It never consults which profiles exist: that is [ProfileResolver]'s job (F14: a retired
     * profile must not block editing or reordering).
     */
    fun sanitized(): KeyboardSettings {
        val seen = HashSet<String>()
        val cleaned = languages.mapNotNull { l ->
            if (!LanguageRules.isKnown(l.languageCode) || !seen.add(l.languageCode)) return@mapNotNull null
            val mode = if (l.inputMode in LanguageRules.allowedModes(l.languageCode)) l.inputMode else LanguageRules.defaultMode(l.languageCode)
            l.copy(
                inputMode = mode,
                transliterationProfileCode = l.transliterationProfileCode?.takeIf {
                    mode == InputMode.PHONETIC_LATIN_TO_NATIVE && PROFILE_CODE.matches(it)
                },
            )
        }.toMutableList()
        for (code in LanguageRules.KNOWN) if (seen.add(code)) cleaned += TypingLanguageSetting(code, enabled = false)
        if (cleaned.none { it.enabled }) {
            val first = cleaned.indexOfFirst { it.languageCode == LanguageRules.KNOWN.first() }.takeIf { it >= 0 } ?: 0
            cleaned[first] = cleaned[first].copy(enabled = true)
        }
        return copy(
            languages = cleaned,
            theme = ThemeIds.normalize(theme),
            keyHeightPercent = keyHeightPercent.coerceIn(KEY_HEIGHT_MIN, KEY_HEIGHT_MAX),
            lastModes = lastModes.filter { (k, v) -> LanguageRules.isKnown(k) && MODE_TOKEN.matches(v) },
            writingModes = writingModes.filter { (k, v) -> LanguageRules.isKnown(k) && MODE_TOKEN.matches(v) },
            latinLayouts = latinLayouts.filter { (k, v) -> LanguageRules.isKnown(k) && MODE_TOKEN.matches(v) },
            fidelLayouts = fidelLayouts.filter { (k, v) -> LanguageRules.isKnown(k) && MODE_TOKEN.matches(v) },
            recentEmoji = recentEmoji.filter { emojiToken(it) }.distinct().take(EMOJI_RECENT_MAX),
            emojiTones = emojiTones.filter { (k, v) -> emojiToken(k) && emojiToken(v) }.entries.take(EMOJI_TONES_MAX).associate { it.key to it.value },
            voiceLanguages = voiceLanguages.filter { (k, v) -> LanguageRules.isKnown(k) && LANGUAGE_TAG.matches(v) },
            // ADR 0040: translation languages are not typing languages; any well-formed tag the catalogue may offer is kept.
            aiPreferredTarget = aiPreferredTarget?.takeIf { LANGUAGE_TAG.matches(it) },
            aiTranslatePairs = aiTranslatePairs.filter { (k, v) -> LANGUAGE_TAG.matches(k) && DESTINATION_ID.matches(v) },
            emailSenderName = emailSenderName.map { if (it == '\n' || it == '\r' || it == '|') ' ' else it }.joinToString("").trim().take(SENDER_NAME_MAX),
        )
    }

    companion object {
        const val KEY_HEIGHT_MIN = 80
        const val KEY_HEIGHT_MAX = 130
        const val KEY_HEIGHT_DEFAULT = 100
        const val SCHEMA_VERSION = 1
        const val EMOJI_RECENT_MAX = 32
        const val EMOJI_TONES_MAX = 400

        /** ADR 0043: am/ti typed natively show Ethiopic numerals until the user picks a style. */
        val DEFAULT_NUMERAL_STYLE = NumeralStyle.ETHIOPIC

        /** An emoji sequence as stored: short, and free of the separators the store uses. */
        private fun emojiToken(s: String) = s.isNotEmpty() && s.length <= 40 && s.none { it == '|' || it == '\n' || it == '\r' }

        private val PROFILE_CODE = Regex("[A-Za-z0-9_]{1,100}")
        private val MODE_TOKEN = Regex("[A-Z_]{1,24}")

        /** A destination id as the AI client makes them: `lang:en` or a destination profile's local id. */
        private val DESTINATION_ID = Regex("[A-Za-z0-9:_-]{1,80}")
        const val SENDER_NAME_MAX = 120

        /** A BCP-47 language tag as speech services report them (`am-ET`, `en-US`, `cmn-Hans-CN`, `es-419`). */
        private val LANGUAGE_TAG = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,4}")

        /** First run: am, ti, om, en enabled in this order (A1.4's default), then Arabic switched off (opt-in, ADR 0034). */
        fun defaultLanguages(): List<TypingLanguageSetting> =
            LanguageRules.KNOWN.map { TypingLanguageSetting(it, enabled = it !in LanguageRules.OPT_IN) }

        val DEFAULT: KeyboardSettings = KeyboardSettings()
    }
}
