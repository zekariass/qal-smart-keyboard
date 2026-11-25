package com.qalsmartkeyboard.ime.sticker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.qalsmartkeyboard.ime.draw.Drawing
import com.qalsmartkeyboard.ime.draw.StrokeRenderer
import java.io.File
import java.io.IOException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Paints a [StickerSpec] into the square `[0, size]` of any [Canvas]: the live preview, a strip thumbnail (no bitmap is
 * needed for those) and the 512 px export all use the same code, so what the user sees is what is saved. Text is fitted by
 * shrinking until it fits the shape's safe area; a sticker without a background gets a contrasting outline so its text
 * reads on any chat background. Reuses its Paints and Path (no allocation per frame except the text layouts).
 *
 * Only system fonts are used (the emoji font draws emoji, a bold system face the text; Amharic and Tigrinya use the
 * system's Ethiopic font): no third-party image or font is bundled (N10).
 */
class StickerRenderer(private val strokes: StrokeRenderer = StrokeRenderer()) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val rect = RectF()

    fun draw(canvas: Canvas, spec: StickerSpec, size: Float) {
        when (spec) {
            is TextStickerSpec -> drawText(canvas, spec, size)
            is EmojiStickerSpec -> drawEmoji(canvas, spec, size)
            is DrawingStickerSpec -> drawDrawing(canvas, spec, size)
        }
    }

    // ---- shapes --------------------------------------------------------------------------------------------------

    private fun drawShape(canvas: Canvas, shape: StickerShape, colour: Int, size: Float) {
        if (shape == StickerShape.NONE) return
        fill.color = colour
        fill.style = Paint.Style.FILL
        val inset = size * 0.03f
        when (shape) {
            StickerShape.CIRCLE -> canvas.drawCircle(size / 2f, size / 2f, size / 2f - inset, fill)
            StickerShape.ROUNDED -> {
                rect.set(inset, inset, size - inset, size - inset)
                canvas.drawRoundRect(rect, size * 0.2f, size * 0.2f, fill)
            }
            StickerShape.BURST -> {
                // A 16-point badge: alternating outer and inner radii.
                val points = 16
                val outer = size / 2f - inset
                val inner = outer * 0.88f
                path.rewind()
                for (i in 0 until points * 2) {
                    val r = if (i % 2 == 0) outer else inner
                    val a = PI * i / points - PI / 2
                    val x = size / 2f + (r * cos(a)).toFloat()
                    val y = size / 2f + (r * sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, fill)
            }
            StickerShape.NONE -> Unit
        }
    }

    /** The side of the square (centred) that text and art may use. */
    private fun safeSide(shape: StickerShape, size: Float) = size * StickerFormat.safeFraction(shape)

    // ---- text ----------------------------------------------------------------------------------------------------

    private fun drawText(canvas: Canvas, s: TextStickerSpec, size: Float) {
        drawShape(canvas, s.shape, s.fill, size)
        val safe = safeSide(s.shape, size)
        drawFitted(canvas, s.text, s.textColour, s.shape == StickerShape.NONE, (size - safe) / 2f, size / 2f - safe / 2f, safe, safe, s.size.maxFraction * size)
    }

    /**
     * Draws [str] centred in the box ([left], [top], [w], [h]), at the largest size up to [maxPx] at which it fits, with
     * an outline of the opposite colour when [outline].
     */
    private fun drawFitted(canvas: Canvas, str: String, colour: Int, outline: Boolean, left: Float, top: Float, w: Float, h: Float, maxPx: Float) {
        val minPx = max(8f, w * 0.04f)
        var px = max(minPx, min(maxPx, h))
        var layout = layoutOf(str, px, w.toInt().coerceAtLeast(1))
        // Shrink until it fits in height AND no word is cut across two lines ("Sela" / "m"): a word breaks only when even the
        // smallest size cannot hold it.
        while (px > minPx && (layout.height > h || widestWord(str, px) > w)) {
            px = max(minPx, px * 0.9f)
            layout = layoutOf(str, px, w.toInt().coerceAtLeast(1))
        }
        canvas.save()
        canvas.translate(left, top + (h - min(layout.height.toFloat(), h)) / 2f)
        if (outline) {
            text.style = Paint.Style.STROKE
            text.strokeJoin = Paint.Join.ROUND
            text.strokeWidth = px * 0.16f
            text.color = StickerColours.contrastOn(colour)
            layout.draw(canvas)
        }
        text.style = Paint.Style.FILL
        text.color = colour
        layout.draw(canvas)
        canvas.restore()
    }

    /** The width of the widest whitespace-separated word of [str] at [px]. */
    private fun widestWord(str: String, px: Float): Float {
        text.textSize = px
        text.style = Paint.Style.FILL
        var widest = 0f
        for (word in str.split(' ')) for (part in word.lines()) if (part.isNotEmpty()) widest = max(widest, text.measureText(part))
        return widest
    }

    private fun layoutOf(str: String, px: Float, width: Int): StaticLayout {
        text.textSize = px
        text.style = Paint.Style.FILL
        return StaticLayout.Builder.obtain(str, 0, str.length, text, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    // ---- emoji and caption ---------------------------------------------------------------------------------------

    private fun drawEmoji(canvas: Canvas, s: EmojiStickerSpec, size: Float) {
        drawShape(canvas, s.shape, s.fill, size)
        val safe = safeSide(s.shape, size)
        val left = (size - safe) / 2f
        val top = (size - safe) / 2f
        val hasCaption = s.caption.isNotBlank()
        val emojiBox = if (hasCaption) safe * 0.62f else safe
        // The emoji: sized to the box, nudged so the glyph is centred on its box rather than on its baseline.
        emojiPaint.textSize = emojiBox * 0.82f
        val width = emojiPaint.measureText(s.emoji)
        if (width > emojiBox) emojiPaint.textSize *= emojiBox / width
        val fm = emojiPaint.fontMetrics
        val baseline = top + emojiBox / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(s.emoji, size / 2f, baseline, emojiPaint)
        if (hasCaption) {
            val capTop = top + emojiBox
            drawFitted(canvas, s.caption.trim(), s.captionColour, s.shape == StickerShape.NONE, left, capTop, safe, safe - emojiBox, safe * 0.2f)
        }
    }

    // ---- drawings ------------------------------------------------------------------------------------------------

    private fun drawDrawing(canvas: Canvas, s: DrawingStickerSpec, size: Float) {
        val d: Drawing = s.drawing
        val content = d.contentBounds()
        val margin = 8f
        val l = max(0f, (content?.left ?: 0f) - margin)
        val t = max(0f, (content?.top ?: 0f) - margin)
        val r = min(d.width, (content?.right ?: d.width) + margin)
        val b = min(d.height, (content?.bottom ?: d.height) + margin)
        val cw = max(1f, r - l)
        val ch = max(1f, b - t)
        val box = size * 0.94f
        val scale = box / max(cw, ch)
        if (s.withBackground && !d.background.isTransparent) {
            fill.color = d.background.colour
            fill.style = Paint.Style.FILL
            rect.set(size * 0.02f, size * 0.02f, size * 0.98f, size * 0.98f)
            canvas.drawRoundRect(rect, size * 0.12f, size * 0.12f, fill)
        }
        // The ink on its own layer, so the eraser's clear blend only removes ink (as in the canvas and the PNG export).
        val layer = canvas.saveLayer(0f, 0f, size, size, null)
        canvas.translate((size - cw * scale) / 2f, (size - ch * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-l, -t)
        strokes.drawStrokes(canvas, d.strokes)
        canvas.restoreToCount(layer)
    }
}

/** The system exporter: [StickerRenderer] into a 512 px bitmap and WebP encoding. Nothing is logged. */
class WebpStickerExporter(
    private val renderer: StickerRenderer = StickerRenderer(),
    private val sdk: Int = Build.VERSION.SDK_INT,
) : StickerExporter {
    override fun exportWebp(spec: StickerSpec, dest: File): Boolean {
        val bmp = Bitmap.createBitmap(StickerFormat.SIZE_PX, StickerFormat.SIZE_PX, Bitmap.Config.ARGB_8888)
        return try {
            renderer.draw(Canvas(bmp), spec, StickerFormat.SIZE_PX.toFloat())
            dest.parentFile?.mkdirs()
            dest.outputStream().use { compress(bmp, sdk, it) }
        } catch (e: IOException) {
            false
        } finally {
            bmp.recycle()
        }
    }

    companion object {
        /** Lossless WebP on API 30+, else the legacy WebP codec (alpha kept); the format never depends on the sticker. */
        fun compress(bmp: Bitmap, sdk: Int, out: java.io.OutputStream): Boolean = when (StickerFormat.codecFor(sdk)) {
            StickerFormat.Codec.LOSSLESS -> bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
            StickerFormat.Codec.LEGACY -> @Suppress("DEPRECATION") bmp.compress(Bitmap.CompressFormat.WEBP, StickerFormat.LEGACY_QUALITY, out)
        }
    }
}
