package com.qalsmartkeyboard.ime.voice

/** What the keyboard knows about one language on one recogniser. */
enum class SupportLevel {
    /** Declared as installed on the device: works offline. */
    ON_DEVICE,

    /** Declared as supported online. */
    ONLINE,

    /** The recogniser cannot be asked (Android before 13, or it did not answer): the language has to be tried. */
    UNKNOWN,

    /** Not declared by the recogniser, or it rejected the language today. */
    UNSUPPORTED;

    val usable: Boolean get() = this != UNSUPPORTED
}

/** The language tag and recogniser a session uses, and how sure the keyboard is that it works. */
data class VoiceChoice(val tag: String, val recognizer: RecognizerRef, val support: SupportLevel)

/**
 * What recognisers said about their languages, cached per recogniser and per (UTC) day, so the keyboard asks a
 * speech service at most once a day ([VoiceProvider.languages] binds to another app). Also remembers the tags a
 * recogniser rejected today (old Android versions only tell by failing) and the tags it produced a result for.
 * In memory only: it holds language tags, never user content.
 */
class VoiceSupportCache(private val now: () -> Long = System::currentTimeMillis) {
    private data class Key(val recognizer: String, val day: Long)

    private val languages = HashMap<Key, RecognizerLanguages?>()
    private val rejected = HashMap<Key, MutableSet<String>>()
    private val worked = HashMap<String, MutableSet<String>>()

    private fun key(r: RecognizerRef) = Key(r.id, Math.floorDiv(now(), DAY_MS))

    /** The recogniser was asked today (its answer may be "cannot tell", null). */
    fun isKnown(r: RecognizerRef): Boolean = languages.containsKey(key(r))

    fun languages(r: RecognizerRef): RecognizerLanguages? = languages[key(r)]

    fun put(r: RecognizerRef, langs: RecognizerLanguages?) {
        languages[key(r)] = langs
    }

    /** [r] failed [tag] with a language error today. */
    fun reject(r: RecognizerRef, tag: String) {
        rejected.getOrPut(key(r)) { HashSet() } += VoiceLanguages.normalize(tag)
    }

    fun isRejected(r: RecognizerRef, tag: String): Boolean = rejected[key(r)]?.contains(VoiceLanguages.normalize(tag)) == true

    /** [r] produced a result for [tag] (so a later generic error is not blamed on the language). */
    fun confirm(r: RecognizerRef, tag: String) {
        worked.getOrPut(r.id) { HashSet() } += VoiceLanguages.normalize(tag)
        rejected[key(r)]?.remove(VoiceLanguages.normalize(tag))
    }

    fun isConfirmed(r: RecognizerRef, tag: String): Boolean = worked[r.id]?.contains(VoiceLanguages.normalize(tag)) == true

    companion object {
        const val DAY_MS = 86_400_000L
    }
}

/**
 * Picks the recogniser and tag for a session (ADR 0029). For each recogniser in the provider's order (system default
 * first, then Google's service if it is installed and is not the default), the tags are matched exactly, then by
 * language (`am` for `am-ET`); the first recogniser that declares one of them wins. So the default service is used
 * whenever it supports the language, and Google's only when the default does not. When no recogniser declares the
 * language but one cannot be asked (Android before 13), the first tag it has not rejected today is tried
 * ([SupportLevel.UNKNOWN]). Otherwise the result is [SupportLevel.UNSUPPORTED].
 */
class VoiceSupport(private val provider: VoiceProvider, val cache: VoiceSupportCache = VoiceSupportCache()) {
    /** Asks every recogniser not asked today (one after the other), then calls [done]. */
    fun loadAll(done: () -> Unit) = load(provider.recognizers(), 0, stopWhenSupported = null, done = done)

    private fun load(rs: List<RecognizerRef>, i: Int, stopWhenSupported: List<String>?, done: () -> Unit) {
        if (i >= rs.size) {
            done()
            return
        }
        val next = { load(rs, i + 1, stopWhenSupported, done) }
        val r = rs[i]
        val then = {
            if (stopWhenSupported != null && declared(r, stopWhenSupported) != null) done() else next()
        }
        if (cache.isKnown(r)) {
            then()
        } else {
            provider.languages(r) { langs ->
                cache.put(r, langs)
                then()
            }
        }
    }

    /** Resolves [tags] (most preferred first) to a [VoiceChoice], asking recognisers only as far as needed. */
    fun resolve(tags: List<String>, done: (VoiceChoice) -> Unit) {
        val rs = provider.recognizers()
        load(rs, 0, stopWhenSupported = tags) { done(choose(tags, rs)) }
    }

    /** The choice for [tags] from what is cached now (no recogniser is asked). */
    fun choose(tags: List<String>, rs: List<RecognizerRef> = provider.recognizers()): VoiceChoice {
        for (r in rs) declared(r, tags)?.let { return it }
        for (r in rs) {
            if (cache.languages(r) != null) continue
            val tag = tags.firstOrNull { !cache.isRejected(r, it) } ?: continue
            return VoiceChoice(VoiceLanguages.normalize(tag), r, SupportLevel.UNKNOWN)
        }
        return VoiceChoice(VoiceLanguages.normalize(tags.first()), rs.firstOrNull() ?: RecognizerRef.DEFAULT, SupportLevel.UNSUPPORTED)
    }

    /** A tag of [tags] that [r] declares (exact matches over all tags first, then language matches), or null. */
    private fun declared(r: RecognizerRef, tags: List<String>): VoiceChoice? {
        val langs = cache.languages(r) ?: return null
        val all = langs.all
        val tag = tags.firstNotNullOfOrNull { t -> VoiceLanguages.exact(t, all)?.takeIf { !cache.isRejected(r, it) } }
            ?: tags.firstNotNullOfOrNull { t -> VoiceLanguages.sameLanguage(t, all)?.takeIf { !cache.isRejected(r, it) } }
            ?: return null
        return VoiceChoice(tag, r, if (tag in langs.onDevice) SupportLevel.ON_DEVICE else SupportLevel.ONLINE)
    }

    /** Every tag some recogniser declares (normalised, from the cache), for "More languages". */
    fun declaredTags(): Set<String> = provider.recognizers().flatMap { cache.languages(it)?.all.orEmpty() }.toSet()
}
