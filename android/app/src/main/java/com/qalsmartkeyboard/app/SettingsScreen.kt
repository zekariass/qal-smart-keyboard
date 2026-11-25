package com.qalsmartkeyboard.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.qalsmartkeyboard.app.auth.AccountStatus
import com.qalsmartkeyboard.app.settings.AboutPage
import com.qalsmartkeyboard.app.settings.AccountPage
import com.qalsmartkeyboard.app.settings.AdvancedPage
import com.qalsmartkeyboard.app.settings.AllLanguagesPage
import com.qalsmartkeyboard.app.settings.AppLanguagePage
import com.qalsmartkeyboard.app.settings.DictionaryPage
import com.qalsmartkeyboard.app.settings.LanguagePage
import com.qalsmartkeyboard.app.settings.LanguagesPage
import com.qalsmartkeyboard.app.settings.PreferencesPage
import com.qalsmartkeyboard.app.settings.SettingsHomeActions
import com.qalsmartkeyboard.app.settings.SettingsHomePage
import com.qalsmartkeyboard.app.settings.SettingsHomeState
import com.qalsmartkeyboard.app.settings.SettingsNavigator
import com.qalsmartkeyboard.app.settings.SettingsPage
import com.qalsmartkeyboard.app.settings.SettingsUpdate
import com.qalsmartkeyboard.app.settings.TranslationPage
import com.qalsmartkeyboard.app.settings.VoiceInsertLanguagePage
import com.qalsmartkeyboard.app.settings.VoiceTypingPage
import com.qalsmartkeyboard.app.settings.appLanguageLabel
import com.qalsmartkeyboard.app.settings.rememberSettingsNavigator
import com.qalsmartkeyboard.app.themes.ThemePickerModel
import com.qalsmartkeyboard.app.themes.themeStore
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.ime.AppLanguage
import com.qalsmartkeyboard.ime.ai.AiLanguages
import com.qalsmartkeyboard.ime.ai.TranslationCatalog
import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess
import com.qalsmartkeyboard.ime.FidelVariant
import com.qalsmartkeyboard.ime.LatinLayout
import com.qalsmartkeyboard.ime.R as ImeR
import kotlinx.coroutines.launch

/** A typing language's name in the UI language (ADR 0032; the keyboard's resources). */
@Composable
internal fun languageName(code: String): String = when (code) {
    "am" -> stringResource(ImeR.string.language_name_am)
    "ti" -> stringResource(ImeR.string.language_name_ti)
    "om" -> stringResource(ImeR.string.language_name_om)
    "en" -> stringResource(ImeR.string.language_name_en)
    "ar" -> stringResource(ImeR.string.language_name_ar)
    else -> AiLanguages.name(code) // a translation language (ADR 0040): the platform's name, else the catalogue's
}

/**
 * The keyboard settings (A1.5, redesigned in ADR 0036): the home page and its sub-pages, with their own back stack
 * ([navigator]; the system Back pops it). Screens outside settings (themes, Word Art, the e-mail writer, privacy,
 * sign-in, setup) belong to the activity and are reached through the `on…` callbacks.
 */
@Composable
fun SettingsScreen(
    settings: KeyboardSettings,
    repository: SettingsRepository,
    modifier: Modifier = Modifier,
    navigator: SettingsNavigator = rememberSettingsNavigator(),
    themeAccess: PremiumThemeAccess = PremiumThemeAccess.NEEDS_CHECK,
    systemDark: Boolean = false,
    /** Qal is turned on and selected; when false the home page shows a setup prompt. */
    keyboardReady: Boolean = true,
    account: AccountStatus = AccountStatus.SignedOut(null),
    /** Who is signed in (masked phone or e-mail, ADR 0035), for "Signed in as ..."; null when unknown. */
    accountLabel: String? = null,
    /** Opens the sign-in screen (owned by the host); the keyboard-opened home's account row uses it when nobody is signed in. */
    onSignIn: () -> Unit = {},
    /** Opened from the keyboard's gear: the home page's arrow and Back call [onBackToTyping]. */
    fromKeyboard: Boolean = false,
    onBackToTyping: () -> Unit = {},
    onRunSetup: () -> Unit = {},
    /** Opens the theme picker (`ThemesScreen`), owned by the host; with a theme id, on that theme's large preview. */
    onOpenThemes: (previewId: String?) -> Unit = {},
    /** Opens the Word Art screen, owned by the host. */
    onOpenWordArt: () -> Unit = {},
    /** Opens the "Write Email with AI" screen, owned by the host (ADR 0031). */
    onOpenEmail: () -> Unit = {},
    /** Opens the privacy area (A2.6), owned by the host. */
    onOpenPrivacy: () -> Unit = {},
    /** The account section (A2.2), supplied by the host that owns the sign-in state. */
    accountContent: @Composable () -> Unit = {},
    /** The dictionary-sync switch (A2.4), supplied by the host that owns the account state. */
    syncContent: @Composable () -> Unit = {},
    /** The personal dictionary (A1.8); a slot so JVM tests can render the screen without the native core. */
    dictionaryContent: @Composable () -> Unit = { PersonalDictionarySection() },
    appVersion: String = BuildConfig.VERSION_NAME,
    /** The native core's version (About); null hides the row (JVM tests have no native core). */
    coreVersion: String? = null,
    /**
     * The translation language catalogue for "Translate into" (ADR 0040): the host fetches or reads the device's copy
     * off the main thread. The default (tests, no AI client) is the five keyboard languages.
     */
    translationCatalog: suspend () -> TranslationCatalog = { TranslationCatalog.BUNDLED },
) {
    val scope = rememberCoroutineScope()
    val update: SettingsUpdate = { transform -> scope.launch { repository.update(transform) } }
    val themes = remember(repository) { ThemePickerModel(repository.themeStore()) }
    val back: () -> Unit = { navigator.back() }

    BackHandler(enabled = !navigator.isHome) { navigator.back() }
    BackHandler(enabled = navigator.isHome && fromKeyboard, onBack = onBackToTyping)

    val destination = navigator.current
    when (destination?.page) {
        null -> SettingsHomePage(
            settings = settings,
            state = SettingsHomeState(
                themeAccess = themeAccess,
                systemDark = systemDark,
                keyboardReady = keyboardReady,
                accountSummary = if (account == AccountStatus.Registered && accountLabel != null) {
                    stringResource(R.string.acct_signed_in_as, accountLabel)
                } else {
                    stringResource(
                        when (account) {
                            AccountStatus.Registered -> R.string.settings_account_signed_in
                            AccountStatus.Guest -> R.string.settings_account_guest
                            is AccountStatus.SignedOut -> R.string.settings_account_signed_out
                        },
                    )
                },
                signedIn = account == AccountStatus.Registered,
                appLanguageSummary = appLanguageLabel(AppLanguage.get(LocalContext.current)),
                fromKeyboard = fromKeyboard,
            ),
            actions = SettingsHomeActions(
                open = { navigator.open(it) },
                selectTheme = { id ->
                    // A locked premium theme is not applied: the picker opens on it, with "Unlock with Premium".
                    if (themes.canApply(id, themeAccess)) scope.launch { themes.apply(id, themeAccess) } else onOpenThemes(id)
                },
                moreThemes = { onOpenThemes(null) },
                runSetup = onRunSetup,
                openEmail = onOpenEmail,
                openWordArt = onOpenWordArt,
                openPrivacy = onOpenPrivacy,
                backToTyping = onBackToTyping,
                signIn = onSignIn,
            ),
            modifier = modifier,
        )
        SettingsPage.LANGUAGES -> LanguagesPage(
            settings = settings,
            onBack = back,
            onOpenLanguage = { navigator.open(SettingsPage.LANGUAGE, it) },
            onMove = { code, to -> scope.launch { repository.moveLanguage(code, to) } },
            onManage = { navigator.open(SettingsPage.ALL_LANGUAGES) },
            modifier = modifier,
        )
        SettingsPage.LANGUAGE -> {
            val language = destination.arg?.let(settings::language)
            if (language == null) {
                LaunchedEffect(destination) { navigator.back() }
            } else {
                val code = language.languageCode
                LanguagePage(
                    language = language,
                    onBack = back,
                    // ADR 0044: the page shows the mode the keyboard opens in, and a pick there is what the keyboard uses.
                    modeInForce = settings.modeInForce(code) ?: language.inputMode,
                    onMode = { m -> scope.launch { repository.update { it.withLanguageMode(code, m) } } },
                    onShowSuggestions = { on -> scope.launch { repository.updateLanguage(code) { it.copy(showTransliterationCandidates = on) } } },
                    modifier = modifier,
                    latinLayout = LatinLayout.fromName(settings.latinLayouts[code]) ?: LatinLayout.DEFAULT,
                    onLatinLayout = { l -> scope.launch { repository.update { it.copy(latinLayouts = it.latinLayouts + (code to l.name)) } } },
                    fidelVariant = FidelVariant.fromName(settings.fidelLayouts[code]) ?: FidelVariant.DEFAULT,
                    onFidelVariant = { v -> scope.launch { repository.update { it.copy(fidelLayouts = it.fidelLayouts + (code to v.name)) } } },
                )
            }
        }
        SettingsPage.ALL_LANGUAGES -> AllLanguagesPage(
            settings = settings,
            onBack = back,
            onEnable = { code, on -> scope.launch { repository.setLanguageEnabled(code, on) } },
            modifier = modifier,
        )
        SettingsPage.PREFERENCES -> PreferencesPage(settings, back, update, modifier)
        SettingsPage.VOICE -> VoiceTypingPage(settings, back, update, modifier, onOpenInsertLanguage = { navigator.open(SettingsPage.VOICE_INSERT_LANGUAGE) })
        SettingsPage.VOICE_INSERT_LANGUAGE -> {
            val catalog by produceState(TranslationCatalog.BUNDLED, translationCatalog) { value = translationCatalog() }
            VoiceInsertLanguagePage(settings, back, update, modifier, catalog)
        }
        SettingsPage.TRANSLATION -> {
            val catalog by produceState(TranslationCatalog.BUNDLED, translationCatalog) { value = translationCatalog() }
            TranslationPage(settings, back, update, modifier, catalog)
        }
        SettingsPage.DICTIONARY -> DictionaryPage(back, syncContent, dictionaryContent, modifier)
        SettingsPage.ACCOUNT -> AccountPage(back, accountContent, modifier)
        SettingsPage.APP_LANGUAGE -> AppLanguagePage(back, modifier)
        SettingsPage.ADVANCED -> AdvancedPage(settings, back, update, modifier)
        SettingsPage.ABOUT -> AboutPage(appVersion, coreVersion, back, modifier)
    }
}
