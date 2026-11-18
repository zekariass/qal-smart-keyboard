package com.qalsmartkeyboard.app.themes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess
import com.qalsmartkeyboard.ime.theme.ThemeAccessHolder
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether this device may use premium themes, from the injected port (the cached verified entitlement token). The
 * port may open Keystore-backed files the first time, so it is built off the main thread; until then (and without
 * a port) the answer is "check needed".
 */
@Composable
fun rememberPremiumThemeAccess(): State<PremiumThemeAccess> {
    val state = remember { mutableStateOf(PremiumThemeAccess.NEEDS_CHECK) }
    val context = LocalContext.current
    DisposableEffect(context) {
        var handle: AutoCloseable? = null
        var closed = false
        val thread = Thread({
            val port = try { ThemeAccessHolder.create() } catch (_: Exception) { null }
            if (port != null && !closed) {
                state.value = port.premium
                handle = port.addListener { state.value = port.premium }
            } else if (!closed) {
                state.value = PremiumThemeAccess.NOT_ON_PLAN
            }
        }, "qal-theme-access-ui")
        thread.start()
        onDispose {
            closed = true
            handle?.close()
        }
    }
    return state
}

/** The theme picker host: remembers the preview across rotation, stores the choice, opens the plans. */
@Composable
fun ThemesHost(
    appliedId: String,
    store: ThemeStore,
    systemDark: Boolean,
    onUnlock: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val access by rememberPremiumThemeAccess()
    val model = rememberSaveable(saver = Saver<ThemePickerModel, String>(save = { it.previewId ?: "" }, restore = { ThemePickerModel(store, it.ifEmpty { null }) })) {
        ThemePickerModel(store)
    }
    val scope = rememberCoroutineScope()
    ThemesScreen(
        appliedId = appliedId,
        previewId = model.previewId,
        access = access,
        systemDark = systemDark,
        onPreview = model::showPreview,
        onClosePreview = model::closePreview,
        onApply = { id -> scope.launch { withContext(Dispatchers.Default) { model.apply(id, access) } } },
        onUnlock = onUnlock,
        onBack = onBack,
        modifier = modifier,
    )
}

/** The picker: Free and Premium sections of preview cards, a large preview with Apply or Unlock. */
@Composable
fun ThemesScreen(
    appliedId: String,
    previewId: String?,
    access: PremiumThemeAccess,
    systemDark: Boolean,
    onPreview: (String) -> Unit,
    onClosePreview: () -> Unit,
    onApply: (String) -> Unit,
    onUnlock: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (previewId != null) {
        ThemeDetail(previewId, appliedId, access, systemDark, onApply, onUnlock, onClosePreview, modifier)
        return
    }
    val applied = ThemeCatalog.find(appliedId)
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.themes_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.themes_intro))
        if (applied != null && applied.isPremium && access != PremiumThemeAccess.ALLOWED) {
            LockedBanner(applied.name, applied.isLight, access, onUnlock)
        }
        SectionTitle(stringResource(R.string.themes_section_free))
        val free = listOf(ThemeCatalog.SYSTEM_ID) + ThemeCatalog.free.map { it.id }
        CardGrid(free) { id -> ThemeCard(id, appliedId, access, systemDark, onPreview) }
        SectionTitle(stringResource(R.string.themes_section_premium))
        Text(stringResource(R.string.themes_premium_note), style = MaterialTheme.typography.bodyMedium)
        CardGrid(ThemeCatalog.premium.map { it.id }) { id -> ThemeCard(id, appliedId, access, systemDark, onPreview) }
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.onb_back)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

/** Two cards per row; the last odd card keeps half the width. */
@Composable
private fun CardGrid(ids: List<String>, card: @Composable (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ids.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { id -> Column(Modifier.weight(1f)) { card(id) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ThemeCard(id: String, appliedId: String, access: PremiumThemeAccess, systemDark: Boolean, onPreview: (String) -> Unit) {
    val theme = ThemePickerModel.previewTheme(id, systemDark)
    val system = id == ThemeCatalog.SYSTEM_ID
    val name = if (system) stringResource(R.string.themes_system) else theme.name
    val premium = !system && theme.isPremium
    val isApplied = id == normalizedApplied(appliedId)
    val locked = premium && access != PremiumThemeAccess.ALLOWED
    val description = themeDescription(name, premium, locked, isApplied)
    Card(
        Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.themes_open_preview)) { onPreview(id) }
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        border = if (isApplied) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyboardThemePreview(theme, Modifier.fillMaxWidth(), showStrip = false)
            Text(name, style = MaterialTheme.typography.titleMedium)
            if (system) Text(stringResource(R.string.themes_system_hint), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (premium) PremiumBadge(locked)
                if (isApplied) Text(stringResource(R.string.themes_applied), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** An unknown or legacy stored id counts as "system" (the keyboard does the same). */
private fun normalizedApplied(appliedId: String) = if (ThemeCatalog.find(appliedId) != null) appliedId else ThemeCatalog.SYSTEM_ID

@Composable
private fun themeDescription(name: String, premium: Boolean, locked: Boolean, applied: Boolean): String = buildString {
    append(name)
    if (premium) append(", ").append(stringResource(if (locked) R.string.themes_premium_locked else R.string.themes_premium))
    if (applied) append(", ").append(stringResource(R.string.themes_applied))
}

@Composable
private fun PremiumBadge(locked: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (locked) LockIcon(MaterialTheme.colorScheme.tertiary)
        Text(stringResource(R.string.themes_badge_premium), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun accessReason(access: PremiumThemeAccess): String = stringResource(
    when (access) {
        PremiumThemeAccess.ALLOWED -> R.string.themes_reason_allowed
        PremiumThemeAccess.SIGNED_OUT -> R.string.themes_reason_signed_out
        PremiumThemeAccess.NOT_ON_PLAN -> R.string.themes_reason_not_on_plan
        PremiumThemeAccess.NEEDS_CHECK -> R.string.themes_reason_needs_check
    },
)

/** Tells why the chosen premium theme is not drawn: the keyboard shows Classic Light or Dark instead. */
@Composable
private fun LockedBanner(name: String, isLight: Boolean, access: PremiumThemeAccess, onUnlock: () -> Unit) {
    val fallback = if (isLight) ThemeCatalog.classicLight.name else ThemeCatalog.classicDark.name
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.themes_locked_title, name), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(stringResource(R.string.themes_locked_body, fallback, accessReason(access)), color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(onClick = onUnlock) { Text(stringResource(R.string.themes_unlock)) }
        }
    }
}

@Composable
private fun ThemeDetail(
    previewId: String,
    appliedId: String,
    access: PremiumThemeAccess,
    systemDark: Boolean,
    onApply: (String) -> Unit,
    onUnlock: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val theme: KeyboardTheme = ThemePickerModel.previewTheme(previewId, systemDark)
    val system = previewId == ThemeCatalog.SYSTEM_ID
    val name = if (system) stringResource(R.string.themes_system) else theme.name
    val premium = !system && theme.isPremium
    val locked = premium && access != PremiumThemeAccess.ALLOWED
    val isApplied = previewId == normalizedApplied(appliedId)
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        if (premium) PremiumBadge(locked)
        if (system) Text(stringResource(R.string.themes_system_hint))
        KeyboardThemePreview(theme, Modifier.fillMaxWidth(), showStrip = true, description = stringResource(R.string.themes_preview_description, name))
        Text(stringResource(R.string.themes_preview_note), style = MaterialTheme.typography.bodySmall)
        if (locked) Text(accessReason(access))
        Spacer(Modifier.height(4.dp))
        when {
            isApplied -> Button(onClick = {}, enabled = false) { Text(stringResource(R.string.themes_applied)) }
            locked -> Button(onClick = onUnlock) { Text(stringResource(R.string.themes_unlock)) }
            else -> Button(onClick = { onApply(previewId); onClose() }) { Text(stringResource(R.string.themes_apply)) }
        }
        OutlinedButton(onClick = onClose) { Text(stringResource(R.string.themes_back_to_list)) }
    }
}
