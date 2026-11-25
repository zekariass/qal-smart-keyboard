package com.qalsmartkeyboard.ime.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

/** What happened to an image the user asked to insert or share. */
enum class ShareOutcome {
    /** Committed into the host app's field with `commitContent`. */
    INSERTED,

    /** The system share sheet was opened. */
    SHARED,

    /** Neither path worked (the host refused, no activity handles the share). */
    FAILED,

    /** There was no image to send. */
    NO_IMAGE,
}

/**
 * Puts an image file in front of another app (T8.4: PNGs of Draw and the QR panel; T8.7: WebP stickers). Two ways, one
 * decision in [ImageSender]: [insert] is `InputConnection.commitContent` into the focused field when the field takes the
 * image's MIME type; [share] is the system share sheet. The file is the user's own, exported on their tap; nothing here
 * logs or keeps its URI.
 *
 * The three PNG methods are the original contract. The `mime` overloads (T8.7) default to "PNG only" so older
 * implementations and fakes keep working; [SystemImageShare] implements them for every type in [ImageMimes].
 */
interface ImageShare {
    /** True when the focused field accepts `image/png` through `commitContent`. */
    fun canInsert(): Boolean

    /** Commits [png] into the focused field; true when the host accepted it. */
    fun insert(png: File, description: String): Boolean

    /** Opens the system share sheet for [png]; true when it was launched. */
    fun share(png: File, title: String): Boolean

    /** True when the focused field accepts [mime] through `commitContent`. */
    fun canInsert(mime: String): Boolean = mime == PngContent.MIME && canInsert()

    /** Commits [file] (of type [mime]) into the focused field; true when the host accepted it. */
    fun insert(file: File, mime: String, description: String): Boolean = mime == PngContent.MIME && insert(file, description)

    /** Opens the system share sheet for [file] (of type [mime]); true when it was launched. */
    fun share(file: File, mime: String, title: String): Boolean = mime == PngContent.MIME && share(file, title)
}

/** The choice between inserting and sharing. */
object ImageSender {
    /**
     * Insert: `commitContent` when the field supports it, else (or when the host refuses it) the share sheet. A missing or
     * empty file sends nothing.
     */
    fun insertOrShare(s: ImageShare, png: File?, description: String, shareTitle: String = description): ShareOutcome {
        if (!hasImage(png)) return ShareOutcome.NO_IMAGE
        if (s.canInsert() && s.insert(png!!, description)) return ShareOutcome.INSERTED
        return if (s.share(png!!, shareTitle)) ShareOutcome.SHARED else ShareOutcome.FAILED
    }

    /** Share: always the share sheet. */
    fun shareOnly(s: ImageShare, png: File?, title: String): ShareOutcome {
        if (!hasImage(png)) return ShareOutcome.NO_IMAGE
        return if (s.share(png!!, title)) ShareOutcome.SHARED else ShareOutcome.FAILED
    }

    /** [insertOrShare] for an image of any supported type ([ImageMimes]), e.g. a WebP sticker. */
    fun insertOrShare(s: ImageShare, file: File?, mime: String, description: String, shareTitle: String = description): ShareOutcome {
        if (!hasImage(file)) return ShareOutcome.NO_IMAGE
        if (s.canInsert(mime) && s.insert(file!!, mime, description)) return ShareOutcome.INSERTED
        return if (s.share(file!!, mime, shareTitle)) ShareOutcome.SHARED else ShareOutcome.FAILED
    }

    /** [shareOnly] for an image of any supported type. */
    fun shareOnly(s: ImageShare, file: File?, mime: String, title: String): ShareOutcome {
        if (!hasImage(file)) return ShareOutcome.NO_IMAGE
        return if (s.share(file!!, mime, title)) ShareOutcome.SHARED else ShareOutcome.FAILED
    }

    private fun hasImage(f: File?) = f != null && f.isFile && f.length() > 0
}

/** Whether a field's declared content MIME types (`EditorInfoCompat.getContentMimeTypes`) take a PNG. */
object PngContent {
    const val MIME = "image/png"

    fun accepts(types: Array<String>?): Boolean = types != null && types.any { ClipDescription.compareMimeTypes(MIME, it) }
}

/** The image types the keyboard hands to other apps. */
object ImageMimes {
    const val PNG = PngContent.MIME
    const val WEBP = "image/webp"

    /** Whether a field's declared content MIME types take [mime]. */
    fun accepts(types: Array<String>?, mime: String): Boolean = types != null && types.any { ClipDescription.compareMimeTypes(mime, it) }
}

/**
 * The system implementation. [authority] is the FileProvider's (`<applicationId>.drawings`); [editorInfo] and [connection]
 * are read at the moment of the tap (the field may have changed since the panel opened).
 */
class SystemImageShare(
    private val context: Context,
    private val authority: String,
    private val editorInfo: () -> EditorInfo?,
    private val connection: () -> InputConnection?,
) : ImageShare {
    override fun canInsert(): Boolean = canInsert(ImageMimes.PNG)

    override fun insert(png: File, description: String): Boolean = insert(png, ImageMimes.PNG, description)

    override fun share(png: File, title: String): Boolean = share(png, ImageMimes.PNG, title)

    override fun canInsert(mime: String): Boolean {
        val info = editorInfo() ?: return false
        return connection() != null && ImageMimes.accepts(EditorInfoCompat.getContentMimeTypes(info), mime)
    }

    override fun insert(file: File, mime: String, description: String): Boolean {
        val info = editorInfo() ?: return false
        val ic = connection() ?: return false
        return try {
            val uri = uriFor(file)
            var flags = 0
            if (android.os.Build.VERSION.SDK_INT >= 25) {
                flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            } else {
                // Before API 25 the framework does not grant it: the host app is given read access by hand.
                info.packageName?.let { context.grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            val content = InputContentInfoCompat(uri, ClipDescription(description, arrayOf(mime)), null)
            InputConnectionCompat.commitContent(ic, info, content, flags, null)
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    override fun share(file: File, mime: String, title: String): Boolean {
        return try {
            val uri = uriFor(file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(context, authority, f)

    companion object {
        /** The FileProvider authority of an app: applicationId-based so two installs never collide. */
        fun authority(context: Context) = context.packageName + ".drawings"
    }
}
