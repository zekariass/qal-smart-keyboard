package com.qalsmartkeyboard.ime.voice

/** A recogniser session the test drives by hand. */
class FakeVoiceInput(val recognizer: RecognizerRef) : VoiceInput {
    var tag: String? = null
    var listener: VoiceListener? = null
    var stopped = false
    var cancelled = false

    override fun start(languageTag: String, listener: VoiceListener) {
        tag = languageTag
        this.listener = listener
    }

    override fun stop() {
        stopped = true
    }

    override fun cancel() {
        cancelled = true
    }
}

/**
 * A provider with scripted recognisers: [languages] maps a recogniser id to what it declares (absent: "cannot tell",
 * like Android before 13). [deferLanguages] holds the answers until [answerLanguages] (an asynchronous service).
 */
class FakeVoiceProvider(
    var recognizerList: List<RecognizerRef> = listOf(RecognizerRef.DEFAULT),
    val languages: MutableMap<String, RecognizerLanguages?> = HashMap(),
    override var available: Boolean = true,
) : VoiceProvider {
    val asked = ArrayList<String>()
    val inputs = ArrayList<FakeVoiceInput>()
    var deferLanguages = false
    private val pending = ArrayList<() -> Unit>()

    val last: FakeVoiceInput get() = inputs.last()

    override fun recognizers(): List<RecognizerRef> = recognizerList

    override fun languages(recognizer: RecognizerRef, done: (RecognizerLanguages?) -> Unit) {
        asked += recognizer.id
        val answer = { done(languages[recognizer.id]) }
        if (deferLanguages) pending += answer else answer()
    }

    fun answerLanguages() {
        val p = pending.toList()
        pending.clear()
        p.forEach { it() }
    }

    override fun input(recognizer: RecognizerRef): VoiceInput = FakeVoiceInput(recognizer).also { inputs += it }

    companion object {
        val GOOGLE = RecognizerRef("com.google.android.googlequicksearchbox/com.example.Recognition", isDefault = false)

        fun online(vararg tags: String) = RecognizerLanguages(tags.map { VoiceLanguages.normalize(it) }.toSet(), emptySet())
    }
}

/** The keyboard side, recording what voice typing did to it. */
class FakeVoiceHost(override var keyboardLanguage: String = "am") : VoiceHost {
    var permission = true
    var permissionRequests = 0
    var beforeCalls = 0
    var before: CharSequence? = ""
    var capital = false
    val committed = ArrayList<String>()

    override fun hasPermission() = permission
    override fun requestPermission() {
        permissionRequests++
    }
    override fun beforeVoice() {
        beforeCalls++
    }
    override fun textBeforeCursor(): CharSequence? = before
    override fun wantsCapital() = capital
    override fun commitVoiceText(text: String) {
        committed += text
    }
}
