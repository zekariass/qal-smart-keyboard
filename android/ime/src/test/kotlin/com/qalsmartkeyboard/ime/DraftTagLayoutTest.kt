package com.qalsmartkeyboard.ime

import com.qalsmartkeyboard.ime.DraftTagLayout.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The space bar's DRAFT tag (N9) never overlaps its label, at portrait and landscape row heights
 * and at several densities (screenshot 18 of the UI polish showed them on top of each other).
 */
class DraftTagLayoutTest {
    /** Roboto-like metrics: ascent -0.93 em, descent 0.24 em. */
    private fun text(chars: Int, sizeDp: Float, d: Float, emPerChar: Float = 0.55f) =
        DraftTagLayout.Text(width = chars * emPerChar * sizeDp * d, ascent = -0.93f * sizeDp * d, descent = 0.24f * sizeDp * d)

    /** The space key of the letters page: about 4/10.5 of the width minus the gaps, and the row height minus the 7dp row gap. */
    private fun place(keyWdp: Float, rowDp: Float, d: Float, labelChars: Int = 13): Triple<DraftTagLayout.Placement, DraftTagLayout.Text, DraftTagLayout.Text> {
        val label = text(labelChars, 13f, d) // "አማ · Phonetic"
        val tag = text(5, 8f, d, emPerChar = 0.65f) // "DRAFT", bold
        val p = DraftTagLayout.place(keyWdp * d, (rowDp - 7f) * d, label, tag, pad = 2f * d, gap = 3f * d)
        return Triple(p, label, tag)
    }

    private fun overlap(a: FloatArray, b: FloatArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    private fun assertInside(box: FloatArray, w: Float, h: Float, what: String) {
        assertTrue("$what left ${box[0]}", box[0] >= -0.01f)
        assertTrue("$what top ${box[1]}", box[1] >= -0.01f)
        assertTrue("$what right ${box[2]} > $w", box[2] <= w + 0.01f)
        assertTrue("$what bottom ${box[3]} > $h", box[3] <= h + 0.01f)
    }

    @Test fun portraitRowsStackTheTagAboveTheLabel() {
        val d = 2.625f
        val (p, label, tag) = place(keyWdp = 150f, rowDp = 50f, d = d)
        assertEquals(Mode.STACKED, p.mode)
        val l = DraftTagLayout.labelBox(p, label)
        val t = DraftTagLayout.tagBox(p, tag)
        assertFalse("tag and label overlap", overlap(l, t))
        assertTrue("tag above label", t[3] <= l[1] + 0.01f)
        assertInside(l, 150f * d, 43f * d, "label")
        assertInside(t, 150f * d, 43f * d, "tag")
        assertEquals(1f, p.labelScale, 0.001f)
    }

    @Test fun landscapeRowsPutTheTagBesideTheLabel() {
        // Landscape: 50dp rows * 0.75 = 37.5dp, i.e. a 30.5dp key; the space bar is wide (2424px / 2.625 screen).
        val d = 2.625f
        val (p, label, tag) = place(keyWdp = 255f, rowDp = 37.5f, d = d)
        assertEquals(Mode.BESIDE, p.mode)
        val l = DraftTagLayout.labelBox(p, label)
        val t = DraftTagLayout.tagBox(p, tag)
        assertFalse("tag and label overlap", overlap(l, t))
        assertTrue("tag right of label", t[0] >= l[2])
        assertInside(l, 255f * d, 30.5f * d, "label")
        assertInside(t, 255f * d, 30.5f * d, "tag")
    }

    @Test fun neverOverlapsAtAnyRowHeightWidthOrDensity() {
        for (d in listOf(1f, 2f, 2.625f, 3.5f)) {
            for (row in 30..70) {
                for (w in listOf(60f, 90f, 120f, 160f, 260f)) {
                    val (p, label, tag) = place(keyWdp = w, rowDp = row.toFloat(), d = d)
                    val l = DraftTagLayout.labelBox(p, label)
                    if (p.showsTag) {
                        val t = DraftTagLayout.tagBox(p, tag)
                        assertFalse("overlap at d=$d row=$row w=$w ${p.mode}", overlap(l, t))
                        assertInside(t, w * d, (row - 7f) * d, "tag d=$d row=$row w=$w")
                    }
                    assertTrue("label wider than the key at d=$d row=$row w=$w", l[2] - l[0] <= w * d + 0.01f)
                }
            }
        }
    }

    @Test fun aKeyTooSmallForBothDropsTheTag() {
        // 13dp tall: no room to stack; 40dp wide: no room beside either.
        val (p, _, _) = place(keyWdp = 40f, rowDp = 20f, d = 2f, labelChars = 13)
        assertEquals(Mode.HIDDEN, p.mode)
        assertFalse(p.showsTag)
    }

    @Test fun theLabelShrinksOnlySoFarBesideTheTag() {
        val d = 2f
        val (p, _, _) = place(keyWdp = 110f, rowDp = 34f, d = d)
        if (p.mode == Mode.BESIDE) assertTrue(p.labelScale >= DraftTagLayout.MIN_LABEL_SCALE)
    }
}
