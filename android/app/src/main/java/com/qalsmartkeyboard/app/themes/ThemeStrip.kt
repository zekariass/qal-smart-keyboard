package com.qalsmartkeyboard.app.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/** How many miniatures the settings home shows before "View more". */
internal const val STRIP_SIZE = 8

/**
 * The themes the settings home shows: the applied one first, then System, the free themes and the premium themes, up
 * to [STRIP_SIZE].
 */
internal fun stripThemeIds(appliedId: String): List<String> {
    val applied = normalizedApplied(appliedId)
    val rest = (listOf(ThemeCatalog.SYSTEM_ID) + ThemeCatalog.free.map { it.id } + ThemeCatalog.premium.map { it.id }).filter { it != applied }
    return (listOf(applied) + rest).take(STRIP_SIZE)
}

/**
 * The "Theme" section at the top of the settings home (ADR 0036): a heading with "View more", then a horizontally
 * scrolling row of miniature keyboards drawn from each theme's own tokens. The applied theme comes first and carries
 * an outline and a check. Tapping a theme calls [onSelect]; the host applies it, or opens the full picker on a locked
 * premium theme.
 */
@Composable
fun ThemeStrip(
    appliedId: String,
    access: PremiumThemeAccess,
    systemDark: Boolean,
    onSelect: (String) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_theme_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = onMore) { Text(stringResource(R.string.settings_theme_more)) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            stripThemeIds(appliedId).forEach { id -> ThemeTile(id, appliedId, access, systemDark, onSelect) }
        }
    }
}

@Composable
private fun ThemeTile(id: String, appliedId: String, access: PremiumThemeAccess, systemDark: Boolean, onSelect: (String) -> Unit) {
    val theme = ThemePickerModel.previewTheme(id, systemDark)
    val system = id == ThemeCatalog.SYSTEM_ID
    val name = if (system) stringResource(R.string.themes_system) else themeName(theme)
    val premium = !system && theme.isPremium
    val locked = premium && access != PremiumThemeAccess.ALLOWED
    val selected = id == normalizedApplied(appliedId)
    val description = buildString {
        append(name)
        if (premium) append(", ").append(stringResource(if (locked) R.string.themes_premium_locked else R.string.themes_premium))
        if (selected) append(", ").append(stringResource(R.string.settings_theme_selected))
    }
    val shape = RoundedCornerShape(10.dp)
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.width(TILE_WIDTH)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(id) })
            .semantics { contentDescription = description }
            .padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            KeyboardThemePreview(
                theme,
                Modifier.fillMaxWidth().clip(shape).border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape),
                showStrip = false,
            )
            if (selected) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_settings_check), contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (locked) LockIcon(colors.onSurfaceVariant, size = 12.dp)
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { }, // the tile's description says it
            )
        }
    }
}

private val TILE_WIDTH = 112.dp
