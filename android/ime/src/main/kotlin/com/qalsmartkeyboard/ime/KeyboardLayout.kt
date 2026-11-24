package com.qalsmartkeyboard.ime

/**
 * What a key does when pressed. [GLOBE] switches language (tap: next, long-press: cycle layouts),
 * [ABC_TOGGLE] is the one-tap ABC <-> native switch (SD 7.1; no layout places it since ADR 0035, the controller still
 * handles it), [FIDEL_CONSONANT] / [FIDEL_VOWEL] are
 * the two-key direct-Fidel entry (SD 7.2).
 */
enum class KeyKind {
    CHAR, SHIFT, BACKSPACE, SPACE, ENTER, GLOBE, ABC_TOGGLE, TO_SYMBOLS, TO_SYMBOLS_2, TO_LETTERS,
    FIDEL_CONSONANT, FIDEL_VOWEL,

    /** Opens the emoji panel (ADR 0028); only in fields that take emoji ([InputProfile.allowsEmoji]). */
    EMOJI,
}

/**
 * What a key's long-press fires when it has no popup: the globe cycles the language's layouts,
 * the comma opens the emoji panel.
 */
enum class LongAction { CYCLE_LAYOUT, EMOJI }

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
 * wide); [longAction] fires on long-press instead (globe, comma). [hint] is a small corner label showing
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
    val longAction: LongAction? = null,
    val hint: String = "",
    val shiftHint: String? = null,
    val order: Int = 0,
    val description: String = "",
) {
    val isLetter: Boolean get() = kind == KeyKind.CHAR && text.length == 1 && text[0].isLetter()
    val isSpacer: Boolean get() = kind == KeyKind.CHAR && text.isEmpty()

    /** What an accessibility service should say for this key. */
    val spoken: String get() = description.ifEmpty { label }

    /** Acts on release (so a long-press can take over) instead of on press. */
    val actsOnRelease: Boolean get() = popup.isNotEmpty() || longAction != null
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
    /**
     * numeralStyle ETHIOPIC (ADR 0043 as amended by ADR 0044): the digit keys of am/ti typed natively type Ethiopic numerals
     * ([ethiopicDigits]). False (WESTERN, the default): they type Western digits with the Ethiopic numeral on long-press.
     */
    val ethiopicNumerals: Boolean = false,
    /** Phonetic mode: the native output of each letter key (secondary key labels). */
    val hints: PhoneticHints? = null,
    /** ADR 0043: the arrangement of the Latin letter keys (the language's choice). */
    val latinLayout: LatinLayout = LatinLayout.DEFAULT,
    /** ADR 0043: the digit row above the letters; off = the digits on long-press of the top letter row. */
    val numberRow: Boolean = true,
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

    /**
     * Ge'ez is typed natively (the Fidel grid, ADR 0007, or Phonetic, product owner 2026-11-19): `.` and `,` tap the
     * Ethiopic full stop ። and comma ፣, with the ASCII mark first in the long-press popup. Not in the English (ABC)
     * writing mode of am/ti, nor in om, en or ar.
     */
    val ethiopicPunctuation: Boolean
        get() = LanguageCatalog.isEthiopic(mode.language) && (mode.kind == LayoutKind.FIDEL && grid != null || mode.kind == LayoutKind.PHONETIC)

    /**
     * ADR 0043 / 0044: the digit keys offer Ethiopic numerals (፩…፱, ፲ on the 0 key, the tens, ፻ and ፼) while Amharic or
     * Tigrinya is typed natively (Fidel or Phonetic): never in the English (ABC) writing mode, other languages or the
     * number pad of number, phone and date fields. Which one a tap types is [ethiopicDigits].
     */
    val ethiopicNumeralKeys: Boolean
        get() = LanguageCatalog.isEthiopic(mode.language) && (mode.kind == LayoutKind.FIDEL || mode.kind == LayoutKind.PHONETIC)

    /**
     * numeralStyle ETHIOPIC: the digit keys type the Ethiopic numeral, the Western digit on long-press. Otherwise (WESTERN,
     * the default since ADR 0044) [ethiopicNumeralKeys] type the Western digit with the Ethiopic numeral on long-press.
     */
    val ethiopicDigits: Boolean get() = ethiopicNumerals && ethiopicNumeralKeys

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

/**
 * Ethiopic numerals (U+1369..U+137C, ADR 0043). Ethiopic has no zero and no place value: 1-9 are ፩…፱, the tens ፲…፺, then
 * ፻ (100) and ፼ (10 000); 2017 is ፳፻፲፯. The digit row puts ፩…፱ on 1-9 and ፲ on the 0 key (its place in the row,
 * and the next numeral after ፱), so every numeral is a tap or a long-press away.
 */
object EthiopicNumerals {
    /** What digit key [d] types: ፩…፱ for 1-9, ፲ (ten) for 0. */
    fun primary(d: Char): String = when (d) {
        '0' -> "፲"
        in '1'..'9' -> (0x1369 + (d - '1')).toChar().toString()
        else -> d.toString()
    }

    /**
     * The long-press numerals when no layout pack is loaded (the packs' `digits` list the same): the ten of each digit
     * (፳ on 2, ...), ፲ on 1, ፻ and ፼ on 0.
     */
    fun alternatives(d: Char): List<String> = when (d) {
        '0' -> listOf("፻", "፼")
        in '1'..'9' -> listOf(primary(d), (0x1372 + (d - '1')).toChar().toString())
        else -> emptyList()
    }

    /** Whether [c] is an Ethiopic number (digit, ten, hundred or ten thousand). */
    fun isNumeral(c: Char): Boolean = c in '፩'..'፼'
}

/** Layouts: ASCII QWERTY (A1.2), Qubee, phonetic, the Fidel grid from core data (A1.4) and Arabic (ADR 0034). */
object Layouts {
    /** Key descriptions in the UI language (ADR 0032); read when a layout is built. */
    private val L: KeyboardLabels get() = KeyboardLabels.current
    private const val FULL_STOP = "።"

    /** The width of a letter row in key units: the bottom row adds up to it (ADR 0035). */
    private const val ROW_UNITS = 10f

    /** The space bar is never narrower than three keys (the Arabic row has one key more). */
    private const val MIN_SPACE_WEIGHT = 3f
    private const val ETHIOPIC_COMMA = "፣"

    /** The Ge'ez punctuation marks every Ethiopic native layout must reach (SD 7.2). */
    val GEEZ_MARKS: List<String> = listOf("።", "፣", "፤", "፥", "፦", "፧", "፨")
    private fun ch(c: String, w: Float = 1f) = Key(KeyKind.CHAR, c, c, w)
    private fun chars(s: String) = s.map { ch(it.toString()) }

    private fun spacer(w: Float) = Key(KeyKind.CHAR, "", "", w)
    private fun backspace(w: Float = 1.5f) = Key(KeyKind.BACKSPACE, "⌫", weight = w, description = L.keyDelete)
    private fun enter(w: Float = 1.5f) = Key(KeyKind.ENTER, "", weight = w)
    /**
     * The comma and emoji keys in one (ADR 0035's row kept short): a tap types the comma, a
     * long-press opens the emoji panel, the ☺ corner hint marks it. The comma's own long-press
     * alternatives stay on the period key and the symbols page. Fields that disallow emoji keep the
     * plain comma with its long-press alternatives.
     */
    private fun emojiComma(base: Key, profile: InputProfile): Key =
        if (!profile.allowsEmoji) base
        else base.copy(
            popup = emptyList(),
            longAction = LongAction.EMOJI,
            hint = "☺",
            description = "${base.description.ifEmpty { L.keyComma }}, ${L.keyEmoji}",
        )

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

    private const val DIGITS = "1234567890"

    private fun digitRow(ctx: LayoutContext): List<Key> = DIGITS.map { digitKey(it, ctx) }

    /** The key of ASCII digit [d] in the digit row (letters and symbols pages). */
    private fun digitKey(d: Char, ctx: LayoutContext): Key {
        if (ctx.arabic) {
            // Western digits type by default (what most Arabic-script phones send); the Arabic-Indic digit is on long-press.
            val indic = ArabicKeys.indicDigit(d)
            return Key(
                KeyKind.CHAR, d.toString(), d.toString(),
                popup = listOf(PopupItem(indic, description = L.keyArabicNumeral.format(indic))),
                hint = indic,
            )
        }
        if (ctx.ethiopicNumeralKeys) {
            // The Ethiopic numeral of the key (፩…፱, ፲ on 0) and the rest of the layout pack's alternatives (the tens on 1-9,
            // hundred and ten thousand on the ፲ key).
            val numeral = EthiopicNumerals.primary(d)
            val others = (ctx.grid?.digits?.get(d.toString()) ?: EthiopicNumerals.alternatives(d)).filter { it != numeral }
            fun ethiopic(n: String) = PopupItem(n, description = L.keyEthiopicNumeral.format(n))
            if (ctx.ethiopicDigits) {
                // numeralStyle ETHIOPIC (ADR 0043): the numeral types; long-press offers the Western digit first (the hint).
                return Key(
                    KeyKind.CHAR, numeral, numeral,
                    popup = listOf(PopupItem(d.toString())) + others.map(::ethiopic),
                    popupColumns = 3, hint = d.toString(), description = L.keyEthiopicNumeral.format(numeral),
                )
            }
            // Default (ADR 0044): the Western digit types; long-press offers the Ethiopic numeral first (the corner hint).
            return Key(
                KeyKind.CHAR, d.toString(), d.toString(),
                popup = (listOf(numeral) + others).map(::ethiopic),
                popupColumns = 3, hint = numeral,
            )
        }
        return ch(d.toString())
    }

    /** One Arabic mark as a popup choice. */
    private fun markItem(m: ArabicKeys.Mark) = PopupItem(m.label, m.text, description = L.keyArabicMark.format(m.name))

    /** One Arabic mark as a key (the Arabic symbols page). */
    private fun markKey(m: ArabicKeys.Mark) = Key(KeyKind.CHAR, m.label, m.text, description = L.keyArabicMark.format(m.name))

    /**
     * The marks key of the Arabic layout, after the globe (where the ABC key used to be): tap types a fatha (what
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
        description = L.keySpaceMode.format(
            LanguageCatalog.spokenName(ctx.mode.language),
            if (ctx.mode.kind == LayoutKind.ENGLISH) ctx.latinLayout.label else LanguageCatalog.spokenKind(ctx.mode.kind),
        ),
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
            return Key(KeyKind.CHAR, ".", ".", 1f, popup = p, popupColumns = 4, hint = ArabicKeys.QUESTION, description = L.keyPeriod)
        }
        val m = marks(ctx)
        if (ctx.ethiopicPunctuation) {
            // Ge'ez typed natively (Fidel, ADR 0007; Phonetic): tap = ። ; ASCII "." leads the popup, then the other marks.
            val others = m.filter { it.label != FULL_STOP }
            return Key(
                KeyKind.CHAR, FULL_STOP, FULL_STOP, 1f,
                popup = listOf(PopupItem(".", description = L.keyPeriod)) + others, popupColumns = 4,
                hint = ".", description = L.keyEthiopicFullStop,
            )
        }
        return Key(KeyKind.CHAR, ".", ".", 1f, popup = m, popupColumns = 4, hint = m.firstOrNull()?.label.orEmpty())
    }

    private fun commaKey(ctx: LayoutContext): Key {
        if (ctx.arabic) {
            // Tap = Arabic comma; long-press: the Arabic semicolon (the hint), then the ASCII comma.
            return Key(
                KeyKind.CHAR, ArabicKeys.COMMA, ArabicKeys.COMMA, 1f,
                popup = listOf(
                    PopupItem(ArabicKeys.SEMICOLON, description = L.keyArabicPunctuation.format(ArabicKeys.SEMICOLON)),
                    PopupItem(",", description = L.keyComma),
                ),
                popupColumns = 2, hint = ArabicKeys.SEMICOLON, description = L.keyArabicComma,
            )
        }
        val m = marks(ctx, setOf("፣", "፤"))
        if (ctx.ethiopicPunctuation) {
            val others = m.filter { it.label != ETHIOPIC_COMMA }
            return Key(
                KeyKind.CHAR, ETHIOPIC_COMMA, ETHIOPIC_COMMA, 1f,
                popup = listOf(PopupItem(",", description = L.keyComma)) + others, popupColumns = 2,
                hint = ",", description = L.keyEthiopicComma,
            )
        }
        return Key(KeyKind.CHAR, ",", ",", 1f, popup = m, popupColumns = 2, hint = m.firstOrNull()?.label.orEmpty())
    }

    private fun letters(profile: InputProfile, ctx: LayoutContext): KeyboardLayout {
        val toSym = Key(KeyKind.TO_SYMBOLS, "?123", weight = 1.25f, description = L.keyNumbersSymbols)
        val bottom = when (profile.kind) {
            FieldKind.EMAIL -> listOf(toSym, ch("@"), ch(","), plainSpace(3f), ch("."), enter())
            FieldKind.URI -> listOf(toSym, ch("/"), plainSpace(2f), ch("."), Key(KeyKind.CHAR, ".com", ".com", 1.5f), enter())
            // ADR 0035, Gboard's order, with the emoji key folded into the comma (a tap types it, a long-press
            // opens the panel) and the language's tag under the globe icon: ?123, comma, globe, (Arabic: the
            // marks key), space, period, Enter. No ABC key: am/ti switch writing modes with the bar's pill
            // (one tap away), om reaches ABC by long-pressing the globe.
            FieldKind.TEXT -> {
                val keys = listOfNotNull(
                    toSym,
                    emojiComma(commaKey(ctx), profile),
                    Key(KeyKind.GLOBE, LanguageCatalog.shortLabel(ctx.mode.language), weight = 1f, longAction = LongAction.CYCLE_LAYOUT, description = L.keySwitchLanguage),
                    if (ctx.arabic) arabicMarksKey() else null,
                )
                val after = listOf(periodKey(ctx), enter())
                // The space bar takes what the letter rows' ten units leave, never less than three keys.
                val space = maxOf(MIN_SPACE_WEIGHT, ROW_UNITS - (keys + after).sumOf { it.weight.toDouble() }.toFloat())
                keys + spaceKey(space, ctx) + after
            }
            else -> listOf(toSym, ch(","), plainSpace(4f), ch("."), enter())
        }
        if (ctx.mode.kind == LayoutKind.FIDEL && ctx.grid != null && profile.kind == FieldKind.TEXT) {
            return fidel(ctx.grid, bottom)
        }
        if (ctx.arabic && profile.kind == FieldKind.TEXT) return arabic(ctx, bottom)
        val arrangement = ctx.latinLayout
        val qubee = ctx.mode.kind == LayoutKind.QUBEE
        // Qubee: no phonetic hints; the apostrophe (hudhaa) is part of Oromo words, so it lives on the primary layer (SD 7.3),
        // after the bottom letters unless the arrangement already has one (Dvorak's top row).
        val hints = if (qubee) null else ctx.hints
        val shiftLetters = arrangementKeys(arrangement.rows[2], hints) +
            if (qubee && !arrangement.hasApostrophe) listOf(Key(KeyKind.CHAR, "'", "'", description = L.keyApostrophe)) else emptyList()
        // Shift and Delete share what the letters leave of the ten units (QWERTY: 1.5 each; never narrower than 1.25).
        val side = maxOf(1.25f, (ROW_UNITS - shiftLetters.size) / 2)
        val shiftRow = listOf(Key(KeyKind.SHIFT, "⇧", weight = side, description = L.keyShift)) + shiftLetters + backspace(side)
        val top = padded(arrangementKeys(arrangement.rows[0], hints))
        val rows = buildList {
            if (ctx.numberRow) add(digitRow(ctx))
            add(if (ctx.numberRow) top else withDigits(top, ctx))
            add(padded(arrangementKeys(arrangement.rows[1], hints)))
            add(shiftRow)
            add(bottom)
        }
        return KeyboardLayout(Page.LETTERS, rows)
    }

    /** Letter keys of an arrangement row; its punctuation keys (Dvorak's `'` `,` `.`) carry their spoken names. */
    private fun arrangementKeys(s: String, hints: PhoneticHints?): List<Key> = s.map { c ->
        val t = c.toString()
        when (c) {
            '\'' -> Key(KeyKind.CHAR, t, t, description = L.keyApostrophe)
            ',' -> Key(KeyKind.CHAR, t, t, description = L.keyComma)
            '.' -> Key(KeyKind.CHAR, t, t, description = L.keyPeriod)
            else -> if (hints == null) ch(t) else Key(KeyKind.CHAR, t, t, hint = hints.lower(c), shiftHint = hints.upper(c))
        }
    }

    /** A letter row shorter than ten keys is centred between spacers, as QWERTY's middle row is. */
    private fun padded(keys: List<Key>): List<Key> {
        if (keys.size >= ROW_UNITS.toInt()) return keys
        val pad = (ROW_UNITS - keys.size) / 2
        return listOf(spacer(pad)) + keys + spacer(pad)
    }

    /**
     * ADR 0043, number row off: the top letter row's first ten keys also offer the digits 1…0 (the digit key's choices
     * first in the long-press popup, as Gboard does); the digit is the corner hint unless the key shows a phonetic hint.
     */
    private fun withDigits(row: List<Key>, ctx: LayoutContext): List<Key> {
        var i = 0
        return row.map { k ->
            if (k.isSpacer || i >= DIGITS.length) return@map k
            val dk = digitKey(DIGITS[i++], ctx)
            val items = listOf(PopupItem(dk.label, dk.text, description = dk.spoken)) + dk.popup
            k.copy(popup = items + k.popup, popupColumns = maxOf(k.popupColumns, items.size).coerceAtMost(5), hint = k.hint.ifEmpty { dk.label })
        }
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
                    // Long-press only (ADR 0043): the key shows what a tap types (the default form, ስ), its family in TalkBack.
                    Key(KeyKind.CHAR, k.tapText, k.tapText, popup = popup, description = k.label)
                }
            }
        }.toMutableList()
        rows[rows.lastIndex] = rows.last() + backspace(1f)
        if (grid.twoKey && grid.vowelKeys.isNotEmpty()) {
            rows += listOf(grid.vowelKeys.map { Key(KeyKind.FIDEL_VOWEL, it.label, order = it.order, description = L.keyVowelOrder.format(it.order, it.label)) })
        }
        val vowelRow = grid.twoKey && grid.vowelKeys.isNotEmpty()
        rows += listOf(bottom)
        // Without the vowel-order row the keys take its height (7 x 42 = 6 x 49: the keyboard keeps its size).
        return KeyboardLayout(Page.LETTERS, rows, rowHeightDp = if (vowelRow) 42f else 49f)
    }

    /**
     * The Arabic letter layout (ADR 0034): the digit row, the three standard rows (the last ending in Delete), then the
     * bottom row. No shift key (Arabic has no case); the marks key sits in the bottom row after the globe.
     */
    private fun arabic(ctx: LayoutContext, bottom: List<Key>): KeyboardLayout {
        val rows = ArabicKeys.ROWS.map { row ->
            row.map { c -> Key(KeyKind.CHAR, c, c, popup = ArabicKeys.ALTERNATIVES[c].orEmpty().map { PopupItem(it) }) }
        }
        val top = if (ctx.numberRow) listOf(digitRow(ctx), rows[0]) else listOf(withDigits(rows[0], ctx))
        return KeyboardLayout(Page.LETTERS, top + listOf(rows[1], rows[2] + backspace(1f), bottom))
    }

    private fun bottomSymbols(profile: InputProfile, ctx: LayoutContext) = listOf(
        // Back to the letters: the language's own glyph on the Arabic layout (its letters are not "ABC").
        Key(KeyKind.TO_LETTERS, if (ctx.arabic) LanguageCatalog.nativeGlyph(ctx.mode.language) else "ABC", weight = 1.5f, description = L.keyLetters),
        emojiComma(symbolKey(',', ctx), profile),
        plainSpace(if (profile.allowsEmoji) 4.5f else 4f), ch("."), enter(),
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
