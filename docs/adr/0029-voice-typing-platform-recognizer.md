# ADR 0029 — Voice typing over the platform speech recogniser

**Status:** Accepted · **Date:** 2026-11-18 · **Plan steps:** voice typing (owner request); cloud speech-to-text is V5.1/V5.2, the iOS companion is V5.3

## Context
The owner asked for voice typing in the Android keyboard: a clearly reachable microphone, the keyboard language as the
default recognition language, a way to change it, every language the recognition provider supports, honest handling of
languages it does not support, recording / processing / error states, a stop button, and the recognised text inserted
into the field. Amharic, Tigrinya and Afaan Oromoo support on phones' speech services is not documented anywhere we could
find (research notes, 2026-11-18): it has to be detected at run time, never assumed. `:ime` must stay network-free
(`NoNetworkTest`) and never log user content (plan N2).

## Decisions

**Recogniser.** Android's `SpeechRecognizer`, i.e. the phone's speech service (usually Google's), reached over IPC. The
keyboard opens no connection itself, so `:ime` stays network-free; the service may use the network, which the disclosure
says. No on-device-only recogniser (`createOnDeviceSpeechRecognizer`, API 31) is forced: the service decides. No
foreground service: the keyboard listens only after a mic tap, only while it is shown, and cancels when it hides, when the
field changes, and when the setting is turned off (a microphone foreground service started by an IME is not allowed on
Android 14+ and is not needed).

**Port.** `VoiceInput` (start(tag) / stop / cancel; callbacks ready, level, partial, end of speech, final, error(kind)) and
`VoiceProvider` (installed?, recognisers in preference order, their languages, a session per recogniser) are pure Kotlin
in `:ime/voice`. `VoiceController` is the state machine and knows neither Android nor the platform service.
`PlatformVoiceProvider` / `PlatformVoiceInput` implement the port over `SpeechRecognizer` on the main thread, as the API
requires. A cloud recogniser later is one more `VoiceProvider` (or one more `RecognizerRef` from a composite provider)
whose `languages` answers from the gateway's list (am, om via Google Chirp) and whose `VoiceInput` records and uploads on
the user's tap; the controller, panel, language memory and insertion do not change. It would live outside `:ime` (like
`AiActions`), injected by `:app`, since `:ime` must not hold network code.

**Language mapping.** Keyboard language to BCP-47 tags in preference order: am `am-ET`; ti `ti-ET`, `ti-ER`; om `om-ET`;
en the device locale when it is English (e.g. `en-GB`), then `en-US`. A tag the user chose for the keyboard language comes
first (then that language's defaults). Tags are normalised (`am_et` -> `am-ET`) and matched exactly first, then by language
(`am` for `am-ET`).

**Support detection and recogniser choice.** On Android 13+ `SpeechRecognizer.checkRecognitionSupport` lists a service's
online, installed, downloadable and pending languages; the answer is cached per recogniser and per day (in memory, tags
only), with a 4 s timeout for services that never answer. Order: the system default service first; if it does not declare
the language and **Google's service** is installed and is not the default, Google's is asked and used. Google's service is
found by querying `RecognitionService.SERVICE_INTERFACE` and picking a service of `com.google.android.googlequicksearchbox`
(the Google app) or else `com.google.android.tts` (Speech Services by Google); the class name comes from the query, never
hard-coded, and it is used through `SpeechRecognizer.createSpeechRecognizer(context, component)`. The default service is
read from the `voice_recognition_service` secure setting where readable (unknown otherwise: Google may then be asked twice,
harmlessly). This covers Samsung phones whose default is Samsung's own service. Before Android 13 nothing can be asked: the
first tag is tried; `ERROR_LANGUAGE_NOT_SUPPORTED` (API 31+) marks it rejected for the day and the next tag, then the next
service, is tried automatically (at most three times); `ERROR_LANGUAGE_UNAVAILABLE` says a download or connection is
needed; a generic client/server error on a non-English language that never produced a result here is shown as "may not be
available" (not cached, so a transient failure does not hide the language). Results never assume the Ethiopian languages
work.

**UI.** A microphone in the one bar (ADR 0007 D45), right side before the gear; hidden in password fields, fields without a
text model, fields whose app asked for no mic (`privateImeOptions` `nm` / `...noMicrophoneKey`), when no recognition
service is installed (`SpeechRecognizer.isRecognitionAvailable`, re-read off the main thread when the keyboard shows), and
when the "Voice typing" setting is off (default on). Tap: the open composition is committed, the emoji panel and AI actions
close, and the **voice panel** replaces the key area at the key area's height (no jump): a language chip and "ABC" (back to
the keyboard) on top; the one-time privacy note; a status line (Starting…, Listening. Speak now., Processing…, or a plain
error); the partial transcript (a preview, never committed until final); a big accent button (stop while listening, with a
ring driven by `onRmsChanged`; a microphone to start or retry) and Try again after errors. The chip opens a picker (the
session is cancelled while choosing): Amharic, Tigrinya, Afaan Oromoo, English with "Works offline", "Needs internet",
"May not be available" (cannot check before Android 13) or "Not available on this phone's speech service", then "More
languages" listing every other tag the services declare. A choice that can work is remembered per keyboard language
(`KeyboardSettings.voiceLanguages`, device-local, not synced). The mic tapped again while listening stops; Back closes the
picker, then the panel. Unsupported-language wording: "Amharic voice typing isn't available on this phone's speech
service. Try English, or install or update Google's speech services." — no promise that installing will help.

**Insertion.** The final result (or, when the user stopped and no final result came, the last partial one) is committed
after finishing any composition, with a leading space when the cursor follows a non-space (not after an opening bracket,
`#`, `@`, the Ethiopic wordspace, nor before closing punctuation such as `.` or `።`), whitespace tidied, and, for English
only, the first letter capitalised where the field's caps mode asks for it. It goes through `KeyboardController
.commitVoiceText` straight to the `InputConnectionAdapter`, bypassing the composer and the `WordLearner`, so **dictated
words are never learned** into the personal dictionary (privacy, and recognition errors would poison it) and the next
typed word is not linked to them.

**Permission.** RECORD_AUDIO is declared by the app and granted to the package the keyboard service runs in. An input
method cannot show the runtime dialog, so without it the mic sends `VoiceIntents.ACTION_VOICE_PERMISSION` (our package,
`FLAG_ACTIVITY_NEW_TASK`) to `VoicePermissionActivity` in `:app`: not exported, its own task (`taskAffinity=""`, excluded
from recents), explains why, asks, and finishes on grant with "Tap the microphone on the keyboard to start" so the user is
back in the app they were typing in. When Android will not show the dialog again it explains the path and opens the app's
settings page. (The existing `EXTRA_OPEN_LINK` deep link goes through `MainActivity` with `CLEAR_TOP`, which would leave the
user in our app after granting, so an action intent was used instead.)

**Privacy.** First use shows, in the panel: "Your phone's speech service (for example Google) turns your voice into text
and may use the internet to do it. Qal doesn't record or store your voice." The privacy dashboard has a Voice typing
section saying the same and that the text is never learned. Transcripts live only in the controller's state until
inserted; logs carry error codes only; nothing goes to analytics or crash reports. The speech service's own privacy terms
apply to the audio.

**Accessibility.** The bar mic is an `ExploreByTouchHelper` virtual Button ("Voice typing" / "Stop voice typing and insert
the text"); the panel uses standard views with Button roles and labels (language chip "Voice typing language: Amharic
(Ethiopia). Change", "Back to keyboard", stop / start / try again); the status line is a polite live region, so listening,
processing and errors are announced. Colours are existing theme tokens in contrast-tested pairs.

**iOS.** Keyboard extensions have no microphone access, so voice typing on iOS is the companion-app flow of plan V5.3
(record in the app, insert pending text through the shared container); `SFSpeechRecognizer` has no evidence of am/ti/om,
so iOS would use the cloud recogniser above.

## Why no cloud recogniser yet
It needs a backend endpoint, per-minute cost control, data-logging opt-out verification with the provider, a consent and
plan decision, and audio capture code outside `:ime`. Google Cloud Speech (Chirp 2) covers am and om; nobody we found
covers ti. The platform service is free, already on most phones, and may cover all three where Gboard does; the port keeps
the cloud path a drop-in.

## Consequences
- Which of am / ti / om work depends on the phone's speech service and is only known at run time; the panel says so.
- On Android 7–12 a language can only be found unsupported by trying it once (one failed start, then remembered for the day).
- A device with no recognition service shows no mic. Mic blocked by the Android 12+ privacy toggle surfaces as the audio error.
- Not done: cloud recognition (V5.1/V5.2), the iOS companion (V5.3), continuous dictation (the service ends after a pause;
  tap again), voice commands and punctuation words, a pending "start listening after the permission was granted".
