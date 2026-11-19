package com.qalsmartkeyboard.ime.toolbar

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import kotlin.math.cos
import kotlin.math.sin

/**
 * The keyboard's one icon family (ADR 0035): outline icons on a 24-unit grid with a 2-unit stroke and round caps, drawn
 * with whatever colour the caller passes (the theme's strip or key tokens). No image resources, no icon library: each
 * icon is a few `Path`s built once and scaled on the canvas, so drawing allocates nothing.
 *
 * The bar's shortcuts, the tools panel tiles, the clipboard panel, the AI chips and the special keys (shift, delete,
 * Enter actions, globe, emoji) all use these, so stroke weight and proportions match everywhere.
 */
enum class ToolIcon {
    TOOLS, BACK, CLOSE, TRANSLATE, AI, CLIPBOARD, EMOJI, MIC, SETTINGS, THEME, LANGUAGES, EMAIL, WORD_ART, WRITING_MODE,
    LOCK, CHECK, GRAMMAR, REWRITE,

    // The translate panel (ADR 0037): swap languages, a list's caret, copy.
    SWAP, EXPAND, COPY,

    // Special keys.
    SHIFT, SHIFT_ON, SHIFT_LOCKED, BACKSPACE, ENTER, SEARCH, SEND, GO, NEXT, PREVIOUS,
}

object ToolIcons {
    /** Stroke width in grid units (of 24). */
    const val STROKE = 2f

    /** One icon: [stroke] is outlined, [fill] filled (small dots, the sparkle's star, a filled shift arrow). */
    private class Glyph(val stroke: Path, val fill: Path? = null)

    private val glyphs = HashMap<ToolIcon, Glyph>()

    private fun glyph(icon: ToolIcon): Glyph = glyphs.getOrPut(icon) { build(icon) }

    /**
     * Draws [icon] centred on ([cx], [cy]), [size] px square, in [color]. [paint] is the caller's scratch paint (its
     * style, colour and stroke are overwritten; nothing is allocated). [strokeScale] thickens small icons (badges).
     */
    fun draw(canvas: Canvas, icon: ToolIcon, cx: Float, cy: Float, size: Float, color: Int, paint: Paint, strokeScale: Float = 1f) {
        val g = glyph(icon)
        val s = size / 24f
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(s, s)
        paint.isAntiAlias = true
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = STROKE * strokeScale
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(g.stroke, paint)
        g.fill?.let {
            paint.style = Paint.Style.FILL
            canvas.drawPath(it, paint)
        }
        canvas.restore()
        paint.style = Paint.Style.FILL
    }

    // ------------------------------------------------------------------ the glyphs (24 × 24 grid)

    private fun Path.rect(l: Float, t: Float, r: Float, b: Float, radius: Float) =
        addRoundRect(RectF(l, t, r, b), radius, radius, Path.Direction.CW)

    private fun Path.circle(cx: Float, cy: Float, r: Float) = addCircle(cx, cy, r, Path.Direction.CW)

    private fun Path.poly(vararg xy: Float, close: Boolean = false) {
        moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) {
            lineTo(xy[i], xy[i + 1])
            i += 2
        }
        if (close) close()
    }

    /** A four-pointed sparkle (the AI mark) of radius [r] around ([cx], [cy]). */
    private fun Path.sparkle(cx: Float, cy: Float, r: Float) {
        val k = r * 0.22f
        moveTo(cx, cy - r)
        quadTo(cx + k, cy - k, cx + r, cy)
        quadTo(cx + k, cy + k, cx, cy + r)
        quadTo(cx - k, cy + k, cx - r, cy)
        quadTo(cx - k, cy - k, cx, cy - r)
        close()
    }

    /** The globe: a circle, one meridian ellipse, the equator and two parallels. */
    private fun Path.globe() {
        circle(12f, 12f, 9f)
        addOval(RectF(8f, 3f, 16f, 21f), Path.Direction.CW)
        poly(3f, 12f, 21f, 12f)
        poly(4.6f, 7.5f, 19.4f, 7.5f)
        poly(4.6f, 16.5f, 19.4f, 16.5f)
    }

    private fun shiftArrow(bodyBottom: Float) = Path().apply {
        poly(12f, 3.5f, 20.5f, 12.5f, 15.5f, 12.5f, 15.5f, bodyBottom, 8.5f, bodyBottom, 8.5f, 12.5f, 3.5f, 12.5f, close = true)
    }

    private fun parse(data: String): Path = try {
        PathParser.createPathFromPathData(data)
    } catch (e: RuntimeException) {
        Path()
    }

    private fun build(icon: ToolIcon): Glyph = when (icon) {
        ToolIcon.TOOLS -> Glyph(
            Path().apply {
                rect(3.5f, 3.5f, 10.5f, 10.5f, 2f)
                rect(13.5f, 3.5f, 20.5f, 10.5f, 2f)
                rect(3.5f, 13.5f, 10.5f, 20.5f, 2f)
                rect(13.5f, 13.5f, 20.5f, 20.5f, 2f)
            },
        )
        ToolIcon.BACK -> Glyph(Path().apply { poly(19.5f, 12f, 4.5f, 12f); poly(11f, 5.5f, 4.5f, 12f, 11f, 18.5f) })
        ToolIcon.CLOSE -> Glyph(Path().apply { poly(6f, 6f, 18f, 18f); poly(18f, 6f, 6f, 18f) })
        // The Material "translate" mark is drawn as a filled outline of 2-unit strokes, the same weight as the rest.
        ToolIcon.TRANSLATE -> Glyph(Path(), parse(TRANSLATE_PATH))
        ToolIcon.AI -> Glyph(Path().apply { sparkle(10.5f, 13.5f, 8f) }, Path().apply { sparkle(19f, 5f, 3.2f) })
        ToolIcon.CLIPBOARD -> Glyph(
            Path().apply {
                rect(5f, 4.5f, 19f, 21f, 2.5f)
                rect(8.5f, 2.5f, 15.5f, 6.5f, 1.5f)
                poly(8.5f, 11f, 15.5f, 11f)
                poly(8.5f, 15f, 13f, 15f)
            },
        )
        ToolIcon.EMOJI -> Glyph(
            Path().apply {
                circle(12f, 12f, 9f)
                addArc(RectF(7.5f, 9f, 16.5f, 16.5f), 25f, 130f)
            },
            Path().apply { circle(9f, 9.75f, 1.3f); circle(15f, 9.75f, 1.3f) },
        )
        ToolIcon.MIC -> Glyph(
            Path().apply {
                rect(9f, 3f, 15f, 14f, 3f)
                addArc(RectF(5.5f, 5f, 18.5f, 17.5f), 0f, 180f)
                poly(12f, 17.5f, 12f, 21f)
            },
        )
        ToolIcon.SETTINGS -> Glyph(
            Path().apply {
                val ro = 10f
                val ri = 7.6f
                fun pt(r: Float, deg: Float) = floatArrayOf(12f + r * cos(Math.toRadians(deg.toDouble())).toFloat(), 12f + r * sin(Math.toRadians(deg.toDouble())).toFloat())
                for (i in 0 until 8) {
                    val a = i * 45f
                    val p1 = pt(ri, a - 17f)
                    val p2 = pt(ro, a - 9f)
                    val p3 = pt(ro, a + 9f)
                    val p4 = pt(ri, a + 17f)
                    if (i == 0) moveTo(p1[0], p1[1]) else lineTo(p1[0], p1[1])
                    lineTo(p2[0], p2[1]); lineTo(p3[0], p3[1]); lineTo(p4[0], p4[1])
                }
                close()
                circle(12f, 12f, 3f)
            },
        )
        ToolIcon.THEME -> Glyph(
            Path().apply {
                // A palette: a round board with a thumb notch, four paint dots.
                moveTo(12f, 3f)
                cubicTo(7f, 3f, 3f, 7f, 3f, 12f)
                cubicTo(3f, 17f, 7f, 21f, 12f, 21f)
                cubicTo(13.4f, 21f, 14.2f, 20f, 13.8f, 18.8f)
                cubicTo(13.4f, 17.4f, 14.4f, 16f, 15.9f, 16f)
                lineTo(17.5f, 16f)
                cubicTo(19.5f, 16f, 21f, 14.5f, 21f, 12.5f)
                cubicTo(21f, 7.2f, 17f, 3f, 12f, 3f)
                close()
            },
            Path().apply { circle(7.5f, 12f, 1.4f); circle(9.5f, 7.8f, 1.4f); circle(14.5f, 7.8f, 1.4f); circle(17f, 11.5f, 1.4f) },
        )
        ToolIcon.LANGUAGES -> Glyph(Path().apply { globe() })
        ToolIcon.EMAIL -> Glyph(Path().apply { rect(3f, 5f, 21f, 19f, 2.5f); poly(3.8f, 7f, 12f, 13f, 20.2f, 7f) })
        ToolIcon.WORD_ART -> Glyph(
            Path().apply { poly(3.5f, 20f, 9.5f, 5f, 15.5f, 20f); poly(5.7f, 14.5f, 13.3f, 14.5f) },
            Path().apply { sparkle(19f, 6f, 3.5f) },
        )
        ToolIcon.WRITING_MODE -> Glyph(
            Path().apply {
                rect(2.5f, 5.5f, 21.5f, 18.5f, 2.5f)
                poly(8f, 15f, 16f, 15f)
            },
            Path().apply {
                for (x in floatArrayOf(6.5f, 9.75f, 13f, 16.25f)) circle(x + 0.75f, 9.25f, 0.95f)
                for (x in floatArrayOf(6.5f, 17.5f)) circle(x, 12.25f, 0.95f)
                for (x in floatArrayOf(9.75f, 12f, 14.25f)) circle(x, 12.25f, 0.95f)
            },
        )
        ToolIcon.LOCK -> Glyph(
            Path().apply {
                moveTo(8f, 10.5f)
                lineTo(8f, 7.5f)
                addArc(RectF(8f, 3.5f, 16f, 11.5f), 180f, 180f)
                lineTo(16f, 10.5f)
            },
            Path().apply { rect(5f, 10f, 19f, 21f, 2f) },
        )
        ToolIcon.CHECK -> Glyph(Path().apply { poly(4.5f, 12.5f, 9.5f, 17.5f, 19.5f, 7f) })
        ToolIcon.GRAMMAR -> Glyph(Path().apply { poly(3f, 15f, 7.5f, 4f, 12f, 15f); poly(4.6f, 11f, 10.4f, 11f); poly(11.5f, 17f, 15f, 20.5f, 21f, 12.5f) })
        ToolIcon.REWRITE -> Glyph(
            Path().apply {
                poly(4f, 20f, 4f, 16.5f, 15.5f, 5f, 19f, 8.5f, 7.5f, 20f, close = true)
                poly(13f, 7.5f, 16.5f, 11f)
            },
        )
        ToolIcon.SWAP -> Glyph(
            Path().apply {
                poly(4f, 8f, 19.5f, 8f); poly(15.5f, 4f, 19.5f, 8f, 15.5f, 12f)
                poly(20f, 16f, 4.5f, 16f); poly(8.5f, 12f, 4.5f, 16f, 8.5f, 20f)
            },
        )
        ToolIcon.EXPAND -> Glyph(Path().apply { poly(6.5f, 9.5f, 12f, 15f, 17.5f, 9.5f) })
        ToolIcon.COPY -> Glyph(
            Path().apply {
                rect(8.5f, 8.5f, 20f, 20f, 2f)
                moveTo(15.5f, 4f)
                lineTo(6f, 4f)
                quadTo(4f, 4f, 4f, 6f)
                lineTo(4f, 15.5f)
            },
        )
        ToolIcon.SHIFT -> Glyph(shiftArrow(20f))
        ToolIcon.SHIFT_ON -> Glyph(shiftArrow(20f), shiftArrow(20f))
        ToolIcon.SHIFT_LOCKED -> Glyph(shiftArrow(17f), Path().apply { addPath(shiftArrow(17f)); rect(8.5f, 19f, 15.5f, 21f, 0.6f) })
        ToolIcon.BACKSPACE -> Glyph(
            Path().apply {
                moveTo(8.2f, 5f)
                lineTo(19.5f, 5f)
                quadTo(21.5f, 5f, 21.5f, 7f)
                lineTo(21.5f, 17f)
                quadTo(21.5f, 19f, 19.5f, 19f)
                lineTo(8.2f, 19f)
                lineTo(2.5f, 12f)
                close()
                poly(11.5f, 9f, 17.5f, 15f)
                poly(17.5f, 9f, 11.5f, 15f)
            },
        )
        ToolIcon.ENTER -> Glyph(Path().apply { poly(19.5f, 5.5f, 19.5f, 13.5f, 5f, 13.5f); poly(9.5f, 9f, 5f, 13.5f, 9.5f, 18f) })
        ToolIcon.SEARCH -> Glyph(Path().apply { circle(10.5f, 10.5f, 6.5f); poly(15.3f, 15.3f, 20.5f, 20.5f) })
        ToolIcon.SEND -> Glyph(Path().apply { poly(3.5f, 4f, 21f, 12f, 3.5f, 20f, 6f, 12f, close = true); poly(6f, 12f, 12.5f, 12f) })
        ToolIcon.GO -> Glyph(Path().apply { poly(4.5f, 12f, 19.5f, 12f); poly(13f, 5.5f, 19.5f, 12f, 13f, 18.5f) })
        ToolIcon.NEXT -> Glyph(Path().apply { poly(3.5f, 12f, 16f, 12f); poly(10.5f, 6.5f, 16f, 12f, 10.5f, 17.5f); poly(20.5f, 6f, 20.5f, 18f) })
        ToolIcon.PREVIOUS -> Glyph(Path().apply { poly(20.5f, 12f, 8f, 12f); poly(13.5f, 6.5f, 8f, 12f, 13.5f, 17.5f); poly(3.5f, 6f, 3.5f, 18f) })
    }

    /** Material "translate" (Apache 2.0), the same mark the AI chips used before. */
    private const val TRANSLATE_PATH =
        "M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53L17,6L17,4h-7L10,2L8,2v2L1,4v1.99h11.17C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19l5,-5 3.11,3.11 0.76,-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12zM15.88,17l1.62,-4.33L19.12,17h-3.24z"
}

/** A [ToolIcon] as a drawable (compound drawables of the AI chips), [sizePx] square, in one colour. */
class ToolIconDrawable(private val icon: ToolIcon, private val color: Int, private val sizePx: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        ToolIcons.draw(canvas, icon, b.exactCenterX(), b.exactCenterY(), b.width().toFloat(), color, paint)
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
