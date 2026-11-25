package com.qalsmartkeyboard.app.creative.annotate

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The irreversibility suite of the redaction (T8.7, SD §6.6 "Blur/redaction is applied locally before export"), on the
 * plain pixel arrays the export uses. A synthetic picture of high-contrast, text-like strokes and checks stands in for
 * a screenshot of text; for every mode (blur, pixelate, solid), several picture sizes and areas from tiny to larger
 * than the picture, also partly outside it, the redacted pixels must:
 * - hold no colour of the original content (no original pixel survives),
 * - have almost no contrast left (standard deviation and neighbour differences far below the original's),
 * - not depend on the text under them (two different "texts" of the same ink coverage give near-identical results),
 * - cover the area plus its safety margin, and leave every other pixel exactly as it was.
 */
class RedactorTest {
    private val ink = 0xFF101010.toInt()
    private val paper = 0xFFF0F0F0.toInt()
    private val red = 0xFFE01818.toInt()
    private val cyan = 0xFF18E0E0.toInt()
    private val palette = setOf(ink, paper, red, cyan)

    /**
     * "Text": lines 12 px high of glyph-like vertical strokes (1 to 3 px) and gaps, each line its own random "letters",
     * random ascender and descender rows, and every fourth line a red/cyan 2 px checker (coloured text). Every line has
     * about half its pixels dark, so only the letters differ between lines, as on a page of text.
     */
    private fun textLike(w: Int, h: Int, variant: Int = 0): IntArray {
        val rnd = Random(1234L + variant)
        val px = IntArray(w * h)
        val lineH = 12
        for (y in 0 until h) {
            val line = y / lineH
            val inLine = y % lineH
            val rowRnd = Random(line * 7919L + variant)
            // The strokes of this text line: a random sequence of widths, the same for every row of the line.
            var x = 0
            val strokes = BooleanArray(w)
            while (x < w) {
                val sw = 1 + rowRnd.nextInt(3)
                val gap = 1 + rowRnd.nextInt(3)
                for (i in x until minOf(w, x + sw)) strokes[i] = true
                x += sw + gap
            }
            for (xx in 0 until w) {
                px[y * w + xx] = when {
                    line % 4 == 3 -> if (((xx / 2) + (y / 2)) % 2 == 0) red else cyan // a checker "line"
                    inLine == 0 || inLine == lineH - 1 -> if (rnd.nextBoolean()) ink else paper // ascender/descender noise
                    strokes[xx] -> ink
                    else -> paper
                }
            }
        }
        return px
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float, mode: RedactMode) =
        Annotation.Redaction(1, mode, RedactArea.Rect(Box(l, t, r, b)), color = 0xFF000000.toInt())

    private fun channelStd(px: IntArray, w: Int, e: PixelRect, shift: Int): Double {
        var sum = 0.0
        var sq = 0.0
        var n = 0
        for (y in e.top until e.bottom) for (x in e.left until e.right) {
            val v = (px[y * w + x] shr shift and 0xFF).toDouble()
            sum += v; sq += v * v; n++
        }
        val mean = sum / n
        return sqrt(max(0.0, sq / n - mean * mean))
    }

    private fun std(px: IntArray, w: Int, e: PixelRect) = (0..2).maxOf { channelStd(px, w, e, it * 8) }

    private fun maxNeighbourDiff(px: IntArray, w: Int, e: PixelRect): Int {
        var m = 0
        for (y in e.top until e.bottom) for (x in e.left until e.right - 1) {
            val a = px[y * w + x]
            val b = px[y * w + x + 1]
            for (s in intArrayOf(0, 8, 16)) m = max(m, abs((a shr s and 0xFF) - (b shr s and 0xFF)))
        }
        return m
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray, w: Int, e: PixelRect): Double {
        var sum = 0.0
        var n = 0
        for (y in e.top until e.bottom) for (x in e.left until e.right) {
            val p = a[y * w + x]
            val q = b[y * w + x]
            for (s in intArrayOf(0, 8, 16)) sum += abs((p shr s and 0xFF) - (q shr s and 0xFF))
            n += 3
        }
        return sum / n
    }

    private data class Case(val w: Int, val h: Int, val area: Box, val label: String)

    /** Picture sizes from a thumbnail to a phone screenshot; areas tiny, one text line, a block, larger than the picture, partly outside. */
    private val cases: List<Case> = buildList {
        for ((w, h) in listOf(64 to 48, 360 to 240, 1080 to 600)) {
            add(Case(w, h, Box(w * 0.4f, h * 0.4f, w * 0.4f + 2f, h * 0.4f + 2f), "tiny 2x2 on ${w}x$h"))
            add(Case(w, h, Box(w * 0.1f, h * 0.3f, w * 0.7f, h * 0.3f + 14f), "one text line on ${w}x$h"))
            add(Case(w, h, Box(w * 0.2f, h * 0.2f, w * 0.8f, h * 0.7f), "a block on ${w}x$h"))
            add(Case(w, h, Box(-50f, -50f, w + 50f, h + 50f), "larger than the picture ${w}x$h"))
            add(Case(w, h, Box(-30f, h * 0.5f, w * 0.3f, h + 25f), "partly outside ${w}x$h"))
        }
    }

    @Test fun blurAndPixelateLeaveNoOriginalColourAndNoContrast() {
        for (mode in listOf(RedactMode.BLUR, RedactMode.PIXELATE)) for (c in cases) {
            val orig = textLike(c.w, c.h)
            val px = orig.copyOf()
            val r = rect(c.area.left, c.area.top, c.area.right, c.area.bottom, mode)
            Redactor.redact(px, c.w, c.h, r, Random(42))
            val e = Redactor.extent(r, c.w, c.h)
            assertFalse("$mode ${c.label}: empty extent", e.isEmpty)
            val origStd = std(orig, c.w, e)
            val outStd = std(px, c.w, e)
            for (y in e.top until e.bottom) for (x in e.left until e.right) {
                val v = px[y * c.w + x]
                assertFalse("$mode ${c.label}: original colour at $x,$y", v in palette)
                assertEquals("$mode ${c.label}: opaque", 0xFF, v ushr 24)
            }
            assertTrue("$mode ${c.label}: std $outStd vs original $origStd", outStd <= 0.12 * origStd)
            if (mode == RedactMode.BLUR) {
                // Neighbouring pixels differ by little more than the added noise: no edge of a glyph is left.
                val d = maxNeighbourDiff(px, c.w, e)
                assertTrue("${c.label}: neighbour difference $d", d <= 2 * Redactor.NOISE + 4)
            }
        }
    }

    @Test fun twoDifferentTextsBecomeIndistinguishable() {
        val bad = ArrayList<String>()
        val ratios = ArrayList<String>()
        // Same ink coverage, different letters: what is left must not tell them apart (the redaction does not depend on the content).
        for (mode in listOf(RedactMode.BLUR, RedactMode.PIXELATE)) for (c in cases) {
            val a = textLike(c.w, c.h, variant = 0)
            val b = textLike(c.w, c.h, variant = 1)
            val inputDiff = meanAbsDiff(a, b, c.w, PixelRect(0, 0, c.w, c.h))
            val r = rect(c.area.left, c.area.top, c.area.right, c.area.bottom, mode)
            Redactor.redact(a, c.w, c.h, r, Random(9))
            Redactor.redact(b, c.w, c.h, r, Random(9))
            val e = Redactor.extent(r, c.w, c.h)
            val outputDiff = meanAbsDiff(a, b, c.w, e)
            ratios += "$mode ${c.label}: %.3f".format(outputDiff / inputDiff)
            if (outputDiff > 0.12 * inputDiff) bad += "$mode ${c.label}: inputs differ by $inputDiff, outputs by $outputDiff"
        }
        assertTrue(bad.joinToString(NL) + NL + "all ratios:" + NL + ratios.joinToString(NL), bad.isEmpty())
    }

    @Test fun solidFillsTheWholeAreaWithOneOpaqueColour() {
        for (c in cases) {
            val px = textLike(c.w, c.h)
            val r = rect(c.area.left, c.area.top, c.area.right, c.area.bottom, RedactMode.SOLID).copy(color = 0x80336699.toInt())
            Redactor.redact(px, c.w, c.h, r, Random(1))
            val e = Redactor.extent(r, c.w, c.h)
            for (y in e.top until e.bottom) for (x in e.left until e.right) {
                assertEquals("${c.label} at $x,$y", 0xFF336699.toInt(), px[y * c.w + x]) // forced opaque: never see-through
            }
        }
    }

    @Test fun onlyTheAreaAndItsMarginChange() {
        for (mode in RedactMode.entries) for (c in cases) {
            val orig = textLike(c.w, c.h)
            val px = orig.copyOf()
            val r = rect(c.area.left, c.area.top, c.area.right, c.area.bottom, mode)
            Redactor.redact(px, c.w, c.h, r, Random(3))
            val e = Redactor.extent(r, c.w, c.h)
            for (y in 0 until c.h) for (x in 0 until c.w) {
                if (!e.contains(x, y)) assertEquals("$mode ${c.label}: outside changed at $x,$y", orig[y * c.w + x], px[y * c.w + x])
            }
        }
    }

    @Test fun theAreaGrowsByASafetyMarginAndIsClampedToThePicture() {
        val r = rect(100f, 100f, 300f, 130f, RedactMode.BLUR) // a 200 x 30 text line
        val p = Redactor.params(r, 1080)
        assertTrue(p.margin >= Redactor.MIN_MARGIN)
        assertEquals(PixelRect(100 - p.margin, 100 - p.margin, 300 + p.margin, 130 + p.margin), Redactor.extent(r, 1080, 600))
        // Partly outside: clamped, not shifted.
        val out = rect(-40f, 590f, 20f, 640f, RedactMode.SOLID)
        assertEquals(PixelRect(0, 590 - Redactor.params(out, 1080).margin, 20 + Redactor.params(out, 1080).margin, 600), Redactor.extent(out, 1080, 600))
        // Entirely outside: nothing to do, nothing changes.
        val gone = rect(2000f, 2000f, 2100f, 2100f, RedactMode.BLUR)
        assertTrue(Redactor.extent(gone, 1080, 600).isEmpty)
        val px = textLike(1080, 600)
        val before = px.copyOf()
        Redactor.redact(px, 1080, 600, gone, Random(1))
        assertTrue(px.contentEquals(before))
    }

    @Test fun parametersScaleWithTheAreaAndThePictureResolution() {
        // Pixelate cells: at least a third of the (grown) area's smaller side, never below the minimum.
        for (side in listOf(2f, 14f, 40f, 120f, 600f, 3000f)) {
            val p = Redactor.params(side, 1080)
            val grown = side + 2 * p.margin
            assertTrue("block ${p.block} for $side", p.block >= Redactor.MIN_BLOCK)
            assertTrue("block ${p.block} >= height/6 for $side", p.block >= grown / 6 || p.block == Redactor.MAX_BLOCK)
            assertTrue("block ${p.block} >= height/3 for $side", p.block >= kotlin.math.ceil(grown / 3).toInt() || p.block == Redactor.MAX_BLOCK)
            assertTrue("blur ${p.blurRadius} >= side/2 for $side", p.blurRadius >= grown / 2 || p.blurRadius == Redactor.MAX_BLUR_RADIUS)
            assertTrue(p.blurRadius >= Redactor.MIN_BLUR_RADIUS)
            assertEquals(3, p.passes)
            assertTrue(p.margin >= kotlin.math.ceil(side * 0.1f).toInt().coerceAtMost(Redactor.MAX_MARGIN))
        }
        // A larger picture of the same text has larger minimums (its glyphs are more pixels).
        val small = Redactor.params(4f, 800)
        val large = Redactor.params(4f, 4096)
        assertTrue(large.block > small.block)
        assertTrue(large.blurRadius > small.blurRadius)
        assertTrue(large.margin >= small.margin)
    }

    @Test fun aBrushAreaCoversItsPathAndTheMarginOnly() {
        val w = 300
        val h = 200
        for (mode in RedactMode.entries) {
            val orig = textLike(w, h)
            val px = orig.copyOf()
            val r = Annotation.Redaction(1, mode, RedactArea.Brush(listOf(Pt(50f, 100f), Pt(150f, 100f), Pt(250f, 60f)), radius = 10f))
            Redactor.redact(px, w, h, r, Random(5))
            val margin = Redactor.params(r, w).margin
            for (y in 0 until h) for (x in 0 until w) {
                val d = sqrt(minOf(
                    Geometry.dist2ToSegment(x + 0.5f, y + 0.5f, Pt(50f, 100f), Pt(150f, 100f)),
                    Geometry.dist2ToSegment(x + 0.5f, y + 0.5f, Pt(150f, 100f), Pt(250f, 60f)),
                ).toDouble())
                val v = px[y * w + x]
                if (d <= 10.0 + margin - 0.01) {
                    assertFalse("$mode: original colour on the path at $x,$y", v in palette && mode != RedactMode.SOLID)
                    if (mode == RedactMode.SOLID) assertEquals(0xFF000000.toInt(), v)
                } else if (d > 10.0 + margin + 0.01) {
                    assertEquals("$mode: changed off the path at $x,$y", orig[y * w + x], v)
                }
            }
        }
    }

    @Test fun aWindowGivesExactlyTheSameResultAsTheWholePicture() {
        val w = 500
        val h = 400
        for (mode in RedactMode.entries) {
            val r = rect(120f, 90f, 330f, 140f, mode)
            val whole = textLike(w, h)
            Redactor.redact(whole, w, h, r, Random(77))
            val win = Redactor.window(r, w, h)
            val part = textLike(w, h)
            val px = IntArray(win.width * win.height)
            for (y in 0 until win.height) System.arraycopy(part, (win.top + y) * w + win.left, px, y * win.width, win.width)
            Redactor.redact(PixelWindow(px, win.left, win.top, win.width, win.height), w, h, r, Random(77))
            for (y in 0 until win.height) for (x in 0 until win.width) {
                assertEquals("$mode at ${win.left + x},${win.top + y}", whole[(win.top + y) * w + win.left + x], px[y * win.width + x])
            }
        }
    }

    @Test fun aWindowThatMissesPartOfTheAreaIsRefusedRatherThanHalfRedacting() {
        val r = rect(100f, 100f, 200f, 150f, RedactMode.BLUR)
        val px = IntArray(50 * 50)
        assertThrows(IllegalArgumentException::class.java) {
            Redactor.redact(PixelWindow(px, 120, 110, 50, 50), 400, 300, r, Random(1))
        }
    }

    @Test fun redactingTwiceOrOverlappingStaysRedacted() {
        val w = 360
        val h = 240
        val px = textLike(w, h)
        val a = rect(20f, 20f, 200f, 80f, RedactMode.PIXELATE)
        val b = rect(150f, 50f, 340f, 200f, RedactMode.BLUR)
        Redactor.redact(px, w, h, a, Random(1))
        Redactor.redact(px, w, h, b, Random(2))
        for (r in listOf(a, b)) {
            val e = Redactor.extent(r, w, h)
            for (y in e.top until e.bottom) for (x in e.left until e.right) assertFalse(px[y * w + x] in palette)
        }
    }

    private companion object {
        val NL: String = System.lineSeparator()
    }
}
