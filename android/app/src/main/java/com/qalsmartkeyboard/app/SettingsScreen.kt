package com.qalsmartkeyboard.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.qalsmartkeyboard.app.settings.VoiceTypingPage
import com.qalsmartkeyboard.app.settings.appLanguageLabel
import com.qalsmartkeyboard.app.settings.rememberSettingsNavigator
import com.qalsmartkeyboard.app.themes.ThemePickerModel
import com.qalsmartkeyboard.app.themes.themeStore
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.ime.AppLanguage
import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess
import com.qalsmartkeyboard.ime.R as ImeR
import kotlinx.coroutines.launch

/** A typing language's name in the UI language (ADR 0032; the keyboard's resources). */
@Composable
internal fun languageName(code: String): String = when (code) {
    "am" -> stringResource(ImeR.string.language_name_am)
    "ti" -> stringResource(ImeR.string.language_name_ti)
    "om" -> stringResource(ImeR.string.language_name_om)
    "en" -> stringResource(ImeR.string.language_name_en)
    else -> code
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
                accountSummary = stringResource(
                    when (account) {
                        AccountStatus.Registered -> R.string.settings_account_signed_in
                        AccountStatus.Guest -> R.string.settings_account_guest
                        is AccountStatus.SignedOut -> R.string.settings_account_signed_out
                    },
                ),
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
                    onMode = { m -> scope.launch { repository.updateLanguage(code) { it.copy(inputMode = m) } } },
                    onShowSuggestions = { on -> scope.launch { repository.updateLanguage(code) { it.copy(showTransliterationCandidates = on) } } },
                    modifier = modifier,
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
        SettingsPage.VOICE -> VoiceTypingPage(settings, back, update, modifier)
        SettingsPage.TRANSLATION -> TranslationPage(settings, back, update, modifier)
        SettingsPage.DICTIONARY -> DictionaryPage(back, syncContent, dictionaryContent, modifier)
        SettingsPage.ACCOUNT -> AccountPage(back, accountContent, modifier)
        SettingsPage.APP_LANGUAGE -> AppLanguagePage(back, modifier)
        SettingsPage.ADVANCED -> AdvancedPage(settings, back, update, modifier)
        SettingsPage.ABOUT -> AboutPage(appVersion, coreVersion, back, modifier)
    }
}
