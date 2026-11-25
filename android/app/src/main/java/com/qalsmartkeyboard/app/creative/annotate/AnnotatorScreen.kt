package com.qalsmartkeyboard.app.creative.annotate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.qalsmartkeyboard.aiclient.FeatureAccess
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.creative.cards.ExportFormat
import com.qalsmartkeyboard.app.themes.LockIcon

/** Which export the user asked for (the dialog says what a flattened copy means before it is made). */
enum class ExportKind { SAVE, SHARE }

/**
 * The image annotator (T8.7). A thin screen over [AnnotatorEditor]: the picture with its marks (pinch to zoom, two
 * fingers to pan), the tools (premium ones with a lock that opens the plans), the options of the chosen tool, and Save
 * a copy / Share, which first explain that the copy is flattened. Everything that needs Android services (picker,
 * share sheet, entitlement, rendering) comes in from [AnnotatorRoute].
 *
 * Layout: rows scroll instead of overflowing, touch targets are 48 dp, start/end instead of left/right, so it works at
 * 360 dp, with large fonts and in right-to-left languages (the picture itself is never mirrored).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotatorScreen(
    editor: AnnotatorEditor,
    access: FeatureAccess,
    preview: ImageBitmap?,
    draftWaiting: Boolean,
    onPick: () -> Unit,
    onContinueDraft: () -> Unit,
    onDiscardDraft: () -> Unit,
    onExport: (ExportKind) -> Unit,
    onSolidify: () -> Unit,
    onUnlock: () -> Unit,
    onBack: () -> Unit,
    onFull: () -> Unit,
    modifier: Modifier = Modifier,
    painter: AnnotationPainter? = null,
    viewport: AnnotViewport = remember { AnnotViewport() },
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val doc = editor.doc
    var menu by remember { mutableStateOf(false) }
    var askText by remember { mutableStateOf<GestureOutcome.AskText?>(null) }
    var exportKind by remember { mutableStateOf<ExportKind?>(null) }
    var confirmNew by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // A premium tool that is no longer allowed (signed out, plan ended) is not kept selected.
    LaunchedEffect(access) { if (!AnnotatorGate.toolAllowed(editor.tool, access)) editor.tool = AnnotTool.PEN }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.annot_screen_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_settings_arrow_back), contentDescription = stringResource(R.string.onb_back))
                    }
                },
                actions = {
                    if (doc != null) {
                        IconButton(onClick = editor::undo, enabled = editor.canUndo) {
                            Icon(painterResource(R.drawable.ic_annot_undo), contentDescription = stringResource(R.string.annot_undo))
                        }
                        IconButton(onClick = editor::redo, enabled = editor.canRedo) {
                            Icon(painterResource(R.drawable.ic_annot_redo), contentDescription = stringResource(R.string.annot_redo))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(painterResource(R.drawable.ic_annot_more), contentDescription = stringResource(R.string.annot_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.annot_new_picture)) }, onClick = {
                                    menu = false
                                    if (editor.dirty) confirmNew = true else onPick()
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.annot_reset_zoom)) }, onClick = { menu = false; viewport.reset() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.annot_draft_discard)) }, onClick = { menu = false; confirmDiscard = true })
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (doc == null) {
                EmptyState(draftWaiting, onPick, onContinueDraft) { confirmDiscard = true }
            } else {
                AnnotCanvas(
                    editor, doc, preview, painter, viewport,
                    onOutcome = { o ->
                        when (o) {
                            is GestureOutcome.AskText -> askText = o
                            GestureOutcome.Full -> onFull()
                            GestureOutcome.None -> Unit
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                ToolRow(editor, access, onUnlock)
                OptionsRow(editor)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { exportKind = ExportKind.SAVE }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.annot_save), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { exportKind = ExportKind.SHARE }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.card_share), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    askText?.let { ask ->
        TextDialog(
            ask,
            onDone = { text -> askText = null; if (editor.putLabel(ask.at, text, ask.editing) == GestureOutcome.Full) onFull() },
            onDismiss = { askText = null },
        )
    }
    exportKind?.let { kind ->
        ExportDialog(
            kind, editor, access,
            onConfirm = { exportKind = null; onExport(kind) },
            onSolidify = onSolidify,
            onUnlock = { exportKind = null; onUnlock() },
            onDismiss = { exportKind = null },
        )
    }
    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text(stringResource(R.string.annot_new_title)) },
            text = { Text(stringResource(R.string.annot_new_body)) },
            confirmButton = { TextButton(onClick = { confirmNew = false; onPick() }) { Text(stringResource(R.string.annot_new_picture)) } },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text(stringResource(R.string.pd_cancel)) } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.annot_discard_title)) },
            text = { Text(stringResource(R.string.annot_discard_body)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDiscardDraft() }) { Text(stringResource(R.string.annot_draft_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.pd_cancel)) } },
        )
    }
}

// ---- No picture yet ---------------------------------------------------------------------------------------------------

@Composable
private fun EmptyState(draftWaiting: Boolean, onPick: () -> Unit, onContinue: () -> Unit, onDiscard: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (draftWaiting) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.annot_draft_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.annot_draft_body))
                    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.annot_draft_continue)) }
                    TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.annot_draft_discard)) }
                }
            }
        }
        Text(stringResource(R.string.annot_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.annot_pick)) }
        Text(stringResource(R.string.annot_privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- The picture ------------------------------------------------------------------------------------------------------

@Composable
private fun AnnotCanvas(
    editor: AnnotatorEditor,
    doc: AnnotDoc,
    preview: ImageBitmap?,
    painter: AnnotationPainter?,
    viewport: AnnotViewport,
    onOutcome: (GestureOutcome) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val slopPx = with(density) { 20.dp.toPx() }
    val outlinePx = with(density) { 2.dp.toPx() }
    val outcome by rememberUpdatedState(onOutcome)
    LaunchedEffect(doc.width, doc.height) { if (viewport.imageWidth != doc.width || viewport.imageHeight != doc.height) viewport.reset(doc.width, doc.height) }
    val primary = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.annot_canvas_desc, doc.items.size)
    Box(
        modifier.clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { viewport.containerWidth = it.width.toFloat(); viewport.containerHeight = it.height.toFloat() }
            .semantics { contentDescription = description },
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(editor, viewport) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val p0 = viewport.toImage(first.position.x, first.position.y)
                    var drawing = editor.press(p0, slopPx / viewport.scale)
                    if (!drawing) editor.cancel()
                    var transformed = false
                    first.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (drawing) { editor.cancel(); drawing = false }
                            transformed = true
                            val c = ev.calculateCentroid(useCurrent = true)
                            val pan = ev.calculatePan()
                            viewport.transform(ev.calculateZoom(), c.x, c.y, pan.x, pan.y)
                        } else if (drawing) {
                            val c = pressed[0]
                            editor.drag(viewport.toImage(c.position.x, c.position.y))
                        } else if (!transformed) {
                            val c = pressed[0]
                            viewport.panBy(c.position.x - c.previousPosition.x, c.position.y - c.previousPosition.y)
                        }
                        ev.changes.forEach { it.consume() }
                    }
                    if (drawing) outcome(editor.release())
                }
            },
        ) {
            val s = viewport.scale
            withTransform({
                translate(viewport.offsetX, viewport.offsetY)
                scale(s, s, pivot = Offset.Zero)
            }) {
                if (preview != null) {
                    drawImage(preview, srcOffset = IntOffset.Zero, srcSize = IntSize(preview.width, preview.height), dstSize = IntSize(doc.width, doc.height))
                }
                val live = editor.live
                if (painter != null) {
                    drawIntoCanvas { painter.draw(it.nativeCanvas, doc.items, doc.width, doc.height, live?.takeIf { l -> l !is Annotation.Redaction }) }
                }
                (live as? Annotation.Redaction)?.let { drawLiveRedaction(it, outlinePx / s) }
                // Redactions are burnt into the preview; their outline shows where they are.
                for (r in doc.redactions) outlineArea(r.area, Color.White.copy(alpha = 0.6f), outlinePx / s, dashed = true)
                drawCrop(editor.liveCrop ?: doc.crop, doc, outlinePx / s)
                editor.selected?.let { sel ->
                    val b = Geometry.bounds(sel, editor.measure).outset(outlinePx * 3 / s)
                    drawRect(primary, Offset(b.left, b.top), Size(b.width, b.height), style = Stroke(outlinePx * 1.5f / s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f / s, 6f / s))))
                }
            }
        }
    }
}

private fun DrawScope.outlineArea(area: RedactArea, color: Color, width: Float, dashed: Boolean) {
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(width * 5, width * 3)) else null
    when (area) {
        is RedactArea.Rect -> {
            val b = area.box.normalized()
            drawRect(color, Offset(b.left, b.top), Size(b.width, b.height), style = Stroke(width, pathEffect = effect))
        }
        is RedactArea.Brush -> {
            val path = Path().apply {
                moveTo(area.points[0].x, area.points[0].y)
                area.points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color, style = Stroke(width, pathEffect = effect))
        }
    }
}

/** The redaction being drawn: a grey veil over its area (the real redaction appears when the finger lifts). */
private fun DrawScope.drawLiveRedaction(r: Annotation.Redaction, width: Float) {
    val veil = Color(0xB3808080)
    when (val a = r.area) {
        is RedactArea.Rect -> {
            val b = a.box.normalized()
            drawRect(veil, Offset(b.left, b.top), Size(b.width, b.height))
        }
        is RedactArea.Brush -> {
            val path = Path().apply {
                moveTo(a.points[0].x, a.points[0].y)
                a.points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, veil, style = Stroke(a.radius * 2, cap = StrokeCap.Round))
        }
    }
    outlineArea(r.area, Color.White, width, dashed = false)
}

/** Dims what the crop cuts off and frames what it keeps. */
private fun DrawScope.drawCrop(crop: PixelRect?, doc: AnnotDoc, width: Float) {
    val c = crop?.clampTo(doc.width, doc.height)?.takeIf { !it.isEmpty } ?: return
    val dim = Color(0x99000000)
    val w = doc.width.toFloat()
    val h = doc.height.toFloat()
    drawRect(dim, Offset(0f, 0f), Size(w, c.top.toFloat()))
    drawRect(dim, Offset(0f, c.bottom.toFloat()), Size(w, h - c.bottom))
    drawRect(dim, Offset(0f, c.top.toFloat()), Size(c.left.toFloat(), c.height.toFloat()))
    drawRect(dim, Offset(c.right.toFloat(), c.top.toFloat()), Size(w - c.right, c.height.toFloat()))
    drawRect(Color.White, Offset(c.left.toFloat(), c.top.toFloat()), Size(c.width.toFloat(), c.height.toFloat()), style = Stroke(width))
}

// ---- Tools ------------------------------------------------------------------------------------------------------------

@Composable
private fun ToolRow(editor: AnnotatorEditor, access: FeatureAccess, onUnlock: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (t in AnnotTool.entries) {
            val locked = !AnnotatorGate.toolAllowed(t, access)
            ToolButton(t, editor.tool == t, AnnotatorGate.isPremium(t), locked) {
                if (locked) onUnlock() else editor.tool = t
            }
        }
    }
}

@Composable
private fun ToolButton(tool: AnnotTool, chosen: Boolean, premium: Boolean, locked: Boolean, onClick: () -> Unit) {
    val name = stringResource(toolName(tool))
    val description = when {
        locked -> stringResource(R.string.annot_tool_locked_desc, name)
        premium -> stringResource(R.string.annot_tool_premium_desc, name)
        else -> name
    }
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (chosen) colors.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.RadioButton
                selected = chosen
            },
        contentAlignment = Alignment.Center,
    ) {
        ToolGlyph(tool, if (chosen) colors.onPrimaryContainer else colors.onSurface, Modifier.size(26.dp))
        if (locked) {
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(16.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                LockIcon(colors.tertiary, size = 11.dp)
            }
        }
    }
}

internal fun toolName(t: AnnotTool) = when (t) {
    AnnotTool.SELECT -> R.string.annot_tool_select
    AnnotTool.PEN -> R.string.annot_tool_pen
    AnnotTool.HIGHLIGHTER -> R.string.annot_tool_highlighter
    AnnotTool.ARROW -> R.string.annot_tool_arrow
    AnnotTool.RECT -> R.string.annot_tool_rect
    AnnotTool.ELLIPSE -> R.string.annot_tool_ellipse
    AnnotTool.TEXT -> R.string.annot_tool_text
    AnnotTool.ERASER -> R.string.annot_tool_eraser
    AnnotTool.CROP -> R.string.annot_tool_crop
    AnnotTool.REDACT_SOLID -> R.string.annot_tool_redact_solid
    AnnotTool.BLUR -> R.string.annot_tool_blur
    AnnotTool.PIXELATE -> R.string.annot_tool_pixelate
    AnnotTool.STEP -> R.string.annot_tool_step
    AnnotTool.SPOTLIGHT -> R.string.annot_tool_spotlight
}

private fun hint(t: AnnotTool) = when (t) {
    AnnotTool.SELECT -> R.string.annot_hint_select
    AnnotTool.TEXT -> R.string.annot_hint_text
    AnnotTool.ERASER -> R.string.annot_hint_eraser
    AnnotTool.CROP -> R.string.annot_hint_crop
    AnnotTool.REDACT_SOLID, AnnotTool.BLUR, AnnotTool.PIXELATE -> R.string.annot_hint_redact
    AnnotTool.STEP -> R.string.annot_hint_step
    AnnotTool.SPOTLIGHT -> R.string.annot_hint_spotlight
    else -> R.string.annot_hint_draw
}

/** The tool's own little picture (drawn, no icon library). */
@Composable
private fun ToolGlyph(tool: AnnotTool, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        val st = Stroke(sw, cap = StrokeCap.Round)
        when (tool) {
            AnnotTool.SELECT -> drawPath(Path().apply { moveTo(w * 0.25f, h * 0.12f); lineTo(w * 0.25f, h * 0.85f); lineTo(w * 0.45f, h * 0.65f); lineTo(w * 0.62f, h * 0.92f); lineTo(w * 0.72f, h * 0.86f); lineTo(w * 0.56f, h * 0.6f); lineTo(w * 0.82f, h * 0.58f); close() }, color)
            AnnotTool.PEN -> drawPath(Path().apply { moveTo(w * 0.12f, h * 0.75f); cubicTo(w * 0.35f, h * 0.2f, w * 0.55f, h * 0.95f, w * 0.88f, h * 0.25f) }, color, style = st)
            AnnotTool.HIGHLIGHTER -> drawLine(color.copy(alpha = 0.55f), Offset(w * 0.12f, h * 0.6f), Offset(w * 0.88f, h * 0.4f), strokeWidth = w * 0.28f)
            AnnotTool.ARROW -> {
                drawLine(color, Offset(w * 0.15f, h * 0.85f), Offset(w * 0.8f, h * 0.2f), strokeWidth = sw, cap = StrokeCap.Round)
                drawPath(Path().apply { moveTo(w * 0.88f, h * 0.12f); lineTo(w * 0.5f, h * 0.22f); lineTo(w * 0.78f, h * 0.5f); close() }, color)
            }
            AnnotTool.RECT -> drawRect(color, Offset(w * 0.15f, h * 0.22f), Size(w * 0.7f, h * 0.56f), style = Stroke(sw))
            AnnotTool.ELLIPSE -> drawOval(color, Offset(w * 0.12f, h * 0.2f), Size(w * 0.76f, h * 0.6f), style = Stroke(sw))
            AnnotTool.TEXT -> {
                drawLine(color, Offset(w * 0.2f, h * 0.2f), Offset(w * 0.8f, h * 0.2f), strokeWidth = sw * 1.4f)
                drawLine(color, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.85f), strokeWidth = sw * 1.4f)
            }
            AnnotTool.ERASER -> {
                drawLine(color, Offset(w * 0.2f, h * 0.2f), Offset(w * 0.8f, h * 0.8f), strokeWidth = sw * 1.3f, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.8f, h * 0.2f), Offset(w * 0.2f, h * 0.8f), strokeWidth = sw * 1.3f, cap = StrokeCap.Round)
            }
            AnnotTool.CROP -> {
                drawPath(Path().apply { moveTo(w * 0.28f, h * 0.05f); lineTo(w * 0.28f, h * 0.72f); lineTo(w * 0.95f, h * 0.72f) }, color, style = st)
                drawPath(Path().apply { moveTo(w * 0.05f, h * 0.28f); lineTo(w * 0.72f, h * 0.28f); lineTo(w * 0.72f, h * 0.95f) }, color, style = st)
            }
            AnnotTool.REDACT_SOLID -> drawRect(color, Offset(w * 0.12f, h * 0.3f), Size(w * 0.76f, h * 0.4f))
            AnnotTool.BLUR -> for (i in 0..2) drawCircle(color.copy(alpha = 0.25f + 0.25f * i), radius = w * (0.42f - 0.12f * i), center = Offset(w / 2, h / 2))
            AnnotTool.PIXELATE -> {
                val c = w / 4
                for (y in 0..3) for (x in 0..3) if ((x + y) % 2 == 0) drawRect(color, Offset(x * c, y * c), Size(c, c))
            }
            AnnotTool.STEP -> {
                drawCircle(color, radius = w * 0.42f)
                drawLine(Color.White.copy(alpha = 0.95f), Offset(w * 0.52f, h * 0.25f), Offset(w * 0.52f, h * 0.75f), strokeWidth = sw * 1.3f)
                drawLine(Color.White.copy(alpha = 0.95f), Offset(w * 0.52f, h * 0.25f), Offset(w * 0.4f, h * 0.36f), strokeWidth = sw * 1.3f)
            }
            AnnotTool.SPOTLIGHT -> {
                drawRect(color.copy(alpha = 0.35f), Offset.Zero, size)
                drawRect(color, Offset(w * 0.3f, h * 0.3f), Size(w * 0.4f, h * 0.4f), style = Stroke(sw))
            }
        }
    }
}

// ---- Options of the chosen tool ---------------------------------------------------------------------------------------

@Composable
private fun OptionsRow(editor: AnnotatorEditor) {
    val t = editor.tool
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(hint(t)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editor.selected != null && t != AnnotTool.ERASER) {
                FilterChip(selected = false, onClick = editor::deleteSelected, label = { Text(stringResource(R.string.annot_delete_selected)) })
            }
            val coloured = t in setOf(AnnotTool.PEN, AnnotTool.HIGHLIGHTER, AnnotTool.ARROW, AnnotTool.RECT, AnnotTool.ELLIPSE, AnnotTool.TEXT, AnnotTool.STEP)
            val stroked = t in setOf(AnnotTool.PEN, AnnotTool.HIGHLIGHTER, AnnotTool.ARROW, AnnotTool.RECT, AnnotTool.ELLIPSE)
            if (t.redacts) {
                for (shape in RegionShape.entries) {
                    FilterChip(
                        selected = editor.regionShape == shape, onClick = { editor.regionShape = shape },
                        label = { Text(stringResource(if (shape == RegionShape.BOX) R.string.annot_region_box else R.string.annot_region_brush)) },
                    )
                }
            }
            if (stroked || (t.redacts && editor.regionShape == RegionShape.BRUSH)) {
                for (th in Thickness.entries) {
                    FilterChip(selected = editor.thickness == th, onClick = { editor.thickness = th }, label = { Text(stringResource(thicknessName(th))) })
                }
            }
            if (t == AnnotTool.TEXT) {
                for (s in LabelSize.entries) {
                    FilterChip(selected = editor.labelSize == s, onClick = { editor.labelSize = s }, label = { Text(stringResource(sizeName(s))) })
                }
                FilterChip(selected = editor.labelBackdrop, onClick = { editor.labelBackdrop = !editor.labelBackdrop }, label = { Text(stringResource(R.string.annot_plate)) })
            }
            if (t == AnnotTool.CROP && editor.doc?.crop != null) {
                FilterChip(selected = false, onClick = editor::clearCrop, label = { Text(stringResource(R.string.annot_clear_crop)) })
            }
            if (coloured) {
                for (c in AnnotatorEditor.PALETTE) ColourSwatch(c, editor.color == c) { editor.color = c }
            }
            if (!coloured && !t.redacts && t != AnnotTool.CROP && editor.selected == null) Spacer(Modifier.height(48.dp))
        }
    }
}

private fun thicknessName(t: Thickness) = when (t) {
    Thickness.THIN -> R.string.annot_thin
    Thickness.MEDIUM -> R.string.annot_medium
    Thickness.THICK -> R.string.annot_thick
}

private fun sizeName(s: LabelSize) = when (s) {
    LabelSize.SMALL -> R.string.annot_size_small
    LabelSize.MEDIUM -> R.string.annot_medium
    LabelSize.LARGE -> R.string.annot_size_large
}

@Composable
private fun ColourSwatch(c: Int, chosen: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.card_color_desc, "#%06X".format(c and 0xFFFFFF))
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = description; role = Role.RadioButton; selected = chosen },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c)).border(if (chosen) 3.dp else 1.dp, if (chosen) colors.primary else colors.outline, CircleShape))
    }
}

// ---- Dialogs ----------------------------------------------------------------------------------------------------------

@Composable
private fun TextDialog(ask: GestureOutcome.AskText, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(ask.editing?.text ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (ask.editing == null) R.string.annot_text_add_title else R.string.annot_text_edit_title)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(Annotation.MAX_TEXT) }, minLines = 1, maxLines = 4,
                label = { Text(stringResource(R.string.annot_text_field)) }, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text(stringResource(R.string.annot_text_done)) } },
        dismissButton = {
            Row {
                if (ask.editing != null) TextButton(onClick = { onDone("") }) { Text(stringResource(R.string.pv_delete_item)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.pd_cancel)) }
            }
        },
    )
}

@Composable
private fun ExportDialog(
    kind: ExportKind,
    editor: AnnotatorEditor,
    access: FeatureAccess,
    onConfirm: () -> Unit,
    onSolidify: () -> Unit,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val doc = editor.doc ?: return
    val locked = AnnotatorGate.lockedTools(doc, access)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (kind == ExportKind.SAVE) R.string.annot_export_title_save else R.string.annot_export_title_share)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.annot_export_body))
                Text(stringResource(R.string.card_export_format), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in ExportFormat.entries) {
                        FilterChip(
                            selected = editor.format == f, onClick = { editor.format = f },
                            label = { Text(stringResource(if (f == ExportFormat.PNG) R.string.card_format_png else R.string.card_format_jpeg)) },
                        )
                    }
                }
                if (locked.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.annot_locked_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(stringResource(R.string.annot_locked_body), color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(stringResource(reason(access)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.themes_unlock)) }
                            if (locked.any { it.redacts }) {
                                OutlinedButton(onClick = onSolidify, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.annot_solidify)) }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.annot_privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = locked.isEmpty()) {
                Text(stringResource(if (kind == ExportKind.SAVE) R.string.annot_export_confirm_save else R.string.card_share))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pd_cancel)) } },
    )
}

private fun reason(access: FeatureAccess) = when (access) {
    FeatureAccess.SIGNED_OUT -> R.string.annot_reason_signed_out
    FeatureAccess.NOT_ON_PLAN -> R.string.annot_reason_not_on_plan
    FeatureAccess.NEEDS_CHECK -> R.string.card_reason_needs_check
    FeatureAccess.ALLOWED -> R.string.card_reason_allowed
}
