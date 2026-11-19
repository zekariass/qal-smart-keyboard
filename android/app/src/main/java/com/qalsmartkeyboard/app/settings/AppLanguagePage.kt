package com.qalsmartkeyboard.app.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.ime.AppLanguage

/**
 * "App language" (ADR 0032): System default, English, አማርኛ, ትግርኛ, Afaan Oromoo, each named in its own language. Changes the
 * UI of this app and the keyboard's labels, never the typing languages. Android 13+ recreates the activity itself; below,
 * the activity is recreated here.
 */
@Composable
fun AppLanguagePage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(AppLanguage.get(context)) }
    SettingsScaffold(stringResource(R.string.settings_app_language_title), onBack, modifier) {
        SettingsNote(stringResource(R.string.settings_app_language_body))
        Column(Modifier.selectableGroup()) {
            (listOf("") + AppLanguage.SUPPORTED).forEach { tag ->
                SettingsRadioRow(
                    title = appLanguageLabel(tag),
                    selected = chosen == tag,
                    onSelect = {
                        if (chosen != tag) {
                            chosen = tag
                            AppLanguage.set(context, tag)
                            if (Build.VERSION.SDK_INT < 33) context.findActivity()?.recreate()
                        }
                    },
                )
            }
        }
    }
}

/** "System default", or the language's name in itself. */
@Composable
fun appLanguageLabel(tag: String): String =
    if (tag.isEmpty()) stringResource(R.string.settings_app_language_system) else AppLanguage.selfName(tag)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
