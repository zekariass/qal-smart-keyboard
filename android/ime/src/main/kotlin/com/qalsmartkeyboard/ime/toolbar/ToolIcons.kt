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
 * The bar's shortcuts, the tools and AI tools grids, the clipboard panel, the AI panels and the special keys (shift, delete,
 * Enter actions, globe, emoji) all use these, so stroke weight and proportions match everywhere.
 */
enum class ToolIcon {
    TOOLS, BACK, CLOSE, TRANSLATE, CLIPBOARD, EMOJI, MIC, SETTINGS, THEME, LANGUAGES, EMAIL, WORD_ART, WRITING_MODE,

    /** ADR 0043: the key layouts: three rows of key outlines, offset like a keyboard's. */
    LAYOUT,

    // T8.3 productivity tools.
    CALCULATOR, UNITS, CURRENCY, CALENDAR, QR,

    // T8.4 Draw & Sketch: the tile and the panel's own controls.
    DRAW, PEN, HIGHLIGHTER, ERASER, UNDO, REDO, TRASH, PAPER, SAVE, IMAGE_INSERT, SHARE, GALLERY, PLUS,

    // T8.15 Draw phase 2: shapes, text, select / move, fill.
    SHAPES, TEXT, SELECT, FILL,
    LOCK, CHECK, GRAMMAR, REWRITE,

    /** T8.7 the sticker maker: a sticker with a peeled corner and a smile. */
    STICKER,

    /** The AI entry (ADR 0041): an "AI" wordmark in a rounded outline, drawn as paths so it never scales with the font. */
    AI,

    // The AI tools grid (ADR 0041): one icon per action.
    ASSIST, CHAT, SIMPLIFY, EXPLAIN, SUMMARIZE, REPLY, PROFESSIONAL, FRIENDLY, POLITE, CONCISE, APOLOGETIC, HONORIFIC,
    FORMAT, SHORTEN, EXPAND_TEXT, CLARIFY, ROMANTIC, CONFIDENT, FUNNY, PERSUASIVE,

    // The text field's action row (ADR 0038) when its labels do not fit.
    CUT, SELECT_ALL,

    // The translate panel (ADR 0037): swap languages, a list's caret, copy.
    SWAP, EXPAND, COPY,

    // Special keys.
    SHIFT, SHIFT_ON, SHIFT_LOCKED, BACKSPACE, ENTER, SEARCH, SEND, GO, NEXT, PREVIOUS,
}

object ToolIcons {
    /** Stroke width in grid units (of 24). */
    const val STROKE = 2f

    /** Stroke width (grid units) of the bold parts of a glyph: the letters of the AI wordmark. */
    const val BOLD_STROKE = 2.2f

    /**
     * One icon: [stroke] is outlined ([strokeUnits] wide), [fill] filled (small dots, the sparkle's star, a filled shift
     * arrow), [bold] outlined [BOLD_STROKE] wide (the AI wordmark's letters).
     */
    private class Glyph(val stroke: Path, val fill: Path? = null, val bold: Path? = null, val strokeUnits: Float = STROKE)

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
        paint.strokeWidth = g.strokeUnits * strokeScale
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(g.stroke, paint)
        g.bold?.let {
            paint.strokeWidth = BOLD_STROKE * strokeScale
            canvas.drawPath(it, paint)
        }
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
        // A subtle rounded outline (so it reads as a tool, not as text) around a bold "A" and a serifed "I".
        ToolIcon.AI -> Glyph(
            Path().apply { rect(2.5f, 4f, 21.5f, 20f, 4.5f) },
            bold = Path().apply {
                poly(5.5f, 16f, 8.5f, 8f, 11.5f, 16f)
                poly(6.7f, 13.2f, 10.3f, 13.2f)
                poly(16.8f, 8f, 16.8f, 16f)
                poly(15.3f, 8f, 18.3f, 8f)
                poly(15.3f, 16f, 18.3f, 16f)
            },
            strokeUnits = 1.6f,
        )
        // A wand with sparkles: AI Assist, the free-form panel.
        ToolIcon.ASSIST -> Glyph(
            Path().apply { poly(3.5f, 20.5f, 14f, 10f) },
            Path().apply { sparkle(17.5f, 6.5f, 4f); sparkle(8f, 5f, 2.2f); sparkle(19.5f, 16f, 2.2f) },
        )
        // A speech bubble with a sparkle: Ask AI, the in-keyboard chat (T8.5).
        ToolIcon.CHAT -> Glyph(
            Path().apply { rect(3f, 3.5f, 21f, 16f, 3.5f); poly(8f, 16f, 8f, 20.5f, 12.5f, 16f) },
            Path().apply { sparkle(12f, 9.75f, 3.4f) },
        )
        // A tangled line, an arrow down, a straight line.
        ToolIcon.SIMPLIFY -> Glyph(
            Path().apply {
                poly(3f, 6.5f, 6f, 3.5f, 9f, 6.5f, 12f, 3.5f, 15f, 6.5f, 18f, 3.5f, 21f, 6.5f)
                poly(12f, 9.5f, 12f, 16f)
                poly(8.8f, 12.8f, 12f, 16f, 15.2f, 12.8f)
                poly(3f, 20f, 21f, 20f)
            },
        )
        // A light bulb.
        ToolIcon.EXPLAIN -> Glyph(
            Path().apply {
                addArc(RectF(5.5f, 2.5f, 18.5f, 15.5f), 135f, 270f)
                poly(7.4f, 13.6f, 9f, 16.8f, 15f, 16.8f, 16.6f, 13.6f)
                poly(9.5f, 20.5f, 14.5f, 20.5f)
            },
        )
        // Lines that narrow: long text condensed.
        ToolIcon.SUMMARIZE -> Glyph(Path().apply { poly(3.5f, 5.5f, 20.5f, 5.5f); poly(6f, 12f, 18f, 12f); poly(9f, 18.5f, 15f, 18.5f) })
        // The reply arrow.
        ToolIcon.REPLY -> Glyph(
            Path().apply {
                poly(9.5f, 5f, 3.5f, 11f, 9.5f, 17f)
                moveTo(3.5f, 11f)
                lineTo(13f, 11f)
                quadTo(20.5f, 11f, 20.5f, 19.5f)
            },
        )
        // A briefcase.
        ToolIcon.PROFESSIONAL -> Glyph(
            Path().apply {
                rect(3f, 7.5f, 21f, 20f, 2.5f)
                poly(9f, 7.5f, 9f, 4.5f, 15f, 4.5f, 15f, 7.5f)
                poly(3f, 13f, 21f, 13f)
            },
        )
        // A face with smiling eyes.
        ToolIcon.FRIENDLY -> Glyph(
            Path().apply {
                circle(12f, 12f, 9f)
                addArc(RectF(7f, 8.5f, 11f, 12f), 200f, 140f)
                addArc(RectF(13f, 8.5f, 17f, 12f), 200f, 140f)
                addArc(RectF(7.5f, 9f, 16.5f, 16.5f), 25f, 130f)
            },
        )
        // A speech bubble with a heart: kind words.
        ToolIcon.POLITE -> Glyph(
            Path().apply {
                moveTo(6f, 3.5f)
                lineTo(18f, 3.5f)
                quadTo(21f, 3.5f, 21f, 6.5f)
                lineTo(21f, 14f)
                quadTo(21f, 17f, 18f, 17f)
                lineTo(11f, 17f)
                lineTo(6.5f, 21f)
                lineTo(6.5f, 17f)
                lineTo(6f, 17f)
                quadTo(3f, 17f, 3f, 14f)
                lineTo(3f, 6.5f)
                quadTo(3f, 3.5f, 6f, 3.5f)
                close()
            },
            Path().apply {
                moveTo(12f, 14f)
                cubicTo(8f, 11.3f, 8f, 7.2f, 10.2f, 7.2f)
                cubicTo(11.2f, 7.2f, 11.8f, 7.9f, 12f, 8.6f)
                cubicTo(12.2f, 7.9f, 12.8f, 7.2f, 13.8f, 7.2f)
                cubicTo(16f, 7.2f, 16f, 11.3f, 12f, 14f)
                close()
            },
        )
        // Two arrows pressing on a line.
        ToolIcon.CONCISE -> Glyph(
            Path().apply {
                poly(12f, 2.5f, 12f, 9f); poly(8.8f, 5.8f, 12f, 9f, 15.2f, 5.8f)
                poly(12f, 21.5f, 12f, 15f); poly(8.8f, 18.2f, 12f, 15f, 15.2f, 18.2f)
                poly(3.5f, 12f, 20.5f, 12f)
            },
        )
        // A face with worried brows: sorry.
        ToolIcon.APOLOGETIC -> Glyph(
            Path().apply {
                circle(12f, 12f, 9f)
                poly(7.3f, 9.3f, 10f, 8f)
                poly(16.7f, 9.3f, 14f, 8f)
                addArc(RectF(8.5f, 15f, 15.5f, 20f), 205f, 130f)
            },
            Path().apply { circle(9f, 11.6f, 1.2f); circle(15f, 11.6f, 1.2f) },
        )
        // A crown: respectful forms.
        ToolIcon.HONORIFIC -> Glyph(
            Path().apply {
                poly(3.5f, 18f, 3f, 7.5f, 8f, 12f, 12f, 5f, 16f, 12f, 21f, 7.5f, 20.5f, 18f, close = true)
                poly(4f, 21f, 20f, 21f)
            },
        )
        // A bulleted list: three dots, three lines.
        ToolIcon.FORMAT -> Glyph(
            Path().apply { poly(9.5f, 6f, 21f, 6f); poly(9.5f, 12f, 21f, 12f); poly(9.5f, 18f, 21f, 18f) },
            Path().apply { circle(4.5f, 6f, 1.3f); circle(4.5f, 12f, 1.3f); circle(4.5f, 18f, 1.3f) },
        )
        // A line squeezed shorter by inward arrows.
        ToolIcon.SHORTEN -> Glyph(
            Path().apply {
                poly(3.5f, 5.5f, 20.5f, 5.5f)
                poly(2.5f, 12f, 9f, 12f); poly(5.8f, 9.5f, 9f, 12f, 5.8f, 14.5f)
                poly(21.5f, 12f, 15f, 12f); poly(18.2f, 9.5f, 15f, 12f, 18.2f, 14.5f)
                poly(7f, 18.5f, 17f, 18.5f)
            },
        )
        // A short line stretched longer by outward arrows.
        ToolIcon.EXPAND_TEXT -> Glyph(
            Path().apply {
                poly(7f, 5.5f, 17f, 5.5f)
                poly(9f, 12f, 2.5f, 12f); poly(5.7f, 9.5f, 2.5f, 12f, 5.7f, 14.5f)
                poly(15f, 12f, 21.5f, 12f); poly(18.3f, 9.5f, 21.5f, 12f, 18.3f, 14.5f)
                poly(3.5f, 18.5f, 20.5f, 18.5f)
            },
        )
        // Lines and a sparkle: clearer text.
        ToolIcon.CLARIFY -> Glyph(
            Path().apply { poly(3.5f, 6.5f, 13f, 6.5f); poly(3.5f, 12f, 10f, 12f); poly(3.5f, 17.5f, 8f, 17.5f) },
            Path().apply { sparkle(16f, 14.5f, 5.5f) },
        )
        // A heart.
        ToolIcon.ROMANTIC -> Glyph(
            Path().apply {
                moveTo(12f, 20f)
                cubicTo(5f, 15.5f, 3f, 8.8f, 7.5f, 6.6f)
                cubicTo(9.7f, 5.6f, 11.5f, 6.9f, 12f, 8.6f)
                cubicTo(12.5f, 6.9f, 14.3f, 5.6f, 16.5f, 6.6f)
                cubicTo(21f, 8.8f, 19f, 15.5f, 12f, 20f)
                close()
            },
        )
        // A shield with a check: assured wording.
        ToolIcon.CONFIDENT -> Glyph(
            Path().apply {
                moveTo(12f, 2.8f)
                lineTo(19.5f, 5.8f)
                lineTo(19.5f, 11f)
                cubicTo(19.5f, 16.5f, 16f, 20f, 12f, 21.3f)
                cubicTo(8f, 20f, 4.5f, 16.5f, 4.5f, 11f)
                lineTo(4.5f, 5.8f)
                close()
                poly(8f, 11.8f, 10.8f, 14.6f, 16.5f, 8.8f)
            },
        )
        // A laughing face: squinted eyes, a wide open mouth.
        ToolIcon.FUNNY -> Glyph(
            Path().apply {
                circle(12f, 12f, 9f)
                poly(6.8f, 8.4f, 9.3f, 9.6f, 6.8f, 10.8f)
                poly(17.2f, 8.4f, 14.7f, 9.6f, 17.2f, 10.8f)
                addArc(RectF(7.3f, 12.3f, 16.7f, 19f), 18f, 144f)
            },
        )
        // A megaphone.
        ToolIcon.PERSUASIVE -> Glyph(
            Path().apply {
                moveTo(3.5f, 10f)
                lineTo(3.5f, 14.5f)
                lineTo(7f, 14.5f)
                lineTo(15.5f, 20f)
                lineTo(15.5f, 4.5f)
                lineTo(7f, 10f)
                close()
                poly(7f, 10f, 7f, 14.5f)
                addArc(RectF(16.2f, 8.2f, 20.8f, 16.3f), -55f, 110f)
            },
        )
        ToolIcon.CUT -> Glyph(
            Path().apply {
                circle(6.5f, 17.5f, 3f)
                circle(17.5f, 17.5f, 3f)
                poly(8.6f, 15.3f, 17.5f, 3.5f)
                poly(15.4f, 15.3f, 6.5f, 3.5f)
            },
        )
        // Dashed corners around a square.
        ToolIcon.SELECT_ALL -> Glyph(
            Path().apply {
                poly(3.5f, 7.5f, 3.5f, 3.5f, 7.5f, 3.5f)
                poly(16.5f, 3.5f, 20.5f, 3.5f, 20.5f, 7.5f)
                poly(20.5f, 16.5f, 20.5f, 20.5f, 16.5f, 20.5f)
                poly(7.5f, 20.5f, 3.5f, 20.5f, 3.5f, 16.5f)
                poly(11f, 3.5f, 13f, 3.5f); poly(11f, 20.5f, 13f, 20.5f)
                poly(3.5f, 11f, 3.5f, 13f); poly(20.5f, 11f, 20.5f, 13f)
                rect(8f, 8f, 16f, 16f, 1.5f)
            },
        )
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
        ToolIcon.LAYOUT -> Glyph(
            Path().apply { poly(7f, 20f, 17f, 20f) },
            // Filled keys (outlines would merge at icon size): three, then two offset like a keyboard's rows.
            Path().apply {
                for (x in floatArrayOf(2.75f, 9.75f, 16.75f)) rect(x, 4.5f, x + 4.5f, 9.5f, 1.2f)
                for (x in floatArrayOf(6.25f, 13.25f)) rect(x, 11.5f, x + 4.5f, 16.5f, 1.2f)
            },
        )
        ToolIcon.CALCULATOR -> Glyph(
            Path().apply {
                rect(4.5f, 2.5f, 19.5f, 21.5f, 2.5f)
                poly(8f, 7.5f, 16f, 7.5f)
            },
            Path().apply {
                for (x in floatArrayOf(8.5f, 12f, 15.5f)) for (y in floatArrayOf(12f, 15.2f, 18.4f)) circle(x, y, 1f)
            },
        )
        // A ruler with ticks.
        ToolIcon.UNITS -> Glyph(
            Path().apply {
                rect(2.5f, 7.5f, 21.5f, 16.5f, 1.8f)
                poly(7f, 7.5f, 7f, 12f); poly(11f, 7.5f, 11f, 10.5f); poly(15f, 7.5f, 15f, 12f); poly(19f, 7.5f, 19f, 10.5f)
            },
        )
        // A coin with an S stroke.
        ToolIcon.CURRENCY -> Glyph(
            Path().apply {
                circle(12f, 12f, 9f)
                moveTo(15f, 9f)
                cubicTo(15f, 7.2f, 9f, 7.2f, 9f, 10f)
                cubicTo(9f, 12.6f, 15f, 11.4f, 15f, 14f)
                cubicTo(15f, 16.8f, 9f, 16.8f, 9f, 15f)
                poly(12f, 5.8f, 12f, 7.4f); poly(12f, 16.6f, 12f, 18.2f)
            },
        )
        ToolIcon.CALENDAR -> Glyph(
            Path().apply {
                rect(3.5f, 5f, 20.5f, 20.5f, 2.5f)
                poly(3.5f, 10f, 20.5f, 10f)
                poly(8f, 3f, 8f, 7f); poly(16f, 3f, 16f, 7f)
            },
            Path().apply {
                for (x in floatArrayOf(8f, 12f, 16f)) circle(x, 14f, 0.95f)
                for (x in floatArrayOf(8f, 12f)) circle(x, 17.3f, 0.95f)
            },
        )
        // Three finder squares and a few data modules.
        ToolIcon.QR -> Glyph(
            Path().apply {
                rect(3.5f, 3.5f, 10f, 10f, 1.2f); rect(14f, 3.5f, 20.5f, 10f, 1.2f); rect(3.5f, 14f, 10f, 20.5f, 1.2f)
            },
            Path().apply {
                rect(5.5f, 5.5f, 8f, 8f, 0.3f); rect(16f, 5.5f, 18.5f, 8f, 0.3f); rect(5.5f, 16f, 8f, 18.5f, 0.3f)
                rect(14f, 14f, 16.2f, 16.2f, 0.3f); rect(18.3f, 14f, 20.5f, 16.2f, 0.3f)
                rect(14f, 18.3f, 16.2f, 20.5f, 0.3f); rect(18.3f, 18.3f, 20.5f, 20.5f, 0.3f)
            },
        )
        // T8.4 Draw & Sketch. The tile: a pencil over a squiggle.
        ToolIcon.DRAW -> Glyph(
            Path().apply {
                poly(6f, 16f, 7f, 12f, 16f, 3.5f, 19.5f, 7f, 10.5f, 16f, close = true)
                poly(14f, 5.5f, 17.5f, 9f)
                moveTo(3f, 21f)
                cubicTo(6f, 18.5f, 8f, 23.5f, 11.5f, 20.5f)
                cubicTo(14.5f, 18f, 16.5f, 22.5f, 21f, 20f)
            },
        )
        ToolIcon.PEN -> Glyph(
            Path().apply {
                poly(4f, 20f, 4f, 16.5f, 15.5f, 5f, 19f, 8.5f, 7.5f, 20f, close = true)
                poly(13f, 7.5f, 16.5f, 11f)
            },
        )
        // A chisel marker with a bar of colour under it.
        ToolIcon.HIGHLIGHTER -> Glyph(
            Path().apply {
                poly(10f, 3.5f, 20.5f, 14f, 15f, 19.5f, 4.5f, 9f, close = true)
                poly(7.3f, 11.7f, 12.3f, 16.7f)
                poly(3.5f, 21.5f, 12f, 21.5f)
            },
        )
        ToolIcon.ERASER -> Glyph(
            Path().apply {
                poly(8.5f, 20f, 3.5f, 15f, 14f, 4.5f, 20.5f, 11f, 11.5f, 20f, close = true)
                poly(8.5f, 20f, 21f, 20f)
                poly(8.2f, 9.8f, 15.2f, 16.8f)
            },
        )
        ToolIcon.UNDO -> Glyph(
            Path().apply {
                poly(9f, 5f, 4.5f, 9.5f, 9f, 14f)
                moveTo(4.5f, 9.5f)
                lineTo(14.5f, 9.5f)
                cubicTo(18.5f, 9.5f, 20f, 12f, 20f, 14.5f)
                cubicTo(20f, 17.5f, 18f, 19.5f, 14.5f, 19.5f)
                lineTo(10f, 19.5f)
            },
        )
        ToolIcon.REDO -> Glyph(
            Path().apply {
                poly(15f, 5f, 19.5f, 9.5f, 15f, 14f)
                moveTo(19.5f, 9.5f)
                lineTo(9.5f, 9.5f)
                cubicTo(5.5f, 9.5f, 4f, 12f, 4f, 14.5f)
                cubicTo(4f, 17.5f, 6f, 19.5f, 9.5f, 19.5f)
                lineTo(14f, 19.5f)
            },
        )
        ToolIcon.TRASH -> Glyph(
            Path().apply {
                poly(4.5f, 7f, 19.5f, 7f)
                poly(9.5f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 14.5f, 7f)
                rect(6.5f, 7f, 17.5f, 20.5f, 2f)
                poly(10f, 11f, 10f, 16.5f); poly(14f, 11f, 14f, 16.5f)
            },
        )
        // A page with a checkerboard: the canvas background.
        ToolIcon.PAPER -> Glyph(
            Path().apply { rect(4.5f, 3.5f, 19.5f, 20.5f, 2f) },
            Path().apply {
                rect(8f, 7f, 12f, 11f, 0.3f); rect(12f, 11f, 16f, 15f, 0.3f); rect(8f, 15f, 12f, 19f, 0.3f)
            },
        )
        ToolIcon.SAVE -> Glyph(
            Path().apply {
                poly(4f, 15f, 4f, 20f, 20f, 20f, 20f, 15f)
                poly(12f, 4f, 12f, 15f)
                poly(7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f)
            },
        )
        ToolIcon.IMAGE_INSERT -> Glyph(
            Path().apply {
                rect(3.5f, 5f, 20.5f, 19f, 2.5f)
                poly(5f, 17f, 10f, 11.5f, 14f, 15.5f, 16.5f, 13f, 19.5f, 17f)
            },
            Path().apply { circle(8f, 9.2f, 1.3f) },
        )
        ToolIcon.SHARE -> Glyph(
            Path().apply {
                circle(6f, 12f, 2.5f); circle(18f, 6f, 2.5f); circle(18f, 18f, 2.5f)
                poly(8.2f, 10.8f, 15.8f, 7.2f); poly(8.2f, 13.2f, 15.8f, 16.8f)
            },
        )
        ToolIcon.GALLERY -> Glyph(
            Path().apply {
                rect(4f, 4f, 10.5f, 10.5f, 1.5f); rect(13.5f, 4f, 20f, 10.5f, 1.5f)
                rect(4f, 13.5f, 10.5f, 20f, 1.5f); rect(13.5f, 13.5f, 20f, 20f, 1.5f)
            },
        )
        ToolIcon.PLUS -> Glyph(Path().apply { poly(12f, 5f, 12f, 19f); poly(5f, 12f, 19f, 12f) })
        // A square and a circle: the shapes.
        ToolIcon.SHAPES -> Glyph(
            Path().apply {
                rect(3.5f, 9f, 13f, 19.5f, 1.5f)
                circle(16f, 8f, 4.5f)
            },
        )
        // A "T": the text tool.
        ToolIcon.TEXT -> Glyph(Path().apply { poly(5f, 6f, 19f, 6f); poly(12f, 6f, 12f, 19f); poly(8.5f, 19f, 15.5f, 19f) })
        // A pointer arrow: select and move.
        ToolIcon.SELECT -> Glyph(
            Path().apply { poly(6f, 3.5f, 6f, 18f, 9.8f, 14.2f, 12.8f, 20.5f, 15.2f, 19.4f, 12.3f, 13.3f, 17.5f, 13.3f, close = true) },
        )
        // A square with hatching: a filled shape.
        ToolIcon.FILL -> Glyph(
            Path().apply { rect(4.5f, 4.5f, 19.5f, 19.5f, 2f) },
            Path().apply { poly(8f, 16f, 16f, 8f); poly(8f, 11.5f, 11.5f, 8f); poly(12.5f, 16f, 16f, 12.5f) },
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
        // T8.7 Sticker maker. A square sticker with a peeled bottom-right corner, a smile and two eyes.
        ToolIcon.STICKER -> Glyph(
            Path().apply {
                moveTo(7f, 3.5f)
                lineTo(17f, 3.5f)
                cubicTo(19f, 3.5f, 20.5f, 5f, 20.5f, 7f)
                lineTo(20.5f, 14f)
                lineTo(14f, 20.5f)
                lineTo(7f, 20.5f)
                cubicTo(5f, 20.5f, 3.5f, 19f, 3.5f, 17f)
                lineTo(3.5f, 7f)
                cubicTo(3.5f, 5f, 5f, 3.5f, 7f, 3.5f)
                close()
                moveTo(20.5f, 14f)
                lineTo(16.5f, 14f)
                cubicTo(15.1f, 14f, 14f, 15.1f, 14f, 16.5f)
                lineTo(14f, 20.5f)
                moveTo(8.2f, 13f)
                cubicTo(9.4f, 15.2f, 11.6f, 15.2f, 12.8f, 13f)
            },
            Path().apply { circle(9f, 9.2f, 1.1f); circle(15f, 9.2f, 1.1f) },
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
