package com.qalsmartkeyboard.app.creative.cards

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * Local image work of the card maker (T8.7, R-8): a background picked with the system photo picker is read here, on the
 * device, downscaled and turned into a new bitmap. Nothing is uploaded and nothing is logged.
 */
object CardImages {
    /** The longest side a picked background keeps (it only has to cover a 1080 x 1350 card). */
    const val MAX_PHOTO_SIDE = 1600
    private const val PHOTO_QUALITY = 88

    /** The power-of-two sample size that keeps the longest side of a [w] x [h] picture at or above [maxSide]. */
    fun sampleSize(w: Int, h: Int, maxSide: Int = MAX_PHOTO_SIDE): Int {
        var s = 1
        val longest = max(w, h)
        while (longest / (s * 2) >= maxSide) s *= 2
        return s
    }

    /**
     * Reads [uri] into a bitmap whose longest side is at most [maxSide], rotated upright by the picture's orientation tag.
     * The result is a new bitmap: the file's metadata (location included) is read for the orientation only and never
     * copied. Null when the content is not a readable picture or does not fit in memory.
     */
    fun loadPhoto(resolver: ContentResolver, uri: Uri, maxSide: Int = MAX_PHOTO_SIDE): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            decoded?.let { upright(it, orientation(resolver, uri), maxSide) }
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** The transform that turns a picture stored with the EXIF [orientation] tag upright (identity for "normal"). */
    fun orientationMatrix(orientation: Int): Matrix {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        return m
    }

    /** The EXIF orientation tag of [uri] (read for the orientation only; nothing else of the metadata is used). */
    fun orientation(resolver: ContentResolver, uri: Uri): Int = try {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    } catch (e: RuntimeException) {
        ExifInterface.ORIENTATION_NORMAL // a damaged metadata block is not a reason to refuse the picture
    }

    private fun upright(src: Bitmap, orientation: Int, maxSide: Int): Bitmap {
        val m = orientationMatrix(orientation)
        val longest = max(src.width, src.height)
        if (longest > maxSide) {
            val s = maxSide.toFloat() / longest
            m.postScale(s, s)
        }
        if (m.isIdentity) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    /** The picked photo as the JPEG bytes stored next to a saved card (a re-encoded copy, no metadata). */
    fun photoJpeg(photo: Bitmap): ByteArray = encode(photo, ExportFormat.JPEG, PHOTO_QUALITY)

    /** The bytes of [bitmap] in [format]: PNG lossless, JPEG at [ExportFormat.JPEG_QUALITY]. A fresh encode: no metadata. */
    fun encode(bitmap: Bitmap, format: ExportFormat, jpegQuality: Int = ExportFormat.JPEG_QUALITY): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        val f = if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        bitmap.compress(f, if (format == ExportFormat.PNG) 100 else jpegQuality, out)
        return out.toByteArray()
    }

    /** A small version of an image file for a list (decoded sampled, never the full card in memory). */
    fun thumbnail(file: File, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) {
            null
        } else {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) })
        }
    } catch (e: OutOfMemoryError) {
        null
    }
}

/**
 * Hands a card to the system share sheet (R-8: sharing is an explicit user action, and the file goes only where the user
 * sends it). The image is copied to `files/card_share/` (the only directory the FileProvider exposes; excluded from backups) under a neutral
 * name; the receiving app gets a one-time read grant.
 */
object CardShare {
    const val DIR = "card_share"

    fun authority(context: Context) = context.packageName + ".cards"

    /**
     * Writes [bytes] as the file to share and returns it, or null when it cannot be written. [dir] is one of the
     * directories `xml/cards_file_paths.xml` exposes ([DIR], or the annotator's `annotate_share`).
     */
    fun stage(context: Context, bytes: ByteArray, format: ExportFormat, name: String = "qal-card", dir: String = DIR): File? = try {
        val d = File(context.filesDir, dir)
        d.mkdirs()
        d.listFiles()?.forEach { it.delete() } // only the file being shared stays
        File(d, "$name.${format.extension}").also { it.writeBytes(bytes) }
    } catch (e: IOException) {
        null
    }

    /**
     * Deletes staged files older than [maxAgeMillis] in [dirs]. The share sheet gives no "done" signal, so a staged copy
     * cannot be deleted right after use. The studio's tools call this when they start, so a copy left by an earlier
     * share (older than [STALE_MILLIS], long read by the receiving app) does not stay on the device; the next share
     * replaces it anyway.
     */
    fun sweep(context: Context, dirs: List<String> = listOf(DIR), maxAgeMillis: Long = STALE_MILLIS, now: Long = System.currentTimeMillis()) {
        for (dir in dirs) {
            File(context.filesDir, dir).listFiles()?.forEach { if (now - it.lastModified() > maxAgeMillis) it.delete() }
        }
    }

    /** A staged copy older than this is no longer being shared. */
    const val STALE_MILLIS = 30L * 60 * 1000

    /** Opens the system share sheet for [file]; false when no app can take it. */
    fun share(context: Context, file: File, format: ExportFormat, title: String): Boolean = try {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    } catch (e: android.content.ActivityNotFoundException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
