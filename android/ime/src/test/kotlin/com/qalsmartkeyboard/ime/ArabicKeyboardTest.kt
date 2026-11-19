package com.qalsmartkeyboard.ime

import android.text.InputType
import com.qalsmartkeyboard.ime.ai.AiLanguages
import com.qalsmartkeyboard.ime.ai.AiLanguagePrefs
import com.qalsmartkeyboard.ime.ai.AiTextTarget
import com.qalsmartkeyboard.ime.ai.EditorAiAccess
import com.qalsmartkeyboard.ime.ai.TranslationDirection
import com.qalsmartkeyboard.ime.userdict.WordLearningSink
import com.qalsmartkeyboard.ime.voice.VoiceLanguages
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arabic as a typing language (ADR 0034): the layout (rows, long-press, punctuation, digits, marks, no shift), the
 * catalogue, right-to-left editing (logical order, Backspace on harakat) through the real core, and the language lists.
 * Synthetic text only (N2).
 */
class ArabicKeyboardTest {
    private val ar = TypingMode("ar", LayoutKind.ARABIC)
    private val text = InputProfile.from(InputType.TYPE_CLASS_TEXT, 0, false, 0)

    private fun ctx(mode: TypingMode = ar) = LayoutContext(mode, indicator = LanguageCatalog.indicator(mode, null))
    private fun page(p: Page, profile: InputProfile = text, mode: TypingMode = ar) = Layouts.forPage(p, profile, ctx(mode))
    private fun letters() = page(Page.LETTERS)

    private val fatha = "\u064E"
    private val shadda = "\u0651"

    // ------------------------------------------------------------------ layout

    @Test fun lettersAreTheStandardArabicRows() {
        val rows = letters().rows
        assertEquals(5, rows.size)
        assertEquals("1234567890".map { it.toString() }, rows[0].map { it.text })
        assertEquals(listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"), rows[1].map { it.text })
        assertEquals(listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"), rows[2].map { it.text })
        assertEquals(listOf("ذ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ", "د"), rows[3].dropLast(1).map { it.text })
        assertEquals(KeyKind.BACKSPACE, rows[3].last().kind)
        // Every letter key shows what it types; 33 distinct keys.
        val letterKeys = rows.subList(1, 4).flatten().filter { it.kind == KeyKind.CHAR }
        assertTrue(letterKeys.all { it.label == it.text })
        assertEquals(33, letterKeys.map { it.text }.toSet().size)
    }

    @Test fun noShiftNoAbcKeyAndNoSpacers() {
        val all = letters().rows.flatten()
        assertTrue(all.none { it.kind == KeyKind.SHIFT || it.kind == KeyKind.ABC_TOGGLE || it.isSpacer })
    }

    @Test fun lamAlefIsOneKeyThatTypesTwoLetters() {
        val k = letters().rows[3][4]
        assertEquals("لا", k.text)
        assertEquals(k.text, k.label) // drawn as one string, so the font shapes the ligature
        assertFalse(k.isLetter) // never upper-cased
        assertEquals("لا", KeyPreview.textFor(k, ShiftState.OFF, Page.LETTERS, null))
    }

    @Test fun longPressOffersHamzaFormsAndFinalForms() {
        val keys = letters().rows.flatten().filter { it.kind == KeyKind.CHAR }.associateBy { it.text }
        fun alts(c: String) = keys.getValue(c).popup.map { it.text }
        assertEquals(listOf("أ", "إ", "آ", "ٱ", "ء"), alts("ا"))
        assertEquals(listOf("ئ", "ى"), alts("ي"))
        assertEquals(listOf("ؤ"), alts("و"))
        assertEquals(listOf("ة"), alts("ه"))
        assertTrue(keys.getValue("ب").popup.isEmpty())
        assertTrue(keys.getValue("ا").actsOnRelease)
    }

    @Test fun bottomRowIsTheOtherLanguagesRowWithTheMarksKeyAfterTheGlobe() {
        val bottom = letters().rows.last()
        // ADR 0035: ?123, comma, globe, marks, emoji, space, period, Enter.
        assertEquals(
            listOf(KeyKind.TO_SYMBOLS, KeyKind.CHAR, KeyKind.GLOBE, KeyKind.CHAR, KeyKind.EMOJI, KeyKind.SPACE, KeyKind.CHAR, KeyKind.ENTER),
            bottom.map { it.kind },
        )
        val oromo = Layouts.forPage(Page.LETTERS, text, LayoutContext(TypingMode("om", LayoutKind.QUBEE))).rows.last()
        assertEquals(oromo.map { it.kind }, bottom.filterIndexed { i, _ -> i != 3 }.map { it.kind })
        assertEquals("Arabic marks", bottom[3].description)
        assertTrue("the space bar keeps three key widths", bottom[5].weight >= 3f)
        assertEquals("ع · العربية", bottom[5].label)
    }

    @Test fun theMarksKeyTypesAFathaAndOffersEveryHarakah() {
        val marks = letters().rows.last()[3]
        assertEquals(fatha, marks.text)
        assertEquals("\u25CC$fatha", marks.label) // on a dotted circle, never a floating mark
        assertEquals("Arabic marks", marks.description)
        assertEquals(
            listOf("\u064E", "\u064F", "\u0650", "\u0652", "\u0651", "\u064B", "\u064C", "\u064D", "\u0670", "\u0640"),
            marks.popup.map { it.text },
        )
        assertEquals("Arabic mark Shadda", marks.popup[4].description)
        assertEquals("\u0640", marks.popup.last().label) // the tatweel is a letter-like mark: no circle
        assertTrue(marks.popup.dropLast(1).all { it.label == "\u25CC" + it.text })
        // The key preview shows the mark on its circle too.
        assertEquals("\u25CC$fatha", KeyPreview.textFor(marks, ShiftState.OFF, Page.LETTERS, null))
    }

    @Test fun commaAndPeriodTypeArabicPunctuation() {
        val bottom = letters().rows.last()
        val comma = bottom[1]
        assertEquals("،", comma.text)
        assertEquals(listOf("؛", ","), comma.popup.map { it.text })
        assertEquals("Arabic comma", comma.description)
        val period = bottom[6]
        assertEquals(".", period.text)
        assertEquals(listOf("؟", "!", "؛", ":"), period.popup.map { it.text })
        assertEquals("؟", period.hint)
        assertEquals("Arabic punctuation ؟", period.popup.first().description)
    }

    @Test fun digitsKeepWesternDigitsWithArabicIndicOnLongPress() {
        val row = letters().rows[0]
        assertEquals("١٢٣٤٥٦٧٨٩٠".map { it.toString() }, row.map { it.popup.single().text })
        assertEquals(row.map { it.popup.single().text }, row.map { it.hint })
        assertEquals("Arabic-Indic numeral ٣", row[2].popup.single().description)
        // The symbols pages share the digit row.
        assertEquals(row.map { it.popup }, page(Page.SYMBOLS).rows[0].map { it.popup })
    }

    @Test fun symbolsPagesUseArabicPunctuationAndOfferTheMarks() {
        val p = page(Page.SYMBOLS)
        assertEquals(6, p.rows.size)
        assertEquals(ArabicKeys.MARKS.map { it.text }, p.rows[1].map { it.text })
        val keys = p.rows.flatten()
        for ((ascii, arabic) in mapOf("?" to "؟", ";" to "؛", "," to "،", "%" to "٪")) {
            val k = keys.first { it.text == arabic }
            assertEquals(listOf(ascii), k.popup.map { it.text })
            assertTrue(ascii, keys.none { it.kind == KeyKind.CHAR && it.text == ascii })
        }
        assertEquals("ع", keys.first { it.kind == KeyKind.TO_LETTERS }.label)
        assertTrue(page(Page.SYMBOLS_2).rows.flatten().any { it.text == "؛" })
    }

    @Test fun otherLanguagesKeepTheirLayouts() {
        for (m in listOf(TypingMode("en", LayoutKind.ENGLISH), TypingMode("om", LayoutKind.QUBEE), TypingMode.abc("am"))) {
            val keys = (Page.entries - Page.NUMBER).flatMap { page(it, mode = m).rows.flatten() }
            assertTrue(keys.none { it.text in setOf("،", "؟", "؛", "٪", fatha) })
            assertTrue(keys.any { it.text == "?" })
            assertTrue(page(Page.LETTERS, mode = m).rows[0].all { it.popup.isEmpty() })
        }
    }

    @Test fun latinFieldsGetQwertyInArabic() {
        val email = InputProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0, false, 0)
        val l = Layouts.forPage(Page.LETTERS, email, LayoutContext(TypingMode.abc("ar")))
        assertTrue(l.rows.flatten().any { it.text == "q" })
        assertTrue(l.rows.flatten().none { it.text == "ب" })
    }

    // ------------------------------------------------------------------ catalogue

    @Test fun catalogue() {
        assertEquals(listOf(LayoutKind.ARABIC), LanguageCatalog.nativeKinds("ar"))
        assertEquals("ع", LanguageCatalog.shortLabel("ar"))
        assertEquals("العربية", LanguageCatalog.displayName("ar"))
        assertEquals("Arabic", LanguageCatalog.spokenName("ar"))
        assertEquals("ع · العربية", LanguageCatalog.indicator(ar, null))
        assertTrue(LanguageCatalog.isRtl("ar"))
        assertTrue(listOf("am", "ti", "om", "en").none { LanguageCatalog.isRtl(it) })
        assertFalse(LanguageCatalog.hasAbcLayout("ar"))
        assertTrue(listOf("am", "ti", "om", "en").all { LanguageCatalog.hasAbcLayout(it) })
        assertTrue(LanguageCatalog.writingModes("ar").isEmpty())
        assertFalse(LanguageCatalog.isEthiopic("ar"))
        // ADR 0039: Arabic learns the user's own words (its only suggestions; no licensed list, N10).
        assertTrue(listOf("am", "ti", "om", "en", "ar").all { LanguageCatalog.learnsWords(it) })
        assertFalse(LayoutKind.ARABIC.latin || LayoutKind.ARABIC.phonetic)
        assertEquals("Arabic letters", LanguageCatalog.spokenKind(LayoutKind.ARABIC))
    }

    @Test fun passthroughComposerDoesNotOfferArabicBeforeTheCoreIsReady() {
        val c = KeyboardController(object : TextSink {
            override fun apply(update: ComposerUpdate) {}
            override fun sendEnterKey() {}
            override fun performEditorAction(actionId: Int) {}
            override fun wantsAutoCapital(capsFlags: Int) = false
        }, { listOf("ar", "en") }) {}
        c.startField(text)
        assertEquals(TypingMode.abc("ar"), c.effectiveMode) // Latin until the core loads, like the other languages
        assertTrue(c.availableLanguages().isEmpty())
    }

    // ------------------------------------------------------------------ typing through the real core

    private class Learned : WordLearningSink {
        val words = ArrayList<String>()
        override fun learn(language: String, word: String, previous: String?) {
            words += "$language:$word"
        }
    }

    private fun session(type: Int = InputType.TYPE_CLASS_TEXT, dictionary: WordLearningSink = WordLearningSink.None) =
        CoreTestEnv.Session(type, languages = { listOf("ar", "en") }, dictionary = dictionary)

    private fun CoreTestEnv.Session.charKey(t: String) = c.layout.rows.flatten().first { it.kind == KeyKind.CHAR && it.text == t }

    @Test fun typesInLogicalOrderWithNoCompositionOrCandidates() {
        val s = session(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        assertEquals(ar, s.c.effectiveMode)
        assertEquals("ع · العربية", s.c.indicator)
        // ADR 0039: the strip applies (the user's own words); with none learned it stays empty and nothing composes.
        assertTrue(s.c.showCandidateStrip && s.c.candidatesRtl)
        assertTrue(s.c.writingModeOptions.isEmpty())
        s.type("مرحبا ")
        s.tap(s.charKey("،"))
        s.type(" كيف")
        s.pick(s.charKey(".").popup.first { it.text == "؟" })
        assertEquals("مرحبا ، كيف؟", s.ed.text.toString())
        assertEquals("", s.ed.composing)
        assertTrue(s.c.candidates.isEmpty())
        assertEquals(ShiftState.OFF, s.c.shift) // no auto-capitalisation: Arabic has no case
    }

    @Test fun popupLettersDigitsAndLamAlef() {
        val s = session()
        s.pick(s.charKey("ا").popup.first { it.text == "أ" })
        s.tap(s.charKey("لا"))
        s.pick(s.charKey("3").popup.single())
        assertEquals("ألا٣", s.ed.text.toString())
    }

    @Test fun backspaceRemovesAHarakahAloneThenTheLetter() {
        val s = session()
        val marks = s.c.layout.rows.last()[3]
        s.type("ب")
        s.tap(marks) // fatha
        s.pick(marks.popup.first { it.text == shadda })
        assertEquals("ب$fatha$shadda", s.ed.text.toString())
        val del = s.key(KeyKind.BACKSPACE)
        s.tap(del)
        assertEquals("ب$fatha", s.ed.text.toString())
        s.tap(del)
        assertEquals("ب", s.ed.text.toString())
        s.tap(del)
        assertEquals("", s.ed.text.toString())
    }

    @Test fun backspaceAfterLamAlefLeavesTheLam() {
        val s = session()
        s.type("ك")
        s.tap(s.charKey("لا"))
        s.tap(s.key(KeyKind.BACKSPACE))
        assertEquals("كل", s.ed.text.toString())
    }

    @Test fun arabicWordsAreLearned() {
        // ADR 0039: Arabic suggestions are the user's own words, so Arabic words are learned like any language's.
        val learned = Learned()
        val s = session(dictionary = learned)
        repeat(2) { s.type("كتاب ") }
        assertEquals(listOf("ar:كتاب", "ar:كتاب"), learned.words)
    }

    @Test fun noAbcLayoutInArabicAndTheGlobeSwitchesLanguage() {
        val s = session()
        s.c.onLongPress(s.key(KeyKind.GLOBE))
        assertEquals(ar, s.c.effectiveMode)
        s.c.toggleAbc()
        assertEquals(ar, s.c.effectiveMode)
        s.c.nextLanguage()
        assertEquals(TypingMode("en", LayoutKind.ENGLISH), s.c.effectiveMode)
        s.c.nextLanguage()
        assertEquals(ar, s.c.effectiveMode)
        assertEquals(listOf("العربية", "English"), s.c.languagePicker().map { it.label })
    }

    @Test fun latinFieldsStayLatinInArabic() {
        val s = session(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(TypingMode.abc("ar"), s.c.effectiveMode)
        s.type("pw")
        assertEquals("pw", s.ed.text.toString())
    }

    // ------------------------------------------------------------------ editing helpers

    @Test fun backspaceUnitsKeepClustersExceptArabicMarks() {
        fun len(t: String, n: Int = 1) = InputConnectionAdapter.utf16LengthOfLastGraphemes(t, n)
        assertEquals(1, len("كتب"))
        assertEquals(1, len("ب$fatha")) // the mark alone
        assertEquals(1, len("ب$fatha$shadda"))
        assertEquals(2, len("ب$fatha$shadda", 2))
        assertEquals(3, len("ب$fatha$shadda", 3))
        assertEquals(1, len("لا")) // lam-alef is two letters
        assertEquals(1, len("ሰላም")) // Fidel: one syllable, as before
        assertEquals(2, len("é")) // a Latin accent still goes with its letter
        assertEquals("👍🏽".length, len("a👍🏽"))
    }

    @Test fun collapsingASelectionMovesToItsLogicalEndInRtlText() {
        assertEquals(21, EditorAiAccess.collapseToEndKey("مرحبا")) // DPAD_LEFT: the end of RTL text is on the left
        assertEquals(21, EditorAiAccess.collapseToEndKey("123 مرحبا"))
        assertEquals(22, EditorAiAccess.collapseToEndKey("hello مرحبا"))
        assertEquals(22, EditorAiAccess.collapseToEndKey("ሰላም"))
        assertEquals(22, EditorAiAccess.collapseToEndKey("123"))
    }

    @Test fun theArabicQuestionMarkEndsASentenceForTheAiToolbar() {
        assertEquals("أنا بخير", AiTextTarget.sentenceBeforeCursor("كيف حالك؟ أنا بخير")!!.text)
        assertEquals("كيف حالك؟", AiTextTarget.sentenceBeforeCursor("مرحبا. كيف حالك؟")!!.text)
    }

    // ------------------------------------------------------------------ language lists

    @Test fun arabicIsAnAiLanguageTranslatedToEnglishByDefault() {
        assertEquals(listOf("am", "ti", "om", "en", "ar"), AiLanguages.ALL)
        assertEquals("en", AiLanguages.defaultTarget("ar"))
        val d = TranslationDirection(AiLanguagePrefs.InMemory()) { emptyList() }.also { it.start("ar", null) }
        assertEquals("ar", d.source)
        assertEquals("en", d.destination.language)
        assertEquals("Auto · Arabic", d.sourceLabel("Auto"))
        d.swap()
        assertEquals("ar", d.destination.language)
    }

    @Test fun voiceTypingUsesAnArabicRecognitionLanguage() {
        assertEquals(listOf("ar-SA"), VoiceLanguages.defaults("ar", Locale.US))
        assertEquals(listOf("ar-EG", "ar-SA"), VoiceLanguages.defaults("ar", Locale.forLanguageTag("ar-EG")))
        assertEquals("ar", VoiceLanguages.keyboardLanguageOf("ar_EG"))
        assertEquals("Arabic", VoiceLanguages.languageName("ar-SA"))
    }
}
