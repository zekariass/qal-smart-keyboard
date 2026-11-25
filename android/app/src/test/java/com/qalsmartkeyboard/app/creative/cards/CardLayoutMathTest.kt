package com.qalsmartkeyboard.app.creative.cards

import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card's layout maths (T8.7) on the plain JVM: export sizes, text boxes, text fitting, gradient and cover geometry. */
class CardLayoutMathTest {
    /** A fake measurer: every character is `0.6 * size` wide, lines `1.2 * size` tall, wrapping at the box width. */
    private val measurer = TextMeasurer { text, size, width ->
        val perLine = maxOf(1, (width / (size * 0.6f)).toInt())
        val lines = text.split('\n').sumOf { maxOf(1, ceil(it.length / perLine.toDouble()).toInt()) }
        ceil(lines * size * 1.2f).toInt()
    }

    @Test fun exportSizesAreFixed() {
        assertEquals(1080 to 1080, CardAspect.SQUARE.width to CardAspect.SQUARE.height)
        assertEquals(1080 to 1350, CardAspect.PORTRAIT.width to CardAspect.PORTRAIT.height)
        assertEquals(CardAspect.SQUARE, CardAspect.of("nonsense"))
        assertEquals(CardAspect.PORTRAIT, CardAspect.of("portrait"))
        assertEquals(ExportFormat.PNG, ExportFormat.of(null)) // the default is PNG
        assertEquals("image/jpeg", ExportFormat.JPEG.mime)
    }

    @Test fun aShortTextTakesTheLargestSize() {
        val r = CardLayoutMath.fit(measurer, "Hi", 900, 300, maxPx = 120f, minPx = 30f)
        assertEquals(FitResult(120f, true), r)
    }

    @Test fun aLongTextShrinksToTheLargestSizeThatFits() {
        val text = "word ".repeat(60)
        val r = CardLayoutMath.fit(measurer, text, 900, 300, maxPx = 120f, minPx = 20f)
        assertTrue(r.fits)
        assertTrue("shrunk: ${r.size}", r.size < 120f && r.size >= 20f)
        assertTrue(measurer.heightFor(text, r.size, 900) <= 300)
        // One pixel larger would overflow: it is the largest.
        assertTrue(measurer.heightFor(text, r.size + 1, 900) > 300)
    }

    @Test fun aTextThatCannotFitKeepsTheMinimumAndSaysSo() {
        val r = CardLayoutMath.fit(measurer, "x".repeat(5000), 300, 100, maxPx = 80f, minPx = 24f)
        assertEquals(FitResult(24f, false), r)
    }

    @Test fun aBlankTextFitsAtTheMaximum() {
        assertEquals(FitResult(50f, true), CardLayoutMath.fit(measurer, "  ", 10, 10, 50f, 10f))
    }

    @Test fun fitIsMonotoneInTheBoxHeight() {
        val text = "Selam ".repeat(20)
        var last = 0f
        for (h in listOf(80, 160, 240, 320, 480)) {
            val s = CardLayoutMath.fit(measurer, text, 600, h, 100f, 10f).size
            assertTrue("height $h gave $s after $last", s >= last)
            last = s
        }
    }

    @Test fun boxesStayInsideTheCardAndGrowInsetWithTheFrame() {
        for (aspect in CardAspect.entries) for (t in CardTemplates.all) for (frame in CardFrame.entries) {
            val (a, b, c) = CardLayoutMath.boxes(t.layout, aspect.width, aspect.height, frame)
            for (box in listOf(a, b, c)) {
                assertTrue("${t.id} $frame $box", box.left >= 0 && box.right <= aspect.width && box.top >= 0 && box.bottom <= aspect.height)
                assertTrue(box.width > 100 && box.height > 10)
            }
            // The three bands do not overlap: headline above the message above the sender line.
            assertTrue("${t.id}: ${a.bottom} <= ${b.top}", a.bottom <= b.top && b.bottom <= c.top)
        }
        val none = CardLayoutMath.boxes(CardTemplates.default.layout, 1080, 1080, CardFrame.NONE).first
        val fancy = CardLayoutMath.boxes(CardTemplates.default.layout, 1080, 1080, CardFrame.DOUBLE).first
        assertTrue(fancy.left > none.left && fancy.width < none.width)
    }

    @Test fun theSameLayoutServesBothAspects() {
        val sq = CardLayoutMath.boxes(CardTemplates.default.layout, 1080, 1080, CardFrame.NONE).second
        val po = CardLayoutMath.boxes(CardTemplates.default.layout, 1080, 1350, CardFrame.NONE).second
        assertEquals(sq.width, po.width)
        assertTrue(po.height > sq.height)
    }

    @Test fun gradientLinesRunThroughTheCentreAndReachTheCorners() {
        val h = CardLayoutMath.gradientLine(1000, 500, 0) // left to right
        assertEquals(0f, h[0], 0.01f); assertEquals(250f, h[1], 0.01f); assertEquals(1000f, h[2], 0.01f); assertEquals(250f, h[3], 0.01f)
        val v = CardLayoutMath.gradientLine(1000, 500, 90) // top to bottom
        assertEquals(500f, v[0], 0.01f); assertEquals(0f, v[1], 0.01f); assertEquals(500f, v[3], 0.01f)
        val d = CardLayoutMath.gradientLine(1000, 1000, 135) // corner to corner
        assertTrue(d[0] > d[2] && d[1] < d[3]) // runs towards the lower left
        assertEquals(500f, (d[0] + d[2]) / 2, 0.01f)
    }

    @Test fun aPhotoIsCentreCroppedToCoverTheCard() {
        val wide = CardLayoutMath.coverTransform(2000, 1000, 1080, 1080) // the height decides
        assertEquals(1.08f, wide[0], 0.001f)
        assertTrue(wide[1] < 0f && wide[2] == 0f)
        val tall = CardLayoutMath.coverTransform(1000, 2000, 1080, 1350)
        assertEquals(1.08f, tall[0], 0.001f) // the width decides
        assertTrue(tall[2] < 0f)
        // The scaled picture always covers the card.
        for ((bw, bh) in listOf(1600 to 900, 900 to 1600, 1200 to 1200, 100 to 300)) {
            val t = CardLayoutMath.coverTransform(bw, bh, 1080, 1350)
            assertTrue(bw * t[0] + 0.01f >= 1080 && bh * t[0] + 0.01f >= 1350)
            assertTrue(t[1] <= 0.01f && t[2] <= 0.01f)
        }
    }

    @Test fun photoSamplingKeepsTheLongestSideAtOrAboveTheTarget() {
        assertEquals(1, CardImages.sampleSize(1200, 800, 1600))
        assertEquals(1, CardImages.sampleSize(3000, 2000, 1600)) // halving would give 1500 < 1600
        assertEquals(2, CardImages.sampleSize(4000, 3000, 1600))
        assertEquals(4, CardImages.sampleSize(8000, 6000, 1600))
        assertFalse(CardImages.sampleSize(12000, 9000, 1600) < 4)
    }
}
