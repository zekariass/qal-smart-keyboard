package com.qalsmartkeyboard.app.themes

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.ime.theme.KeyboardTheme

private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

/**
 * A miniature keyboard drawn from the theme's own tokens (background gradient, key, special and accent fills,
 * borders, shadows, corner radius, labels, candidate strip), so the preview shows what the keyboard will look
 * like. Decorative in the cards (they carry the description); the large preview passes its own [description].
 */
@Composable
fun KeyboardThemePreview(theme: KeyboardTheme, modifier: Modifier = Modifier, showStrip: Boolean = true, description: String? = null) {
    Canvas(modifier.aspectRatio(if (showStrip) 1.55f else 1.9f).clearAndSetSemantics { if (description != null) contentDescription = description }) {
        drawKeyboard(theme, showStrip)
    }
}

private fun DrawScope.drawKeyboard(theme: KeyboardTheme, showStrip: Boolean) {
    val w = size.width
    val h = size.height
    val bg = if (theme.isGradient) Brush.verticalGradient(listOf(Color(theme.bgTop), Color(theme.bgBottom))) else Brush.verticalGradient(listOf(Color(theme.bgTop), Color(theme.bgTop)))
    drawRoundRect(bg, size = size, cornerRadius = CornerRadius(w * 0.03f))

    val pad = w * 0.025f
    val stripH = if (showStrip) h * 0.15f else 0f
    val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    if (showStrip) {
        val words = listOf("selam", "ሰላም", "hello")
        labels.color = theme.stripText
        labels.textSize = stripH * 0.5f
        words.forEachIndexed { i, word ->
            val cx = w * (0.2f + 0.3f * i)
            drawContext.canvas.nativeCanvas.drawText(word, cx, stripH * 0.68f, labels)
            if (i > 0) drawLine(Color(theme.stripDivider), Offset(w * (0.05f + 0.3f * i), stripH * 0.28f), Offset(w * (0.05f + 0.3f * i), stripH * 0.8f), strokeWidth = w * 0.004f)
        }
    }
    val top = stripH + pad * 0.4f
    val rowCount = 4
    val rowH = (h - top - pad) / rowCount
    val gap = w * 0.012f
    val radius = (theme.cornerRadiusDp / 6f * rowH * 0.18f).coerceAtMost(rowH * 0.4f)
    val borderW = if (theme.hasBorder) (w * 0.004f).coerceAtLeast(1f) else 0f
    val shadowDy = if (theme.hasShadow) (rowH * 0.06f).coerceAtLeast(1f) else 0f

    fun key(x: Float, y: Float, kw: Float, kh: Float, fill: Int, label: String?, labelColor: Int) {
        if (theme.hasShadow) drawRoundRect(Color(theme.shadow), Offset(x, y + shadowDy), Size(kw, kh), CornerRadius(radius))
        drawRoundRect(Color(fill), Offset(x, y), Size(kw, kh), CornerRadius(radius))
        if (theme.hasBorder) drawRoundRect(Color(theme.border), Offset(x + borderW / 2, y + borderW / 2), Size(kw - borderW, kh - borderW), CornerRadius(radius), style = Stroke(borderW))
        if (label != null) {
            labels.color = labelColor
            labels.textSize = kh * 0.46f
            drawContext.canvas.nativeCanvas.drawText(label, x + kw / 2, y + kh / 2 - (labels.descent() + labels.ascent()) / 2, labels)
        }
    }

    val inner = w - pad * 2
    // Rows 1-3: letters, the last row with the special keys at the sides.
    for (r in 0 until 3) {
        val letters = rows[r]
        val special = r == 2
        val units = letters.length + if (special) 2.8f else 0f
        val unit = (inner - gap * (letters.length + (if (special) 2 else 0) - 1)) / units
        var x = pad + if (r == 1) unit * 0.25f else 0f
        val y = top + r * rowH + gap / 2
        val kh = rowH - gap
        if (special) {
            key(x, y, unit * 1.4f, kh, theme.special, "⇧", theme.specialText)
            x += unit * 1.4f + gap
        }
        for (ch in letters) {
            key(x, y, unit, kh, theme.key, ch.toString(), theme.keyText)
            x += unit + gap
        }
        if (special) key(x, y, unit * 1.4f, kh, theme.special, "⌫", theme.specialText)
    }
    // Bottom row: mode key, comma, space, period, enter (accent).
    val y = top + 3 * rowH + gap / 2
    val kh = rowH - gap
    val unit = (inner - gap * 4) / 10.4f
    var x = pad
    key(x, y, unit * 1.5f, kh, theme.special, "?123", theme.specialText)
    x += unit * 1.5f + gap
    key(x, y, unit, kh, theme.special, ",", theme.specialText)
    x += unit + gap
    key(x, y, unit * 4.9f, kh, theme.key, "አማ", theme.hintText)
    x += unit * 4.9f + gap
    key(x, y, unit, kh, theme.special, ".", theme.specialText)
    x += unit + gap
    key(x, y, unit * 2f, kh, theme.accent, "⏎", theme.onAccent)
}

/** A small padlock for the Premium badge, drawn so that it needs no icon library. */
@Composable
fun LockIcon(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Box(modifier.size(size).clearAndSetSemantics { }) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val bodyTop = this.size.height * 0.45f
            drawRoundRect(color, Offset(w * 0.12f, bodyTop), Size(w * 0.76f, this.size.height * 0.5f), CornerRadius(w * 0.12f))
            drawArc(
                color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(w * 0.27f, this.size.height * 0.08f), size = Size(w * 0.46f, bodyTop - this.size.height * 0.08f + w * 0.1f),
                style = Stroke(w * 0.12f),
            )
        }
    }
}
