# ADR 0049 — Voice + AI chain (T8.12)

**Status:** Accepted · **Date:** 2026-11-25 · **Plan step:** T8.12 (builds on ADR 0029 voice typing, ADR 0031/0037 AI panels)

## Context
After dictation the user often wants the transcript fixed or in another language. T8.12 adds, to the Android keyboard
only: (1) one-tap **Fix grammar** and **Translate** on what was just dictated, (2) a **"speak in one language, insert in
another"** mode. Both reuse the existing `/ai/transform` (`GRAMMAR_CORRECTION`) and `/ai/translate` calls through
`AiActions`; no backend or contract change. Server ASR metering (V5.1) does not exist yet, so no new quota: each AI call
is metered as the existing GRAMMAR / TRANSLATE feature. Plan N2: no audio, transcript or result is stored or logged.

## Decisions

**D1. No new port.** `AiActions` is already pure Kotlin in `:ime`, so `VoiceController` takes `ai: () -> AiActions?`
(null while the app registered none). `:ime` stays network-free (`NoNetworkTest`).

**D2. Follow-ups (offer after dictation).** When a dictation into the **app's field** (not a keyboard-own field: those have
their own AI controls) is inserted, and `VoicePrefs.followUps` is on, and AI is usable (`ai()` non-null, `availability.block
== NONE`), the controller exposes `followUp: DictationFollowUp?`. It is a one-row strip above the keys: **Fix grammar**,
**Translate ▾** (to the last destination, or `defaultDestination(package)`), **✕**. It never calls the server by itself.
It is dismissed by: the ✕, any typing / cursor move / field change / keyboard hide (the host calls
`VoiceController.onEditorChanged()` for changes that are not its own commit), a new dictation, or 20 s without a tap.
Password and no-text-model fields never offer it (voice is not offered there anyway).

**D3. Applying a result.** Tapping an action sends the dictated text (`DictationFollowUp.text`, exactly what was committed,
at most `AiTextTarget.MAX_CHARS`) as `AiRequest(newRequestId(), AiTask.Grammar | AiTask.Translate(destination, source), text)`.
While it runs the strip shows a spinner (the keys stay usable, which dismisses it). On success the host replaces the
dictated text in the field with `VoiceHost.replaceDictated(expected = text, replacement = result)`: it replaces only if
the text just before the cursor still reads `expected` (trailing whitespace kept), else it does nothing and the
controller reports `Failed(Moved)` (the field changed meanwhile; nothing is inserted elsewhere). After a replace the strip
shows **Undo** (host `replaceDictated(expected = result, replacement = original)`), kept until dismissed. Grammar is always
in the dictation's own language (server rule). Failures map to `AiFailure` and show the existing short messages
(`AiMessages`); the dictated text is untouched.

**D4. "Insert in" mode.** `VoicePrefs.insertLanguage: String?` (null = as spoken; otherwise one of the translation
catalogue languages, a plain language, not a profile). Chosen from a chip on the voice panel (picker = the translation
catalogue, `AiActions.translationCatalog()`), remembered per device in `KeyboardSettings.voiceInsertLanguage`. With it set
and different from the spoken language (`VoiceLanguages.keyboardLanguageOf(tag)`), a final result is **not committed
immediately**: the panel shows "Translating…", the text goes to `AiTask.Translate(destination(language), sourceLanguage =
spoken)`, and the translation is committed instead. If the call fails or AI is blocked (offline, quota, not signed in), the
**original dictation is committed** (nothing the user said is lost) and the strip shows the failure plus a **Translate**
retry (a D2 follow-up). Translation of the dictation counts as one TRANSLATE use. Off by default; when AI is unusable the
chip is hidden.

**D5. Privacy.** Dictated text and results live only in controller fields (`followUp`, in-flight request) until dismissed
or the session ends; `toString` of every new type omits text; nothing is logged, persisted or put in analytics. Tests prove
`toString` and that `cancel()` / dismissal clears them.

**D6. Settings (device-local, not synced).** `voiceFollowUps: Boolean = true` ("Offer Fix grammar / Translate after
dictation") and `voiceInsertLanguage: String? = null`, in `KeyboardSettings`, shown on the settings' Voice section.

## API every slice codes against (`:ime/voice`, package `com.qalsmartkeyboard.ime.voice`)
```kotlin
sealed interface FollowUpPhase {
    data object Offer : FollowUpPhase
    data class Working(val action: FollowUpAction) : FollowUpPhase
    data class Done(val action: FollowUpAction) : FollowUpPhase          // result inserted; Undo available
    data class Failed(val failure: AiFailure?, val moved: Boolean = false, val action: FollowUpAction) : FollowUpPhase
}
enum class FollowUpAction { GRAMMAR, TRANSLATE }
class DictationFollowUp(val text: String, val spokenLanguage: String /* am|ti|om|en|ar */, val destination: AiDestination, val phase: FollowUpPhase) // toString has no text

// VoicePrefs additions (InMemory defaults: followUps = true, insertLanguage = null)
val followUps: Boolean
val insertLanguage: String?
fun chooseInsertLanguage(language: String?)

// VoiceHost addition (default impl returns false so existing hosts and tests compile)
fun replaceDictated(expected: String, replacement: String): Boolean

// VoiceController additions
constructor params (before onChanged, defaulted): ai: () -> AiActions? = { null }, packageName: () -> String? = { null },
    runOnMain: (() -> Unit) -> Unit = { it() }, newRequestId: () -> String = { UUID.randomUUID().toString() }
val followUp: DictationFollowUp?
val insertLanguage: String?                 // effective: pref, null when AI unusable
val insertTranslating: Boolean              // final result is being translated
fun insertLanguageChoices(): List<AiDestination>   // plain languages of the catalogue, for the chip picker
fun chooseInsertLanguage(language: String?)
fun fixGrammar()                             // FollowUp action
fun translateFollowUp(destination: AiDestination? = null)
fun undoFollowUp()
fun dismissFollowUp()
fun onEditorChanged()                        // host: the editor changed for a reason other than our commit
```
The panel view (`VoicePanelView`) shows the "Insert in" chip; a new `DictationFollowUpView` (the strip) renders `followUp`
and calls the four actions above. `KeyboardImeService` wires the host (`replaceDictated`, `onEditorChanged`), the AI actions
and the strip's place in the layout (S4).

## Not in scope
Companion-app Dictation screen, server ASR metering in seconds (V5.1), speech-to-speech (D6.8). The tracker's "tested on
device in am and en" is the owner's phone test: Amharic recognition needs Google's speech service (see handoff notes).
