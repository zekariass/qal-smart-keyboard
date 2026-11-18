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

    companion object {
        val DEFAULT = LayoutContext(TypingMode.abc("en"))
    }
}

/** Layouts: ASCII QWERTY (A1.2), Qubee, phonetic and the Fidel grid from core data (A1.4). */
object Layouts {
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
    private fun backspace(w: Float = 1.5f) = Key(KeyKind.BACKSPACE, "⌫", weight = w, description = "Delete")
    private fun enter(w: Float = 1.5f) = Key(KeyKind.ENTER, "", weight = w)
    private fun emojiKey(profile: InputProfile, w: Float = 1f): Key? =
        if (profile.allowsEmoji) Key(KeyKind.EMOJI, "", weight = w, description = "Emoji") else null

    private fun plainSpace(w: Float) = Key(KeyKind.SPACE, " ", " ", w, description = "Space")

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
        val alts = if (ctx.ethiopicNumerals && ctx.ethiopic) ctx.grid?.digits?.get(d.toString()).orEmpty() else emptyList()
        Key(
            KeyKind.CHAR, d.toString(), d.toString(),
            popup = alts.map { PopupItem(it, description = "Ethiopic numeral $it") },
            hint = alts.firstOrNull().orEmpty(),
        )
    }

    private fun spaceKey(w: Float, ctx: LayoutContext) = Key(
        KeyKind.SPACE, ctx.indicator, " ", w,
        popup = ctx.picker, popupColumns = 1,
        description = "Space, ${LanguageCatalog.spokenName(ctx.mode.language)} ${LanguageCatalog.spokenKind(ctx.mode.kind)}",
        draftBadge = ctx.draft,
    )

    private fun marks(ctx: LayoutContext, only: Set<String>? = null): List<PopupItem> =
        ctx.ethiopicMarks.filter { only == null || it in only }
            .map { PopupItem(it, description = "Ethiopic punctuation $it") }

    private fun periodKey(ctx: LayoutContext): Key {
        val m = marks(ctx)
        if (ctx.fidelPunctuation) {
            // Fidel grid only (ADR 0007): tap = ። ; ASCII "." leads the popup, then the other marks.
            val others = m.filter { it.label != FULL_STOP }
            return Key(
                KeyKind.CHAR, FULL_STOP, FULL_STOP, 0.75f,
                popup = listOf(PopupItem(".", description = "Period")) + others, popupColumns = 4,
                hint = ".", description = "Ethiopic full stop",
            )
        }
        return Key(KeyKind.CHAR, ".", ".", 0.75f, popup = m, popupColumns = 4, hint = m.firstOrNull()?.label.orEmpty())
    }

    private fun commaKey(ctx: LayoutContext): Key {
        val m = marks(ctx, setOf("፣", "፤"))
        if (ctx.fidelPunctuation) {
            val others = m.filter { it.label != ETHIOPIC_COMMA }
            return Key(
                KeyKind.CHAR, ETHIOPIC_COMMA, ETHIOPIC_COMMA, 0.75f,
                popup = listOf(PopupItem(",", description = "Comma")) + others, popupColumns = 2,
                hint = ",", description = "Ethiopic comma",
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
        val ethiopic = LanguageCatalog.isEthiopic(lang)
        return if (ctx.mode.isAbc) {
            Key(KeyKind.ABC_TOGGLE, LanguageCatalog.nativeGlyph(lang), description = "Switch to ${LanguageCatalog.spokenName(lang)} keys")
        } else {
            Key(KeyKind.ABC_TOGGLE, "ABC", description = if (ethiopic) "Switch to English writing mode" else "Switch to ABC Latin keys")
        }
    }

    private fun letters(profile: InputProfile, ctx: LayoutContext): KeyboardLayout {
        val toSym = Key(KeyKind.TO_SYMBOLS, "?123", weight = 1.25f, description = "Numbers and symbols")
        val bottom = when (profile.kind) {
            FieldKind.EMAIL -> listOf(toSym, ch("@"), ch(","), plainSpace(3f), ch("."), enter())
            FieldKind.URI -> listOf(toSym, ch("/"), plainSpace(2f), ch("."), Key(KeyKind.CHAR, ".com", ".com", 1.5f), enter())
            FieldKind.TEXT -> listOfNotNull(
                toSym,
                Key(KeyKind.GLOBE, "🌐", weight = 1f, longAction = true, description = "Switch language"),
                toggleKey(ctx),
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
        val shiftRow = if (ctx.mode.kind == LayoutKind.QUBEE) {
            // The apostrophe (hudhaa) is part of Oromo words, so it lives on the primary layer (SD 7.3).
            listOf(Key(KeyKind.SHIFT, "⇧", weight = 1.25f, description = "Shift")) + chars("zxcvbnm") +
                Key(KeyKind.CHAR, "'", "'", description = "Apostrophe, hudhaa") + backspace(1.25f)
        } else {
            listOf(Key(KeyKind.SHIFT, "⇧", weight = 1.5f, description = "Shift")) + letterKeys("zxcvbnm", ctx.hints) + backspace()
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
            rows += listOf(grid.vowelKeys.map { Key(KeyKind.FIDEL_VOWEL, it.label, order = it.order, description = "Vowel order ${it.order} ${it.label}") })
        }
        rows += listOf(bottom)
        return KeyboardLayout(Page.LETTERS, rows, rowHeightDp = 42f)
    }

    private fun bottomSymbols(profile: InputProfile) = listOfNotNull(
        Key(KeyKind.TO_LETTERS, "ABC", weight = 1.5f, description = "Letters"), ch(","), emojiKey(profile, 1f),
        plainSpace(if (profile.allowsEmoji) 3.5f else 4f), ch("."), enter(),
    )

    private fun symbols1(profile: InputProfile, ctx: LayoutContext): KeyboardLayout {
        val marks = ctx.ethiopicMarks
        val rows = buildList {
            add(digitRow(ctx))
            if (marks.isNotEmpty()) {
                add(marks.map { Key(KeyKind.CHAR, it, it, description = "Ethiopic punctuation $it") })
            }
            add(chars("@#$%&-+()/"))
            add(chars("*\"':;!?~`|"))
            add(listOf(Key(KeyKind.TO_SYMBOLS_2, "=\\<", weight = 1.5f, description = "More symbols")) + chars(",._") + listOf(backspace()))
            add(bottomSymbols(profile))
        }
        return KeyboardLayout(Page.SYMBOLS, rows, if (rows.size > 5) 44f else 50f)
    }

    private fun symbols2(profile: InputProfile, ctx: LayoutContext) = KeyboardLayout(
        Page.SYMBOLS_2,
        listOf(
            digitRow(ctx),
            chars("~`|^{}[]<>"),
            chars("=\\£€¥°©®™•"),
            listOf(Key(KeyKind.TO_SYMBOLS, "?123", weight = 1.5f, description = "Numbers and symbols")) + chars(",._;:") + listOf(backspace()),
            bottomSymbols(profile),
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
