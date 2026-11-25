package com.qalsmartkeyboard.app.creative.annotate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.max
import kotlin.math.min

/**
 * The editor's view of the picture (T8.7): fitted into the container, then zoomed ([zoom], 1 = fit, up to [MAX_ZOOM])
 * and panned. Only the view changes; annotations stay in image pixels. Plain maths over snapshot state, JVM-tested.
 */
class AnnotViewport(var imageWidth: Int = 1, var imageHeight: Int = 1) {
    var containerWidth: Float by mutableFloatStateOf(1f)
    var containerHeight: Float by mutableFloatStateOf(1f)
    var zoom: Float by mutableFloatStateOf(1f)
        private set
    var panX: Float by mutableFloatStateOf(0f)
        private set
    var panY: Float by mutableFloatStateOf(0f)
        private set

    private val fit: Float get() = min(containerWidth / max(1, imageWidth), containerHeight / max(1, imageHeight))

    /** Screen pixels per image pixel. */
    val scale: Float get() = fit * zoom

    val offsetX: Float get() = (containerWidth - imageWidth * scale) / 2 + panX
    val offsetY: Float get() = (containerHeight - imageHeight * scale) / 2 + panY

    fun toImage(x: Float, y: Float) = Pt((x - offsetX) / scale, (y - offsetY) / scale)

    fun toScreenX(ix: Float) = offsetX + ix * scale
    fun toScreenY(iy: Float) = offsetY + iy * scale

    fun reset(w: Int = imageWidth, h: Int = imageHeight) {
        imageWidth = w
        imageHeight = h
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    /** Zooms by [factor] around the screen point ([cx], [cy]) (it stays over the same image point) and pans by ([dx], [dy]). */
    fun transform(factor: Float, cx: Float, cy: Float, dx: Float, dy: Float) {
        val anchor = toImage(cx, cy)
        zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        // Keep the anchor under the fingers: offset' = c - anchor * scale'.
        panX = cx - anchor.x * scale - (containerWidth - imageWidth * scale) / 2 + dx
        panY = cy - anchor.y * scale - (containerHeight - imageHeight * scale) / 2 + dy
        clampPan()
    }

    fun panBy(dx: Float, dy: Float) {
        panX += dx
        panY += dy
        clampPan()
    }

    /** The picture can be moved until its edge reaches the middle of the container, never out of sight. */
    private fun clampPan() {
        val limX = max(0f, (imageWidth * scale - containerWidth) / 2) + containerWidth / 4
        val limY = max(0f, (imageHeight * scale - containerHeight) / 2) + containerHeight / 4
        panX = if (zoom == 1f) 0f else panX.coerceIn(-limX, limX)
        panY = if (zoom == 1f) 0f else panY.coerceIn(-limY, limY)
    }

    companion object {
        const val MAX_ZOOM = 8f
    }
}
