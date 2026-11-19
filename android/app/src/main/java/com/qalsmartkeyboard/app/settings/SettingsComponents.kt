package com.qalsmartkeyboard.app.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.app.R

/*
 * The building blocks every settings page shares (ADR 0036): one scaffold (top app bar with Back, a scrolling column
 * that respects the system bars), small section headers, and Material 3 list rows. A row is one focusable target with
 * a role; a switch, radio button or checkbox is toggled by tapping the whole row. No cards, no dividers.
 */

/**
 * A settings page: a top app bar with [title] and, when [onBack] is set, a Back arrow ([backDescription] for TalkBack),
 * then [content] in a vertically scrolling column. Draws edge to edge: the bar takes the status bar inset, the column
 * the navigation bar and the sides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backDescription: String = stringResource(R.string.onb_back),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_settings_arrow_back), contentDescription = backDescription) }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        Column(
            Modifier.fillMaxSize()
                .padding(top = padding.calculateTopPadding(), start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction))
                .verticalScroll(rememberScrollState()),
        ) {
            content()
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

/** A small section header (a heading for TalkBack). */
@Composable
fun SettingsHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** A quiet line of supporting text under a header or at the top of a page. Use sparingly: one where it is needed. */
@Composable
fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun RowIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Summary(text: String) {
    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** A row that opens a page or a tool: optional leading [icon], [title] and a one-line value [summary]. */
@Composable
fun SettingsRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    titleColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, color = titleColor) },
        supportingContent = summary?.let { { Summary(it) } },
        leadingContent = icon?.let { { RowIcon(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

/** A row that only shows a value (About). */
@Composable
fun SettingsValueRow(title: String, value: String, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Summary(value) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.semantics(mergeDescendants = true) {},
    )
}

/** A switch row: the whole row toggles; the switch on the right only shows the state. */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Summary(it) } },
        leadingContent = icon?.let { { RowIcon(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

/** One option of a single choice: the radio button leads, the whole row selects. Put the rows in a `selectableGroup`. */
@Composable
fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Summary(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}

/** One option of a multiple choice: the whole row toggles; the checkbox on the right shows the state. */
@Composable
fun SettingsCheckboxRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Summary(it) } },
        trailingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
    )
}
