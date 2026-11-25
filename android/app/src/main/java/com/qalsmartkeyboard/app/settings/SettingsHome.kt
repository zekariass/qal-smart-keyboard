package com.qalsmartkeyboard.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.languageName
import com.qalsmartkeyboard.app.themes.ThemeStrip
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess

/** What the home page shows that does not come from [KeyboardSettings]. */
data class SettingsHomeState(
    val themeAccess: PremiumThemeAccess = PremiumThemeAccess.NEEDS_CHECK,
    val systemDark: Boolean = false,
    /** Qal is turned on and selected as the keyboard; when not, a setup prompt sits under the themes. */
    val keyboardReady: Boolean = true,
    /** The account row's summary (signed in, guest, not signed in). */
    val accountSummary: String? = null,
    /** The App language row's summary. */
    val appLanguageSummary: String? = null,
    /**
     * Opened from the keyboard's gear: the bar's arrow (and Back) returns to typing, and the account row comes first
     * (ADR 0035).
     */
    val fromKeyboard: Boolean = false,
    /** Signed in with an account (not a guest): the keyboard-opened account row leads to the account page, else to sign-in. */
    val signedIn: Boolean = false,
)

/** The home page's actions; [open] goes to a settings page, the rest leave settings for a screen the activity owns. */
data class SettingsHomeActions(
    val open: (SettingsPage) -> Unit = {},
    val selectTheme: (String) -> Unit = {},
    val moreThemes: () -> Unit = {},
    val runSetup: () -> Unit = {},
    val openEmail: () -> Unit = {},
    val openWordArt: () -> Unit = {},
    val openCreative: () -> Unit = {},
    val openPrivacy: () -> Unit = {},
    val backToTyping: () -> Unit = {},
    /** Opens the sign-in screen (the keyboard-opened account row while nobody is signed in). */
    val signIn: () -> Unit = {},
)

/**
 * The settings home (ADR 0036, benchmarked on Gboard's settings): the theme strip at the very top, a setup prompt only
 * while the keyboard is not ready, then short rows in four groups. Each row is a title, an optional one-line value and
 * an icon; nothing is explained here that a page explains better.
 */
@Composable
fun SettingsHomePage(
    settings: KeyboardSettings,
    state: SettingsHomeState,
    actions: SettingsHomeActions,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(R.string.app_name),
        onBack = if (state.fromKeyboard) actions.backToTyping else null,
        backDescription = stringResource(R.string.settings_back_to_typing),
        modifier = modifier,
    ) {
        // ADR 0035: from the keyboard the account comes first: "Sign in" straight to the sign-in screen, or who is signed in.
        if (state.fromKeyboard) {
            SettingsRow(
                stringResource(if (state.signedIn) R.string.acct_title else R.string.acct_sign_in),
                if (state.signedIn) ({ actions.open(SettingsPage.ACCOUNT) }) else actions.signIn,
                summary = state.accountSummary,
                icon = R.drawable.ic_settings_account,
            )
        }
        ThemeStrip(settings.theme, state.themeAccess, state.systemDark, onSelect = actions.selectTheme, onMore = actions.moreThemes)
        if (!state.keyboardReady) SetupPrompt(actions.runSetup)

        SettingsHeader(stringResource(R.string.settings_group_keyboard))
        SettingsRow(
            stringResource(R.string.settings_languages_title),
            { actions.open(SettingsPage.LANGUAGES) },
            summary = settings.enabledLanguages.map { languageName(it) }.joinToString(", "),
            icon = R.drawable.ic_settings_language,
        )
        SettingsRow(stringResource(R.string.settings_preferences), { actions.open(SettingsPage.PREFERENCES) }, icon = R.drawable.ic_settings_tune)
        SettingsRow(
            stringResource(R.string.settings_voice_typing),
            { actions.open(SettingsPage.VOICE) },
            summary = stringResource(if (settings.voiceTypingEnabled) R.string.pv_on else R.string.pv_off),
            icon = R.drawable.ic_settings_mic,
        )
        SettingsRow(stringResource(R.string.settings_dictionary), { actions.open(SettingsPage.DICTIONARY) }, icon = R.drawable.ic_settings_book)

        SettingsHeader(stringResource(R.string.settings_group_tools))
        SettingsRow(
            stringResource(R.string.settings_translate_title),
            { actions.open(SettingsPage.TRANSLATION) },
            summary = translationSummary(settings),
            icon = R.drawable.ic_settings_translate,
        )
        SettingsRow(stringResource(R.string.settings_email_title), actions.openEmail, icon = R.drawable.ic_settings_mail)
        SettingsRow(stringResource(R.string.settings_wordart_title), actions.openWordArt, icon = R.drawable.ic_settings_text_fields)
        SettingsRow(stringResource(R.string.settings_creative_title), actions.openCreative, summary = stringResource(R.string.settings_creative_summary), icon = R.drawable.ic_settings_palette)

        SettingsHeader(stringResource(R.string.settings_group_account))
        if (!state.fromKeyboard) {
            SettingsRow(stringResource(R.string.acct_title), { actions.open(SettingsPage.ACCOUNT) }, summary = state.accountSummary, icon = R.drawable.ic_settings_account)
        }
        SettingsRow(stringResource(R.string.pv_entry_title), actions.openPrivacy, icon = R.drawable.ic_settings_shield)

        SettingsHeader(stringResource(R.string.settings_group_app))
        SettingsRow(
            stringResource(R.string.settings_app_language_title),
            { actions.open(SettingsPage.APP_LANGUAGE) },
            summary = state.appLanguageSummary,
            icon = R.drawable.ic_settings_phone,
        )
        if (state.keyboardReady) {
            SettingsRow(stringResource(R.string.settings_setup_title), actions.runSetup, icon = R.drawable.ic_settings_keyboard)
        }
        SettingsRow(stringResource(R.string.settings_advanced), { actions.open(SettingsPage.ADVANCED) }, icon = R.drawable.ic_settings_build)
        SettingsRow(stringResource(R.string.settings_about), { actions.open(SettingsPage.ABOUT) }, icon = R.drawable.ic_settings_info)
    }
}

/** The one call to action of the home page, shown only while Qal is not turned on or not selected. */
@Composable
private fun SetupPrompt(onSetUp: () -> Unit) {
    Surface(
        onClick = onSetUp,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_settings_keyboard), contentDescription = null)
            Text(stringResource(R.string.settings_setup_prompt), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_setup_prompt_button), style = MaterialTheme.typography.labelLarge)
        }
    }
}
