package com.qalsmartkeyboard.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.ime.AppLanguage

/**
 * "App language" (ADR 0032): System default, English, አማርኛ, ትግርኛ, Afaan Oromoo, each named in its own language. Changes the
 * UI of this app and the keyboard's labels, never the typing languages. Android 13+ recreates the activity itself; below,
 * the activity is recreated here.
 */
@Composable
fun AppLanguageCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(AppLanguage.get(context)) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_app_language_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_app_language_body), style = MaterialTheme.typography.bodySmall)
            (listOf("") + AppLanguage.SUPPORTED).forEach { tag ->
                val label = if (tag.isEmpty()) stringResource(R.string.settings_app_language_system) else AppLanguage.selfName(tag)
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = chosen == tag,
                        role = Role.RadioButton,
                        onClick = {
                            if (chosen != tag) {
                                chosen = tag
                                AppLanguage.set(context, tag)
                                if (Build.VERSION.SDK_INT < 33) context.findActivity()?.recreate()
                            }
                        },
                    ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = chosen == tag, onClick = null)
                    Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
