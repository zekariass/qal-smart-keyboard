package com.qalsmartkeyboard.app.wordart

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.voice.DictationModel
import com.qalsmartkeyboard.app.voice.DictationPermission
import com.qalsmartkeyboard.app.voice.VoiceTextField
import kotlinx.coroutines.launch
import com.qalsmartkeyboard.ime.R as ImeR
import com.qalsmartkeyboard.ime.wordart.Greetings
import com.qalsmartkeyboard.ime.wordart.WordArtStyleNames
import com.qalsmartkeyboard.ime.wordart.Rendered
import com.qalsmartkeyboard.ime.wordart.StyleKind
import com.qalsmartkeyboard.ime.wordart.Support
import com.qalsmartkeyboard.ime.wordart.WordArt
import com.qalsmartkeyboard.ime.wordart.WordArtCategory
import com.qalsmartkeyboard.ime.wordart.WordArtOptions
import com.qalsmartkeyboard.ime.wordart.WordArtStyle
import com.qalsmartkeyboard.ime.wordart.titleRes

/** The language names of the greeting picker (the codes the engine knows). */
private val LANGUAGE_NAMES = mapOf(
    "am" to R.string.wordart_app_lang_am,
    "ti" to R.string.wordart_app_lang_ti,
    "om" to R.string.wordart_app_lang_om,
    "en" to R.string.wordart_app_lang_en,
)

/**
 * The host: keeps the screen's state across rotation and does the Android work (clipboard, share sheet). A tap on a
 * style (or its Copy) copies the complete styled text and confirms with a "Copied" snackbar (ADR 0038).
 */
@Composable
fun WordArtHost(onBack: () -> Unit, modifier: Modifier = Modifier, dictation: DictationModel? = null) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(WordArtCategory.POPULAR.name) }
    var language by rememberSaveable { mutableStateOf("en") }
    var greeting by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.wordart_app_copied)
    WordArtScreen(
        text = text, onText = { text = it },
        category = WordArtCategory.valueOf(category), onCategory = { category = it.name },
        language = language, onLanguage = { language = it },
        greeting = greeting, onGreeting = { greeting = it },
        onCopy = {
            copyToClipboard(context, it)
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(copiedMessage, duration = SnackbarDuration.Short)
            }
        },
        onShare = { share(context, it) },
        onBack = onBack,
        modifier = modifier,
        snackbar = snackbar,
        dictation = dictation,
    )
}

/** Copies [text] as a plain clip; not marked sensitive (the user is going to paste it into another app). */
fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Word Art", text))
}

/** Opens the system share sheet with [text]. */
fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.wordart_app_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

/**
 * Word Art in the companion app: the same engine as the keyboard's panel. A text field (with a microphone that
 * dictates at the cursor, ADR 0038), a greeting switch with its language, category chips and a lazily composed list of
 * style previews; a tap on a preview copies its complete styled text, and each has Copy and Share. Previews read as the
 * plain text plus the style name for TalkBack (the decorative Unicode letters are read badly). The list keeps clear of
 * the keyboard (`imePadding`), so the field is never hidden behind it.
 */
@Composable
fun WordArtScreen(
    text: String,
    onText: (String) -> Unit,
    category: WordArtCategory,
    onCategory: (WordArtCategory) -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    greeting: Boolean,
    onGreeting: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    dictation: DictationModel? = null,
) {
    val options = WordArtOptions(language, greeting)
    val sample = WordArt.sample(language)
    val styles = remember(category) { WordArt.stylesIn(category) }
    // Supported styles first; the rest stay visible, disabled, with the reason.
    val rows = remember(category, text, language, greeting) {
        styles.map { s ->
            val typed = text.trim()
            val use = if (WordArt.body(s, typed, options).isBlank()) sample else typed
            s to WordArt.render(s, use, options)
        }.sortedBy { if (it.second.support == Support.NONE) 1 else 0 }
    }
    val usable = text.isNotBlank() || greeting
    Box(modifier.fillMaxSize().imePadding()) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.wordart_app_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.wordart_app_intro))
                VoiceTextField(
                    value = text,
                    onValueChange = { onText(it.take(500)) },
                    fieldId = "wordart",
                    dictation = dictation,
                    label = { Text(stringResource(R.string.wordart_app_field_label)) },
                    placeholder = { Text(sample) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(ImeR.string.wordart_greeting), Modifier.weight(1f))
                    Switch(checked = greeting, onCheckedChange = onGreeting)
                }
                if (greeting) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Greetings.LANGUAGES) { code ->
                            FilterChip(selected = code == language, onClick = { onLanguage(code) }, label = { Text(stringResource(LANGUAGE_NAMES.getValue(code))) })
                        }
                    }
                    Text(stringResource(R.string.wordart_app_draft_note), style = MaterialTheme.typography.bodySmall)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(WordArtCategory.entries.toList()) { c ->
                        FilterChip(selected = c == category, onClick = { onCategory(c) }, label = { Text(stringResource(c.titleRes())) })
                    }
                }
                if (text.isBlank()) {
                    Text(stringResource(R.string.wordart_app_sample_note), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.wordart_app_tap_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(rows, key = { it.first.id }) { (style, rendered) -> StyleCard(style, rendered, usable, onCopy, onShare) }
        item {
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(bottom = 16.dp)) { Text(stringResource(R.string.onb_back)) }
        }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (dictation != null) DictationPermission(dictation)
}

@Composable
private fun StyleCard(style: WordArtStyle, rendered: Rendered, usable: Boolean, onCopy: (String) -> Unit, onShare: (String) -> Unit) {
    val ok = rendered.support != Support.NONE
    val label = WordArtStyleNames.label(LocalContext.current, style)
    val reason = stringResource(if (style.kind == StyleKind.SPACING) ImeR.string.wordart_unavailable else ImeR.string.wordart_latin_only)
    val description = if (ok) stringResource(ImeR.string.wordart_row, label, rendered.plain)
    else stringResource(ImeR.string.wordart_row_disabled, label, rendered.plain, reason)
    val copyDesc = stringResource(R.string.wordart_app_copy_desc, label)
    val shareDesc = stringResource(R.string.wordart_app_share_desc, label)
    val copyLabel = stringResource(ImeR.string.wordart_copy)
    // The whole card is the result: a tap copies the complete styled text (ADR 0038).
    Card(
        Modifier.fillMaxWidth().clip(CardDefaults.shape)
            .clickable(enabled = ok && usable, onClickLabel = copyLabel, role = Role.Button) { onCopy(rendered.text) },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // One spoken unit: the style name and the plain text; the decorated string itself is not read.
            Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
                Text(if (ok) label else "$label · $reason", style = MaterialTheme.typography.labelMedium)
                Text(if (ok) rendered.text else rendered.plain, style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onCopy(rendered.text) }, enabled = ok && usable,
                    modifier = Modifier.semantics { contentDescription = copyDesc },
                ) { Text(stringResource(ImeR.string.wordart_copy)) }
                Button(
                    onClick = { onShare(rendered.text) }, enabled = ok && usable,
                    modifier = Modifier.semantics { contentDescription = shareDesc },
                ) { Text(stringResource(R.string.wordart_app_share)) }
            }
        }
    }
}
