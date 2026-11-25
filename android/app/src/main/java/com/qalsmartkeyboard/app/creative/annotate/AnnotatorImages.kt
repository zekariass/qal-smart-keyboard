package com.qalsmartkeyboard.app.creative.annotate

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import com.qalsmartkeyboard.app.creative.cards.CardImages
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** A picture read for annotating: the working bitmap and the size of the file it came from (before downscaling). */
class ImportedPicture(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)

/**
 * Reads the picture to annotate (T8.7, R-8): only on the device, from the one item the user picked; nothing is uploaded,
 * logged or copied. The file is decoded **sampled** so a huge photo never becomes a huge bitmap (at most [MAX_SIDE] on
 * the long side and [MAX_PIXELS] in all), turned upright by its EXIF orientation, and drawn onto a fresh opaque bitmap:
 * the metadata (location, camera, time) is read for the orientation only and nothing of it can reach an export.
 */
object AnnotatorImages {
    const val MAX_SIDE = 4096
    const val MAX_PIXELS = 8_000_000L

    /** The preview of the editor works on a copy at most this long (the export uses the full working bitmap). */
    const val PREVIEW_SIDE = 2048

    /** The smallest power-of-two sample size that brings a [w] x [h] picture within both limits. */
    fun sampleSize(w: Int, h: Int, maxSide: Int = MAX_SIDE, maxPixels: Long = MAX_PIXELS): Int {
        require(w > 0 && h > 0) { "size $w x $h" }
        var s = 1
        while (true) {
            val sw = (w + s - 1) / s
            val sh = (h + s - 1) / s
            if (sw <= maxSide && sh <= maxSide && sw.toLong() * sh <= maxPixels) return s
            s *= 2
        }
    }

    /** The size [w] x [h] scaled down (never up) to fit both limits, keeping the aspect ratio. */
    fun fit(w: Int, h: Int, maxSide: Int = MAX_SIDE, maxPixels: Long = MAX_PIXELS): Pair<Int, Int> {
        var k = min(1.0, maxSide.toDouble() / max(w, h))
        val area = w.toDouble() * h * k * k
        if (area > maxPixels) k *= kotlin.math.sqrt(maxPixels / area)
        return max(1, (w * k).toInt()) to max(1, (h * k).toInt())
    }

    /** Reads [uri] (a `content:` URI the user picked). Null when it is not a readable picture or does not fit in memory. */
    fun load(resolver: ContentResolver, uri: Uri): ImportedPicture? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null // only what a picker hands over, never a raw path
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            ImportedPicture(upright(decoded, CardImages.orientation(resolver, uri)), bounds.outWidth, bounds.outHeight)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * [src] turned upright by the EXIF [orientation], fitted within the limits and drawn onto a **new opaque** ARGB bitmap
     * (a white ground under any transparency): the working bitmap. [src] is recycled.
     */
    fun upright(src: Bitmap, orientation: Int): Bitmap {
        val m: Matrix = CardImages.orientationMatrix(orientation)
        val turned = android.graphics.RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()).also { m.mapRect(it) }
        val (w, h) = fit(Math.round(turned.width()), Math.round(turned.height()))
        val k = w / turned.width()
        m.postTranslate(-turned.left, -turned.top)
        m.postScale(k, h / turned.height())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (src !== out) src.recycle()
        return out
    }

    /** The editor's preview copy of [base] ([base] itself when it is small enough). */
    fun preview(base: Bitmap, maxSide: Int = PREVIEW_SIDE): Bitmap {
        val longest = max(base.width, base.height)
        if (longest <= maxSide) return base
        val k = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(base, max(1, (base.width * k).toInt()), max(1, (base.height * k).toInt()), true)
    }
}
