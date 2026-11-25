package com.qalsmartkeyboard.app.creative.annotate

import java.util.Random
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The numbers one redaction is applied with (all in pixels of the image it is applied to). */
data class RedactParams(
    /** The area grows by this much on every side, so the edges of glyphs next to it are covered too. */
    val margin: Int,
    /** Box-blur radius of one pass (BLUR). Three passes approximate a Gaussian with sigma close to the radius. */
    val blurRadius: Int,
    val passes: Int,
    /** Mosaic cell size (PIXELATE); the cells average pixels that were first blurred with this radius. */
    val block: Int,
    /** Each output channel gets a random offset in [-noise, +noise] (BLUR and PIXELATE). */
    val noise: Int,
) {
    /** How far outside its extent a redaction reads pixels. */
    fun reach(mode: RedactMode): Int = when (mode) {
        RedactMode.SOLID -> 0
        RedactMode.BLUR -> blurRadius * passes
        RedactMode.PIXELATE -> block * passes
    }
}

/** A rectangle of pixels cut out of an image: [px] holds [width] x [height] ARGB values whose top-left is ([x], [y]). */
class PixelWindow(val px: IntArray, val x: Int, val y: Int, val width: Int, val height: Int) {
    init {
        require(width >= 0 && height >= 0 && px.size >= width * height) { "window $width x $height, ${px.size}" }
    }

    val rect: PixelRect get() = PixelRect(x, y, x + width, y + height)
}

/**
 * Irreversible redaction on a plain ARGB pixel array (T8.7, SD §6.6 "Blur/redaction is applied locally before export").
 * Pure Kotlin: the export ([AnnotationExporter]) and the editing preview both call this, and the irreversibility tests
 * run it on the JVM. Nothing here is a view effect that a device could switch off (no RenderEffect, no
 * BlurMaskFilter): the pixels themselves are replaced, and the export encodes those pixels.
 *
 * Why the numbers are what they are (an area's "side" is its smaller dimension after the margin):
 * - **Margin** 10% of the side, at least [MIN_MARGIN] px (more on large images), at most [MAX_MARGIN]: a box drawn
 *   tight around a line of text still covers ascenders, descenders and anti-aliased glyph edges.
 * - **Blur**: three box-blur passes of radius = half the side (at least [MIN_BLUR_RADIUS] px, more on large images),
 *   sigma about the radius. The strokes of a letter are a tenth of the line height or less, so their contrast is
 *   attenuated far below one 8-bit step: nothing is left to deconvolve, and the random noise added afterwards removes
 *   the exactness a deconvolution would need. Pixels are sampled from the real image around the area (not clamped at
 *   the area's border), so the border rows are blurred as strongly as the middle; at the picture's own edges the
 *   blur window is cut short and renormalised, so the edge row is never weighted more than any other pixel.
 * - **Pixelate**: cells of a third of the side (at least [MIN_BLOCK] px, more on large images), averaged from pixels that
 *   were first blurred with a radius of one cell. Plain mosaics of small cells over text in a known font can be reversed
 *   by trying candidate texts (the "Depix" attack); here every cell is already a smooth mixture over a wider
 *   neighbourhood, cells are at least a third of a text line, and the noise breaks exact matching.
 * - **Solid** fills the area with one opaque colour: nothing of the pixels survives. The strongest choice.
 *
 * An area partly outside the image is clamped to it; an area entirely outside changes nothing.
 */
object Redactor {
    const val MIN_MARGIN = 3
    const val MARGIN_FRACTION = 0.10f
    const val MAX_MARGIN = 96
    const val MIN_BLUR_RADIUS = 12
    const val BLUR_FRACTION = 0.5f
    const val MAX_BLUR_RADIUS = 256
    const val PASSES = 3
    const val MIN_BLOCK = 10
    const val BLOCK_DIVISOR = 3f
    const val MAX_BLOCK = 512
    const val NOISE = 3

    /** The parameters for an area whose smaller side is [areaMinSide] px, on an image whose longer side is [imageLongSide]. */
    fun params(areaMinSide: Float, imageLongSide: Int): RedactParams {
        val minMargin = max(MIN_MARGIN, ceilDiv(imageLongSide, 800))
        val margin = ceil(max(0f, areaMinSide) * MARGIN_FRACTION).toInt().coerceIn(minMargin, max(minMargin, MAX_MARGIN))
        val side = max(0f, areaMinSide) + 2 * margin
        val minBlur = max(MIN_BLUR_RADIUS, ceilDiv(imageLongSide, 200))
        val blur = ceil(side * BLUR_FRACTION).toInt().coerceIn(minBlur, max(minBlur, MAX_BLUR_RADIUS))
        val minBlock = max(MIN_BLOCK, ceilDiv(imageLongSide, 160))
        val block = ceil(side / BLOCK_DIVISOR).toInt().coerceIn(minBlock, max(minBlock, MAX_BLOCK))
        return RedactParams(margin, blur, PASSES, block, NOISE)
    }

    fun params(r: Annotation.Redaction, imageLongSide: Int): RedactParams = params(areaSide(r.area), imageLongSide)

    private fun areaSide(a: RedactArea): Float = when (a) {
        is RedactArea.Rect -> a.box.normalized().minSide
        is RedactArea.Brush -> 2 * a.radius
    }

    /** The pixels [r] rewrites on a [imageW] x [imageH] image: its area grown by the margin, clamped to the image. */
    fun extent(r: Annotation.Redaction, imageW: Int, imageH: Int): PixelRect {
        val p = params(r, max(imageW, imageH))
        return r.area.bounds().outset(p.margin.toFloat()).toPixelsOutward().clampTo(imageW, imageH)
    }

    /** The pixels [r] reads: its [extent] plus the blur's reach, clamped. Pass at least this much to [redact]. */
    fun window(r: Annotation.Redaction, imageW: Int, imageH: Int): PixelRect {
        val e = extent(r, imageW, imageH)
        if (e.isEmpty) return e
        val p = params(r, max(imageW, imageH))
        return e.outset(p.reach(r.mode)).clampTo(imageW, imageH)
    }

    /**
     * Applies [r] to [win], a part of an [imageW] x [imageH] image (the whole image is fine too). Rewrites every pixel of
     * the redaction's extent that lies in [win] (for a brush area, those within the brush radius plus the margin of the
     * path); leaves every other pixel as it was. Output pixels are opaque.
     */
    fun redact(win: PixelWindow, imageW: Int, imageH: Int, r: Annotation.Redaction, rng: Random) {
        val p = params(r, max(imageW, imageH))
        val grown = r.area.bounds().outset(p.margin.toFloat()).toPixelsOutward()
        val e = grown.clampTo(imageW, imageH)
        if (e.isEmpty) return
        // A window that misses part of the extent would leave pixels unredacted: refuse rather than half-redact.
        require(intersect(e, win.rect) == e) { "the window does not hold the redaction" }
        val mask = mask(r.area, p.margin, e)
        when (r.mode) {
            RedactMode.SOLID -> {
                val c = r.color or OPAQUE
                write(win, e, mask) { _, _ -> c }
            }
            RedactMode.BLUR -> {
                val blurred = blurred(win, e, p.blurRadius, p.passes)
                write(win, e, mask) { x, y -> jitter(blurred[(y - e.top) * e.width + (x - e.left)], p.noise, rng) }
            }
            RedactMode.PIXELATE -> {
                val blurred = blurred(win, e, min(p.block, MAX_BLUR_RADIUS), p.passes)
                val cells = cellMeans(blurred, e, grown.left, grown.top, p.block)
                write(win, e, mask) { x, y -> jitter(cells(x, y), p.noise, rng) }
            }
        }
    }

    /** Applies [r] to a whole image held in [px] ([w] x [h]). */
    fun redact(px: IntArray, w: Int, h: Int, r: Annotation.Redaction, rng: Random) = redact(PixelWindow(px, 0, 0, w, h), w, h, r, rng)

    // ---- Implementation ------------------------------------------------------------------------------------------------

    private const val OPAQUE = 0xFF000000.toInt()

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    private fun intersect(a: PixelRect, b: PixelRect) =
        PixelRect(max(a.left, b.left), max(a.top, b.top), min(a.right, b.right), min(a.bottom, b.bottom))

    /** Which pixels of [e] the area covers (null: all of them). A brush covers pixels whose centre is near its path. */
    private fun mask(area: RedactArea, margin: Int, e: PixelRect): BooleanArray? {
        if (area !is RedactArea.Brush) return null
        val m = BooleanArray(e.width * e.height)
        val reach = area.radius + margin
        val r2 = reach * reach
        val pts = area.points
        val segments = if (pts.size == 1) listOf(pts[0] to pts[0]) else pts.zipWithNext()
        for ((a, b) in segments) {
            val x0 = max(e.left, (min(a.x, b.x) - reach).toInt() - 1)
            val x1 = min(e.right, (max(a.x, b.x) + reach).toInt() + 2)
            val y0 = max(e.top, (min(a.y, b.y) - reach).toInt() - 1)
            val y1 = min(e.bottom, (max(a.y, b.y) + reach).toInt() + 2)
            for (y in y0 until y1) for (x in x0 until x1) {
                val i = (y - e.top) * e.width + (x - e.left)
                if (!m[i] && Geometry.dist2ToSegment(x + 0.5f, y + 0.5f, a, b) <= r2) m[i] = true
            }
        }
        return m
    }

    private inline fun write(win: PixelWindow, e: PixelRect, mask: BooleanArray?, value: (Int, Int) -> Int) {
        for (y in e.top until e.bottom) {
            val row = (y - win.y) * win.width - win.x
            for (x in e.left until e.right) {
                if (mask != null && !mask[(y - e.top) * e.width + (x - e.left)]) continue
                win.px[row + x] = value(x, y) or OPAQUE
            }
        }
    }

    private fun jitter(rgb: Int, noise: Int, rng: Random): Int {
        if (noise <= 0) return rgb
        val span = 2 * noise + 1
        val r = ((rgb shr 16 and 0xFF) + rng.nextInt(span) - noise).coerceIn(0, 255)
        val g = ((rgb shr 8 and 0xFF) + rng.nextInt(span) - noise).coerceIn(0, 255)
        val b = ((rgb and 0xFF) + rng.nextInt(span) - noise).coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * The pixels of [e] after [passes] box blurs of [radius], sampled from the window around [e] (up to the blur's reach,
     * cut short at the window's edges, which are the image's edges or beyond the reach). Packed RGB, row-major over [e].
     * One colour channel at a time, so it needs two channel buffers of the sampled area at most.
     */
    private fun blurred(win: PixelWindow, e: PixelRect, radius: Int, passes: Int): IntArray {
        val s = intersect(e.outset(radius * passes), win.rect)
        val sw = s.width
        val sh = s.height
        val out = IntArray(e.width * e.height)
        val a = IntArray(sw * sh)
        val b = IntArray(sw * sh)
        for (shift in intArrayOf(16, 8, 0)) {
            for (y in 0 until sh) {
                val src = (s.top + y - win.y) * win.width + (s.left - win.x)
                for (x in 0 until sw) a[y * sw + x] = win.px[src + x] shr shift and 0xFF
            }
            repeat(passes) {
                boxH(a, b, sw, sh, radius)
                boxV(b, a, sw, sh, radius)
            }
            for (y in e.top until e.bottom) {
                val srow = (y - s.top) * sw - s.left
                val orow = (y - e.top) * e.width - e.left
                for (x in e.left until e.right) out[orow + x] = out[orow + x] or (a[srow + x] shl shift)
            }
        }
        return out
    }

    /**
     * One horizontal box-blur pass of radius [r]. At the edges the window is cut short and the sum divided by the pixels
     * actually in it: no pixel is ever counted twice. (Clamping to the edge would count the border pixel up to r times,
     * so a large blur touching the picture's edge would keep much of the border row's content.)
     */
    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val last = w - 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (i in 0..min(r, last)) sum += src[row + i]
            for (x in 0 until w) {
                val n = min(last, x + r) - max(0, x - r) + 1
                dst[row + x] = (sum + n / 2) / n
                if (x + r + 1 <= last) sum += src[row + x + r + 1]
                if (x - r >= 0) sum -= src[row + x - r]
            }
        }
    }

    /** One vertical pass, like [boxH]. */
    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val last = h - 1
        for (x in 0 until w) {
            var sum = 0
            for (i in 0..min(r, last)) sum += src[i * w + x]
            for (y in 0 until h) {
                val n = min(last, y + r) - max(0, y - r) + 1
                dst[y * w + x] = (sum + n / 2) / n
                if (y + r + 1 <= last) sum += src[(y + r + 1) * w + x]
                if (y - r >= 0) sum -= src[(y - r) * w + x]
            }
        }
    }

    /**
     * The mean of [values] (packed RGB over [e]) in each [block] x [block] cell of a grid whose origin is ([ox], [oy]) (the
     * grown area's corner, so the grid does not depend on clamping). Returns a lookup by image pixel.
     */
    private fun cellMeans(values: IntArray, e: PixelRect, ox: Int, oy: Int, block: Int): (Int, Int) -> Int {
        val cx0 = Math.floorDiv(e.left - ox, block)
        val cy0 = Math.floorDiv(e.top - oy, block)
        val cols = Math.floorDiv(e.right - 1 - ox, block) - cx0 + 1
        val rows = Math.floorDiv(e.bottom - 1 - oy, block) - cy0 + 1
        val sr = LongArray(cols * rows)
        val sg = LongArray(cols * rows)
        val sb = LongArray(cols * rows)
        val n = IntArray(cols * rows)
        fun cell(x: Int, y: Int) = (Math.floorDiv(y - oy, block) - cy0) * cols + (Math.floorDiv(x - ox, block) - cx0)
        for (y in e.top until e.bottom) for (x in e.left until e.right) {
            val v = values[(y - e.top) * e.width + (x - e.left)]
            val c = cell(x, y)
            sr[c] += (v shr 16 and 0xFF).toLong(); sg[c] += (v shr 8 and 0xFF).toLong(); sb[c] += (v and 0xFF).toLong(); n[c]++
        }
        val means = IntArray(cols * rows) { c ->
            val k = max(1, n[c])
            (((sr[c] + k / 2) / k).toInt() shl 16) or (((sg[c] + k / 2) / k).toInt() shl 8) or ((sb[c] + k / 2) / k).toInt()
        }
        return { x, y -> means[cell(x, y)] }
    }
}
