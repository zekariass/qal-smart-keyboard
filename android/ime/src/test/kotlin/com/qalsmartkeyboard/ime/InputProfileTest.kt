package com.qalsmartkeyboard.ime

import android.text.InputType as T
import android.view.inputmethod.EditorInfo as E
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputProfileTest {
    private fun p(type: Int, opts: Int = 0) = InputProfile.from(type, opts, false, 0)

    @Test fun passwordVariantsDisableLearning() {
        for (v in listOf(T.TYPE_TEXT_VARIATION_PASSWORD, T.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, T.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            val x = p(T.TYPE_CLASS_TEXT or v)
            assertEquals(FieldKind.PASSWORD, x.kind)
            assertFalse(x.learningAllowed)
        }
        val n = p(T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_VARIATION_PASSWORD)
        assertEquals(FieldKind.PASSWORD, n.kind)
        assertFalse(n.learningAllowed)
    }

    @Test fun noPersonalizedLearningFlag() {
        val x = p(T.TYPE_CLASS_TEXT, E.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertEquals(FieldKind.TEXT, x.kind)
        assertFalse(x.learningAllowed)
        assertTrue(p(T.TYPE_CLASS_TEXT).learningAllowed)
    }

    @Test fun emailUriNumberPhone() {
        assertEquals(FieldKind.EMAIL, p(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).kind)
        assertEquals(FieldKind.EMAIL, p(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS).kind)
        assertEquals(FieldKind.URI, p(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI).kind)
        assertEquals(FieldKind.NUMBER, p(T.TYPE_CLASS_NUMBER).kind)
        assertEquals(FieldKind.PHONE, p(T.TYPE_CLASS_PHONE).kind)
        assertEquals(Page.NUMBER, Layouts.startPage(p(T.TYPE_CLASS_PHONE)))
        assertEquals(Page.LETTERS, Layouts.startPage(p(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI)))
    }

    @Test fun capsOnlyForPlainText() {
        assertEquals(T.TYPE_TEXT_FLAG_CAP_SENTENCES, p(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_CAP_SENTENCES).capsFlags)
        assertEquals(0, p(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or T.TYPE_TEXT_FLAG_CAP_SENTENCES).capsFlags)
    }

    @Test fun enterActions() {
        val multi = T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE
        assertEquals(EnterAction.Newline, p(multi).enterAction)
        assertEquals(EnterAction.EnterKey, p(T.TYPE_CLASS_TEXT).enterAction)
        assertEquals(EnterAction.Editor(E.IME_ACTION_SEARCH), p(T.TYPE_CLASS_TEXT, E.IME_ACTION_SEARCH).enterAction)
        assertEquals(EnterAction.Newline, p(T.TYPE_CLASS_TEXT, E.IME_ACTION_SEND or E.IME_FLAG_NO_ENTER_ACTION).enterAction)
        assertEquals(EnterAction.Editor(E.IME_ACTION_SEND), p(multi, E.IME_ACTION_SEND).enterAction)
        assertEquals(EnterAction.Editor(77), InputProfile.from(T.TYPE_CLASS_TEXT, 0, true, 77).enterAction)
        assertEquals(EnterLabel.SEARCH, p(T.TYPE_CLASS_TEXT, E.IME_ACTION_SEARCH).enterLabel)
    }

    @Test fun multilineOnlyInPlainText() {
        assertTrue(p(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_FLAG_MULTI_LINE).multiline)
        assertFalse(p(T.TYPE_CLASS_TEXT).multiline)
    }
}
