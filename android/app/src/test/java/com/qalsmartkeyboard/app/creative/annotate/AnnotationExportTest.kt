package com.qalsmartkeyboard.app.creative.annotate

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import com.qalsmartkeyboard.app.creative.cards.CardImages
import com.qalsmartkeyboard.app.creative.cards.ExportFormat
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.Random
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The export pipeline (T8.7) with real pixels (NATIVE graphics): redactions burnt into the pixels before anything is
 * drawn, checked by decoding the exported file; the export calls exactly the pure [Redactor]; a crop really discards the
 * pixels outside it; the export is a fresh encode with no metadata of the picked file (EXIF, GPS); the imported picture
 * is decoded within the memory limits and turned upright; the picture itself is never changed by an export.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnnotationExportTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var dir: File

    private val ink = 0xFF101010.toInt()
    private val paper = 0xFFF0F0F0.toInt()
    private val red = 0xFFE01818.toInt()
    private val cyan = 0xFF18E0E0.toInt()
    private val palette = setOf(ink, paper, red, cyan)
    private val marker = 0xFFFF00FF.toInt() // a colour that only exists outside the crop

    @Before fun setUp() {
        dir = Files.createTempDirectory("annot-export").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    /** A screenshot-like picture of text: strokes and red/cyan checks, as in RedactorTest. */
    private fun textPicture(w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val line = y / 12
            val rnd = Random(line * 31L)
            var x = 0
            while (x < w) {
                val sw = 1 + rnd.nextInt(3)
                val gap = 1 + rnd.nextInt(3)
                for (i in x until minOf(w, x + sw)) px[y * w + i] = ink
                for (i in minOf(w, x + sw) until minOf(w, x + sw + gap)) px[y * w + i] = paper
                x += sw + gap
            }
            if (line % 4 == 3) for (xx in 0 until w) px[y * w + xx] = if (((xx / 2) + (y / 2)) % 2 == 0) red else cyan
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

    private fun std(px: IntArray, w: Int, e: PixelRect): Double {
        var worst = 0.0
        for (s in intArrayOf(0, 8, 16)) {
            var sum = 0.0
            var sq = 0.0
            var n = 0
            for (y in e.top until e.bottom) for (x in e.left until e.right) {
                val v = (px[y * w + x] shr s and 0xFF).toDouble()
                sum += v; sq += v * v; n++
            }
            val m = sum / n
            worst = max(worst, sqrt(max(0.0, sq / n - m * m)))
        }
        return worst
    }

    private fun redaction(id: Int, mode: RedactMode, l: Float, t: Float, r: Float, b: Float) =
        Annotation.Redaction(id, mode, RedactArea.Rect(Box(l, t, r, b)))

    // ---- Redaction in the exported file -----------------------------------------------------------------------------

    @Test fun theExportedPngHoldsNoOriginalPixelOfARedactedArea() {
        val base = textPicture(600, 400)
        val doc = AnnotDoc(600, 400)
            .with(redaction(1, RedactMode.BLUR, 40f, 40f, 300f, 70f))
            .with(redaction(2, RedactMode.PIXELATE, 320f, 100f, 580f, 200f))
            .with(redaction(3, RedactMode.SOLID, 50f, 250f, 250f, 290f))
            .with(redaction(4, RedactMode.BLUR, -40f, 330f, 120f, 460f)) // partly outside
        val out = decode(AnnotationExporter().export(base, doc, ExportFormat.PNG))
        assertEquals(600 to 400, out.width to out.height)
        val px = pixels(out)
        val orig = pixels(base)
        for (r in doc.redactions) {
            val e = Redactor.extent(r, 600, 400)
            for (y in e.top until e.bottom) for (x in e.left until e.right) {
                val v = px[y * 600 + x]
                assertFalse("${r.mode} kept an original colour at $x,$y", v in palette)
            }
            if (r.mode != RedactMode.SOLID) {
                assertTrue("${r.mode}: ${std(px, 600, e)} vs ${std(orig, 600, e)}", std(px, 600, e) <= 0.2 * std(orig, 600, e))
            } else {
                for (y in e.top until e.bottom) for (x in e.left until e.right) assertEquals(Color.BLACK, px[y * 600 + x])
            }
        }
        // Outside every redaction the picture is exactly the original (PNG is lossless).
        assertEquals(orig[5 * 600 + 590], px[5 * 600 + 590])
        // And the imported picture itself was not touched.
        assertTrue(orig.contentEquals(pixels(base)))
    }

    @Test fun theExportCallsExactlyThePureRedactorOnThePixels() {
        val base = textPicture(400, 300)
        val r = redaction(1, RedactMode.PIXELATE, 60f, 60f, 260f, 120f)
        val doc = AnnotDoc(400, 300).with(r)
        val exported = pixels(decode(AnnotationExporter(random = { Random(2024) }).export(base, doc, ExportFormat.PNG)))
        val expected = pixels(base)
        Redactor.redact(expected, 400, 300, r, Random(2024))
        assertTrue("the exported pixels are the Redactor's, bit for bit", expected.contentEquals(exported))
    }

    @Test fun marksAreDrawnAfterTheRedactionOverItsPixels() {
        val base = textPicture(400, 300)
        val green = 0xFF00C853.toInt()
        val doc = AnnotDoc(400, 300)
            .with(redaction(1, RedactMode.BLUR, 50f, 50f, 350f, 150f))
            .with(Annotation.Shape(2, ShapeKind.RECT, Box(100f, 80f, 300f, 120f), green, 6f))
        val px = pixels(decode(AnnotationExporter().export(base, doc, ExportFormat.PNG)))
        // The frame's colour is on top of the blurred pixels (drawn after the redaction, not under it)...
        assertEquals(green, px[80 * 400 + 200])
        // ...and nothing of the original survives around it.
        for (y in 90 until 110) for (x in 120 until 280) assertFalse(px[y * 400 + x] in palette)
    }

    @Test fun aJpegExportIsRedactedToo() {
        val base = textPicture(480, 320)
        val r = redaction(1, RedactMode.BLUR, 40f, 40f, 400f, 90f)
        val doc = AnnotDoc(480, 320).with(r)
        val bytes = AnnotationExporter().export(base, doc, ExportFormat.JPEG)
        assertEquals(listOf(0xFF, 0xD8, 0xFF), bytes.take(3).map { it.toInt() and 0xFF })
        val px = pixels(decode(bytes))
        val e = Redactor.extent(r, 480, 320).let { PixelRect(it.left + 8, it.top + 8, it.right - 8, it.bottom - 8) } // inside JPEG's 8 px blocks
        assertTrue(std(px, 480, e) <= 0.2 * std(pixels(base), 480, e))
    }

    // ---- Crop -------------------------------------------------------------------------------------------------------

    @Test fun aCropDiscardsEveryPixelOutsideIt() {
        val base = textPicture(500, 400)
        // A marker colour that only exists outside the crop.
        Canvas(base).apply {
            drawRect(0f, 0f, 500f, 40f, android.graphics.Paint().apply { color = marker })
            drawRect(450f, 0f, 500f, 400f, android.graphics.Paint().apply { color = marker })
        }
        val crop = PixelRect(30, 60, 430, 360)
        val doc = AnnotDoc(500, 400, crop = crop)
        val out = decode(AnnotationExporter().export(base, doc, ExportFormat.PNG))
        assertEquals(400 to 300, out.width to out.height)
        val px = pixels(out)
        val orig = pixels(base)
        assertFalse("a pixel from outside the crop survived", px.any { it == marker })
        for (y in 0 until 300 step 7) for (x in 0 until 400 step 5) assertEquals(orig[(y + 60) * 500 + x + 30], px[y * 400 + x])
    }

    @Test fun marksAndRedactionsKeepTheirPlaceInACroppedExport() {
        val base = textPicture(500, 400)
        val green = 0xFF00C853.toInt()
        val doc = AnnotDoc(500, 400, crop = PixelRect(100, 100, 400, 300))
            .with(redaction(1, RedactMode.SOLID, 50f, 150f, 250f, 180f)) // half outside the crop
            .with(Annotation.Step(2, Pt(300f, 250f), 1, green, 20f))
        val px = pixels(decode(AnnotationExporter().export(base, doc, ExportFormat.PNG)))
        val m = Redactor.params(doc.redactions[0], 500).margin
        assertEquals(Color.BLACK, px[(165 - 100) * 300 + 0]) // the solid box reaches the crop's left edge
        assertEquals(Color.BLACK, px[(165 - 100) * 300 + (249 + m - 100)])
        assertEquals(green, px[(250 - 100) * 300 + (300 - 100 + 15)]) // the step's ring, at crop-relative coordinates
    }

    // ---- The rest of the marks --------------------------------------------------------------------------------------

    @Test fun everyKindOfMarkDrawsAndGeezAndLatinLabelsRender() {
        val w = 600
        val h = 400
        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(Color.WHITE) }
        val painter = AnnotationPainter()
        for (text in listOf("ሰላም ለሁሉም ትግርኛ", "Afaan Oromoo", "English\nሁለት መስመር")) for (plate in listOf(true, false)) {
            val label = Annotation.Label(1, Pt(20f, 20f), text, 0xFFE53935.toInt(), 40f, plate)
            val px = pixels(decode(AnnotationExporter().export(base, AnnotDoc(w, h).with(label), ExportFormat.PNG)))
            val b = painter.measure(label)
            var coloured = 0
            for (y in b.top.toInt() until b.bottom.toInt().coerceAtMost(h)) for (x in b.left.toInt() until b.right.toInt().coerceAtMost(w)) {
                if (Color.red(px[y * w + x]) > 150 && Color.green(px[y * w + x]) < 110) coloured++
            }
            assertTrue("'$text' plate=$plate: $coloured red pixels", coloured > 40)
        }
        val all = AnnotDoc(w, h)
            .with(Annotation.Ink(1, listOf(Pt(10f, 300f), Pt(100f, 320f), Pt(200f, 300f)), 0xFF1E88E5.toInt(), 8f))
            .with(Annotation.Ink(2, listOf(Pt(10f, 350f), Pt(300f, 350f)), 0xFFFFEB3B.toInt(), 30f, highlighter = true))
            .with(Annotation.Arrow(3, Pt(300f, 50f), Pt(500f, 150f), 0xFFE53935.toInt(), 6f))
            .with(Annotation.Shape(4, ShapeKind.ELLIPSE, Box(350f, 200f, 550f, 300f), 0xFF43A047.toInt(), 6f))
            .with(Annotation.Step(5, Pt(100f, 100f), 12, 0xFF8E24AA.toInt(), 30f))
        val px = pixels(decode(AnnotationExporter().export(base, all, ExportFormat.PNG)))
        assertEquals(0xFF1E88E5.toInt(), px[305 * w + 175]) // on the stroke's last, straight piece
        val hl = px[350 * w + 150] // translucent: yellowish, not opaque yellow
        assertTrue(Color.blue(hl) in 100..250 && Color.red(hl) > 240)
        assertEquals(0xFF43A047.toInt(), px[250 * w + 350 + 1].let { if (Color.green(it) > 120 && Color.red(it) < 120) 0xFF43A047.toInt() else it })
        assertEquals(0xFF8E24AA.toInt(), px[80 * w + 100]) // the marker's disc
    }

    @Test fun aSpotlightDimsEverythingOutsideItsBox() {
        val base = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(Color.WHITE) }
        val doc = AnnotDoc(300, 200).with(Annotation.Spotlight(1, Box(100f, 50f, 200f, 150f)))
        val px = pixels(decode(AnnotationExporter().export(base, doc, ExportFormat.PNG)))
        assertEquals(Color.WHITE, px[100 * 300 + 150])
        assertTrue(Color.red(px[10 * 300 + 10]) < 120)
    }

    @Test fun theEditorPreviewUsesTheSameRedactionOnASmallerCopy() {
        val base = textPicture(800, 400)
        val preview = Bitmap.createScaledBitmap(base, 400, 200, false)
        val doc = AnnotDoc(800, 400).with(redaction(1, RedactMode.BLUR, 100f, 100f, 500f, 160f))
        val shown = AnnotationExporter().redactedPreview(preview, doc)
        val px = pixels(shown)
        val e = Redactor.extent(Geometry.scaled(doc.redactions[0], 0.5f) as Annotation.Redaction, 400, 200)
        for (y in e.top until e.bottom) for (x in e.left until e.right) assertFalse(px[y * 400 + x] in palette)
        assertFalse(shown === preview)
    }

    // ---- Import: bounded, upright, no metadata ----------------------------------------------------------------------

    @Test fun sampleSizesKeepTheWorkingBitmapWithinTheMemoryBudget() {
        assertEquals(4, AnnotatorImages.sampleSize(12000, 9000)) // 3000 x 2250, never 12000 x 9000
        assertEquals(2, AnnotatorImages.sampleSize(4000, 3000)) // 12 MP is over the 8 MP budget
        assertEquals(1, AnnotatorImages.sampleSize(1080, 2400)) // a screenshot keeps every pixel
        assertEquals(2, AnnotatorImages.sampleSize(4097, 100))
        for ((w, h) in listOf(100_000 to 100_000, 30_000 to 500, 9000 to 9000)) {
            val s = AnnotatorImages.sampleSize(w, h)
            val sw = (w + s - 1) / s
            val sh = (h + s - 1) / s
            assertTrue("$w x $h -> $s", sw <= AnnotatorImages.MAX_SIDE && sh <= AnnotatorImages.MAX_SIDE && sw.toLong() * sh <= AnnotatorImages.MAX_PIXELS)
        }
        val (fw, fh) = AnnotatorImages.fit(6000, 1000)
        assertEquals(4096, fw)
        assertEquals(682, fh)
    }

    private fun contentUri(file: File): Uri {
        val uri = Uri.parse("content://com.example.photos/media/${file.name}")
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri, Supplier { FileInputStream(file) })
        return uri
    }

    @Test fun aWidePictureIsDecodedSampledAndFittedWithinTheLimits() {
        val big = Bitmap.createBitmap(5000, 200, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(0xFF336699.toInt()) }
        val f = File(dir, "wide.png").also { it.writeBytes(CardImages.encode(big, ExportFormat.PNG)) }
        val pic = AnnotatorImages.load(app.contentResolver, contentUri(f))!!
        assertTrue(pic.bitmap.width <= AnnotatorImages.MAX_SIDE)
        assertEquals(5000 to 200, pic.originalWidth to pic.originalHeight)
        assertEquals(pic.bitmap.width / 25f, pic.bitmap.height.toFloat(), 2f) // same shape
        assertEquals(0xFF336699.toInt(), pic.bitmap.getPixel(10, 10))
    }

    @Test fun aPhotoIsTurnedUprightAndItsMetadataNeverReachesTheExport() {
        // A JPEG with an orientation tag (rotate 90) and a GPS position, like a phone photo.
        val src = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888).also { b ->
            Canvas(b).drawColor(Color.WHITE)
            Canvas(b).drawRect(0f, 0f, 30f, 60f, android.graphics.Paint().apply { color = Color.RED }) // a red left edge
        }
        val f = File(dir, "photo.jpg").also { it.writeBytes(CardImages.encode(src, ExportFormat.JPEG)) }
        ExifInterface(f.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "9/1,1/1,48/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "38/1,44/1,24/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            setAttribute(ExifInterface.TAG_MAKE, "SecretCam")
            saveAttributes()
        }
        val raw = f.readBytes()
        assertTrue("precondition: the source has EXIF", indexOf(raw, "Exif".toByteArray()) >= 0)
        assertTrue("precondition: the source has the camera name", indexOf(raw, "SecretCam".toByteArray()) >= 0)
        assertTrue("precondition: the source has a GPS position", ExifInterface(f.path).getLatLong(FloatArray(2)))

        val pic = AnnotatorImages.load(app.contentResolver, contentUri(f))!!
        assertEquals("upright: rotated a quarter turn", 60 to 120, pic.bitmap.width to pic.bitmap.height)
        assertTrue("the red edge is now at the top", Color.red(pic.bitmap.getPixel(30, 5)) > 200 && Color.green(pic.bitmap.getPixel(30, 5)) < 90)
        assertEquals("opaque", 0xFF, Color.alpha(pic.bitmap.getPixel(30, 100)))

        val doc = AnnotDoc(pic.bitmap.width, pic.bitmap.height).with(Annotation.Ink(1, listOf(Pt(5f, 60f), Pt(50f, 60f)), Color.BLUE, 4f))
        for (format in ExportFormat.entries) {
            val bytes = AnnotationExporter().export(pic.bitmap, doc, format)
            assertFalse("$format has an EXIF block", indexOf(bytes, "Exif".toByteArray()) >= 0)
            assertFalse("$format has the camera name", indexOf(bytes, "SecretCam".toByteArray()) >= 0)
            assertFalse("$format has GPS", indexOf(bytes, "GPS".toByteArray()) >= 0)
            val out = File(dir, "out.${format.extension}").also { it.writeBytes(bytes) }
            val exif = ExifInterface(out.path)
            assertFalse("$format: no position", exif.getLatLong(FloatArray(2)))
            assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
            val o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            assertTrue("$format: no orientation tag to re-rotate it", o == ExifInterface.ORIENTATION_UNDEFINED || o == ExifInterface.ORIENTATION_NORMAL)
        }
    }

    @Test fun onlyContentUrisAreReadNeverARawPath() {
        val f = File(dir, "x.png").also { it.writeBytes(CardImages.encode(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), ExportFormat.PNG)) }
        assertNull(AnnotatorImages.load(app.contentResolver, Uri.fromFile(f)))
        val notAPicture = File(dir, "x.txt").also { it.writeText("hello") }
        assertNull(AnnotatorImages.load(app.contentResolver, contentUri(notAPicture)))
    }

    @Test fun aTransparentPictureBecomesAnOpaqueWorkingBitmap() {
        val clear = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888) // fully transparent
        val up = AnnotatorImages.upright(clear, ExifInterface.ORIENTATION_NORMAL)
        assertEquals(Color.WHITE, up.getPixel(5, 5))
        assertEquals(40 to 30, up.width to up.height)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Suppress("unused")
    private fun near(a: Int, b: Int, tol: Int) = abs(Color.red(a) - Color.red(b)) <= tol && abs(Color.green(a) - Color.green(b)) <= tol && abs(Color.blue(a) - Color.blue(b)) <= tol
}
