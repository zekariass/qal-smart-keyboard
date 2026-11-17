package com.qalsmartkeyboard.app.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.data.InputMode

private fun languageNameRes(code: String): Int = when (code) {
    "am" -> R.string.lang_am
    "ti" -> R.string.lang_ti
    "om" -> R.string.lang_om
    else -> R.string.lang_en
}

private fun launch(context: Context, intent: Intent) {
    // Settings screens and browsers can be missing on unusual devices; never crash the flow.
    try { context.startActivity(intent) } catch (_: Exception) { }
}

/**
 * First-run flow: privacy, enable, select, languages, tutorial. Scrolls in portrait, landscape and
 * with large text; navigation buttons stay pinned at the bottom.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onEdit: ((LanguageDraft) -> LanguageDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = OnboardingStep.entries
    val total = steps.size
    val position = state.step.ordinal + 1
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.onb_step_progress, position, total), style = MaterialTheme.typography.labelLarge)
            val skipDescription = stringResource(R.string.onb_skip_description)
            TextButton(onClick = onSkip, modifier = Modifier.semantics { contentDescription = skipDescription }) {
                Text(stringResource(R.string.onb_skip))
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.step) {
                OnboardingStep.PRIVACY -> PrivacyStep()
                OnboardingStep.ENABLE -> EnableStep(state.ime)
                OnboardingStep.SELECT -> SelectStep(state.ime)
                OnboardingStep.LANGUAGES -> LanguagesStep(state.draft, onEdit)
                OnboardingStep.TUTORIAL -> TutorialStep(state)
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.step != OnboardingStep.PRIVACY) {
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.onb_back)) }
            }
            Button(onClick = onNext, enabled = state.canContinue) {
                Text(stringResource(if (state.step == OnboardingStep.TUTORIAL) R.string.onb_finish else R.string.onb_continue))
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun PrivacyStep() {
    val context = LocalContext.current
    val url = stringResource(R.string.privacy_notice_url)
    Heading(stringResource(R.string.onb_welcome_title))
    Text(stringResource(R.string.onb_welcome_intro))
    Text(stringResource(R.string.onb_privacy_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.onb_privacy_offline))
    Text(stringResource(R.string.onb_privacy_keystrokes))
    Text(stringResource(R.string.onb_privacy_ai))
    Text(stringResource(R.string.onb_privacy_settings))
    val linkDescription = stringResource(R.string.onb_privacy_link_description)
    TextButton(
        onClick = { launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
        modifier = Modifier.semantics { contentDescription = linkDescription },
    ) { Text(stringResource(R.string.onb_privacy_link)) }
}

@Composable
private fun StatusLine(ok: Boolean, onText: String, offText: String) {
    // Text, not only colour or an icon: the state is readable by TalkBack and in grayscale.
    Text(
        (if (ok) "✓ " else "• ") + (if (ok) onText else offText),
        style = MaterialTheme.typography.titleMedium,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun EnableStep(ime: ImeStatus) {
    val context = LocalContext.current
    Heading(stringResource(R.string.onb_enable_title))
    Text(stringResource(R.string.onb_enable_body))
    StatusLine(ime.enabled, stringResource(R.string.onb_enable_status_on), stringResource(R.string.onb_enable_status_off))
    Button(onClick = { launch(context, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) {
        Text(stringResource(R.string.onb_enable_button))
    }
    Text(stringResource(R.string.onb_enable_warning), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SelectStep(ime: ImeStatus) {
    val context = LocalContext.current
    Heading(stringResource(R.string.onb_select_title))
    Text(stringResource(R.string.onb_select_body))
    StatusLine(ime.selected, stringResource(R.string.onb_select_status_on), stringResource(R.string.onb_select_status_off))
    Button(onClick = {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }) { Text(stringResource(R.string.onb_select_button)) }
}

@Composable
private fun LanguagesStep(draft: LanguageDraft, onEdit: ((LanguageDraft) -> LanguageDraft) -> Unit) {
    Heading(stringResource(R.string.onb_lang_title))
    Text(stringResource(R.string.onb_lang_body))
    draft.order.forEachIndexed { i, code ->
        val name = stringResource(languageNameRes(code))
        val on = code in draft.enabled
        val enabledDescription = stringResource(R.string.onb_lang_enabled_description, name)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = on,
                        enabled = !(on && draft.enabled.size <= 1),
                        onCheckedChange = { onEdit { d -> d.toggle(code) } },
                        modifier = Modifier.semantics { contentDescription = enabledDescription },
                    )
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        if (code == draft.primary) {
                            Text(stringResource(R.string.onb_lang_first_badge), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val up = stringResource(R.string.onb_lang_up_description, name)
                    val down = stringResource(R.string.onb_lang_down_description, name)
                    val first = stringResource(R.string.onb_lang_make_first_description, name)
                    TextButton(enabled = i > 0, onClick = { onEdit { d -> d.move(code, -1) } }, modifier = Modifier.semantics { contentDescription = up }) {
                        Text(stringResource(R.string.onb_lang_up))
                    }
                    TextButton(enabled = i < draft.order.lastIndex, onClick = { onEdit { d -> d.move(code, 1) } }, modifier = Modifier.semantics { contentDescription = down }) {
                        Text(stringResource(R.string.onb_lang_down))
                    }
                    TextButton(enabled = code != draft.primary, onClick = { onEdit { d -> d.makePrimary(code) } }, modifier = Modifier.semantics { contentDescription = first }) {
                        Text(stringResource(R.string.onb_lang_make_first))
                    }
                }
            }
        }
    }
    // am and ti: phonetic QWERTY by default (ADR 0007 U3), Fidel offered as an option.
    listOf("am", "ti").filter { it in draft.enabled }.forEach { code ->
        val name = stringResource(if (code == "am") R.string.lang_am_short else R.string.lang_ti_short)
        Text(stringResource(R.string.onb_lang_mode_title, name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        ModeOption(draft, code, InputMode.PHONETIC_LATIN_TO_NATIVE, R.string.onb_mode_phonetic, R.string.onb_mode_phonetic_hint, onEdit)
        ModeOption(draft, code, InputMode.DIRECT_SCRIPT_LAYOUT, R.string.onb_mode_fidel, R.string.onb_mode_fidel_hint, onEdit)
    }
}

@Composable
private fun ModeOption(
    draft: LanguageDraft,
    code: String,
    mode: InputMode,
    label: Int,
    hint: Int,
    onEdit: ((LanguageDraft) -> LanguageDraft) -> Unit,
) {
    val selected = draft.modes[code] == mode
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = { onEdit { d -> d.withMode(code, mode) } }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(stringResource(label))
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TutorialStep(state: OnboardingState) {
    var text by rememberSaveable { mutableStateOf("") }
    Heading(stringResource(R.string.onb_tutorial_title))
    Text(stringResource(R.string.onb_tutorial_body))
    if (!state.ime.selected) {
        Text(stringResource(R.string.onb_tutorial_not_selected), color = MaterialTheme.colorScheme.error)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.onb_tutorial_field_label)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.onb_tips_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    listOf(
        R.string.onb_tip_phonetic, R.string.onb_tip_fidel, R.string.onb_tip_globe_tap, R.string.onb_tip_globe_long,
        R.string.onb_tip_abc, R.string.onb_tip_space, R.string.onb_tip_punctuation, R.string.onb_tip_draft,
    ).forEach { Text("• " + stringResource(it)) }
}
