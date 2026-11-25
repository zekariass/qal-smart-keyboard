package com.qalsmartkeyboard.app.creative.cards

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The renderer (T8.7) with real Canvas and StaticLayout pixels (NATIVE graphics): sizes, every template and every
 * background draws, Ge'ez and Latin text with long lines, the preview scale, PNG and JPEG encoding and the R-8 rule that an
 * export is a freshly rendered bitmap (no metadata of a picked photo can be in it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardRendererTest {
    private val renderer = CardRenderer()
    private val resolve: (Int) -> String = { "Text $it" }
    private fun doc(id: String = "plain") = CardTemplates.find(id)!!.newDoc(resolve)

    private fun distinctColours(b: Bitmap, step: Int = 12): Int {
        val set = HashSet<Int>()
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) { set += b.getPixel(x, y); x += step }
            y += step
        }
        return set.size
    }

    @Test fun exportSizesAreExactlyTheFixedOnes() {
        val sq = renderer.render(doc().copy(aspect = CardAspect.SQUARE), null)
        assertEquals(1080 to 1080, sq.width to sq.height)
        val po = renderer.render(doc().copy(aspect = CardAspect.PORTRAIT), null)
        assertEquals(1080 to 1350, po.width to po.height)
    }

    @Test fun thePreviewIsTheSameCardAtASmallerScale() {
        val d = doc("birthday")
        val full = renderer.render(d, null, 1f)
        val half = renderer.render(d, null, 0.5f)
        assertEquals(540 to 540, half.width to half.height)
        // The same picture: a coarse sample of both agrees on the background and the text areas alike.
        var differing = 0
        var total = 0
        for (y in 0 until 540 step 30) for (x in 0 until 540 step 30) {
            total++
            val a = full.getPixel(x * 2, y * 2)
            val b = half.getPixel(x, y)
            if (kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) > 120) differing++
        }
        assertTrue("$differing of $total samples differ", differing * 10 < total)
    }

    @Test fun everyTemplateDrawsOnBothAspects() {
        for (t in CardTemplates.all) for (a in CardAspect.entries) {
            val b = renderer.render(t.newDoc(resolve, a), null, 0.25f)
            assertTrue("${t.id} $a", distinctColours(b, 4) > 3)
        }
    }

    @Test fun everyBackgroundKindDrawsAndPatternsAreNotFlat() {
        val base = doc().copy(headline = "", body = "", from = "") // no text: only the background
        for (g in CardPalette.gradients) assertTrue(g.id, distinctColours(renderer.render(base.copy(background = g.toBackground()), null, 0.25f), 4) > 8)
        for (p in CardPalette.patterns) assertTrue(p.kind.name, distinctColours(renderer.render(base.copy(background = p), null, 0.25f), 3) >= 2)
        val solid = renderer.render(base.copy(background = CardBackground.Solid(0xFF336699.toInt())), null, 0.1f)
        assertEquals(0xFF336699.toInt(), solid.getPixel(5, 5))
    }

    @Test fun aPhotoCoversTheCardAndTheDimmingDarkensIt() {
        val photo = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(Color.WHITE) }
        val base = doc().copy(headline = "", body = "", from = "")
        val bright = renderer.render(base.copy(background = CardBackground.Photo(null, 0)), photo, 0.2f)
        val dim = renderer.render(base.copy(background = CardBackground.Photo(null, 60)), photo, 0.2f)
        assertEquals(Color.WHITE, bright.getPixel(0, 0)) // covers right up to the corner
        assertEquals(Color.WHITE, bright.getPixel(bright.width - 1, bright.height - 1))
        assertTrue(Color.red(dim.getPixel(10, 10)) < 140)
        // No picture yet (the file is gone): a neutral background, not a crash.
        assertNotNull(renderer.render(base.copy(background = CardBackground.Photo("missing.bg.jpg", 30)), null, 0.2f))
    }

    @Test fun textIsDrawnAndMoreTextChangesTheCard() {
        val blank = renderer.render(doc().copy(headline = "", body = "", from = ""), null, 0.5f)
        val withText = renderer.render(doc().copy(headline = "Happy Birthday", body = "Baga gammaddan! Wishing you joy.", from = "Almaz"), null, 0.5f)
        assertEquals(1, distinctColours(blank, 2))
        assertTrue(distinctColours(withText, 2) > 1)
    }

    @Test fun geezAndLatinTextWithVeryLongLinesRenderWithoutFailing() {
        val geez = "ሰላም ለሁላችሁ። መልካም ልደት ይሁንላችሁ፤ ዓመቱ የደስታ ይሁን። ".repeat(12)
        val latin = "Baga ayyaana dhalootaa ga'an, waan hundaaf galatoomaa. ".repeat(12)
        val unbroken = "ሰላም".repeat(150) + "x".repeat(300)
        for (text in listOf(geez, latin, unbroken, "ትግርኛ ሰላም\nAfaan Oromoo\nEnglish")) for (a in CardAspect.entries) {
            val b = renderer.render(doc("congrats").copy(headline = text.take(CardDoc.MAX_HEADLINE), body = text.take(CardDoc.MAX_BODY), from = text.take(CardDoc.MAX_FROM), aspect = a), null, 0.5f)
            assertNotNull(b)
        }
    }

    @Test fun theLargestTextScaleAndEveryFontStillRender() {
        for (f in CardFont.entries) for (scale in listOf(CardTextStyle.MIN_SCALE, 1f, CardTextStyle.MAX_SCALE)) {
            val d = doc("quote").let { it.copy(text = it.text.copy(font = f, sizeScale = scale, bold = true, shadow = true)) }
            assertNotNull(renderer.render(d, null, 0.25f))
        }
    }

    @Test fun everyFrameChangesThePicture() {
        val none = renderer.render(doc().copy(frame = CardFrame.NONE), null, 0.25f)
        for (f in CardFrame.entries.filter { it != CardFrame.NONE }) {
            val framed = renderer.render(doc().copy(frame = f), null, 0.25f)
            assertFalse("$f", sameCardPixels(none, framed))
        }
    }

    private fun sameCardPixels(a: Bitmap, b: Bitmap): Boolean {
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) return false
        return true
    }

    // ---- Encoding and privacy (R-8) ---------------------------------------------------------------------------------

    @Test fun pngAndJpegAreEncodedAsTheChosenFormat() {
        val b = renderer.render(doc("birthday"), null, 0.25f)
        val png = CardImages.encode(b, ExportFormat.PNG)
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), png.take(4).map { it.toInt() and 0xFF })
        val jpeg = CardImages.encode(b, ExportFormat.JPEG)
        assertEquals(listOf(0xFF, 0xD8, 0xFF), jpeg.take(3).map { it.toInt() and 0xFF })
        assertEquals(b.width to b.height, BitmapFactory.decodeByteArray(png, 0, png.size).let { it.width to it.height })
    }

    @Test fun anExportIsAFreshlyRenderedBitmapWithNoMetadataOfAPickedPhoto() {
        // A "photo" whose source file carries EXIF with a GPS position: only its pixels are ever read, and the export
        // is a new bitmap encoded from scratch, so none of the source's bytes (or its EXIF segment) can be in the file.
        val photo = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(0xFF2E7D32.toInt()) }
        val marker = "GPSLatitude-12.3456".toByteArray()
        val d = doc().copy(background = CardBackground.Photo(null, 20), format = ExportFormat.JPEG)
        val out = renderer.render(d, photo, 0.25f)
        assertFalse("a fresh bitmap, not the picked one", out === photo)
        assertNotEquals(photo.width, out.width)
        for (format in ExportFormat.entries) {
            val bytes = CardImages.encode(out, format)
            assertFalse("$format has an EXIF segment", indexOf(bytes, "Exif".toByteArray()) >= 0)
            assertFalse(indexOf(bytes, marker) >= 0)
            assertFalse(indexOf(bytes, "GPS".toByteArray()) >= 0)
        }
        // And the stored copy of the photo (what a saved card keeps) is a re-encode without metadata as well.
        assertFalse(indexOf(CardImages.photoJpeg(photo), "Exif".toByteArray()) >= 0)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
