package com.qalsmartkeyboard.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
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

/**
 * Owner request: while composing the whole bar is the suggestion bar — a back chevron at the left end returns to the
 * tools view; the candidates are full words in a sideways-scrolling list (no truncation, nothing dropped).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CandidateStripSlotsTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (411 * d).toInt()

    private fun strip(): CandidateStripView = CandidateStripView(ctx).also {
        it.setModes(listOf(LayoutKind.PHONETIC, LayoutKind.FIDEL, LayoutKind.ABC), LayoutKind.PHONETIC)
        it.micShown = true
        it.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        it.layout(0, 0, widthPx, it.measuredHeight)
    }

    private fun CandidateStripView.draw() = draw(Canvas(Bitmap.createBitmap(widthPx, measuredHeight, Bitmap.Config.ARGB_8888)))

    private fun words(vararg s: String) = s.map { Suggestion(it, SuggestionKind.COMPLETION) }

    /** Candidate indices left to right, as placed. */
    private fun CandidateStripView.order(): List<Int> =
        slots().filter { it.target is Target.Candidate }.sortedBy { it.shape.left }.map { (it.target as Target.Candidate).index }

    private fun CandidateStripView.touch(action: Int, x: Float) =
        onTouchEvent(MotionEvent.obtain(0L, 0L, action, x, (measuredHeight / 2).toFloat(), 0))

    @Test fun composingShowsEveryCandidateWholeBehindABackChevron() {
        val s = strip()
        s.show(words("ሰላ", "ሰላም", "ሠላ", "ሰላምታ", "ሰለ"))
        s.draw()
        val targets = s.slots().map { it.target }
        assertEquals(Target.Back, targets.first()) // the chevron takes the tools button's place
        assertEquals(Target.Mic, targets.last())
        assertFalse(Target.Tools in targets || Target.Pill in targets)
        assertEquals(listOf(0, 1, 2, 3, 4), s.order()) // the full list, in core order
        assertEquals(Target.Candidate(0), s.slots().single { it.primary }.target)
        // A word is never cut to a share of the bar: each cell is as wide as its word plus padding.
        s.slots().filter { it.target is Target.Candidate }.forEach { assertTrue(it.shape.width() > 20f * d) }
    }

    @Test fun theBackChevronReturnsToTheToolsBarUntilTheNextWord() {
        val s = strip()
        s.show(words("se", "selam", "selamta"))
        s.activate(Target.Back)
        val t = s.slots().map { it.target }
        assertEquals(Target.Tools, t.first())
        assertTrue(Target.Pill in t)
        assertTrue(t.none { it is Target.Candidate || it == Target.Back })
        // Typing on (a new list) brings the suggestions back; an empty list leaves the tools bar for good.
        s.show(words("sa", "sab"))
        assertEquals(Target.Back, s.slots().first().target)
        s.show(emptyList())
        assertEquals(Target.Tools, s.slots().first().target)
    }

    @Test fun anAutocorrectionIsBoldMarkedAndTheTypedWordIsQuoted() {
        val s = strip()
        s.show(listOf(Suggestion("teh", SuggestionKind.TRANSLITERATION), Suggestion("the", SuggestionKind.CORRECTION), Suggestion("tea", SuggestionKind.COMPLETION)), autocorrect = 1)
        s.draw()
        assertEquals(listOf(0, 1, 2), s.order())
        val primary = s.slots().single { it.primary }
        assertEquals(Target.Candidate(1), primary.target)
        assertTrue(primary.autocorrect)
        assertEquals("“teh”", s.slots().single { it.target == Target.Candidate(0) }.label)
    }

    @Test fun aLongListScrollsSidewaysInsteadOfDroppingWords() {
        val s = strip()
        var picked = -1
        s.onSelect = { picked = it }
        s.show((0 until 10).map { Suggestion("a-much-longer-suggestion-$it", SuggestionKind.COMPLETION) })
        // Every candidate is a slot: the last ones start scrolled off the right edge (empty hit).
        assertEquals(10, s.order().size)
        assertTrue(s.slots().first { it.target == Target.Candidate(9) }.hit.isEmpty)
        assertTrue(s.slots().first { it.target == Target.Candidate(0) }.hit.width() > 0f)
        // A sideways drag across the middle scrolls them into reach (no candidate tap fires).
        val (midStart, midEnd) = s.middleRange()
        s.touch(MotionEvent.ACTION_DOWN, midEnd - 8f)
        s.touch(MotionEvent.ACTION_MOVE, midEnd - 60f) // past the slop: the sideways drag starts
        s.touch(MotionEvent.ACTION_MOVE, midStart - 2000f) // drag far past the end: clamps at the last word
        assertTrue("a full drag reaches the last word", s.slots().first { it.target == Target.Candidate(9) }.hit.width() > 0f)
        assertTrue("the first word scrolled off", s.slots().first { it.target == Target.Candidate(0) }.hit.isEmpty)
        s.touch(MotionEvent.ACTION_UP, midStart - 2000f)
        assertEquals(-1, picked)
        // A tap on a visible word reports its core index.
        s.touch(MotionEvent.ACTION_DOWN, midEnd - 8f)
        s.touch(MotionEvent.ACTION_UP, midEnd - 8f)
        assertEquals(9, picked)
    }
}
