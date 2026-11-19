package com.qalsmartkeyboard.ime

/**
 * What a key does when pressed. [GLOBE] switches language (tap: next, long-press: cycle layouts),
 * [ABC_TOGGLE] is the one-tap ABC <-> native switch (SD 7.1), [FIDEL_CONSONANT] / [FIDEL_VOWEL] are
 * the two-key direct-Fidel entry (SD 7.2).
 */
enum class KeyKind {
    CHAR, SHIFT, BACKSPACE, SPACE, ENTER, GLOBE, ABC_TOGGLE, TO_SYMBOLS, TO_SYMBOLS_2, TO_LETTERS,
    FIDEL_CONSONANT, FIDEL_VOWEL,

    /** Opens the emoji panel (ADR 0028); only in fields that take emoji ([InputProfile.allowsEmoji]). */
    EMOJI,
}

/**
 * One long-press choice. [text] is committed as a `Char`; when [language] is set the choice is a
 * language of the picker instead.
 */
data class PopupItem(
    val label: String,
    val text: String = label,
    val language: String? = null,
    val description: String = label,
    val selected: Boolean = false,
)

/**
 * One key. [text] is what a CHAR key commits (lower-case for letters; shift upper-cases it); for
 * [KeyKind.FIDEL_CONSONANT] it is the core series id. [weight] is the relative width inside its row.
 * A CHAR key with empty [text] is a layout spacer. [popup] opens on long-press ([popupColumns]
 * wide); [longAction] fires on long-press instead (globe). [hint] is a small corner label showing
 * the first alternative, or (phonetic letter keys) the native character the key produces;
 * [shiftHint], when not null, replaces it while shift is on. [order] is the vowel order of a FIDEL_VOWEL key.
 */
data class Key(
    val kind: KeyKind,
    val label: String,
    val text: String = "",
    val weight: Float = 1f,
    val popup: List<PopupItem> = emptyList(),
    val popupColumns: Int = 5,
    val longAction: Boolean = false,
    val hint: String = "",
    val shiftHint: String? = null,
    val order: Int = 0,
    val description: String = "",
    val draftBadge: Boolean = false,
) {
    val isLetter: Boolean get() = kind == KeyKind.CHAR && text.length == 1 && text[0].isLetter()
    val isSpacer: Boolean get() = kind == KeyKind.CHAR && text.isEmpty()

    /** What an accessibility service should say for this key. */
    val spoken: String get() = description.ifEmpty { label }

    /** Acts on release (so a long-press can take over) instead of on press. */
    val actsOnRelease: Boolean get() = popup.isNotEmpty() || longAction
}

enum class Page { LETTERS, SYMBOLS, SYMBOLS_2, NUMBER }

class KeyboardLayout(val page: Page, val rows: List<List<Key>>, val rowHeightDp: Float = 50f)

/** What the layout builder needs to know about the current typing state. */
data class LayoutContext(
    /** The mode in force for this field (already ABC where transliteration is not allowed). */
    val mode: TypingMode,
    /** Direct-Fidel data for [TypingMode.language], when loaded. */
    val grid: FidelGrid? = null,
    /** The indicator text for the space bar (SD 7.1). */
    val indicator: String = "",
    /** The enabled languages for the space-bar picker. */
    val picker: List<PopupItem> = emptyList(),
    val draft: Boolean = false,
    /** numeralStyle ETHIOPIC: Ethiopic numerals are offered as alternatives for typed digits (SD 7.2). */
    val ethiopicNumerals: Boolean = false,
    /** Phonetic mode: the native output of each letter key (secondary key labels). */
    val hints: PhoneticHints? = null,
) {
    /** Ethiopic punctuation and numerals are offered while an Ethiopic language is typed natively. */
    val ethiopic: Boolean get() = LanguageCatalog.isEthiopic(mode.language) && !mode.isAbc && grid != null

    /**
     * The Ge'ez marks offered while an Ethiopic language is typed natively (Fidel or Phonetic, not
     * English): the layout pack's `punctuation` in its order, then any of [Layouts.GEEZ_MARKS] the
     * pack lacks (also when no layout pack is loaded), so all of them are always reachable.
     */
    val ethiopicMarks: List<String>
        get() = if (!LanguageCatalog.isEthiopic(mode.language) || mode.isAbc) emptyList()
        else (grid?.punctuation.orEmpty() + Layouts.GEEZ_MARKS).distinct()

    /** The Fidel grid layout: `.` and `,` tap the Ethiopic full stop and comma (ADR 0007). */
    val fidelPunctuation: Boolean get() = mode.kind == LayoutKind.FIDEL && grid != null

    /** The Arabic letter layout is in force (ADR 0034): Arabic punctuation, Arabic-Indic digits and the marks. */
    val arabic: Boolean get() = mode.kind == LayoutKind.ARABIC

    companion object {
        val DEFAULT = LayoutContext(TypingMode.abc("en"))
    }
}

/**
 * The Arabic keyboard's data (ADR 0034): the standard Arabic arrangement (Windows Arabic 101 / Android), long-press
 * alternatives, the marks (harakat and tatweel), Arabic punctuation and digits. Rows are listed in visual order, left to
 * right, as the keys are drawn; the text they type is in logical order.
 */
object ArabicKeys {
    const val COMMA = "،" // ،
    const val SEMICOLON = "؛" // ؛
    const val QUESTION = "؟" // ؟
    const val PERCENT = "٪" // ٪
    const val TATWEEL = "\u0640" // ـ

    /** Lam-alef: one key, two letters (ل + ا); the font draws the ligature. */
    const val LAM_ALEF = "لا"

    /** Drawn under a combining mark on a key or popup so it never floats on nothing. */
    private const val DOTTED_CIRCLE = "\u25CC"

    val ROWS: List<List<String>> = listOf(
        listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"),
        listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"),
        listOf("ذ", "ء", "ؤ", "ر", LAM_ALEF, "ى", "ة", "و", "ز", "ظ", "د"),
    )

    /** Long-press alternatives: the hamza forms, alef wasla, and the letters' other code points. */
    val ALTERNATIVES: Map<String, List<String>> = mapOf(
        "ا" to listOf("أ", "إ", "آ", "ٱ", "ء"),
        "ي" to listOf("ئ", "ى"),
        "و" to listOf("ؤ"),
        "ه" to listOf("ة"),
    )

    /** A mark and its technical name (spoken by TalkBack inside a translated phrase; the name itself is not translated, like Qubee). */
    class Mark(val text: String, val name: String) {
        /** What a key or popup shows: a combining mark on a dotted circle, the tatweel as it is. */
        val label: String get() = if (text == TATWEEL) text else DOTTED_CIRCLE + text
    }

    /** The harakat, the superscript (dagger) alef and the tatweel, in the marks key's popup order. */
    val MARKS: List<Mark> = listOf(
        Mark("\u064E", "Fatha"),
        Mark("\u064F", "Damma"),
        Mark("\u0650", "Kasra"),
        Mark("\u0652", "Sukun"),
        Mark("\u0651", "Shadda"),
        Mark("\u064B", "Fathatan"),
        Mark("\u064C", "Dammatan"),
        Mark("\u064D", "Kasratan"),
        Mark("\u0670", "Superscript alef"),
        Mark(TATWEEL, "Tatweel"),
    )

    /** ASCII digit -> Arabic-Indic digit (U+0660..U+0669). */
    fun indicDigit(d: Char): String = (0x0660 + (d - '0')).toChar().toString()

    /** ASCII symbols the Arabic symbols pages replace with their Arabic form (the ASCII one stays on long-press). */
    val SYMBOLS: Map<Char, String> = mapOf(',' to COMMA, ';' to SEMICOLON, '?' to QUESTION, '%' to PERCENT)
}

/** Layouts: ASCII QWERTY (A1.2), Qubee, phonetic, the Fidel grid from core data (A1.4) and Arabic (ADR 0034). */
object Layouts {
    /** Key descriptions in the UI language (ADR 0032); read when a layout is built. */
    private val L: KeyboardLabels get() = KeyboardLabels.current
    private const val FULL_STOP = "።"
    private const val ETHIOPIC_COMMA = "፣"

    /** The Ge'ez punctuation marks every Ethiopic native layout must reach (SD 7.2). */
    val GEEZ_MARKS: List<String> = listOf("።", "፣", "፤", "፥", "፦", "፧", "፨")
    private fun ch(c: String, w: Float = 1f) = Key(KeyKind.CHAR, c, c, w)
    private fun chars(s: String) = s.map { ch(it.toString()) }

    /** A row of letter keys; in Phonetic mode each carries the engine's output for it as a hint. */
    private fun letterKeys(s: String, hints: PhoneticHints?) = if (hints == null) chars(s) else s.map { c ->
        Key(KeyKind.CHAR, c.toString(), c.toString(), hint = hints.lower(c), shiftHint = hints.upper(c))
    }
    private fun spacer(w: Float) = Key(KeyKind.CHAR, "", "", w)
    private fun backspace(w: Float = 1.5f) = Key(KeyKind.BACKSPACE, "⌫", weight = w, description = L.keyDelete)
    private fun enter(w: Float = 1.5f) = Key(KeyKind.ENTER, "", weight = w)
    private fun emojiKey(profile: InputProfile, w: Float = 1f): Key? =
        if (profile.allowsEmoji) Key(KeyKind.EMOJI, "", weight = w, description = L.keyEmoji) else null

    private fun plainSpace(w: Float) = Key(KeyKind.SPACE, " ", " ", w, description = L.keySpace)

    fun forPage(page: Page, profile: InputProfile, ctx: LayoutContext = LayoutContext.DEFAULT): KeyboardLayout = when (page) {
        Page.LETTERS -> letters(profile, ctx)
        Page.SYMBOLS -> symbols1(profile, ctx)
        Page.SYMBOLS_2 -> symbols2(profile, ctx)
        Page.NUMBER -> number(profile)
    }

    /** The page a field starts on. */
    fun startPage(profile: InputProfile): Page = when (profile.kind) {
        FieldKind.NUMBER, FieldKind.PHONE -> Page.NUMBER
        else -> Page.LETTERS
    }

    private fun digitRow(ctx: LayoutContext): List<Key> = "1234567890".map { d ->
        if (ctx.arabic) {
            // Western digits type by default (what most Arabic-script phones send); the Arabic-Indic digit is on long-press.
            val indic = ArabicKeys.indicDigit(d)
            return@map Key(
                KeyKind.CHAR, d.toString(), d.toString(),
                popup = listOf(PopupItem(indic, description = L.keyArabicNumeral.format(indic))),
                hint = indic,
            )
        }
        val alts = if (ctx.ethiopicNumerals && ctx.ethiopic) ctx.grid?.digits?.get(d.toString()).orEmpty() else emptyList()
        Key(
            KeyKind.CHAR, d.toString(), d.toString(),
            popup = alts.map { PopupItem(it, description = L.keyEthiopicNumeral.format(it)) },
            hint = alts.firstOrNull().orEmpty(),
        )
    }

    /** One Arabic mark as a popup choice. */
    private fun markItem(m: ArabicKeys.Mark) = PopupItem(m.label, m.text, description = L.keyArabicMark.format(m.name))

    /** One Arabic mark as a key (the Arabic symbols page). */
    private fun markKey(m: ArabicKeys.Mark) = Key(KeyKind.CHAR, m.label, m.text, description = L.keyArabicMark.format(m.name))

    /**
     * The marks key of the Arabic layout, in the slot the other languages use for the ABC key: tap types a fatha (what
     * the key shows), long-press offers every harakah, the superscript alef and the tatweel.
     */
    private fun arabicMarksKey(): Key {
        val first = ArabicKeys.MARKS.first()
        return Key(
            KeyKind.CHAR, first.label, first.text,
            popup = ArabicKeys.MARKS.map(::markItem), popupColumns = 5,
            description = L.keyArabicMarks,
        )
    }

    /** A symbols-page key: on the Arabic layout `,` `;` `?` `%` type their Arabic form, the ASCII one is on long-press. */
    private fun symbolKey(c: Char, ctx: LayoutContext): Key {
        val ar = if (ctx.arabic) ArabicKeys.SYMBOLS[c] else null
        if (ar == null) return ch(c.toString())
        val description = if (ar == ArabicKeys.COMMA) L.keyArabicComma else L.keyArabicPunctuation.format(ar)
        return Key(KeyKind.CHAR, ar, ar, popup = listOf(PopupItem(c.toString())), hint = c.toString(), description = description)
    }

    private fun symbolKeys(s: String, ctx: LayoutContext) = s.map { symbolKey(it, ctx) }

    private fun spaceKey(w: Float, ctx: LayoutContext) = Key(
        KeyKind.SPACE, ctx.indicator, " ", w,
        popup = ctx.picker, popupColumns = 1,
        description = L.keySpaceMode.format(LanguageCatalog.spokenName(ctx.mode.language), LanguageCatalog.spokenKind(ctx.mode.kind)),
        draftBadge = ctx.draft,
    )

    private fun marks(ctx: LayoutContext, only: Set<String>? = null): List<PopupItem> =
        ctx.ethiopicMarks.filter { only == null || it in only }
            .map { PopupItem(it, description = L.keyEthiopicPunctuation.format(it)) }

    private fun periodKey(ctx: LayoutContext): Key {
        if (ctx.arabic) {
            // Tap = full stop (Arabic uses "."); long-press: the Arabic question mark first (the hint), then ! ؛ :
            val p = listOf(
                PopupItem(ArabicKeys.QUESTION, description = L.keyArabicPunctuation.format(ArabicKeys.QUESTION)),
                PopupItem("!"),
                PopupItem(ArabicKeys.SEMICOLON, description = L.keyArabicPunctuation.format(ArabicKeys.SEMICOLON)),
                PopupItem(":"),
            )
            return Key(KeyKind.CHAR, ".", ".", 0.75f, popup = p, popupColumns = 4, hint = ArabicKeys.QUESTION, description = L.keyPeriod)
        }
        val m = marks(ctx)
        if (ctx.fidelPunctuation) {
            // Fidel grid only (ADR 0007): tap = ። ; ASCII "." leads the popup, then the other marks.
            val others = m.filter { it.label != FULL_STOP }
            return Key(
                KeyKind.CHAR, FULL_STOP, FULL_STOP, 0.75f,
                popup = listOf(PopupItem(".", description = L.keyPeriod)) + others, popupColumns = 4,
                hint = ".", description = L.keyEthiopicFullStop,
            )
        }
        return Key(KeyKind.CHAR, ".", ".", 0.75f, popup = m, popupColumns = 4, hint = m.firstOrNull()?.label.orEmpty())
    }

    private fun commaKey(ctx: LayoutContext): Key {
        if (ctx.arabic) {
            // Tap = Arabic comma; long-press: the Arabic semicolon (the hint), then the ASCII comma.
            return Key(
                KeyKind.CHAR, ArabicKeys.COMMA, ArabicKeys.COMMA, 0.75f,
                popup = listOf(
                    PopupItem(ArabicKeys.SEMICOLON, description = L.keyArabicPunctuation.format(ArabicKeys.SEMICOLON)),
                    PopupItem(",", description = L.keyComma),
                ),
                popupColumns = 2, hint = ArabicKeys.SEMICOLON, description = L.keyArabicComma,
            )
        }
        val m = marks(ctx, setOf("፣", "፤"))
        if (ctx.fidelPunctuation) {
            val others = m.filter { it.label != ETHIOPIC_COMMA }
            return Key(
                KeyKind.CHAR, ETHIOPIC_COMMA, ETHIOPIC_COMMA, 0.75f,
                popup = listOf(PopupItem(",", description = L.keyComma)) + others, popupColumns = 2,
                hint = ",", description = L.keyEthiopicComma,
            )
        }
        return Key(KeyKind.CHAR, ",", ",", 0.75f, popup = m, popupColumns = 2, hint = m.firstOrNull()?.label.orEmpty())
    }

    /**
     * The one-tap ABC key. In am/ti it is the English writing mode switch: `ABC` goes to English, the
     * native glyph goes back to the last of Fidel/Phonetic. Hidden in English (ABC == English).
     */
    private fun toggleKey(ctx: LayoutContext): Key? {
        val lang = ctx.mode.language
        if (lang == "en") return null // ABC and English are both plain Latin passthrough
        if (!LanguageCatalog.hasAbcLayout(lang)) return null // Arabic: no Latin layout of its own (ADR 0034)
        val ethiopic = LanguageCatalog.isEthiopic(lang)
        return if (ctx.mode.isAbc) {
            Key(KeyKind.ABC_TOGGLE, LanguageCatalog.nativeGlyph(lang), description = L.keySwitchToLanguageKeys.format(LanguageCatalog.spokenName(lang)))
        } else {
            Key(KeyKind.ABC_TOGGLE, "ABC", description = if (ethiopic) L.keySwitchToEnglishMode else L.keySwitchToAbc)
        }
    }

    private fun letters(profile: InputProfile, ctx: LayoutContext): KeyboardLayout {
        val toSym = Key(KeyKind.TO_SYMBOLS, "?123", weight = 1.25f, description = L.keyNumbersSymbols)
        val bottom = when (profile.kind) {
            FieldKind.EMAIL -> listOf(toSym, ch("@"), ch(","), plainSpace(3f), ch("."), enter())
            FieldKind.URI -> listOf(toSym, ch("/"), plainSpace(2f), ch("."), Key(KeyKind.CHAR, ".com", ".com", 1.5f), enter())
            FieldKind.TEXT -> listOfNotNull(
                toSym,
                Key(KeyKind.GLOBE, "🌐", weight = 1f, longAction = true, description = L.keySwitchLanguage),
                if (ctx.arabic) arabicMarksKey() else toggleKey(ctx),
                commaKey(ctx),
                emojiKey(profile, 0.9f),
                spaceKey(if (profile.allowsEmoji) 2.75f else 3f, ctx),
                periodKey(ctx),
                enter(),
            )
            else -> listOf(toSym, ch(","), plainSpace(4f), ch("."), enter())
        }
        if (ctx.mode.kind == LayoutKind.FIDEL && ctx.grid != null && profile.kind == FieldKind.TEXT) {
            return fidel(ctx.grid, bottom)
        }
        if (ctx.arabic && profile.kind == FieldKind.TEXT) return arabic(ctx, bottom)
        val shiftRow = if (ctx.mode.kind == LayoutKind.QUBEE) {
            // The apostrophe (hudhaa) is part of Oromo words, so it lives on the primary layer (SD 7.3).
            listOf(Key(KeyKind.SHIFT, "⇧", weight = 1.25f, description = L.keyShift)) + chars("zxcvbnm") +
                Key(KeyKind.CHAR, "'", "'", description = L.keyApostrophe) + backspace(1.25f)
        } else {
            listOf(Key(KeyKind.SHIFT, "⇧", weight = 1.5f, description = L.keyShift)) + letterKeys("zxcvbnm", ctx.hints) + backspace()
        }
        return KeyboardLayout(
            Page.LETTERS,
            listOf(
                digitRow(ctx),
                letterKeys("qwertyuiop", ctx.hints),
                listOf(spacer(0.5f)) + letterKeys("asdfghjkl", ctx.hints) + listOf(spacer(0.5f)),
                shiftRow,
                bottom,
            ),
        )
    }

    /**
     * The direct Fidel grid built from the core's layout data: consonant keys (tap = two-key
     * consonant, or the default form in LONG_PRESS-only layouts; long-press = the orders and the
     * labialised forms), a vowel-order row for TWO_KEY, then the bottom row.
     */
    private fun fidel(grid: FidelGrid, bottom: List<Key>): KeyboardLayout {
        val rows = grid.rows.map { row ->
            row.map { k ->
                val popup = if (grid.longPress) k.forms.map { PopupItem(it.label, it.text) } else emptyList()
                if (grid.twoKey) {
                    Key(KeyKind.FIDEL_CONSONANT, k.label, k.seriesId, popup = popup, description = k.label)
                } else {
                    Key(KeyKind.CHAR, k.label, k.tapText, popup = popup, description = k.label)
                }
            }
        }.toMutableList()
        rows[rows.lastIndex] = rows.last() + backspace(1f)
        if (grid.twoKey && grid.vowelKeys.isNotEmpty()) {
            rows += listOf(grid.vowelKeys.map { Key(KeyKind.FIDEL_VOWEL, it.label, order = it.order, description = L.keyVowelOrder.format(it.order, it.label)) })
        }
        rows += listOf(bottom)
        return KeyboardLayout(Page.LETTERS, rows, rowHeightDp = 42f)
    }

    /**
     * The Arabic letter layout (ADR 0034): the digit row, the three standard rows (the last ending in Delete), then the
     * bottom row. No shift key (Arabic has no case); the marks key sits in the bottom row's ABC slot.
     */
    private fun arabic(ctx: LayoutContext, bottom: List<Key>): KeyboardLayout {
        val rows = ArabicKeys.ROWS.map { row ->
            row.map { c -> Key(KeyKind.CHAR, c, c, popup = ArabicKeys.ALTERNATIVES[c].orEmpty().map { PopupItem(it) }) }
        }
        return KeyboardLayout(Page.LETTERS, listOf(digitRow(ctx), rows[0], rows[1], rows[2] + backspace(1f), bottom))
    }

    private fun bottomSymbols(profile: InputProfile, ctx: LayoutContext) = listOfNotNull(
        // Back to the letters: the language's own glyph on the Arabic layout (its letters are not "ABC").
        Key(KeyKind.TO_LETTERS, if (ctx.arabic) LanguageCatalog.nativeGlyph(ctx.mode.language) else "ABC", weight = 1.5f, description = L.keyLetters),
        symbolKey(',', ctx), emojiKey(profile, 1f),
        plainSpace(if (profile.allowsEmoji) 3.5f else 4f), ch("."), enter(),
    )

    private fun symbols1(profile: InputProfile, ctx: LayoutContext): KeyboardLayout {
        val marks = ctx.ethiopicMarks
        val rows = buildList {
            add(digitRow(ctx))
            if (marks.isNotEmpty()) {
                add(marks.map { Key(KeyKind.CHAR, it, it, description = L.keyEthiopicPunctuation.format(it)) })
            }
            if (ctx.arabic) add(ArabicKeys.MARKS.map(::markKey))
            add(symbolKeys("@#$%&-+()/", ctx))
            add(symbolKeys("*\"':;!?~`|", ctx))
            add(listOf(Key(KeyKind.TO_SYMBOLS_2, "=\\<", weight = 1.5f, description = L.keyMoreSymbols)) + symbolKeys(",._", ctx) + listOf(backspace()))
            add(bottomSymbols(profile, ctx))
        }
        return KeyboardLayout(Page.SYMBOLS, rows, if (rows.size > 5) 44f else 50f)
    }

    private fun symbols2(profile: InputProfile, ctx: LayoutContext) = KeyboardLayout(
        Page.SYMBOLS_2,
        listOf(
            digitRow(ctx),
            chars("~`|^{}[]<>"),
            chars("=\\£€¥°©®™•"),
            listOf(Key(KeyKind.TO_SYMBOLS, "?123", weight = 1.5f, description = L.keyNumbersSymbols)) + symbolKeys(",._;:", ctx) + listOf(backspace()),
            bottomSymbols(profile, ctx),
        ),
    )

    /** Number/phone/date fields: a dial-pad style grid with characters those fields accept. */
    private fun number(profile: InputProfile): KeyboardLayout {
        val phone = profile.kind == FieldKind.PHONE
        return KeyboardLayout(
            Page.NUMBER,
            listOf(
                chars("123") + ch(if (phone) "*" else ","),
                chars("456") + ch(if (phone) "(" else "/"),
                chars("789") + ch(if (phone) ")" else ":"),
                if (phone) listOf(ch("+"), ch("0"), ch("#"), backspace(1f))
                else listOf(ch("."), ch("0"), ch("-"), backspace(1f)),
                listOf(plainSpace(3f), enter(1f)),
            ),
        )
    }
}
