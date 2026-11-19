package com.qalsmartkeyboard.ime

import android.text.InputType as T
import android.view.inputmethod.EditorInfo as E
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0035: the letters page's bottom row, after Gboard: ?123, comma, globe, emoji, space, period, Enter. */
class BottomRowTest {
    private fun profile(type: Int) = InputProfile.from(type, E.IME_ACTION_UNSPECIFIED, false, 0)
    private val text = profile(T.TYPE_CLASS_TEXT)

    private fun bottom(mode: TypingMode, p: InputProfile = text) = Layouts.forPage(Page.LETTERS, p, LayoutContext(mode)).rows.last()

    private val modes = listOf(
        TypingMode("am", LayoutKind.PHONETIC), TypingMode.abc("am"), TypingMode("ti", LayoutKind.PHONETIC),
        TypingMode.abc("ti"), TypingMode("om", LayoutKind.QUBEE), TypingMode.abc("om"), TypingMode("en", LayoutKind.ENGLISH),
    )

    @Test fun everyLatinLetterLayoutHasTheSameSevenKeys() {
        for (m in modes) {
            assertEquals(
                "$m",
                listOf(KeyKind.TO_SYMBOLS, KeyKind.CHAR, KeyKind.GLOBE, KeyKind.EMOJI, KeyKind.SPACE, KeyKind.CHAR, KeyKind.ENTER),
                bottom(m).map { it.kind },
            )
            // Ge'ez typed natively (am/ti Phonetic) taps the Ethiopic comma and full stop (owner, 2026-11-19).
            val geez = m.kind == LayoutKind.PHONETIC
            assertEquals("$m", if (geez) "፣" else ",", bottom(m)[1].text)
            assertEquals("$m", if (geez) "።" else ".", bottom(m)[5].text)
        }
    }

    @Test fun thereIsNoAbcKeyAnyMore() {
        for (m in modes + TypingMode("ar", LayoutKind.ARABIC)) {
            for (page in Page.entries) {
                val keys = Layouts.forPage(page, text, LayoutContext(m)).rows.flatten()
                assertTrue("$m $page", keys.none { it.kind == KeyKind.ABC_TOGGLE })
            }
        }
    }

    @Test fun theRowAddsUpToTheLetterRowsTenUnits() {
        for (m in modes) {
            val row = bottom(m)
            assertEquals("$m", 10f, row.sumOf { it.weight.toDouble() }.toFloat(), 0.001f)
            assertEquals("$m: space ${row[4].weight}", 3.25f, row[4].weight, 0.001f)
        }
        // A text field without emoji (raw key events) gives the emoji key's width to the space bar.
        val raw = bottom(TypingMode.abc("en"), profile(T.TYPE_NULL))
        assertTrue(raw.none { it.kind == KeyKind.EMOJI })
        assertEquals(4.25f, raw.first { it.kind == KeyKind.SPACE }.weight, 0.001f)
    }

    @Test fun commaPeriodGlobeAndEmojiAreOneKeyWide() {
        val row = bottom(TypingMode("am", LayoutKind.PHONETIC))
        assertEquals(listOf(1.25f, 1f, 1f, 1f, 3.25f, 1f, 1.5f), row.map { it.weight })
    }

    @Test fun theGlobeStillCyclesLayoutsOnLongPress() {
        // With the ABC key gone, Oromo reaches ABC (and am/ti every layout) by long-pressing the globe.
        for (m in modes) {
            val globe = bottom(m)[2]
            assertEquals(KeyKind.GLOBE, globe.kind)
            assertTrue("$m", globe.longAction)
        }
    }

    @Test fun otherFieldKindsKeepTheirRows() {
        assertEquals(listOf(1.25f, 1f, 1f, 3f, 1f, 1.5f), bottom(TypingMode.abc("en"), profile(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)).map { it.weight })
        assertEquals(6, bottom(TypingMode.abc("en"), profile(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI)).size)
    }
}
