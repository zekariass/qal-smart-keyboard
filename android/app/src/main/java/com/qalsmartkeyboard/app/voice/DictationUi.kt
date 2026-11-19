package com.qalsmartkeyboard.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.ime.R as ImeR
import com.qalsmartkeyboard.ime.toolbar.ToolIcon
import com.qalsmartkeyboard.ime.toolbar.ToolIcons
import com.qalsmartkeyboard.ime.voice.PlatformVoiceProvider
import com.qalsmartkeyboard.ime.voice.SupportLevel
import com.qalsmartkeyboard.ime.voice.VoiceIntents
import com.qalsmartkeyboard.ime.voice.VoiceLanguages
import com.qalsmartkeyboard.ime.voice.VoicePanelView
import com.qalsmartkeyboard.ime.voice.VoicePrefs
import com.qalsmartkeyboard.ime.voice.VoiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's [DictationModel] for one screen: the platform recogniser (its services read off the main thread), the voice
 * settings shared with the keyboard (on/off, the language chosen per keyboard language, the one-time note), and the
 * microphone permission. Listening stops when the screen leaves.
 */
@Composable
fun rememberDictation(settings: KeyboardSettings, update: ((KeyboardSettings) -> KeyboardSettings) -> Unit): DictationModel {
    val context = LocalContext.current.applicationContext
    val current by rememberUpdatedState(settings)
    val save by rememberUpdatedState(update)
    val provider = remember { PlatformVoiceProvider(context) }
    val model = remember {
        val prefs = object : VoicePrefs {
            override val enabled: Boolean get() = current.voiceTypingEnabled
            override fun chosen(keyboardLanguage: String): String? = current.voiceLanguages[keyboardLanguage]
            override fun choose(keyboardLanguage: String, tag: String) {
                if (current.voiceLanguages[keyboardLanguage] != tag) save { it.copy(voiceLanguages = it.voiceLanguages + (keyboardLanguage to tag)) }
            }
            override val noticeShown: Boolean get() = true // the app's screens show the privacy line next to the field instead
            override fun noticeSeen() {}
        }
        DictationModel(
            provider, prefs,
            hasPermission = { granted(context) },
            language = { current.enabledLanguages.firstOrNull { it in VoiceLanguages.KEYBOARD_LANGUAGES } ?: "en" },
        )
    }
    LaunchedEffect(provider) {
        withContext(Dispatchers.IO) { provider.refresh() } // a PackageManager query: off the main thread
        model.servicesKnown = true // the microphones appear once a recogniser is known to be installed
    }
    DisposableEffect(model) { onDispose { model.cancel() } }
    return model
}

private fun granted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * An outlined text field with a microphone (ADR 0038): the cursor and selection are kept in a [TextFieldValue], so
 * dictated text goes in at the cursor; the status line under it shows listening, the words heard so far, processing, a
 * failure with Try again, or the permission denial. [value] stays the screen's plain string.
 */
@Composable
fun VoiceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    fieldId: String,
    dictation: DictationModel?,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // The screen changed the text (a paste button, Clear, a new result): keep the cursor where it can be.
    val shown = if (tfv.text == value) tfv else TextFieldValue(value, TextRange(minOf(tfv.selection.end, value.length)))
    val latest by rememberUpdatedState(shown)
    val change by rememberUpdatedState(onValueChange)
    val target = remember(fieldId) {
        object : DictationTarget {
            override fun textBeforeCursor(): CharSequence = DictationText.before(latest)
            override fun insert(text: String) {
                val next = DictationText.insert(latest, text)
                tfv = next
                change(next.text)
            }
        }
    }
    val micDescription = stringResource(if (dictation?.activeField == fieldId && dictation.state.listening) ImeR.string.editor_dictate_stop else ImeR.string.editor_dictate)
    Column(modifier) {
        OutlinedTextField(
            value = shown,
            onValueChange = {
                tfv = it
                if (it.text != value) onValueChange(it.text)
            },
            label = label,
            placeholder = placeholder,
            supportingText = supportingText,
            isError = isError,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            trailingIcon = if (dictation != null && dictation.available) {
                {
                    val active = dictation.activeField == fieldId && dictation.state != VoiceState.Idle
                    MicButton(active = active, description = micDescription) { dictation.toggle(fieldId, target) }
                }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (dictation != null && dictation.activeField == fieldId) DictationStatus(dictation)
    }
}

private val VoiceState.listening: Boolean get() = this is VoiceState.Listening || this == VoiceState.Preparing

@Composable
private fun MicButton(active: Boolean, description: String, onClick: () -> Unit) {
    val listening = stringResource(ImeR.string.voice_listening_short)
    val tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics {
            contentDescription = description
            if (active) stateDescription = listening
        },
    ) {
        Canvas(Modifier.size(32.dp)) {
            if (active) drawCircle(fill)
            drawIntoCanvas { c ->
                ToolIcons.draw(c.nativeCanvas, ToolIcon.MIC, size.width / 2f, size.height / 2f, size.minDimension * 0.7f, tint.toArgb(), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
            }
        }
    }
}

/** The line under a dictating field: what the recogniser is doing, what it heard so far, and its controls. */
@Composable
fun DictationStatus(model: DictationModel) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (model.permissionDenied) {
            Text(stringResource(ImeR.string.voice_err_permission), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    try {
                        context.startActivity(VoiceIntents.permission(context.packageName))
                    } catch (e: RuntimeException) {
                        // No permission screen: nothing else to offer.
                    }
                }) { Text(stringResource(com.qalsmartkeyboard.app.R.string.voice_perm_allow)) }
                TextButton(onClick = model::cancel) { Text(stringResource(ImeR.string.ai_cancel)) }
            }
            return@Column
        }
        val s = model.state
        val tag = model.voice.currentTag
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s == VoiceState.Preparing || s is VoiceState.Processing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            val line = when (s) {
                VoiceState.Preparing -> stringResource(ImeR.string.voice_preparing)
                is VoiceState.Listening -> stringResource(ImeR.string.voice_listening)
                is VoiceState.Processing -> stringResource(ImeR.string.voice_processing)
                VoiceState.Ready -> stringResource(ImeR.string.voice_ready)
                is VoiceState.Failed -> VoicePanelView.errorText(context, s.kind, s.tag)
                VoiceState.Idle -> ""
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if (s is VoiceState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val partial = when (s) {
            is VoiceState.Listening -> s.partial
            is VoiceState.Processing -> s.partial
            else -> ""
        }
        // What was heard so far: progress only, not in the field until the recogniser is done.
        if (partial.isNotBlank()) Text(partial, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = model::openLanguages) {
                    Text(VoiceLanguages.displayName(tag) + "  ▾", modifier = Modifier.semantics { contentDescription = context.getString(ImeR.string.voice_language_chip, VoiceLanguages.displayName(tag)) })
                }
                DropdownMenu(expanded = model.pickerOpen, onDismissRequest = model::closeLanguages) {
                    if (model.voice.pickerLoading) {
                        DropdownMenuItem(text = { Text(stringResource(ImeR.string.voice_picker_checking)) }, onClick = {}, enabled = false)
                    }
                    // Only what this phone's speech service reports (or cannot rule out on old Android) is offered.
                    model.voice.languageOptions().filter { it.support.usable }.forEach { o ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(o.label)
                                    Text(supportText(o.support), style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            onClick = { model.chooseLanguage(o.tag) },
                        )
                    }
                }
            }
            when (s) {
                VoiceState.Preparing, is VoiceState.Listening -> TextButton(onClick = model::stop) { Text(stringResource(ImeR.string.voice_stop)) }
                VoiceState.Ready, is VoiceState.Failed -> TextButton(onClick = model::retry) { Text(stringResource(ImeR.string.voice_retry)) }
                else -> Unit
            }
            TextButton(onClick = model::cancel) { Text(stringResource(ImeR.string.ai_cancel)) }
        }
        Text(stringResource(ImeR.string.voice_notice), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun supportText(s: SupportLevel): String = stringResource(
    when (s) {
        SupportLevel.ON_DEVICE -> ImeR.string.voice_support_offline
        SupportLevel.ONLINE -> ImeR.string.voice_support_online
        SupportLevel.UNKNOWN -> ImeR.string.voice_support_unknown
        SupportLevel.UNSUPPORTED -> ImeR.string.voice_support_none
    },
)

/** Asks for the microphone when the model needs it (the system dialog), and hands the answer back. */
@Composable
fun DictationPermission(model: DictationModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> model.onPermissionResult(granted) }
    LaunchedEffect(model.permissionNeeded) {
        if (model.permissionNeeded) {
            model.onPermissionAsked()
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
