package com.qalsmartkeyboard.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.qalsmartkeyboard.ime.CandidateStripView.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** ADR 0039: the Gboard-like three-slot strip (centre = what Space commits), its autocorrection mark, RTL and expand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CandidateStripSlotsTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val widthPx = (411 * ctx.resources.displayMetrics.density).toInt()

    private fun strip(): CandidateStripView = CandidateStripView(ctx).also {
        it.setModes(listOf(LayoutKind.FIDEL, LayoutKind.PHONETIC, LayoutKind.ABC), LayoutKind.PHONETIC)
        it.micShown = true
        it.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        it.layout(0, 0, widthPx, it.measuredHeight)
    }

    private fun CandidateStripView.draw() = draw(Canvas(Bitmap.createBitmap(widthPx, measuredHeight, Bitmap.Config.ARGB_8888)))

    private fun words(vararg s: String) = s.map { Suggestion(it, SuggestionKind.COMPLETION) }

    /** Candidate indices left to right, as drawn. */
    private fun CandidateStripView.order(): List<Int> =
        slots().filter { it.target is Target.Candidate }.sortedBy { it.hit.left }.map { (it.target as Target.Candidate).index }

    @Test fun slotOrderPutsWhatSpaceCommitsInTheCentre() {
        assertEquals(listOf(1, 0, 2), CandidateStripView.slotOrder(5, -1, rtl = false))
        assertEquals(listOf(0, 1, 2), CandidateStripView.slotOrder(5, autocorrect = 1, rtl = false))
        assertEquals(listOf(2, 1, 0), CandidateStripView.slotOrder(5, autocorrect = 1, rtl = true))
        assertEquals(listOf(null, 0, null), CandidateStripView.slotOrder(1, -1, rtl = false))
        assertEquals(listOf(1, 0, null), CandidateStripView.slotOrder(2, -1, rtl = false))
        assertEquals(listOf(null, 0, 1), CandidateStripView.slotOrder(2, -1, rtl = true))
    }

    @Test fun threeEqualSlotsKeepTheBarsToolsPillAndMic() {
        val s = strip()
        s.show(words("hel", "hello", "help"), isDraft = false)
        s.draw()
        assertEquals(listOf(1, 0, 2), s.order())
        val cells = s.slots().filter { it.target is Target.Candidate }
        val widths = cells.map { it.hit.width() }
        assertTrue(widths.toString(), widths.max() - widths.min() < 1f)
        assertTrue(cells.single { it.primary }.target == Target.Candidate(0))
        val targets = s.slots().map { it.target }
        assertTrue(Target.Tools in targets && Target.Pill in targets && Target.Mic in targets)
        assertFalse(Target.More in targets) // three candidates fit
    }

    @Test fun anAutocorrectionIsCentredMarkedAndTheTypedWordIsQuoted() {
        val s = strip()
        s.show(listOf(Suggestion("teh", SuggestionKind.TRANSLITERATION), Suggestion("the", SuggestionKind.CORRECTION), Suggestion("tea", SuggestionKind.COMPLETION)), false, autocorrect = 1)
        s.draw()
        assertEquals(listOf(0, 1, 2), s.order())
        val centre = s.slots().single { it.primary }
        assertEquals(Target.Candidate(1), centre.target)
        assertTrue(centre.autocorrect)
        assertEquals("“teh”", s.slots().single { it.target == Target.Candidate(0) }.label)
    }

    @Test fun arabicMirrorsTheSideSlots() {
        val s = strip()
        s.show(words("كت", "كتاب", "كتب"), isDraft = false, rtl = true)
        s.draw()
        assertEquals(listOf(2, 0, 1), s.order())
    }

    @Test fun moreThanThreeCandidatesExpandAndFoldBack() {
        val s = strip()
        var picked = -1
        s.onSelect = { picked = it }
        s.show(words("ሰላ", "ሰላም", "ሠላ", "ሰላምታ", "ሰለ"), isDraft = true)
        s.draw()
        assertTrue(Target.More in s.slots().map { it.target })
        assertEquals(3, s.order().size)
        s.activate(Target.More)
        s.draw()
        assertEquals(s.order(), s.order().sorted()) // the full list, in core order
        assertTrue(s.order().size > 3)
        s.activate(Target.Candidate(3))
        assertEquals(3, picked)
        s.activate(Target.More)
        assertEquals(3, s.order().size)
        // New candidates start folded.
        s.activate(Target.More)
        s.show(words("a", "b", "c", "d"), isDraft = false)
        assertEquals(3, s.order().size)
    }
}
