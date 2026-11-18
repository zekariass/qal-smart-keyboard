package com.qalsmartkeyboard.app.themes

import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePickerModelTest {
    private class FakeStore : ThemeStore {
        val saved = ArrayList<String>()
        override suspend fun set(id: String) { saved += id }
    }

    private val store = FakeStore()
    private val model = ThemePickerModel(store)

    @Test fun previewNeverApplies() {
        model.showPreview("midnight")
        assertEquals("midnight", model.previewId)
        model.showPreview("aurora")
        model.showPreview(ThemeCatalog.SYSTEM_ID)
        model.closePreview()
        assertNull(model.previewId)
        assertTrue("preview must not write the setting", store.saved.isEmpty())
    }

    @Test fun unknownIdsCannotBePreviewedOrApplied() = runBlocking {
        model.showPreview("neon_future")
        assertNull(model.previewId)
        assertEquals(ApplyResult.UNKNOWN, model.apply("neon_future", PremiumThemeAccess.ALLOWED))
        assertTrue(store.saved.isEmpty())
        assertNull(ThemePickerModel(store, initialPreview = "neon_future").previewId)
        assertEquals("amoled_black", ThemePickerModel(store, initialPreview = "amoled_black").previewId)
    }

    @Test fun applyingAFreeThemePersistsItWhateverTheEntitlement() = runBlocking {
        for (access in PremiumThemeAccess.entries) {
            assertEquals(ApplyResult.APPLIED, model.apply("forest_green", access))
        }
        assertEquals(PremiumThemeAccess.entries.size, store.saved.size)
        assertEquals("forest_green", store.saved.last())
        assertEquals(ApplyResult.APPLIED, model.apply(ThemeCatalog.SYSTEM_ID, PremiumThemeAccess.SIGNED_OUT))
        assertEquals("system", store.saved.last())
    }

    @Test fun aPremiumThemeNeedsTheEntitlement() = runBlocking {
        for (access in listOf(PremiumThemeAccess.SIGNED_OUT, PremiumThemeAccess.NOT_ON_PLAN, PremiumThemeAccess.NEEDS_CHECK)) {
            assertEquals(ApplyResult.NEEDS_PREMIUM, model.apply("aurora", access))
            assertFalse(model.canApply("aurora", access))
        }
        assertTrue(store.saved.isEmpty())
        assertTrue(model.canApply("aurora", PremiumThemeAccess.ALLOWED))
        assertEquals(ApplyResult.APPLIED, model.apply("aurora", PremiumThemeAccess.ALLOWED))
        assertEquals(listOf("aurora"), store.saved)
    }

    @Test fun systemPreviewFollowsTheDeviceMode() {
        assertSame(ThemeCatalog.classicLight, ThemePickerModel.previewTheme("system", systemDark = false))
        assertSame(ThemeCatalog.classicDark, ThemePickerModel.previewTheme("system", systemDark = true))
        assertSame(ThemeCatalog.find("midnight"), ThemePickerModel.previewTheme("midnight", systemDark = false))
    }
}
