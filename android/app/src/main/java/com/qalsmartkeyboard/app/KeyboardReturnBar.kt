package com.qalsmartkeyboard.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Shown above the settings screen when the keyboard's gear opened it (deep link `SETTINGS`): a clear
 * "Back to typing" action, and the system Back does the same. [onDone] finishes the screen so the
 * user lands back in the app they were typing in.
 */
@Composable
fun KeyboardReturnBar(onDone: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onDone)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_opened_from_keyboard),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onDone) { Text(stringResource(R.string.settings_back_to_typing)) }
    }
}
