package com.qalsmartkeyboard.ime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** ADR 0032: the stored UI language and the keyboard's localised context (the typing languages are not touched). */
@RunWith(RobolectricTestRunner::class)
class AppLanguageTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @After fun backToSystem() = AppLanguage.set(ctx, "")

    @Test @Config(sdk = [32])
    fun belowAndroid13TheChoiceIsStoredAndAppliedByTheWrappers() {
        assertEquals("", AppLanguage.get(ctx))
        assertSame(ctx, AppLanguage.uiContext(ctx)) // system language: the service's own resources
        AppLanguage.set(ctx, "ti")
        assertEquals("ti", AppLanguage.get(ctx))
        val ui = AppLanguage.uiContext(ctx)
        assertEquals("ምድምሳስ", ui.getString(R.string.kb_key_delete))
        assertEquals("ti", AppLanguage.wrap(ctx).resources.configuration.locales[0].language)
        assertEquals("Delete", ctx.getString(R.string.kb_key_delete)) // the service itself is unchanged
    }

    @Test @Config(sdk = [34])
    fun android13AndLaterUseThePlatformPerAppLanguage() {
        AppLanguage.set(ctx, "om")
        assertEquals("om", AppLanguage.get(ctx))
        assertSame(ctx, AppLanguage.wrap(ctx)) // the platform applies it to activities
        assertEquals("Haqaa", AppLanguage.uiContext(ctx).getString(R.string.kb_key_delete))
        AppLanguage.set(ctx, "")
        assertEquals("", AppLanguage.get(ctx))
    }

    @Test fun unknownTagsMeanTheSystemLanguage() {
        assertEquals("am", AppLanguage.normalize("am-ET"))
        assertEquals("", AppLanguage.normalize("fr"))
        assertEquals("", AppLanguage.normalize(""))
    }

    @Test fun listenersHearAChange() {
        var heard = 0
        val l: () -> Unit = { heard++ }
        AppLanguage.addListener(l)
        AppLanguage.set(ctx, "am")
        AppLanguage.removeListener(l)
        AppLanguage.set(ctx, "")
        assertEquals(1, heard)
    }
}
