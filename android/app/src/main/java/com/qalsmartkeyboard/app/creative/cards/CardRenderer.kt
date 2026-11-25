package com.qalsmartkeyboard.app.creative.cards

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a [CardDoc] with the platform's Canvas and StaticLayout (T8.7). The same code makes the live preview and the
 * exported file: both come from [render], the preview only at a smaller [scale] (the geometry is laid out once in card
 * units of [CardAspect.width] pixels and the canvas is scaled, so line breaks never differ).
 *
 * Every export is a freshly rendered bitmap: nothing of a picked photo except its (rotated, downscaled) pixels reaches
 * it, so no EXIF (location, camera, time) can leak into a shared card (R-8). Text goes through StaticLayout, so Ge'ez
 * (Fidel) and Latin lines wrap at word breaks and a very long word breaks inside, whatever the language.
 */
class CardRenderer {
    private val typefaces = HashMap<Triple<CardFont, Boolean, Boolean>, Typeface>()

    /** A new bitmap of the card, [scale] times its export size (1: 1080 x 1080 or 1080 x 1350). */
    fun render(doc: CardDoc, photo: Bitmap?, scale: Float = 1f): Bitmap {
        require(scale > 0f && scale <= 1f) { "scale $scale" }
        val w = doc.aspect.width
        val h = doc.aspect.height
        val out = Bitmap.createBitmap(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.scale(out.width.toFloat() / w, out.height.toFloat() / h)
        draw(canvas, doc, photo)
        return out
    }

    /** Draws the card on [canvas] in card units (the canvas is `doc.aspect.width` x `height` units). */
    fun draw(canvas: Canvas, doc: CardDoc, photo: Bitmap?) {
        val w = doc.aspect.width
        val h = doc.aspect.height
        val template = CardTemplates.find(doc.templateId) ?: CardTemplates.default
        drawBackground(canvas, doc.background, photo, w, h)
        drawDecor(canvas, template.layout.decor, template.accent, w, h)
        drawFrame(canvas, doc.frame, doc.text.color, w, h)
        val (headBox, bodyBox, fromBox) = CardLayoutMath.boxes(template.layout, w, h, doc.frame)
        drawText(canvas, doc.headline, headBox, template.layout.headline, doc, w)
        drawText(canvas, doc.body, bodyBox, template.layout.body, doc, w)
        drawText(canvas, doc.from, fromBox, template.layout.from, doc, w)
    }

    // ---- Background -------------------------------------------------------------------------------------------------

    private fun drawBackground(c: Canvas, bg: CardBackground, photo: Bitmap?, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (bg) {
            is CardBackground.Solid -> c.drawColor(opaque(bg.color))
            is CardBackground.Gradient -> {
                val l = CardLayoutMath.gradientLine(w, h, bg.angleDeg)
                val colors = bg.colors.map(::opaque).toIntArray()
                p.shader = LinearGradient(l[0], l[1], l[2], l[3], colors, null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            }
            is CardBackground.Pattern -> {
                c.drawColor(opaque(bg.base))
                drawPattern(c, bg, w, h)
            }
            is CardBackground.Photo -> {
                c.drawColor(NO_PHOTO)
                if (photo != null && photo.width > 0 && photo.height > 0) {
                    val t = CardLayoutMath.coverTransform(photo.width, photo.height, w, h)
                    val m = Matrix().apply { postScale(t[0], t[0]); postTranslate(t[1], t[2]) }
                    c.save()
                    c.clipRect(0, 0, w, h)
                    p.isFilterBitmap = true
                    c.drawBitmap(photo, m, p)
                    c.restore()
                }
                if (bg.dimPercent > 0) c.drawColor(Color.argb(bg.dimPercent * 255 / 100, 0, 0, 0))
            }
        }
    }

    private fun drawPattern(c: Canvas, bg: CardBackground.Pattern, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = opaque(bg.accent) }
        val wf = w.toFloat()
        val hf = h.toFloat()
        when (bg.kind) {
            PatternKind.DOTS -> {
                val step = 60f
                var row = 0
                var y = step / 2
                while (y < hf + step) {
                    var x = if (row % 2 == 0) step / 2 else step
                    while (x < wf + step) {
                        c.drawCircle(x, y, 8f, p)
                        x += step
                    }
                    y += step * 0.8f
                    row++
                }
            }
            PatternKind.STRIPES -> {
                c.save()
                c.rotate(-45f, wf / 2, hf / 2)
                val span = max(wf, hf) * 1.5f
                var x = -span
                while (x < span * 2) {
                    c.drawRect(x, -span, x + 36f, span * 2, p)
                    x += 90f
                }
                c.restore()
            }
            PatternKind.CHECKS -> {
                val s = 90f
                var yi = 0
                var y = 0f
                while (y < hf) {
                    var xi = 0
                    var x = 0f
                    while (x < wf) {
                        if ((xi + yi) % 2 == 0) c.drawRect(x, y, x + s, y + s, p)
                        x += s
                        xi++
                    }
                    y += s
                    yi++
                }
            }
            PatternKind.WAVES -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 8f
                val path = Path()
                var y = 40f
                while (y < hf + 80f) {
                    path.rewind()
                    path.moveTo(0f, y)
                    var x = 0f
                    while (x < wf) {
                        path.quadTo(x + 40f, y - 28f, x + 80f, y)
                        path.quadTo(x + 120f, y + 28f, x + 160f, y)
                        x += 160f
                    }
                    c.drawPath(path, p)
                    y += 84f
                }
            }
            PatternKind.CONFETTI -> scatter(c, p, Random(CONFETTI_SEED), 110, 0f, 0f, wf, hf, 16f, 34f)
        }
    }

    // ---- Decoration -------------------------------------------------------------------------------------------------

    private fun drawDecor(c: Canvas, decor: CardDecor, accent: Int, w: Int, h: Int) {
        if (decor == CardDecor.NONE) return
        val wf = w.toFloat()
        val hf = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = opaque(accent) }
        when (decor) {
            CardDecor.CONFETTI -> {
                p.alpha = 220
                val r = Random(CONFETTI_SEED)
                scatter(c, p, r, 26, 0f, 0f, wf, hf * 0.1f, 14f, 30f)
                scatter(c, p, r, 22, 0f, hf * 0.92f, wf, hf, 14f, 30f)
                scatter(c, p, r, 8, 0f, hf * 0.1f, wf * 0.06f, hf * 0.92f, 12f, 24f)
                scatter(c, p, r, 8, wf * 0.94f, hf * 0.1f, wf, hf * 0.92f, 12f, 24f)
            }
            CardDecor.STARS -> {
                val r = Random(STAR_SEED)
                p.alpha = 230
                repeat(14) {
                    val top = it % 2 == 0
                    val x = r.nextFloat() * wf
                    val y = if (top) r.nextFloat() * hf * 0.1f else hf * 0.9f + r.nextFloat() * hf * 0.09f
                    star(c, p, x, y, 10f + r.nextFloat() * 22f)
                }
            }
            CardDecor.SUN -> {
                p.alpha = 70
                val cx = wf / 2
                c.drawCircle(cx, 0f, wf * 0.17f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 10f
                p.strokeCap = Paint.Cap.ROUND
                p.alpha = 90
                for (i in 0..8) {
                    val a = PI * (0.1 + 0.8 * i / 8)
                    c.drawLine(cx + cos(a).toFloat() * wf * 0.2f, sin(a).toFloat() * wf * 0.2f, cx + cos(a).toFloat() * wf * 0.26f, sin(a).toFloat() * wf * 0.26f, p)
                }
            }
            CardDecor.RIBBON -> {
                p.alpha = 60
                c.drawRect(0f, 0f, wf, hf * 0.09f, p)
                c.drawRect(0f, hf * 0.93f, wf, hf, p)
            }
            CardDecor.DOTS_ARC -> {
                p.alpha = 160
                val n = 9
                for (i in 0 until n) {
                    val a = PI * (i + 0.5) / n
                    c.drawCircle(wf / 2 + cos(a).toFloat() * wf * 0.3f, hf * 0.035f + sin(a).toFloat() * hf * 0.03f, 9f, p)
                }
            }
            CardDecor.HEARTS -> {
                p.alpha = 150
                val r = Random(STAR_SEED)
                repeat(10) {
                    val top = it % 2 == 0
                    val x = r.nextFloat() * wf
                    val y = if (top) r.nextFloat() * hf * 0.1f else hf * 0.9f + r.nextFloat() * hf * 0.08f
                    heart(c, p, x, y, 16f + r.nextFloat() * 20f)
                }
            }
            CardDecor.QUOTE_MARKS -> {
                p.alpha = 90
                p.typeface = typeface(CardFont.SERIF, bold = true, italic = false)
                p.textSize = wf * 0.34f
                c.drawText("“", wf * 0.05f, hf * 0.2f, p)
            }
            CardDecor.NONE -> Unit
        }
    }

    private fun scatter(c: Canvas, p: Paint, r: Random, count: Int, l: Float, t: Float, rt: Float, b: Float, minS: Float, maxS: Float) {
        repeat(count) {
            val x = l + r.nextFloat() * (rt - l)
            val y = t + r.nextFloat() * (b - t)
            val s = minS + r.nextFloat() * (maxS - minS)
            if (r.nextBoolean()) {
                c.drawCircle(x, y, s / 2, p)
            } else {
                c.save()
                c.rotate(r.nextFloat() * 180f, x, y)
                c.drawRect(x - s / 2, y - s / 4, x + s / 2, y + s / 4, p)
                c.restore()
            }
        }
    }

    private fun star(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float) {
        val path = Path()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else r * 0.42f
            val a = -PI / 2 + i * PI / 5
            val x = cx + cos(a).toFloat() * rad
            val y = cy + sin(a).toFloat() * rad
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }

    private fun heart(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float) {
        val path = Path()
        path.moveTo(cx, cy + s * 0.9f)
        path.cubicTo(cx - s * 1.6f, cy - s * 0.2f, cx - s * 0.6f, cy - s * 1.1f, cx, cy - s * 0.3f)
        path.cubicTo(cx + s * 0.6f, cy - s * 1.1f, cx + s * 1.6f, cy - s * 0.2f, cx, cy + s * 0.9f)
        path.close()
        c.drawPath(path, p)
    }

    // ---- Frame ------------------------------------------------------------------------------------------------------

    private fun drawFrame(c: Canvas, frame: CardFrame, color: Int, w: Int, h: Int) {
        if (frame == CardFrame.NONE) return
        val wf = w.toFloat()
        val hf = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = opaque(color)
            alpha = 235
        }
        fun rect(inset: Float) = RectF(inset, inset, wf - inset, hf - inset)
        when (frame) {
            CardFrame.THIN -> {
                p.strokeWidth = wf * 0.004f
                c.drawRect(rect(wf * 0.025f), p)
            }
            CardFrame.DOUBLE -> {
                p.strokeWidth = wf * 0.007f
                c.drawRect(rect(wf * 0.025f), p)
                p.strokeWidth = wf * 0.003f
                c.drawRect(rect(wf * 0.04f), p)
            }
            CardFrame.ROUNDED -> {
                p.strokeWidth = wf * 0.006f
                val r = wf * 0.05f
                c.drawRoundRect(rect(wf * 0.03f), r, r, p)
            }
            CardFrame.CORNERS -> {
                p.strokeWidth = wf * 0.007f
                p.strokeCap = Paint.Cap.ROUND
                val i = wf * 0.035f
                val len = wf * 0.14f
                for ((sx, sy) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
                    val x = if (sx == 0f) i else wf - i
                    val y = if (sy == 0f) i else hf - i
                    val dx = if (sx == 0f) 1f else -1f
                    val dy = if (sy == 0f) 1f else -1f
                    c.drawLine(x, y, x + dx * len, y, p)
                    c.drawLine(x, y, x, y + dy * len, p)
                    p.style = Paint.Style.FILL
                    c.drawCircle(x + dx * len * 0.3f, y + dy * len * 0.3f, wf * 0.006f, p)
                    p.style = Paint.Style.STROKE
                }
            }
            CardFrame.DOTTED -> {
                val sw = wf * 0.008f
                p.strokeWidth = sw
                p.strokeCap = Paint.Cap.ROUND
                p.pathEffect = DashPathEffect(floatArrayOf(0.1f, sw * 2.6f), 0f)
                val r = wf * 0.04f
                c.drawRoundRect(rect(wf * 0.03f), r, r, p)
            }
            CardFrame.NONE -> Unit
        }
    }

    // ---- Text -------------------------------------------------------------------------------------------------------

    private fun drawText(c: Canvas, text: String, box: Box, slot: TextSlot, doc: CardDoc, w: Int) {
        if (text.isBlank()) return
        val style = doc.text
        val bold = slot.bold || style.bold
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = typeface(style.font, bold, slot.italic)
            color = style.color
        }
        val maxPx = slot.maxSize * w * style.sizeScale
        val minPx = slot.minSize * w * min(1f, style.sizeScale)
        val measure = TextMeasurer { t, size, width ->
            paint.textSize = size
            layout(t, paint, width, style.align, slot.lineSpacing, Int.MAX_VALUE).height
        }
        val fit = CardLayoutMath.fit(measure, text, box.width, box.height, maxPx, minPx)
        paint.textSize = fit.size
        if (style.shadow) paint.setShadowLayer(fit.size * 0.08f, 0f, fit.size * 0.05f, SHADOW)
        val lineHeight = max(1, layout(text, paint, box.width, style.align, slot.lineSpacing, 1).height)
        val maxLines = if (fit.fits) Int.MAX_VALUE else max(1, box.height / lineHeight)
        val l = layout(text, paint, box.width, style.align, slot.lineSpacing, maxLines)
        val dy = max(0, (box.height - l.height) / 2)
        c.save()
        c.translate(box.left.toFloat(), (box.top + dy).toFloat())
        l.draw(c)
        c.restore()
    }

    private fun layout(text: String, paint: TextPaint, width: Int, align: CardTextAlign, spacing: Float, maxLines: Int): StaticLayout {
        val a = when (align) {
            CardTextAlign.START -> Layout.Alignment.ALIGN_NORMAL
            CardTextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            CardTextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(a)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setMaxLines(maxLines)
            .setEllipsize(if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END)
            .build()
    }

    private fun typeface(font: CardFont, bold: Boolean, italic: Boolean): Typeface = typefaces.getOrPut(Triple(font, bold, italic)) {
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        Typeface.create(Typeface.create(font.family, Typeface.NORMAL), style)
    }

    private fun opaque(argb: Int) = argb or (0xFF shl 24)

    companion object {
        private const val CONFETTI_SEED = 20_26L
        private const val STAR_SEED = 11L
        private const val NO_PHOTO = 0xFF455A64.toInt()
        private const val SHADOW = 0x99000000.toInt()
    }
}
