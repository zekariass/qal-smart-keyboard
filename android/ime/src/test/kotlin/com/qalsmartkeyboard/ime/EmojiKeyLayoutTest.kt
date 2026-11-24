package com.qalsmartkeyboard.ime

import android.text.InputType as T
import android.view.inputmethod.EditorInfo as E
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emoji access (ADR 0028): folded into the comma's long-press; where it is, where it is hidden. */
class EmojiKeyLayoutTest {
    private fun profile(type: Int) = InputProfile.from(type, E.IME_ACTION_UNSPECIFIED, false, 0)

    private fun keys(page: Page, p: InputProfile, ctx: LayoutContext = LayoutContext.DEFAULT) = Layouts.forPage(page, p, ctx).rows.flatten()

    @Test fun plainTextFieldsHaveTheEmojiKeyOnLettersAndSymbols() {
        val p = profile(T.TYPE_CLASS_TEXT)
        for (page in listOf(Page.LETTERS, Page.SYMBOLS, Page.SYMBOLS_2)) {
            assertTrue("$page", keys(page, p).any { it.longAction == LongAction.EMOJI })
        }
        assertTrue(p.allowsEmoji)
    }

    @Test fun theEmojiActionLivesOnTheCommaKey() {
        // A tap types the comma, a long-press opens the emoji panel; the ☺ corner hint marks it.
        val bottom = Layouts.forPage(Page.LETTERS, profile(T.TYPE_CLASS_TEXT)).rows.last()
        val comma = bottom.first { it.longAction == LongAction.EMOJI }
        assertEquals(KeyKind.CHAR, comma.kind)
        assertEquals(",", comma.text)
        assertEquals("☺", comma.hint)
        assertTrue(comma.actsOnRelease)
    }

    @Test fun hiddenWhereEmojiMakeNoSense() {
        val hidden = listOf(
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_VARIATION_PASSWORD,
            T.TYPE_CLASS_NUMBER, T.TYPE_CLASS_PHONE, T.TYPE_CLASS_DATETIME,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI,
            T.TYPE_NULL,
        )
        for (type in hidden) {
            val p = profile(type)
            assertFalse("allowsEmoji for $type", p.allowsEmoji)
            for (page in Page.entries) {
                assertTrue("$type $page", keys(page, p).none { it.kind == KeyKind.EMOJI })
                assertTrue("$type $page", keys(page, p).none { it.longAction == LongAction.EMOJI })
            }
        }
    }

    @Test fun rowsOfOtherFieldsAreUnchanged() {
        // An email field keeps its bottom row exactly (no emoji key, no weight change).
        val bottom = Layouts.forPage(Page.LETTERS, profile(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)).rows.last()
        assertEquals(listOf(1.25f, 1f, 1f, 3f, 1f, 1.5f), bottom.map { it.weight })
    }

    @Test fun spaceBarKeepsAUsableShareInEveryLetterLayout() {
        val p = profile(T.TYPE_CLASS_TEXT)
        for (mode in listOf(TypingMode.abc("am"), TypingMode.abc("en"), TypingMode("om", LayoutKind.QUBEE), TypingMode("am", LayoutKind.PHONETIC))) {
            val bottom = Layouts.forPage(Page.LETTERS, p, LayoutContext(mode)).rows.last()
            val space = bottom.first { it.kind == KeyKind.SPACE }
            val share = space.weight / bottom.sumOf { it.weight.toDouble() }.toFloat()
            assertTrue("$mode space share $share", share >= 0.25f)
        }
    }

    @Test fun theKeyIsDescribedForAccessibility() {
        val key = keys(Page.LETTERS, profile(T.TYPE_CLASS_TEXT)).first { it.longAction == LongAction.EMOJI }
        assertEquals("Comma, Emoji", key.spoken)
        assertTrue(key.actsOnRelease)
    }
}
