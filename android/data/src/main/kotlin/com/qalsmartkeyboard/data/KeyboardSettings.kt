package com.qalsmartkeyboard.data

/** Input modes of a typing language; names match the contract's `InputMode` enum (SD 7.1, 10.2). */
enum class InputMode { DIRECT_SCRIPT_LAYOUT, PHONETIC_LATIN_TO_NATIVE, LATIN_PASSTHROUGH }

/** Contract `TypingLanguage.conversionTrigger`. Stored and synced; the engine applies it in a later step. */
enum class ConversionTrigger { LIVE_COMPOSING, WORD_BOUNDARY, MANUAL }

/** Contract `TypingLanguage.unknownTokenBehavior`. Stored and synced; the engine applies it in a later step. */
enum class UnknownTokenBehavior { PRESERVE_LATIN, SUGGEST_ONLY }

/** Contract preferences `numeralStyle`. */
enum class NumeralStyle { WESTERN, ETHIOPIC }

/** Keyboard theme. The contract's `keyboardTheme` is a free string (max 40); we use these names. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Language-pack release channel (A2.3); names match the contract's `ReleaseChannel`. STABLE unless a tester opts in. */
enum class PackChannel { STABLE, BETA, INTERNAL }

/** Static facts about the launch languages (SD 7.1) that validation needs. */
object LanguageRules {
    /** Launch languages in their default order. */
    val KNOWN: List<String> = listOf("am", "ti", "om", "en")

    fun isKnown(code: String) = code in KNOWN

    /** Allowed modes; the first is the default (am/ti: phonetic QWERTY, Fidel one globe long-press away; ADR 0007). */
    fun allowedModes(code: String): List<InputMode> = when (code) {
        "am", "ti" -> listOf(InputMode.PHONETIC_LATIN_TO_NATIVE, InputMode.DIRECT_SCRIPT_LAYOUT, InputMode.LATIN_PASSTHROUGH)
        "om", "en" -> listOf(InputMode.LATIN_PASSTHROUGH)
        else -> emptyList()
    }

    fun defaultMode(code: String): InputMode = allowedModes(code).firstOrNull() ?: InputMode.LATIN_PASSTHROUGH
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
    val numeralStyle: NumeralStyle = NumeralStyle.WESTERN,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Contract `hapticsEnabled`. */
    val hapticsEnabled: Boolean = true,
    /** Key height as a percentage of the default row height. */
    val keyHeightPercent: Int = KEY_HEIGHT_DEFAULT,
    /** Last-used native layout per language, as the IME's `LayoutKind` name (the IME interprets it). */
    val lastModes: Map<String, String> = emptyMap(),
    /** A2.3: allow language-pack updates on a metered connection. Default off (SD: Wi-Fi-only by default). Local only. */
    val packUpdatesOnMobileData: Boolean = false,
    /** A2.3: channel asked of `GET /language-resources`. Local only. */
    val packChannel: PackChannel = PackChannel.STABLE,
) {
    /** Enabled language codes in the user's order. */
    val enabledLanguages: List<String> get() = languages.filter { it.enabled }.map { it.languageCode }

    fun language(code: String): TypingLanguageSetting? = languages.firstOrNull { it.languageCode == code }

    val keyHeightScale: Float get() = keyHeightPercent / 100f

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
            keyHeightPercent = keyHeightPercent.coerceIn(KEY_HEIGHT_MIN, KEY_HEIGHT_MAX),
            lastModes = lastModes.filter { (k, v) -> LanguageRules.isKnown(k) && MODE_TOKEN.matches(v) },
        )
    }

    companion object {
        const val KEY_HEIGHT_MIN = 80
        const val KEY_HEIGHT_MAX = 130
        const val KEY_HEIGHT_DEFAULT = 100
        const val SCHEMA_VERSION = 1

        private val PROFILE_CODE = Regex("[A-Za-z0-9_]{1,100}")
        private val MODE_TOKEN = Regex("[A-Z_]{1,24}")

        /** First run: am, ti, om, en all enabled in this order (A1.4's default). */
        fun defaultLanguages(): List<TypingLanguageSetting> = LanguageRules.KNOWN.map { TypingLanguageSetting(it) }

        val DEFAULT: KeyboardSettings = KeyboardSettings()
    }
}
