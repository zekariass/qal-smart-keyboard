package com.qalsmartkeyboard.app.creative.cards

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.aiclient.FeatureAccess
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.themes.LockIcon

/**
 * The quote and greeting card maker (T8.7). A thin screen over [CardEditor]: a sticky live preview (the renderer's own
 * bitmap), five steps as chips (template, text, background, style, export) and the controls of the open step. Every
 * choice goes through [editor]; locked (premium) choices call [onUnlock], which opens the plans screen. It never
 * touches Android services: the host passes the preview, the template thumbnails and the actions in.
 *
 * Layout: nothing is wider than the screen (rows wrap or scroll), touch targets are at least 48 dp, start/end instead of
 * left/right, so it works at 360 dp, with large fonts and in right-to-left languages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardMakerScreen(
    editor: CardEditor,
    access: FeatureAccess,
    preview: ImageBitmap?,
    templateThumbs: Map<String, ImageBitmap>,
    onPickPhoto: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onUnlock: () -> Unit,
    onBack: () -> Unit,
    onNew: () -> Unit,
    resolve: (Int) -> String,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val doc = editor.doc
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.card_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_settings_arrow_back), contentDescription = stringResource(R.string.onb_back))
                    }
                },
                actions = { TextButton(onClick = onNew) { Text(stringResource(R.string.card_new)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            PreviewBox(preview, doc.aspect, compact = imeUp)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (s in CardSection.entries) {
                    FilterChip(selected = editor.section == s, onClick = { editor.section = s }, label = { Text(stringResource(sectionTitle(s))) })
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (editor.section) {
                    CardSection.TEMPLATE -> TemplateStep(editor, access, templateThumbs, onUnlock, resolve)
                    CardSection.TEXT -> TextStep(editor)
                    CardSection.BACKGROUND -> BackgroundStep(editor, access, onPickPhoto, onUnlock)
                    CardSection.STYLE -> StyleStep(editor, access, onUnlock)
                    CardSection.EXPORT -> ExportStep(editor, access, onSave, onShare, onUnlock)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun sectionTitle(s: CardSection) = when (s) {
    CardSection.TEMPLATE -> R.string.card_section_template
    CardSection.TEXT -> R.string.card_section_text
    CardSection.BACKGROUND -> R.string.card_section_background
    CardSection.STYLE -> R.string.card_section_style
    CardSection.EXPORT -> R.string.card_section_export
}

/** The live preview, kept in view; smaller while the on-screen keyboard is up so the field stays visible. */
@Composable
private fun PreviewBox(preview: ImageBitmap?, aspect: CardAspect, compact: Boolean) {
    val description = stringResource(R.string.card_preview_desc)
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier.height(if (compact) 96.dp else 220.dp).aspectRatio(aspect.width.toFloat() / aspect.height).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .semantics { contentDescription = description },
        ) {
            if (preview != null) Image(preview, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
}

// ---- Template ---------------------------------------------------------------------------------------------------------

@Composable
private fun TemplateStep(editor: CardEditor, access: FeatureAccess, thumbs: Map<String, ImageBitmap>, onUnlock: () -> Unit, resolve: (Int) -> String) {
    StepTitle(stringResource(R.string.card_group_free))
    Grid(CardTemplates.free, columns = 3) { t -> TemplateCard(t, editor.doc.templateId == t.id, locked = false, thumbs[t.id]) { editor.applyTemplate(t, resolve) } }
    StepTitle(stringResource(R.string.card_group_premium))
    Grid(CardTemplates.premium, columns = 3) { t ->
        val locked = !CardGate.templateAllowed(t, access)
        TemplateCard(t, editor.doc.templateId == t.id, locked, thumbs[t.id]) { if (locked) onUnlock() else editor.applyTemplate(t, resolve) }
    }
    if (access != FeatureAccess.ALLOWED) AccessNote(access)
}

@Composable
private fun TemplateCard(t: CardTemplate, selected: Boolean, locked: Boolean, thumb: ImageBitmap?, onClick: () -> Unit) {
    val name = stringResource(t.nameRes)
    val description = when {
        locked -> stringResource(R.string.card_template_locked_desc, name)
        t.premium -> stringResource(R.string.card_template_premium_desc, name)
        else -> name
    }
    val shape = RoundedCornerShape(10.dp)
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
            }
            .padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(colors.surfaceVariant).border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)) {
            if (thumb != null) Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (locked) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                    LockIcon(colors.tertiary, size = 14.dp)
                }
            }
        }
        Text(name, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.primary else colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (t.premium) Text(stringResource(R.string.themes_badge_premium), style = MaterialTheme.typography.labelSmall, color = colors.tertiary)
    }
}

@Composable
private fun AccessNote(access: FeatureAccess) {
    Text(
        stringResource(
            when (access) {
                FeatureAccess.SIGNED_OUT -> R.string.card_reason_signed_out
                FeatureAccess.NOT_ON_PLAN -> R.string.card_reason_not_on_plan
                FeatureAccess.NEEDS_CHECK -> R.string.card_reason_needs_check
                FeatureAccess.ALLOWED -> R.string.card_reason_allowed
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A grid with [columns] equal columns; the last short row keeps the column width. */
@Composable
private fun <T> Grid(items: List<T>, columns: Int, item: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { item(it) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---- Text -------------------------------------------------------------------------------------------------------------

@Composable
private fun TextStep(editor: CardEditor) {
    val d = editor.doc
    OutlinedTextField(
        value = d.headline, onValueChange = editor::headline, singleLine = true,
        label = { Text(stringResource(R.string.card_field_headline)) }, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = d.body, onValueChange = editor::body, minLines = 3, maxLines = 6,
        label = { Text(stringResource(R.string.card_field_body)) }, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = d.from, onValueChange = editor::from, singleLine = true,
        label = { Text(stringResource(R.string.card_field_from)) }, modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.card_text_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- Background -------------------------------------------------------------------------------------------------------

@Composable
private fun BackgroundStep(editor: CardEditor, access: FeatureAccess, onPickPhoto: () -> Unit, onUnlock: () -> Unit) {
    val bg = editor.doc.background
    StepTitle(stringResource(R.string.card_bg_colors))
    Grid(CardPalette.solids, columns = SWATCHES) { c ->
        val chosen = bg is CardBackground.Solid && bg.color == c
        Swatch(Brush.linearGradient(listOf(Color(c), Color(c))), chosen, stringResource(R.string.card_color_desc, hex(c))) { editor.background(CardBackground.Solid(c)) }
    }
    StepTitle(stringResource(R.string.card_bg_gradients))
    Grid(CardPalette.gradients.withIndex().toList(), columns = SWATCHES) { (i, g) ->
        val chosen = bg is CardBackground.Gradient && bg.presetId == g.id
        val locked = g.premium && access != FeatureAccess.ALLOWED
        Swatch(
            Brush.linearGradient(g.colors.map { Color(it) }), chosen,
            stringResource(if (locked) R.string.card_gradient_desc_locked else R.string.card_gradient_desc, i + 1), locked,
        ) { if (locked) onUnlock() else editor.background(g.toBackground()) }
    }
    StepTitle(stringResource(R.string.card_bg_patterns))
    Grid(CardPalette.patterns.withIndex().toList(), columns = SWATCHES) { (i, p) ->
        val chosen = bg == p
        Swatch(Brush.linearGradient(0f to Color(p.base), 0.5f to Color(p.base), 0.5f to Color(p.accent), 1f to Color(p.accent)), chosen, stringResource(R.string.card_pattern_desc, stringResource(patternName(p.kind)), i + 1)) {
            editor.background(p)
        }
    }
    StepTitle(stringResource(R.string.card_bg_photo))
    OutlinedButton(onClick = onPickPhoto, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.card_bg_pick_photo)) }
    Text(stringResource(R.string.card_bg_photo_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (bg is CardBackground.Photo) {
        Text(stringResource(R.string.card_bg_dim, bg.dimPercent), style = MaterialTheme.typography.labelLarge)
        Slider(value = bg.dimPercent.toFloat(), onValueChange = { editor.dim(it.toInt()) }, valueRange = 0f..CardBackground.MAX_DIM.toFloat())
    }
}

private const val SWATCHES = 5

private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

private fun patternName(k: PatternKind) = when (k) {
    PatternKind.DOTS -> R.string.card_pattern_dots
    PatternKind.STRIPES -> R.string.card_pattern_stripes
    PatternKind.CHECKS -> R.string.card_pattern_checks
    PatternKind.WAVES -> R.string.card_pattern_waves
    PatternKind.CONFETTI -> R.string.card_pattern_confetti
}

/** A round colour choice with a 48 dp touch target. */
@Composable
private fun Swatch(fill: Brush, chosen: Boolean, description: String, locked: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description; selected = chosen },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(fill).border(if (chosen) 3.dp else 1.dp, if (chosen) colors.primary else colors.outline, CircleShape), contentAlignment = Alignment.Center) {
            if (locked) LockIcon(Color.White, size = 16.dp)
        }
    }
}

// ---- Style ------------------------------------------------------------------------------------------------------------

@Composable
private fun StyleStep(editor: CardEditor, access: FeatureAccess, onUnlock: () -> Unit) {
    val style = editor.doc.text
    StepTitle(stringResource(R.string.card_style_font))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in CardFont.entries) {
            FilterChip(
                selected = style.font == f, onClick = { editor.font(f) },
                label = { Text(stringResource(fontName(f)), fontFamily = composeFamily(f)) },
            )
        }
    }
    Text(stringResource(R.string.card_style_size, (style.sizeScale * 100).toInt()), style = MaterialTheme.typography.labelLarge)
    Slider(value = style.sizeScale, onValueChange = editor::sizeScale, valueRange = CardTextStyle.MIN_SCALE..CardTextStyle.MAX_SCALE)
    StepTitle(stringResource(R.string.card_style_color))
    Grid(CardPalette.textColors, columns = SWATCHES) { c ->
        Swatch(Brush.linearGradient(listOf(Color(c), Color(c))), style.color == c, stringResource(R.string.card_color_desc, hex(c))) { editor.color(c) }
    }
    StepTitle(stringResource(R.string.card_style_align))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (a in CardTextAlign.entries) {
            FilterChip(selected = style.align == a, onClick = { editor.align(a) }, label = { Text(stringResource(alignName(a))) })
        }
    }
    SwitchRow(stringResource(R.string.card_style_bold), style.bold, editor::bold)
    SwitchRow(stringResource(R.string.card_style_shadow), style.shadow, editor::shadow)
    StepTitle(stringResource(R.string.card_style_frame))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in CardFrame.entries) {
            val locked = !CardGate.frameAllowed(f, access)
            FilterChip(
                selected = editor.doc.frame == f,
                onClick = { if (locked) onUnlock() else editor.frame(f) },
                label = { Text(stringResource(frameName(f))) },
                leadingIcon = if (locked) ({ LockIcon(MaterialTheme.colorScheme.tertiary, size = 16.dp) }) else null,
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun composeFamily(f: CardFont): FontFamily? = when (f) {
    CardFont.SANS, CardFont.SANS_LIGHT, CardFont.CONDENSED -> FontFamily.SansSerif
    CardFont.SERIF -> FontFamily.Serif
    CardFont.MONO -> FontFamily.Monospace
    CardFont.CURSIVE -> FontFamily.Cursive
    CardFont.CASUAL -> null
}

private fun fontName(f: CardFont) = when (f) {
    CardFont.SANS -> R.string.card_font_sans
    CardFont.SANS_LIGHT -> R.string.card_font_light
    CardFont.CONDENSED -> R.string.card_font_condensed
    CardFont.SERIF -> R.string.card_font_serif
    CardFont.CURSIVE -> R.string.card_font_script
    CardFont.CASUAL -> R.string.card_font_casual
    CardFont.MONO -> R.string.card_font_mono
}

private fun alignName(a: CardTextAlign) = when (a) {
    CardTextAlign.START -> R.string.card_align_start
    CardTextAlign.CENTER -> R.string.card_align_center
    CardTextAlign.END -> R.string.card_align_end
}

private fun frameName(f: CardFrame) = when (f) {
    CardFrame.NONE -> R.string.card_frame_none
    CardFrame.THIN -> R.string.card_frame_thin
    CardFrame.DOUBLE -> R.string.card_frame_double
    CardFrame.ROUNDED -> R.string.card_frame_rounded
    CardFrame.CORNERS -> R.string.card_frame_corners
    CardFrame.DOTTED -> R.string.card_frame_dotted
}

// ---- Export -----------------------------------------------------------------------------------------------------------

@Composable
private fun ExportStep(editor: CardEditor, access: FeatureAccess, onSave: () -> Unit, onShare: () -> Unit, onUnlock: () -> Unit) {
    val doc = editor.doc
    val locked = !CardGate.canExport(doc, access)
    StepTitle(stringResource(R.string.card_export_size))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (a in CardAspect.entries) {
            FilterChip(
                selected = doc.aspect == a, onClick = { editor.aspect(a) },
                label = { Text(stringResource(if (a == CardAspect.SQUARE) R.string.card_aspect_square else R.string.card_aspect_portrait)) },
            )
        }
    }
    StepTitle(stringResource(R.string.card_export_format))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in ExportFormat.entries) {
            FilterChip(selected = doc.format == f, onClick = { editor.format(f) }, label = { Text(stringResource(if (f == ExportFormat.PNG) R.string.card_format_png else R.string.card_format_jpeg)) })
        }
    }
    if (locked) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.card_locked_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(stringResource(R.string.card_locked_body), color = MaterialTheme.colorScheme.onErrorContainer)
                AccessNote(access)
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.themes_unlock)) }
            }
        }
    }
    Button(onClick = onShare, enabled = !locked, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.card_share)) }
    OutlinedButton(onClick = onSave, enabled = !locked, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.card_save)) }
    Text(stringResource(R.string.card_privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
