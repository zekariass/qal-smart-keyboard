package com.qalsmartkeyboard.ime

import android.text.InputType
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.NumeralStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0044 (amends ADR 0043): while Amharic or Tigrinya is typed natively (Phonetic and Fidel) the digit keys type Western
 * digits by default with the Ethiopic numeral on long-press (and as the corner hint); numeralStyle ETHIOPIC, chosen on the
 * settings page, swaps them. Everything else keeps plain Western digits.
 */
class EthiopicNumeralsTest {
    private val ethiopic = listOf("፩", "፪", "፫", "፬", "፭", "፮", "፯", "፰", "፱", "፲")
    private val western = "1234567890".map { it.toString() }
    private val ethiopicFirst = KeyboardSettings.DEFAULT.copy(numeralStyle = NumeralStyle.ETHIOPIC, numeralStyleChosen = true)

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

    /** The default: Western digit on the key, the Ethiopic numeral first on long-press and in the corner. */
    private fun assertWesternFirst(row: List<Key>, what: String = "") {
        assertEquals(what, western, row.map { it.text })
        assertEquals(what, western, row.map { it.label })
        assertEquals(what, ethiopic, row.map { it.popup.first().text })
        assertEquals(what, ethiopic, row.map { it.hint })
        // 1 also offers ten (from the layout pack), 2 its ten; 0 offers ፲, hundred and ten thousand.
        assertEquals(what, listOf("፩", "፲"), row[0].popup.map { it.text })
        assertEquals(what, listOf("፪", "፳"), row[1].popup.map { it.text })
        assertEquals(what, listOf("፲", "፻", "፼"), row[9].popup.map { it.text })
        assertEquals(what, "Ethiopic numeral ፩", row[0].popup.first().description)
    }

    @Test fun amPhoneticDigitRowIsWesternWithEthiopicOnLongPressByDefault() {
        val sess = session()
        assertEquals(TypingMode("am", LayoutKind.PHONETIC), sess.c.effectiveMode)
        assertWesternFirst(sess.c.digitRow())
        assertTrue(sess.c.digitRow().none { it.isLetter })
        sess.tap(sess.c.digitRow()[0])
        sess.pick(sess.c.digitRow()[1].popup.first())
        sess.pick(sess.c.digitRow()[9].popup.first())
        assertEquals("1፪፲", sess.ed.text.toString())
    }

    @Test fun ethiopicChosenPutsEthiopicOnTheKeysWithWesternOnLongPress() {
        val sess = session(ethiopicFirst)
        val row = sess.c.digitRow()
        assertEquals(ethiopic, row.map { it.text })
        assertEquals(ethiopic, row.map { it.label })
        // The Western digit leads each popup and is the corner hint.
        assertEquals(western, row.map { it.popup.first().text })
        assertEquals(western, row.map { it.hint })
        assertEquals(listOf("1", "፲"), row[0].popup.map { it.text })
        assertEquals(listOf("2", "፳"), row[1].popup.map { it.text })
        assertEquals(listOf("0", "፻", "፼"), row[9].popup.map { it.text })
        assertEquals("Ethiopic numeral ፩", row[0].spoken)
        assertTrue(row.none { it.isLetter })
    }

    @Test fun tigrinyaToo() {
        val sess = session()
        sess.mode(TypingMode("ti", LayoutKind.PHONETIC))
        assertWesternFirst(sess.c.digitRow(), "ti")
        val eth = session(ethiopicFirst)
        eth.mode(TypingMode("ti", LayoutKind.PHONETIC))
        assertEquals(ethiopic, eth.c.digitRow().map { it.text })
    }

    @Test fun symbolsPagesFollowTheStyleAndSwitchingPagesKeepsWorking() {
        for ((st, primary) in listOf(KeyboardSettings.DEFAULT to western, ethiopicFirst to ethiopic)) {
            val sess = session(st)
            sess.tap(sess.key(KeyKind.TO_SYMBOLS))
            assertEquals(Page.SYMBOLS, sess.c.layout.page)
            assertEquals(primary, sess.c.digitRow().map { it.text })
            sess.tap(sess.key(KeyKind.TO_SYMBOLS_2))
            assertEquals(Page.SYMBOLS_2, sess.c.layout.page)
            assertEquals(primary, sess.c.digitRow().map { it.text })
            sess.tap(sess.key(KeyKind.TO_SYMBOLS))
            sess.tap(sess.key(KeyKind.TO_LETTERS))
            assertEquals(Page.LETTERS, sess.c.layout.page)
            assertEquals(primary, sess.c.digitRow().map { it.text })
            // Typing on the symbols page commits the key's primary.
            sess.tap(sess.key(KeyKind.TO_SYMBOLS))
            sess.tap(sess.c.digitRow()[2])
            assertEquals(primary[2], sess.ed.text.toString())
        }
        val sess = session()
        sess.tap(sess.key(KeyKind.TO_SYMBOLS))
        assertWesternFirst(sess.c.digitRow(), "symbols")
    }

    @Test fun fidelFollowsTheSameSettingOnTheSymbolsPage() {
        for ((st, primary) in listOf(KeyboardSettings.DEFAULT to western, ethiopicFirst to ethiopic)) {
            val sess = session(st)
            sess.mode(TypingMode("am", LayoutKind.FIDEL))
            // The Fidel grid has no digit row on its letters page; its ?123 page has the digit row.
            assertTrue(sess.c.layout.rows.flatten().none { it.text in ethiopic || it.text in western })
            sess.tap(sess.key(KeyKind.TO_SYMBOLS))
            assertEquals(primary, sess.c.digitRow().map { it.text })
            if (primary == western) assertWesternFirst(sess.c.digitRow(), "fidel")
            sess.tap(sess.c.digitRow()[0])
            assertEquals(primary[0], sess.ed.text.toString())
        }
    }

    @Test fun numberRowOffOffersBothOnTheTopLetterRow() {
        for ((st, first) in listOf(KeyboardSettings.DEFAULT to listOf("1", "፩", "፲"), ethiopicFirst to listOf("፩", "1", "፲"))) {
            val sess = session(st.copy(numberRow = false))
            val q = sess.c.layout.rows.first().first()
            assertEquals("q", q.text)
            assertEquals(first, q.popup.map { it.text })
            val p = sess.c.layout.rows.first()[9]
            assertEquals(if (first[0] == "1") "0" else "፲", p.popup.first().text)
            assertTrue(p.popup.map { it.text }.containsAll(listOf("0", "፲", "፻", "፼")))
        }
    }

    @Test fun englishWritingModeOtherLanguagesAndNumberFieldsStayPlainWestern() {
        for (st in listOf(KeyboardSettings.DEFAULT, ethiopicFirst)) {
            val sess = session(st, languages = EnabledLanguages { listOf("am", "om", "en", "ar") })
            sess.mode(TypingMode.abc("am")) // the English writing mode of Amharic
            assertEquals(western, sess.c.digitRow().map { it.text })
            assertTrue(sess.c.digitRow().all { it.popup.isEmpty() && it.hint.isEmpty() })
            sess.mode(TypingMode("om", LayoutKind.QUBEE))
            assertEquals(western, sess.c.digitRow().map { it.text })
            assertTrue(sess.c.digitRow().all { it.popup.isEmpty() })
            sess.mode(TypingMode("en", LayoutKind.ENGLISH))
            assertEquals(western, sess.c.digitRow().map { it.text })
            assertTrue(sess.c.digitRow().all { it.popup.isEmpty() })
            sess.mode(TypingMode("ar", LayoutKind.ARABIC))
            // Arabic keeps Western digits with the Arabic-Indic digit on long-press (ADR 0034).
            assertEquals(western, sess.c.digitRow().map { it.text })
            assertEquals("١", sess.c.digitRow()[0].popup.single().text)

            for (type in listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)) {
                val num = session(st, type = type)
                assertEquals(Page.NUMBER, num.c.layout.page)
                val keys = num.c.layout.rows.flatten()
                assertTrue("$type", keys.map { it.text }.containsAll(western))
                assertTrue("$type", keys.none { it.text in ethiopic || it.popup.any { p -> p.text in ethiopic } })
            }
        }
    }

    @Test fun longPressGivesTheOtherStyle() {
        val sess = session(ethiopicFirst)
        sess.pick(sess.c.digitRow()[0].popup.first())
        sess.pick(sess.c.digitRow()[9].popup[1])
        assertEquals("1፻", sess.ed.text.toString())
    }

    @Test fun aNumeralEndsThePhoneticWordAndIsNeitherComposedNorSuggested() {
        val sess = session(ethiopicFirst)
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
        // The same from the long-press popup with the default style.
        val def = session()
        def.type("selam")
        def.pick(def.c.digitRow()[0].popup.first())
        assertTrue(def.ed.text.toString(), def.ed.text.toString().endsWith("፩"))
        assertFalse(def.ed.text.toString().any { it in 'a'..'z' })
        assertFalse(def.c.composing)
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
