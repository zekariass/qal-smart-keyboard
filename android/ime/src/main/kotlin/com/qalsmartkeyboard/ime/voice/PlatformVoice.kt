package com.qalsmartkeyboard.ime.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * [VoiceProvider] over the platform's `SpeechRecognizer` (ADR 0029). The recognition itself runs in another app (the
 * device's speech service, usually Google's), reached over Android IPC; `:ime` opens no network connection.
 *
 * Recognisers, in order: the system default (`SpeechRecognizer.createSpeechRecognizer(context)`), then Google's
 * service when it is installed and is not the default ([GOOGLE_PACKAGES], matched by package among the services that
 * answer `RecognitionService.SERVICE_INTERFACE`; the class name is taken from that query, never hard-coded).
 *
 * Language lists come from `SpeechRecognizer.checkRecognitionSupport` (Android 13+). Before that there is no way to
 * ask, so [languages] answers null and the language is tried ([VoiceErrorKind.LANGUAGE_NOT_SUPPORTED] and
 * [VoiceErrorKind.LANGUAGE_UNAVAILABLE] come back as errors on Android 12+).
 *
 * Main thread only, as `SpeechRecognizer` requires; [refresh] may run on any thread. Logs carry error codes only.
 */
class PlatformVoiceProvider(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : VoiceProvider {
    @Volatile private var services: List<ComponentName> = emptyList()
    @Volatile private var defaultService: ComponentName? = null
    @Volatile private var hasService = false
    private val main = Handler(Looper.getMainLooper())

    /**
     * Re-reads the installed recognition services (a PackageManager query: call it off the main thread, at start-up
     * and when the keyboard is shown, since speech services can be installed or removed at any time).
     */
    fun refresh() {
        hasService = try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: RuntimeException) {
            false
        }
        services = try {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo?.let { si -> ComponentName(si.packageName, si.name) } }
        } catch (e: RuntimeException) {
            emptyList()
        }
        defaultService = try {
            // The user's "default voice input" service. Not a public constant; unreadable on some builds (then unknown).
            Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
                ?.takeIf { it.isNotEmpty() }?.let { ComponentName.unflattenFromString(it) }
        } catch (e: RuntimeException) {
            null
        }
    }

    override val available: Boolean get() = hasService

    override fun recognizers(): List<RecognizerRef> = choose(services, defaultService)

    override fun languages(recognizer: RecognizerRef, done: (RecognizerLanguages?) -> Unit) {
        if (sdkInt < 33) {
            done(null) // checkRecognitionSupport is Android 13+: the language has to be tried
            return
        }
        checkSupport(recognizer, done)
    }

    @RequiresApi(33)
    private fun checkSupport(recognizer: RecognizerRef, done: (RecognizerLanguages?) -> Unit) {
        val rec = try {
            create(context, recognizer)
        } catch (e: RuntimeException) {
            null
        }
        if (rec == null) {
            done(null)
            return
        }
        var answered = false
        fun answer(langs: RecognizerLanguages?) {
            if (answered) return
            answered = true
            try {
                rec.destroy()
            } catch (e: RuntimeException) {
                Log.w(TAG, "destroy failed: ${e.javaClass.simpleName}")
            }
            done(langs)
        }
        // A service that never answers must not leave the panel waiting.
        main.postDelayed({ answer(null) }, SUPPORT_TIMEOUT_MS)
        try {
            rec.checkRecognitionSupport(
                recognizerIntent(context, null),
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) = answer(toLanguages(support))
                    override fun onError(error: Int) {
                        Log.i(TAG, "support check failed: $error")
                        answer(null)
                    }
                },
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "support check unavailable: ${e.javaClass.simpleName}")
            answer(null)
        }
    }

    override fun input(recognizer: RecognizerRef): VoiceInput = PlatformVoiceInput(context, recognizer)

    companion object {
        private const val TAG = "QalVoice"
        private const val SUPPORT_TIMEOUT_MS = 4_000L

        /**
         * Google's speech services, most preferred first: the Google app (its `RecognitionService` backs "Google voice
         * typing") and Speech Recognition & Synthesis from Google. Only used when the default service does not
         * support the language.
         */
        val GOOGLE_PACKAGES = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")

        /** The recogniser order: the default, then the first Google service that is not the default. Pure. */
        fun choose(services: List<ComponentName>, default: ComponentName?): List<RecognizerRef> {
            val out = arrayListOf(RecognizerRef.DEFAULT)
            val google = GOOGLE_PACKAGES.firstNotNullOfOrNull { pkg -> services.firstOrNull { it.packageName == pkg } }
            if (google != null && google != default) out += RecognizerRef(google.flattenToString(), isDefault = false)
            return out
        }

        @RequiresApi(33)
        internal fun toLanguages(s: RecognitionSupport): RecognizerLanguages {
            fun norm(l: List<String>?) = l.orEmpty().map { VoiceLanguages.normalize(it) }.filter { it.isNotEmpty() }.toSet()
            val installed = norm(s.installedOnDeviceLanguages)
            // Supported for download or downloading still means the service knows the language (it may fetch it).
            val online = norm(s.onlineLanguages) + norm(s.supportedOnDeviceLanguages) + norm(s.pendingOnDeviceLanguages)
            return RecognizerLanguages(online, installed)
        }

        internal fun create(context: Context, r: RecognizerRef): SpeechRecognizer? =
            if (r.isDefault) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                ComponentName.unflattenFromString(r.id)?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
            }

        /** The request: free-form dictation in [tag], partial results on, one result. */
        fun recognizerIntent(context: Context, tag: String?): Intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                if (tag != null) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
                }
            }

        /** `SpeechRecognizer.ERROR_*` to [VoiceErrorKind]. Codes newer than the device simply never arrive. Pure. */
        fun mapError(code: Int): VoiceErrorKind = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> VoiceErrorKind.NETWORK
            SpeechRecognizer.ERROR_AUDIO -> VoiceErrorKind.AUDIO
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> VoiceErrorKind.SERVER
            SpeechRecognizer.ERROR_CLIENT -> VoiceErrorKind.CLIENT
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceErrorKind.NO_SPEECH
            SpeechRecognizer.ERROR_NO_MATCH -> VoiceErrorKind.NO_MATCH
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceErrorKind.BUSY
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceErrorKind.PERMISSION
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> VoiceErrorKind.LANGUAGE_NOT_SUPPORTED
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceErrorKind.LANGUAGE_UNAVAILABLE
            else -> VoiceErrorKind.CLIENT
        }

        /** `onRmsChanged` dB (about -2 silent to 10 loud on Google's service) to 0..1. */
        fun levelOf(rmsDb: Float): Float = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)

        /** The first result of a results bundle, or "". */
        internal fun firstResult(b: Bundle?): String =
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    }
}

/**
 * One session over a platform `SpeechRecognizer`: created on [start], destroyed when the session ends. Main thread.
 * Recognised text goes only to the listener (never logged).
 */
class PlatformVoiceInput(private val context: Context, private val recognizer: RecognizerRef) : VoiceInput {
    private var rec: SpeechRecognizer? = null
    private var listener: VoiceListener? = null
    private val main = Handler(Looper.getMainLooper())

    override fun start(languageTag: String, listener: VoiceListener) {
        cancel()
        this.listener = listener
        val r = try {
            PlatformVoiceProvider.create(context, recognizer)
        } catch (e: RuntimeException) {
            null
        }
        if (r == null) {
            main.post { end()?.onError(VoiceErrorKind.UNAVAILABLE) }
            return
        }
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { this@PlatformVoiceInput.listener?.onReady() }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { this@PlatformVoiceInput.listener?.onLevel(PlatformVoiceProvider.levelOf(rmsdB)) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { this@PlatformVoiceInput.listener?.onEndOfSpeech() }
            override fun onError(error: Int) {
                Log.i(TAG, "recognition error $error") // the code only, never text
                end()?.onError(PlatformVoiceProvider.mapError(error))
            }
            override fun onResults(results: Bundle?) { end()?.onFinal(PlatformVoiceProvider.firstResult(results)) }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = PlatformVoiceProvider.firstResult(partialResults)
                if (text.isNotEmpty()) this@PlatformVoiceInput.listener?.onPartial(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        try {
            r.startListening(PlatformVoiceProvider.recognizerIntent(context, languageTag))
        } catch (e: RuntimeException) {
            Log.w(TAG, "start failed: ${e.javaClass.simpleName}")
            main.post { end()?.onError(VoiceErrorKind.CLIENT) }
        }
    }

    override fun stop() {
        try {
            rec?.stopListening()
        } catch (e: RuntimeException) {
            Log.w(TAG, "stop failed: ${e.javaClass.simpleName}")
        }
    }

    override fun cancel() {
        listener = null
        val r = rec ?: return
        rec = null
        try {
            r.cancel()
            r.destroy()
        } catch (e: RuntimeException) {
            Log.w(TAG, "cancel failed: ${e.javaClass.simpleName}")
        }
    }

    /** Ends the session: releases the recogniser and returns the listener (null if already ended or cancelled). */
    private fun end(): VoiceListener? {
        val l = listener
        listener = null
        val r = rec
        rec = null
        if (r != null) main.post {
            try {
                r.destroy()
            } catch (e: RuntimeException) {
                Log.w(TAG, "destroy failed: ${e.javaClass.simpleName}")
            }
        }
        return l
    }

    private companion object {
        const val TAG = "QalVoice"
    }
}
