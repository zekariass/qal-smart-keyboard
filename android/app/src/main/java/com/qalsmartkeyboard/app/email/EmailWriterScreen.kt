package com.qalsmartkeyboard.app.email

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.ime.R as ImeR
import com.qalsmartkeyboard.ime.ai.AiEmailMode
import com.qalsmartkeyboard.ime.ai.AiEmailRules
import com.qalsmartkeyboard.ime.ai.AiEmailTone
import com.qalsmartkeyboard.ime.ai.AiGate
import com.qalsmartkeyboard.ime.ai.AiLanguages
import com.qalsmartkeyboard.ime.ai.AiLength
import com.qalsmartkeyboard.ime.ai.AiLink
import com.qalsmartkeyboard.ime.ai.AiMessages
import com.qalsmartkeyboard.ime.ai.ClipAvailability
import com.qalsmartkeyboard.ime.ai.SystemAiClipboard

/** What the e-mail screen asks of its host: the clipboard, sharing and the app's other screens. */
interface EmailWriterHost {
    fun clipboard(): ClipAvailability
    /** Reads the clipboard's text (only on the user's Paste tap); null when empty or sensitive. */
    fun paste(): String?
    fun copy(text: String): Boolean
    fun share(subject: String, body: String)
    fun openLink(link: AiLink)

    companion object {
        /** The real host over the system clipboard (sensitive clips are never read) and the share sheet. */
        fun of(context: Context, onLink: (AiLink) -> Unit): EmailWriterHost = object : EmailWriterHost {
            private val clip = SystemAiClipboard(context)
            override fun clipboard() = clip.availability()
            override fun paste() = clip.readText()
            override fun copy(text: String) = clip.copy(text)
            override fun share(subject: String, body: String) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(chooser)
                } catch (e: RuntimeException) {
                    // No app to share with: nothing to do (the text is still on screen to copy).
                }
            }
            override fun openLink(link: AiLink) = onLink(link)
        }
    }
}

/**
 * The companion app's "Write Email with AI" screen (ADR 0031): mode (New / Reply / Improve), what to say, the e-mail
 * received or the draft (with Paste), tone, length, language and names; then the preview with an editable subject and
 * body, highlighted `[placeholders]`, Regenerate, Copy subject, Copy body, Copy all and Share. One scrolling column that
 * works at 360 dp and in landscape.
 */
@Composable
fun EmailWriterScreen(model: EmailWriterModel, host: EmailWriterHost, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var copiedNote by remember { mutableStateOf<String?>(null) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.onb_back)) }
            Text(
                stringResource(R.string.email_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (model.fromKeyboard) Text(stringResource(R.string.email_from_keyboard), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiEmailMode.entries.forEach { m ->
                FilterChip(selected = model.mode == m, onClick = { model.mode = m; model.clearProblem() }, label = { Text(AiMessagesCompat.mode(m)) })
            }
        }
        when (model.mode) {
            AiEmailMode.REPLY -> PasteField(
                label = stringResource(ImeR.string.ai_email_original_label), value = model.originalEmail, limit = AiEmailRules.MAX_EMAIL,
                host = host, onValue = { model.originalEmail = it; model.clearProblem() },
            )
            AiEmailMode.IMPROVE -> PasteField(
                label = stringResource(ImeR.string.ai_email_draft_label), value = model.draft, limit = AiEmailRules.MAX_EMAIL,
                host = host, onValue = { model.draft = it; model.clearProblem() },
            )
            AiEmailMode.NEW -> Unit
        }
        OutlinedTextField(
            value = model.instructions,
            onValueChange = { model.instructions = it; model.clearProblem() },
            label = {
                Text(
                    stringResource(
                        when (model.mode) {
                            AiEmailMode.NEW -> ImeR.string.ai_email_instructions_new
                            AiEmailMode.REPLY -> ImeR.string.ai_email_instructions_reply
                            AiEmailMode.IMPROVE -> ImeR.string.ai_email_instructions_improve
                        },
                    ),
                )
            },
            placeholder = { Text(stringResource(ImeR.string.ai_email_instructions_hint)) },
            supportingText = { Text(AiMessages.count(model.instructions.length, AiEmailRules.MAX_INSTRUCTIONS)) },
            isError = model.instructions.length > AiEmailRules.MAX_INSTRUCTIONS,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(ImeR.string.ai_email_tone), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiEmailTone.entries.forEach { t ->
                FilterChip(selected = model.tone == t, onClick = { model.tone = t }, label = { Text(AiMessagesCompat.tone(t)) })
            }
        }
        Text(AiMessagesCompat.toneHint(model.tone), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(ImeR.string.ai_email_length_language), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiLength.entries.forEach { l -> FilterChip(selected = model.length == l, onClick = { model.length = l }, label = { Text(AiMessagesCompat.length(l)) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiLanguages.ALL.forEach { code ->
                FilterChip(selected = model.language == code, onClick = { model.language = code }, label = { Text(AiLanguages.name(code)) })
            }
        }
        OutlinedTextField(
            value = model.recipientName, onValueChange = { model.recipientName = it.take(AiEmailRules.MAX_NAME) },
            label = { Text(stringResource(ImeR.string.ai_email_recipient)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model.senderName, onValueChange = { model.senderName = it.take(AiEmailRules.MAX_NAME) },
            label = { Text(stringResource(ImeR.string.ai_email_sender)) },
            supportingText = { Text(stringResource(ImeR.string.ai_email_sender_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        model.problem?.let {
            Text(AiMessagesCompat.problem(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        when (model.step) {
            EmailStep.WORKING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(stringResource(ImeR.string.ai_working_email))
                    Text(stringResource(ImeR.string.ai_wait_long, 30), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = model::cancel) { Text(stringResource(ImeR.string.ai_cancel)) }
            }
            else -> Button(onClick = model::generate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(ImeR.string.ai_email_generate)) }
        }
        if (model.step == EmailStep.FAILED) {
            val f = model.failure
            if (f != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(AiMessagesCompat.failure(f), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            f.link?.let { link -> Button(onClick = { host.openLink(link) }) { Text(AiMessagesCompat.link(link)) } }
                            if (AiGate.canRetry(f)) OutlinedButton(onClick = model::retry) { Text(stringResource(ImeR.string.ai_retry)) }
                        }
                    }
                }
            }
        }
        if (model.hasResult && model.step != EmailStep.WORKING) {
            Preview(model, host) { copiedNote = it }
        }
        copiedNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

@Composable
private fun Preview(model: EmailWriterModel, host: EmailWriterHost, onNote: (String?) -> Unit) {
    val copied = stringResource(ImeR.string.ai_copied)
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    Text(stringResource(R.string.email_preview), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    OutlinedTextField(
        value = model.subject, onValueChange = { model.subject = it },
        label = { Text(stringResource(ImeR.string.ai_email_subject)) },
        visualTransformation = PlaceholderHighlight(highlight), singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.body, onValueChange = { model.body = it },
        label = { Text(stringResource(ImeR.string.ai_email_body)) },
        visualTransformation = PlaceholderHighlight(highlight), minLines = 8, modifier = Modifier.fillMaxWidth(),
    )
    val fill = model.placeholders
    if (fill.isNotEmpty()) {
        Text(stringResource(ImeR.string.ai_email_fill_in, fill.joinToString(", ")), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
    }
    model.notes.take(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { if (host.copy(model.subject)) onNote(copied) }, enabled = model.subject.isNotBlank()) { Text(stringResource(ImeR.string.ai_email_copy_subject)) }
        OutlinedButton(onClick = { if (host.copy(model.body)) onNote(copied) }) { Text(stringResource(R.string.email_copy_body)) }
        Button(onClick = { if (host.copy(AiEmailRules.fullText(model.subject, model.body))) onNote(copied) }) { Text(stringResource(R.string.email_copy_all)) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { host.share(model.subject, model.body) }) { Text(stringResource(R.string.email_share)) }
        OutlinedButton(onClick = model::regenerate) { Text(stringResource(ImeR.string.ai_regenerate)) }
    }
    Text(stringResource(R.string.email_paste_hint), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.width(1.dp))
}

/** A multi-line field with a Paste button (the clipboard is read only on that tap; a sensitive clip is not offered). */
@Composable
private fun PasteField(label: String, value: String, limit: Int, host: EmailWriterHost, onValue: (String) -> Unit) {
    val clip = host.clipboard()
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        supportingText = { Text(AiMessages.count(value.length, limit)) },
        isError = value.length > limit,
        minLines = 4,
        maxLines = 12,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { host.paste()?.let(onValue) }, enabled = clip == ClipAvailability.TEXT) {
            Text(stringResource(ImeR.string.ai_paste_clipboard))
        }
        if (value.isNotEmpty()) TextButton(onClick = { onValue("") }) { Text(stringResource(ImeR.string.ai_source_clear)) }
    }
    if (clip == ClipAvailability.SENSITIVE) Text(stringResource(ImeR.string.ai_clipboard_sensitive), style = MaterialTheme.typography.bodySmall)
}

/** Highlights `[placeholders]` in a text field (bold on a container colour) without changing the text. */
class PlaceholderHighlight(private val background: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val ranges = AiEmailRules.placeholderRanges(text.text)
        if (ranges.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val b = AnnotatedString.Builder(text)
        ranges.forEach { r -> b.addStyle(SpanStyle(background = background, fontWeight = FontWeight.Bold), r.first, r.last + 1) }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is PlaceholderHighlight && other.background == background
    override fun hashCode() = background.hashCode()
}

/** [AiMessages] for Compose (the same wording as the keyboard). */
private object AiMessagesCompat {
    @Composable fun mode(m: AiEmailMode) = AiMessages.mode(LocalContext.current, m)
    @Composable fun tone(t: AiEmailTone) = AiMessages.tone(LocalContext.current, t)
    @Composable fun toneHint(t: AiEmailTone) = AiMessages.toneHint(LocalContext.current, t)
    @Composable fun length(l: AiLength) = AiMessages.length(LocalContext.current, l)
    @Composable fun problem(p: com.qalsmartkeyboard.ime.ai.AiEmailProblem) = AiMessages.problem(LocalContext.current, p)
    @Composable fun failure(f: com.qalsmartkeyboard.ime.ai.AiFailure) = AiMessages.failure(LocalContext.current, f)
    @Composable fun link(l: AiLink) = AiMessages.link(LocalContext.current, l)
}
