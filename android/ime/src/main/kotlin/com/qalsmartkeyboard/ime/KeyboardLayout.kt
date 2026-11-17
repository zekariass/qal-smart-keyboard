package com.qalsmartkeyboard.ime

/** What a key does when pressed. [MODE] is the TEMPORARY A1.3 typing-mode toggle (A1.4 replaces it). */
enum class KeyKind { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, MODE, TO_SYMBOLS, TO_SYMBOLS_2, TO_LETTERS }

/**
 * One key. [text] is what a CHAR key commits (lower-case for letters; shift upper-cases it).
 * [weight] is the relative width inside its row. A CHAR key with empty [text] is a layout spacer.
 */
data class Key(val kind: KeyKind, val label: String, val text: String = "", val weight: Float = 1f) {
    val isLetter: Boolean get() = kind == KeyKind.CHAR && text.length == 1 && text[0].isLetter()
    val isSpacer: Boolean get() = kind == KeyKind.CHAR && text.isEmpty()
}

enum class Page { LETTERS, SYMBOLS, SYMBOLS_2, NUMBER }

class KeyboardLayout(val page: Page, val rows: List<List<Key>>)

/** ASCII QWERTY layouts (plan A1.2). Other scripts and language layouts arrive with A1.4. */
object Layouts {
    private fun ch(c: String, w: Float = 1f) = Key(KeyKind.CHAR, c, c, w)
    private fun chars(s: String) = s.map { ch(it.toString()) }
    private fun spacer(w: Float) = Key(KeyKind.CHAR, "", "", w)
    private fun space(w: Float) = Key(KeyKind.SPACE, " ", " ", w)
    private fun backspace(w: Float = 1.5f) = Key(KeyKind.BACKSPACE, "⌫", weight = w)
    private fun enter(w: Float = 1.5f) = Key(KeyKind.ENTER, "", weight = w)

    private val digits = chars("1234567890")

    fun forPage(page: Page, profile: InputProfile): KeyboardLayout = when (page) {
        Page.LETTERS -> letters(profile)
        Page.SYMBOLS -> symbols1()
        Page.SYMBOLS_2 -> symbols2()
        Page.NUMBER -> number(profile)
    }

    /** The page a field starts on. */
    fun startPage(profile: InputProfile): Page = when (profile.kind) {
        FieldKind.NUMBER, FieldKind.PHONE -> Page.NUMBER
        else -> Page.LETTERS
    }

    private fun letters(profile: InputProfile): KeyboardLayout {
        val toSym = Key(KeyKind.TO_SYMBOLS, "?123", weight = 1.5f)
        val bottom = when (profile.kind) {
            FieldKind.EMAIL -> listOf(toSym, ch("@"), ch(","), space(3f), ch("."), enter())
            FieldKind.URI -> listOf(toSym, ch("/"), space(2f), ch("."), Key(KeyKind.CHAR, ".com", ".com", 1.5f), enter())
            // Plain text: the mode key (ABC / አማ / ትግ) sits next to ?123; its label is drawn live.
            FieldKind.TEXT -> listOf(toSym, Key(KeyKind.MODE, "ABC", weight = 1.25f), ch(","), space(3.75f), ch("."), enter())
            else -> listOf(toSym, ch(","), space(4f), ch("."), enter())
        }
        return KeyboardLayout(
            Page.LETTERS,
            listOf(
                digits,
                chars("qwertyuiop"),
                listOf(spacer(0.5f)) + chars("asdfghjkl") + listOf(spacer(0.5f)),
                listOf(Key(KeyKind.SHIFT, "⇧", weight = 1.5f)) + chars("zxcvbnm") + listOf(backspace()),
                bottom,
            ),
        )
    }

    private fun bottomSymbols() = listOf(
        Key(KeyKind.TO_LETTERS, "ABC", weight = 1.5f), ch(","), space(4f), ch("."), enter(),
    )

    private fun symbols1() = KeyboardLayout(
        Page.SYMBOLS,
        listOf(
            digits,
            chars("@#$%&-+()/"),
            chars("*\"':;!?~`|"),
            listOf(Key(KeyKind.TO_SYMBOLS_2, "=\\<", weight = 1.5f)) + chars(",._") + listOf(backspace()),
            bottomSymbols(),
        ),
    )

    private fun symbols2() = KeyboardLayout(
        Page.SYMBOLS_2,
        listOf(
            digits,
            chars("~`|^{}[]<>"),
            chars("=\\£€¥°©®™•"),
            listOf(Key(KeyKind.TO_SYMBOLS, "?123", weight = 1.5f)) + chars(",._;:") + listOf(backspace()),
            bottomSymbols(),
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
                listOf(space(3f), enter(1f)),
            ),
        )
    }
}
