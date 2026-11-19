package com.qalsmartkeyboard.ime

import android.text.InputType
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.NumeralStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0043: Ethiopic numerals are the default output of the digit keys while Amharic or Tigrinya is typed natively
 * (Phonetic and Fidel), Western digits on long-press; everything else keeps Western digits.
 */
class EthiopicNumeralsTest {
    private val ethiopic = listOf("፩", "፪", "፫", "፬", "፭", "፮", "፯", "፰", "፱", "፲")
    private val western = "1234567890".map { it.toString() }

    private fun prefs(s: KeyboardSettings = KeyboardSettings.DEFAULT) = SettingsPreferences({ s })

    private fun session(
        s: KeyboardSettings = KeyboardSettings.DEFAULT,
        type: Int = InputType.TYPE_CLASS_TEXT,
        languages: EnabledLanguages? = null,
    ): CoreTestEnv.Session {
        val p = prefs(s)
        return CoreTestEnv.Session(type = type, languages = languages ?: p, prefs = p)
    }

    private fun KeyboardController.digitRow(): List<Key> = layout.rows.first()

    @Test fun amPhoneticDigitRowIsEthiopicWithWesternOnLongPress() {
        val sess = session()
        assertEquals(TypingMode("am", LayoutKind.PHONETIC), sess.c.effectiveMode)
        val row = sess.c.digitRow()
        assertEquals(ethiopic, row.map { it.text })
        assertEquals(ethiopic, row.map { it.label })
        // The Western digit leads each popup and is the corner hint.
        assertEquals(western, row.map { it.popup.first().text })
        assertEquals(western, row.map { it.hint })
        // 1 also offers ten (from the layout pack); 2 its ten; the ፲ key (0) offers 0, hundred and ten thousand.
        assertEquals(listOf("1", "፲"), row[0].popup.map { it.text })
        assertEquals(listOf("2", "፳"), row[1].popup.map { it.text })
        assertEquals(listOf("0", "፻", "፼"), row[9].popup.map { it.text })
        assertEquals("Ethiopic numeral ፩", row[0].spoken)
        // Numerals are not letters: shift leaves them alone.
        assertTrue(row.none { it.isLetter })
    }

    @Test fun tigrinyaToo() {
        val sess = session()
        sess.mode(TypingMode("ti", LayoutKind.PHONETIC))
        assertEquals(ethiopic, sess.c.digitRow().map { it.text })
    }

    @Test fun symbolsPagesShowEthiopicDigitsAndSwitchingPagesKeepsWorking() {
        val sess = session()
        sess.tap(sess.key(KeyKind.TO_SYMBOLS))
        assertEquals(Page.SYMBOLS, sess.c.layout.page)
        assertEquals(ethiopic, sess.c.digitRow().map { it.text })
        sess.tap(sess.key(KeyKind.TO_SYMBOLS_2))
        assertEquals(Page.SYMBOLS_2, sess.c.layout.page)
        assertEquals(ethiopic, sess.c.digitRow().map { it.text })
        sess.tap(sess.key(KeyKind.TO_SYMBOLS))
        sess.tap(sess.key(KeyKind.TO_LETTERS))
        assertEquals(Page.LETTERS, sess.c.layout.page)
        assertEquals(ethiopic, sess.c.digitRow().map { it.text })
        // Typing on the symbols page commits the numeral.
        sess.tap(sess.key(KeyKind.TO_SYMBOLS))
        sess.tap(sess.c.digitRow()[2])
        assertEquals("፫", sess.ed.text.toString())
    }

    @Test fun fidelFollowsTheSameSettingOnTheSymbolsPage() {
        val sess = session()
        sess.mode(TypingMode("am", LayoutKind.FIDEL))
        // The Fidel grid has no digit row on its letters page; its ?123 page has the Ethiopic one.
        assertTrue(sess.c.layout.rows.flatten().none { it.text in ethiopic || it.text in western })
        sess.tap(sess.key(KeyKind.TO_SYMBOLS))
        assertEquals(ethiopic, sess.c.digitRow().map { it.text })
        sess.tap(sess.c.digitRow()[0])
        assertEquals("፩", sess.ed.text.toString())
    }

    @Test fun englishWritingModeOtherLanguagesAndNumberFieldsStayWestern() {
        val sess = session(languages = EnabledLanguages { listOf("am", "om", "en", "ar") })
        sess.mode(TypingMode.abc("am")) // the English writing mode of Amharic
        assertEquals(western, sess.c.digitRow().map { it.text })
        assertTrue(sess.c.digitRow().all { it.popup.isEmpty() })
        sess.mode(TypingMode("om", LayoutKind.QUBEE))
        assertEquals(western, sess.c.digitRow().map { it.text })
        sess.mode(TypingMode("en", LayoutKind.ENGLISH))
        assertEquals(western, sess.c.digitRow().map { it.text })
        sess.mode(TypingMode("ar", LayoutKind.ARABIC))
        // Arabic keeps Western digits with the Arabic-Indic digit on long-press (ADR 0034).
        assertEquals(western, sess.c.digitRow().map { it.text })
        assertEquals("١", sess.c.digitRow()[0].popup.single().text)

        for (type in listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)) {
            val num = session(type = type)
            assertEquals(Page.NUMBER, num.c.layout.page)
            val keys = num.c.layout.rows.flatten().map { it.text }
            assertTrue("$type", keys.containsAll(western))
            assertTrue("$type", keys.none { it in ethiopic })
        }
    }

    @Test fun westernChosenKeepsPlainDigits() {
        val sess = session(KeyboardSettings.DEFAULT.copy(numeralStyle = NumeralStyle.WESTERN, numeralStyleChosen = true))
        assertEquals(western, sess.c.digitRow().map { it.text })
        assertTrue(sess.c.digitRow().all { it.popup.isEmpty() })
    }

    @Test fun longPressGivesTheWesternDigit() {
        val sess = session()
        sess.pick(sess.c.digitRow()[0].popup.first())
        sess.pick(sess.c.digitRow()[9].popup[1])
        assertEquals("1፻", sess.ed.text.toString())
    }

    @Test fun aNumeralEndsThePhoneticWordAndIsNeitherComposedNorSuggested() {
        val sess = session()
        sess.type("selam")
        assertTrue(sess.c.composing)
        sess.tap(sess.c.digitRow()[0])
        val text = sess.ed.text.toString()
        // The word is converted and committed, then the numeral: nothing stays composing, no candidates for a number.
        assertTrue(text, text.endsWith("፩"))
        assertFalse(text.any { it in 'a'..'z' })
        assertFalse(sess.c.composing)
        assertEquals("", sess.ed.composing)
        assertTrue(sess.c.candidates.none { s -> s.text.any { EthiopicNumerals.isNumeral(it) } })
        sess.tap(sess.key(KeyKind.SPACE))
        sess.tap(sess.c.digitRow()[1])
        assertTrue(sess.ed.text.toString().endsWith("፩ ፪"))
    }

    @Test fun numeralTableCoversTheEthiopicNumbers() {
        assertEquals(ethiopic, "1234567890".map { EthiopicNumerals.primary(it) })
        assertEquals(listOf("፻", "፼"), EthiopicNumerals.alternatives('0'))
        assertEquals(listOf("፱", "፺"), EthiopicNumerals.alternatives('9'))
        assertTrue(EthiopicNumerals.isNumeral('፩') && EthiopicNumerals.isNumeral('፼'))
        assertFalse(EthiopicNumerals.isNumeral('ሀ') || EthiopicNumerals.isNumeral('1') || EthiopicNumerals.isNumeral('።'))
        assertFalse('፩'.isLetter())
    }
}
