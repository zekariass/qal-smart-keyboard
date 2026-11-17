# android/app

Application module (Compose companion UI). Assembles ime, data, billing-* libraries; play/direct flavours. Plan steps A1.1, A1.7, A2.x.

## Onboarding (A1.7, package `onboarding`)
First launch shows a five-step flow; the result ("completed or skipped") is stored by `:data`
(`SettingsRepository.onboardingCompleted`, key `onboarding_completed`, not part of `KeyboardSettings`, so "Reset to defaults"
does not bring it back). It can be skipped at any step ("Skip setup" marks it done without saving the language draft) and
re-run from the settings screen ("Run setup again").

| Step | What it does |
|---|---|
| Privacy | Welcome and short notice (offline typing, no account, typed text stays on the device, no AI features in this version, settings stored locally) plus a link to the full notice. The URL is the placeholder `privacy_notice_url` in `strings.xml`; replace it before release. |
| Enable | Opens `Settings.ACTION_INPUT_METHOD_SETTINGS`. "Continue" is enabled once `InputMethodManager.enabledInputMethodList` contains our package. |
| Select | `showInputMethodPicker()`. Selected = `Settings.Secure.DEFAULT_INPUT_METHOD` starts with our package. |
| Languages | Enable/disable, reorder and "make first" for am, ti, om, en; am and ti choose Phonetic QWERTY (default, ADR 0007 U3) or Fidel grid. Saved through `SettingsRepository.update` when "Continue" is pressed; a language whose mode changed forgets its remembered layout so the keyboard opens in the chosen mode. |
| Tutorial | A text field to try the keyboard, plus tips that match `android/ime/README.md` (phonetic `selam`, Fidel two-key entry, globe tap/long-press, ABC key, long-press space, punctuation long-press, DRAFT tag). |

System state is re-read in `MainActivity.onResume` and `onWindowFocusChanged` (the picker is a dialog window), so the flow moves on by itself when the user comes back from system settings, and falls back if the keyboard was turned off again.

| File | Role |
|---|---|
| `OnboardingModel.kt` | Android-free state machine (`OnboardingFlow` step rules, `LanguageDraft`, `OnboardingModel` over `SettingsRepository`). JVM-tested. |
| `OnboardingViewModel.kt` | Thin `ViewModel` (survives rotation) and `ImeStatusReader` (system facts). |
| `OnboardingSaver.kt` | Keeps the step and the language draft in the `SavedStateHandle` (ADR 0007 D12), so the flow resumes the same step after process death (for example after enabling the keyboard in system settings). The IME status is re-read on resume; restoring is lenient (garbage falls back to defaults). |
| `OnboardingScreen.kt` | Compose UI: scrollable content with pinned Back/Continue, content descriptions, status as text (not colour only); works in portrait, landscape and large text. |

## Personal dictionary (A1.8)
`PersonalDictionarySection` (bottom of the settings screen): per language the number of words, "Show words" (paged, 50 at a time) with a Delete button per word, and "Clear all" behind a confirmation. It uses the same process-wide `UserDictionary` as the keyboard (`android/ime/README.md`), so a deletion also reaches a running keyboard and is saved encrypted. Words appear on screen only; nothing is logged or uploaded.

All user-visible text is in `res/values/strings.xml` (English; add `values-am`, `values-ti`, `values-om` later).

## Tests
`./gradlew :app:testPlayDebugUnitTest :app:testDirectDebugUnitTest` (from `android/`; `testDebugUnitTest` skips the flavoured app tests): step rules, IME status parsing, language draft rules and the whole new-user
flow over a real DataStore on a temp file (including restart persistence of the completed flag and the language choice).
