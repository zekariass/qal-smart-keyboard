package com.qalsmartkeyboard.app.creative.cards

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The editor's rules (T8.7): templates keep the user's own text, limits, the photo and "saved" flags. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardEditorTest {
    private val resolve: (Int) -> String = { "s$it" }
    private fun editor() = CardEditor(CardTemplates.default.newDoc(resolve))
    private fun bmp() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    @Test fun aNewTemplateReplacesTheStartingTextButKeepsWhatTheUserWrote() {
        val e = editor()
        val congrats = CardTemplates.find("congrats")!!
        e.applyTemplate(congrats, resolve)
        assertEquals("s${congrats.sample.headline}", e.doc.headline)
        assertEquals(congrats.background, e.doc.background)
        // The user writes their own body; the next template keeps it but takes the new starting headline.
        e.body("Dear Almaz, well done!")
        val thanks = CardTemplates.find("thanks")!!
        e.applyTemplate(thanks, resolve)
        assertEquals("s${thanks.sample.headline}", e.doc.headline)
        assertEquals("Dear Almaz, well done!", e.doc.body)
        assertEquals("thanks", e.doc.templateId)
    }

    @Test fun anEmptiedFieldTakesTheNextTemplatesText() {
        val e = editor()
        e.body("")
        val t = CardTemplates.find("holiday")!!
        e.applyTemplate(t, resolve)
        assertEquals("s${t.sample.body}", e.doc.body)
    }

    @Test fun aTemplateKeepsTheChosenAspectAndFormat() {
        val e = editor()
        e.aspect(CardAspect.PORTRAIT)
        e.format(ExportFormat.JPEG)
        e.applyTemplate(CardTemplates.find("quote")!!, resolve)
        assertEquals(CardAspect.PORTRAIT, e.doc.aspect)
        assertEquals(ExportFormat.JPEG, e.doc.format)
    }

    @Test fun textIsCutToItsLimits() {
        val e = editor()
        e.headline("h".repeat(500)); e.body("b".repeat(5000)); e.from("f".repeat(500))
        assertEquals(CardDoc.MAX_HEADLINE, e.doc.headline.length)
        assertEquals(CardDoc.MAX_BODY, e.doc.body.length)
        assertEquals(CardDoc.MAX_FROM, e.doc.from.length)
    }

    @Test fun styleChangesStayInRange() {
        val e = editor()
        e.sizeScale(9f)
        assertEquals(CardTextStyle.MAX_SCALE, e.doc.text.sizeScale, 0f)
        e.sizeScale(0f)
        assertEquals(CardTextStyle.MIN_SCALE, e.doc.text.sizeScale, 0f)
        e.font(CardFont.SERIF); e.align(CardTextAlign.END); e.bold(true); e.shadow(true); e.color(0xFF00FF00.toInt())
        assertEquals(CardTextStyle(CardFont.SERIF, CardTextStyle.MIN_SCALE, 0xFF00FF00.toInt(), CardTextAlign.END, bold = true, shadow = true), e.doc.text)
    }

    @Test fun aPickedPhotoBecomesTheBackgroundAndKeepsItsDimmingUntilAnotherBackgroundReplacesIt() {
        val e = editor()
        val p = bmp()
        e.usePhoto(p)
        assertEquals(CardBackground.Photo(null, 30), e.doc.background)
        assertTrue(e.photoDirty)
        assertTrue(e.photo === p)
        e.dim(50)
        assertEquals(50, (e.doc.background as CardBackground.Photo).dimPercent)
        e.dim(500)
        assertEquals(CardBackground.MAX_DIM, (e.doc.background as CardBackground.Photo).dimPercent)
        e.background(CardBackground.Solid(0xFF000000.toInt()))
        assertNull(e.photo)
        assertFalse(e.photoDirty)
    }

    @Test fun dimmingDoesNothingWithoutAPhoto() {
        val e = editor()
        val before = e.doc
        e.dim(40)
        assertEquals(before, e.doc)
    }

    @Test fun openingASavedCardIsNotAChangeAndSavingClearsTheFlags() {
        val e = editor()
        e.headline("changed")
        assertTrue(e.dirty)
        val p = bmp()
        e.open("abc123", CardTemplates.find("love")!!.newDoc(resolve), p)
        assertEquals("abc123", e.editingId)
        assertFalse(e.dirty)
        assertFalse(e.photoDirty)
        assertNotNull(e.photo)
        e.body("more")
        assertTrue(e.dirty)
        e.saved("abc123")
        assertFalse(e.dirty)
    }

    @Test fun startingANewCardForgetsTheOldOne() {
        val e = editor()
        e.open("abc123", CardTemplates.find("love")!!.newDoc(resolve), bmp())
        e.startNew(CardTemplates.default, resolve)
        assertNull(e.editingId)
        assertNull(e.photo)
        assertEquals(CardTemplates.default.id, e.doc.templateId)
        assertEquals(CardSection.TEMPLATE, e.section)
    }

    @Test fun deletingTheOpenCardMakesItANewOneThatStillCarriesItsPhoto() {
        val e = editor()
        e.open("abc123", CardTemplates.find("love")!!.newDoc(resolve).copy(background = CardBackground.Photo("abc123.bg.jpg")), bmp())
        e.detach()
        assertNull(e.editingId)
        assertTrue(e.photoDirty) // the next save writes the photo again
    }
}
