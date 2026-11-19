package com.qalsmartkeyboard.ime.voice

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The platform adapter: API gating, recogniser order, error mapping, the request and the permission intent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformVoiceTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val google = ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
    private val tts = ComponentName("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService")
    private val vendor = ComponentName("com.vendor.voice", "com.vendor.voice.Recognizer")

    @Test fun beforeAndroid13LanguagesAreNotAskedSoTheyAreTried() {
        var called = false
        var result: RecognizerLanguages? = RecognizerLanguages(emptySet(), emptySet())
        PlatformVoiceProvider(ctx, sdkInt = 32).languages(RecognizerRef.DEFAULT) {
            called = true
            result = it
        }
        assertTrue(called)
        assertNull(result)
    }

    @Test fun theDefaultComesFirstThenGoogleWhenItIsAnotherService() {
        assertEquals(listOf(RecognizerRef.DEFAULT), PlatformVoiceProvider.choose(emptyList(), null))
        assertEquals(listOf(RecognizerRef.DEFAULT), PlatformVoiceProvider.choose(listOf(vendor), vendor))
        assertEquals(
            listOf(RecognizerRef.DEFAULT, RecognizerRef(google.flattenToString(), false)),
            PlatformVoiceProvider.choose(listOf(vendor, tts, google), vendor), // the Google app before Speech Services
        )
        assertEquals(listOf(RecognizerRef.DEFAULT), PlatformVoiceProvider.choose(listOf(vendor, google), google)) // Google is the default
        assertEquals(
            listOf(RecognizerRef.DEFAULT, RecognizerRef(tts.flattenToString(), false)),
            PlatformVoiceProvider.choose(listOf(tts), null), // default unknown: Google may be asked twice, harmlessly
        )
    }

    @Test fun installedServicesAreFoundByTheirInterface() {
        val p = PlatformVoiceProvider(ctx)
        p.refresh()
        assertFalse(p.available)
        val pm = shadowOf(ctx.packageManager)
        pm.addServiceIfNotPresent(google)
        pm.addIntentFilterForService(google, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        p.refresh()
        assertTrue(p.available)
        assertEquals(listOf(RecognizerRef.DEFAULT, RecognizerRef(google.flattenToString(), false)), p.recognizers())
    }

    @Test fun recognitionSupportListsBecomeNormalisedTagSets() {
        val s = RecognitionSupport.Builder()
            .setOnlineLanguages(listOf("am-ET", "en_US"))
            .setInstalledOnDeviceLanguages(listOf("en-US"))
            .setSupportedOnDeviceLanguages(listOf("fr-FR"))
            .setPendingOnDeviceLanguages(listOf("de_de"))
            .build()
        val l = PlatformVoiceProvider.toLanguages(s)
        assertEquals(setOf("am-ET", "en-US", "fr-FR", "de-DE"), l.online)
        assertEquals(setOf("en-US"), l.onDevice)
    }

    @Test fun platformErrorsMapToKinds() {
        val m = PlatformVoiceProvider::mapError
        assertEquals(VoiceErrorKind.NETWORK, m(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(VoiceErrorKind.NETWORK, m(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals(VoiceErrorKind.AUDIO, m(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(VoiceErrorKind.SERVER, m(SpeechRecognizer.ERROR_SERVER))
        assertEquals(VoiceErrorKind.SERVER, m(SpeechRecognizer.ERROR_SERVER_DISCONNECTED))
        assertEquals(VoiceErrorKind.CLIENT, m(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(VoiceErrorKind.NO_SPEECH, m(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(VoiceErrorKind.NO_MATCH, m(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(VoiceErrorKind.BUSY, m(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(VoiceErrorKind.BUSY, m(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS))
        assertEquals(VoiceErrorKind.PERMISSION, m(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(VoiceErrorKind.LANGUAGE_NOT_SUPPORTED, m(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertEquals(VoiceErrorKind.LANGUAGE_UNAVAILABLE, m(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals(VoiceErrorKind.CLIENT, m(999))
    }

    @Test fun theLevelIsScaledAndClamped() {
        assertEquals(0f, PlatformVoiceProvider.levelOf(-10f), 0f)
        assertEquals(0f, PlatformVoiceProvider.levelOf(-2f), 0f)
        assertEquals(0.5f, PlatformVoiceProvider.levelOf(4f), 0.001f)
        assertEquals(1f, PlatformVoiceProvider.levelOf(30f), 0f)
    }

    @Test fun theRequestAsksForFreeFormDictationInTheTagWithPartialResults() {
        val i = PlatformVoiceProvider.recognizerIntent(ctx, "am-ET")
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, i.action)
        assertEquals("am-ET", i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, i.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertTrue(i.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertEquals(ctx.packageName, i.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE))
        assertNull(PlatformVoiceProvider.recognizerIntent(ctx, null).getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
    }

    @Test fun thePermissionIntentTargetsOurOwnAppInANewTask() {
        val i = VoiceIntents.permission("com.qalsmartkeyboard.app")
        assertEquals(VoiceIntents.ACTION_VOICE_PERMISSION, i.action)
        assertEquals("com.qalsmartkeyboard.app", i.`package`)
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
