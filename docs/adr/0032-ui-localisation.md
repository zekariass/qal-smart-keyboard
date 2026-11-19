# ADR 0032 — UI localisation: Amharic, Tigrinya and Afaan Oromoo app and keyboard UI

**Status:** Accepted (strings DRAFT under N9) · **Date:** 2026-11-19 · **Builds on:** ADR 0007 (N9 DRAFT rule, typing
languages), ADR 0028 (emoji panel), ADR 0029 (voice typing), ADR 0030 (Word Art), ADR 0031 (AI panels) · **Contract:** none

## Context
The app and the keyboard were English-only. Most strings were already Android resources (`values/strings.xml` in `:app`
and `:ime`), but the settings screen, the keyboard's key descriptions, the Enter-key labels, the writing-mode selector,
the language names, the theme names and the Word Art style names were hard-coded. Users of the four launch languages
expect the UI in their own language, and the phone may already be set to Amharic, Tigrinya or Afaan Oromoo. The
*typing* languages (ADR 0007) are a separate setting and must not change with the UI language.

## Decisions

### 1. Strings out of code
Every clearly user-visible string moved to resources (log messages, test strings, clipboard labels and the developer
"Hello core" smoke line stay as they are):

- `:app` (`values/strings.xml`, 17 new): the settings screen (title, languages, numerals, haptics, key preview, packs on
  mobile data, key height `%1$d%%`, reset) and the App language card.
- `:ime` (`values/strings.xml`, 183 new): language names (`language_name_*`), the writing-mode selector and spoken layout
  names (`kb_mode_*`, `kb_spoken_*`), every key description (`kb_key_*`), the Enter-key labels (`kb_enter_*`), 20 theme
  names (`theme_name_<id>`) and 119 Word Art style names (`wordart_style_<id>`).
- The pure keyboard code (`Layouts`, `KeyboardController`, `LanguageCatalog`, `AiLanguages`, `VoiceLanguages`) has no
  `Context`. It reads `KeyboardLabels.current`, a value object loaded from resources by the keyboard service
  (`onCreateInputView`) and the main activity, defaulting to English (`KeyboardLabels.ENGLISH`) in plain JVM tests. A
  Robolectric test checks that the English resources equal those defaults. `KeyboardTheme.name` and `WordArtStyle.label`
  stay the English names (ids, tests); screens show `ThemeNames.name(context, theme)` and
  `WordArtStyleNames.label(context, style)`, explicit id → resource maps (no `getIdentifier`), with a test that every
  catalogue entry has one.
- Fidel (ፊደል), Qubee, QWERTY, ABC, "AI", brand names and the language self-names in the space-bar picker stay as they are
  in every UI language.

### 2. Locale mechanism: platform per-app language, no AppCompat
The app is pure Compose on `ComponentActivity` with no AppCompat dependency. AppCompat's `setApplicationLocales` backport
works below Android 13 only with `AppCompatActivity`, so adopting it would mean a new dependency and new activity base
classes for one setting. Instead, `AppLanguage` (in `:ime`, because both the app and the keyboard need it):

- **Android 13+**: `LocaleManager.setApplicationLocales`. `res/xml/locales_config.xml` (en, am, ti, om) is referenced by
  `android:localeConfig`, so the system's own "App languages" settings list Qal too, and both entry points stay in sync
  (the system is the source of truth; `get` reads it). The platform recreates the activities.
- **Android 7–12**: the choice is stored in a small `SharedPreferences` file and applied by `AppLanguage.wrap()` in each
  activity's `attachBaseContext` (`createConfigurationContext`); the settings card recreates the activity.
- An OS upgrade from 12 to 13 hands a stored choice to the platform once (`syncToPlatform`, application start).
- **Default: the system language.** Nothing is stored until the user picks one, so a phone set to am/ti/om gets that UI
  automatically (Android's resource resolution), and any other system language falls back to English.
- Settings → **App language**: System default, English, አማርኛ, ትግርኛ, Afaan Oromoo, each name in its own language.

### 3. The keyboard follows the app language
The keyboard service runs in the app process. On Android 13+ the per-app locale becomes part of the process
configuration, which services receive too, and `InputMethodService` recreates its input view on that configuration change.
Two gaps remain: Android 7–12 has no per-app locale at all, and the service context is not guaranteed to carry it on
every release. So the service never relies on it: `onCreateInputView` builds every view and toast on
`AppLanguage.uiContext(this)`, which is the service itself when its configuration already has the chosen language, and
otherwise a `ContextWrapper` whose only override is `getResources()` (resources from `createConfigurationContext` with
the chosen locale). Windows, system services, theme and display metrics stay the service's. The same call reloads
`KeyboardLabels` and drops the cached key layout. A language change in the app notifies running keyboards
(`AppLanguage.addListener`), which rebuild their input view at once. The bar, AI row and panels, emoji panel, Word Art,
voice panel, key descriptions and Enter labels all follow; the typing languages, layouts and the space-bar language
picker do not change. Voice typing's list of other languages is named by the platform in the UI language.

### 4. Translations are DRAFT (N9)
`values-am`, `values-ti` and `values-om` (906 strings each: 397 app, 509 keyboard) are machine-assisted drafts following
`docs/l10n/ui-glossary-am-ti-om.md`: glossary, style rules (polite and gender-neutral address; ። ፣ « » for am/ti; Qubee
with escaped hudhaa for om; brand names in Latin), and per language the strings the drafter was least sure about. The
native-speaker panel reviews the glossary first, then the strings; reviewed files keep the same keys and arguments. The
UI does not mark DRAFT translations (unlike DRAFT language data on the candidate strip): a mistranslated button is
low-risk, and the English fallback is one setting away.

### 5. Guard rails
- `StringResourcesTest` (JVM, `:app`): for both modules, every `values-*/strings.xml` has the default's translatable key
  set, the same format arguments per key, no unescaped `'` or `"`, no stray `%`, and plurals with `one` and `other`
  (none exist today; CLDR am, ti and om all use one/other).
- `UiLocalisationSmokeTest` (Robolectric, `:ime`): the bar, the keys and the emoji panel build and draw with `am`, `ti`
  and `om` qualifiers; key descriptions, theme and style names come from the locale.
- `SettingsLocalisationTest` (Robolectric, `:app`): the main settings screen renders in each language.
- `AppLanguageTest` (Robolectric, `:ime`): storage below 13, the platform path on 13+, the keyboard's localised context.

## Consequences
- A new user-visible string must be added to all four `values` files (or the build's unit tests fail), so features ship
  with at least a DRAFT translation.
- Layout text is longer in Ge'ez and in Oromo than in English; chips and the Enter key use short forms (see the glossary)
  and the existing views already ellipsize or scroll.
- Not covered: store listing, notification channels (none yet), server-generated text (AI errors come from resources;
  server messages stay as sent), and the language-pack data (ADR 0007).
