package com.qalsmartkeyboard.ime.voice

import com.qalsmartkeyboard.ime.voice.FakeVoiceProvider.Companion.GOOGLE
import com.qalsmartkeyboard.ime.voice.FakeVoiceProvider.Companion.online
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recogniser and tag choice, the per-day support cache, and the "cannot ask" path of old Android versions. */
class VoiceSupportTest {
    private var now = 1_000L * VoiceSupportCache.DAY_MS + 5
    private fun cache() = VoiceSupportCache { now }

    private fun resolve(s: VoiceSupport, vararg tags: String): VoiceChoice {
        var out: VoiceChoice? = null
        s.resolve(tags.toList()) { out = it }
        return out!!
    }

    @Test fun theDefaultServiceIsUsedWhenItDeclaresTheLanguage() {
        val p = FakeVoiceProvider(listOf(RecognizerRef.DEFAULT, GOOGLE))
        p.languages["default"] = online("am-ET", "en-US")
        val c = resolve(VoiceSupport(p, cache()), "am-ET")
        assertEquals(VoiceChoice("am-ET", RecognizerRef.DEFAULT, SupportLevel.ONLINE), c)
        assertEquals(listOf("default"), p.asked) // Google's service is not even asked
    }

    @Test fun googleIsPreferredOnlyWhenTheDefaultLacksTheLanguage() {
        val p = FakeVoiceProvider(listOf(RecognizerRef.DEFAULT, GOOGLE))
        p.languages["default"] = online("en-US", "ko-KR") // e.g. a vendor service
        p.languages[GOOGLE.id] = RecognizerLanguages(setOf("am-ET"), setOf("en-US"))
        val c = resolve(VoiceSupport(p, cache()), "am-ET")
        assertEquals(GOOGLE, c.recognizer)
        assertEquals(SupportLevel.ONLINE, c.support)
        // English is declared by the default service, so it stays there (even though Google has it on device).
        assertEquals(VoiceChoice("en-US", RecognizerRef.DEFAULT, SupportLevel.ONLINE), resolve(VoiceSupport(p, cache()), "en-GB", "en-US"))
    }

    @Test fun exactTagsWinOverLanguageMatchesThenTheBareLanguageIsAccepted() {
        val p = FakeVoiceProvider()
        p.languages["default"] = online("ti-ER", "en")
        val s = VoiceSupport(p, cache())
        assertEquals("ti-ER", resolve(s, "ti-ET", "ti-ER").tag) // second preference, but exact
        assertEquals("en", resolve(s, "en-GB", "en-US").tag) // the service only lists "en"
    }

    @Test fun anUndeclaredLanguageIsUnsupportedWhenTheServiceListsItsLanguages() {
        val p = FakeVoiceProvider(listOf(RecognizerRef.DEFAULT, GOOGLE))
        p.languages["default"] = online("en-US")
        p.languages[GOOGLE.id] = online("en-US", "am-ET")
        val c = resolve(VoiceSupport(p, cache()), "ti-ET", "ti-ER")
        assertEquals(SupportLevel.UNSUPPORTED, c.support)
        assertEquals("ti-ET", c.tag)
        assertFalse(c.support.usable)
    }

    @Test fun beforeAndroid13TheLanguageIsTriedAndRejectionsAreRemembered() {
        val p = FakeVoiceProvider(listOf(RecognizerRef.DEFAULT, GOOGLE)) // no answers: null = cannot tell
        val s = VoiceSupport(p, cache())
        assertEquals(VoiceChoice("ti-ET", RecognizerRef.DEFAULT, SupportLevel.UNKNOWN), resolve(s, "ti-ET", "ti-ER"))
        s.cache.reject(RecognizerRef.DEFAULT, "ti-ET")
        assertEquals(VoiceChoice("ti-ER", RecognizerRef.DEFAULT, SupportLevel.UNKNOWN), resolve(s, "ti-ET", "ti-ER"))
        s.cache.reject(RecognizerRef.DEFAULT, "ti-ER")
        assertEquals(VoiceChoice("ti-ET", GOOGLE, SupportLevel.UNKNOWN), resolve(s, "ti-ET", "ti-ER")) // next service
        s.cache.reject(GOOGLE, "ti-ET")
        s.cache.reject(GOOGLE, "ti-ER")
        assertEquals(SupportLevel.UNSUPPORTED, resolve(s, "ti-ET", "ti-ER").support)
    }

    @Test fun answersAreCachedPerRecogniserAndDay() {
        val p = FakeVoiceProvider()
        p.languages["default"] = online("am-ET")
        val s = VoiceSupport(p, cache())
        resolve(s, "am-ET")
        resolve(s, "am-ET")
        s.loadAll {}
        assertEquals(1, p.asked.size)
        now += VoiceSupportCache.DAY_MS // a new day: asked again (speech services update their languages)
        resolve(s, "am-ET")
        assertEquals(2, p.asked.size)
    }

    @Test fun rejectionsLastOneDayAndAResultClearsThem() {
        val c = cache()
        c.reject(RecognizerRef.DEFAULT, "am-ET")
        assertTrue(c.isRejected(RecognizerRef.DEFAULT, "am_ET"))
        now += VoiceSupportCache.DAY_MS
        assertFalse(c.isRejected(RecognizerRef.DEFAULT, "am-ET"))
        c.reject(RecognizerRef.DEFAULT, "am-ET")
        c.confirm(RecognizerRef.DEFAULT, "am-ET")
        assertFalse(c.isRejected(RecognizerRef.DEFAULT, "am-ET"))
        assertTrue(c.isConfirmed(RecognizerRef.DEFAULT, "am-ET"))
    }

    @Test fun anAsynchronousServiceIsWaitedFor() {
        val p = FakeVoiceProvider()
        p.languages["default"] = online("om-ET")
        p.deferLanguages = true
        var out: VoiceChoice? = null
        VoiceSupport(p, cache()).resolve(listOf("om-ET")) { out = it }
        assertEquals(null, out)
        p.answerLanguages()
        assertEquals(SupportLevel.ONLINE, out!!.support)
    }

    @Test fun declaredTagsCollectEveryRecogniser() {
        val p = FakeVoiceProvider(listOf(RecognizerRef.DEFAULT, GOOGLE))
        p.languages["default"] = online("en-US", "fr-FR")
        p.languages[GOOGLE.id] = RecognizerLanguages(setOf("am-ET"), setOf("de-DE"))
        val s = VoiceSupport(p, cache())
        s.loadAll {}
        assertEquals(setOf("en-US", "fr-FR", "am-ET", "de-DE"), s.declaredTags())
    }
}
