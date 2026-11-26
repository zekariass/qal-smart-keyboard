package com.qalsmartkeyboard.app.creative

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.creative.annotate.AnnotatorRoute
import com.qalsmartkeyboard.app.creative.annotate.AnnotatorViewModel
import com.qalsmartkeyboard.app.creative.annotate.SavedAnnotation
import com.qalsmartkeyboard.app.creative.cards.CardImages
import com.qalsmartkeyboard.app.creative.cards.CardMakerRoute
import com.qalsmartkeyboard.app.creative.cards.CardMakerViewModel
import com.qalsmartkeyboard.app.creative.cards.CardOutcome
import com.qalsmartkeyboard.app.creative.cards.CardShare
import com.qalsmartkeyboard.app.creative.cards.SavedCard
import com.qalsmartkeyboard.app.creative.draw.DrawingsViewModel
import com.qalsmartkeyboard.app.creative.draw.SavedDrawingItem
import com.qalsmartkeyboard.app.settings.SettingsScaffold
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the studio gives a tool's screen: the way back to the hub and the way to the plans screen (a locked premium part). */
class CreativeToolHost(val onBack: () -> Unit, val onUnlock: () -> Unit)

/**
 * One tool of the Creative studio (T8.7). The hub lists [CreativeTools.all] as cards; opening one shows its [content] in
 * place of the hub.
 *
 * **To add a tool, add one line to [CreativeTools.all]**: `CreativeTool("annotator", R.string.x_title, R.string.x_summary,
 * R.drawable.ic_x) { host, modifier -> AnnotatorRoute(host, modifier) }`. The tool draws its own top bar (and calls
 * `host.onBack`), calls `host.onUnlock` where a premium part needs the plans screen, and keeps its draft in an
 * [activityViewModel] so the draft survives the trip to the plans.
 */
class CreativeTool(
    val id: String,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @DrawableRes val icon: Int,
    val content: @Composable (host: CreativeToolHost, modifier: Modifier) -> Unit,
)

/** The tools of the studio, in the order the hub shows them. */
object CreativeTools {
    val all: List<CreativeTool> = listOf(
        CreativeTool("cards", R.string.card_title, R.string.studio_cards_summary, R.drawable.ic_settings_palette) { host, modifier -> CardMakerRoute(host, modifier) },
        CreativeTool("annotator", R.string.annot_title, R.string.studio_annot_summary, R.drawable.ic_annot_tool) { host, modifier -> AnnotatorRoute(host, modifier) },
    )

    fun find(id: String?): CreativeTool? = all.firstOrNull { it.id == id }
}

/** The studio's navigation state: which tool is open (null: the hub). Kept by the activity, so it survives the plans detour. */
class CreativeStudioViewModel : ViewModel() {
    var openTool: String? by mutableStateOf(null)
}

/**
 * The Creative studio: the hub of the creative tools, and the open tool. [onUnlock] opens the plans screen (the host
 * activity owns it); the studio is shown again, with the draft, when the user comes back.
 */
@Composable
fun CreativeStudioRoute(onBack: () -> Unit, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val studio = activityViewModel { CreativeStudioViewModel() }
    val tool = CreativeTools.find(studio.openTool)
    if (tool != null) {
        BackHandler { studio.openTool = null }
        tool.content(CreativeToolHost(onBack = { studio.openTool = null }, onUnlock = onUnlock), modifier)
        return
    }
    BackHandler(onBack = onBack)
    val cards = activityViewModel(CardMakerViewModel.create)
    val annotations = activityViewModel(AnnotatorViewModel.create)
    val drawings = activityViewModel(DrawingsViewModel.create)
    CreativeStudioScreen(
        cards = cards,
        annotations = annotations,
        drawings = drawings,
        onOpenTool = { studio.openTool = it.id },
        onCardOpened = { studio.openTool = "cards" },
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * The hub: the tool cards, then "Your cards", "Your annotated pictures" and "Your drawings" (the personal gallery, T8.7).
 * An annotated picture is flattened, so it is shared or deleted, not edited. A drawing is the keyboard's Draw gallery (T8.15):
 * it is shared as a PNG or deleted here, and edited in the keyboard's Draw panel.
 */
@Composable
internal fun CreativeStudioScreen(
    cards: CardMakerViewModel,
    onOpenTool: (CreativeTool) -> Unit,
    /** A saved card was opened into the card maker's editor: show the card maker. */
    onCardOpened: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    annotations: AnnotatorViewModel? = null,
    drawings: DrawingsViewModel? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf<SavedCard?>(null) }
    var confirmDeletePicture by remember { mutableStateOf<SavedAnnotation?>(null) }
    var confirmDeleteDrawing by remember { mutableStateOf<SavedDrawingItem?>(null) }
    LaunchedEffect(Unit) {
        cards.refreshGallery()
        annotations?.refreshGallery()
        drawings?.refreshGallery()
    }
    val openFailed = stringResource(R.string.studio_card_open_failed)
    val shareFailed = stringResource(R.string.card_share_failed)
    val shareTitle = stringResource(R.string.card_share_title)
    Box(modifier) {
        SettingsScaffold(stringResource(R.string.studio_title), onBack) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.studio_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (t in CreativeTools.all) ToolCard(t) { onOpenTool(t) }
                Text(
                    stringResource(R.string.studio_mine),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
                if (cards.gallery.isEmpty()) {
                    Text(stringResource(R.string.studio_mine_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (card in cards.gallery) {
                    SavedCardRow(
                        card,
                        onEdit = {
                            scope.launch {
                                if (cards.open(card.id)) onCardOpened() else snackbar.showSnackbar(openFailed)
                            }
                        },
                        onShare = {
                            scope.launch {
                                val r = cards.stageSaved(card)
                                if (r !is CardOutcome.Shareable || !CardShare.share(context, r.file, r.format, shareTitle)) snackbar.showSnackbar(shareFailed)
                            }
                        },
                        onDelete = { confirmDelete = card },
                    )
                }
                if (annotations != null) {
                    val pictureShareTitle = stringResource(R.string.annot_share_title)
                    val pictureShareFailed = stringResource(R.string.annot_share_failed)
                    Text(
                        stringResource(R.string.studio_annot_mine),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                    if (annotations.gallery.isEmpty()) {
                        Text(stringResource(R.string.studio_annot_mine_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    for (item in annotations.gallery) {
                        SavedItemRow(
                            item.modifiedMillis, item.image, R.string.studio_annot_item_desc,
                            onEdit = null,
                            onShare = {
                                scope.launch {
                                    val r = annotations.stageSaved(item)
                                    if (r !is CardOutcome.Shareable || !CardShare.share(context, r.file, r.format, pictureShareTitle)) snackbar.showSnackbar(pictureShareFailed)
                                }
                            },
                            onDelete = { confirmDeletePicture = item },
                        )
                    }
                }
                if (drawings != null) {
                    val drawingShareTitle = stringResource(R.string.drawing_share_title)
                    val drawingShareFailed = stringResource(R.string.drawing_share_failed)
                    Text(
                        stringResource(R.string.studio_drawings_mine),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                    if (drawings.gallery.isEmpty()) {
                        Text(stringResource(R.string.studio_drawings_mine_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    for (item in drawings.gallery) {
                        SavedItemRow(
                            item.modifiedMillis, item.image, R.string.studio_drawing_item_desc,
                            onEdit = null,
                            onShare = {
                                scope.launch {
                                    val r = drawings.stageSaved(item)
                                    if (r !is CardOutcome.Shareable || !CardShare.share(context, r.file, r.format, drawingShareTitle)) snackbar.showSnackbar(drawingShareFailed)
                                }
                            },
                            onDelete = { confirmDeleteDrawing = item },
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    confirmDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.studio_delete_title)) },
            text = { Text(stringResource(R.string.studio_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; scope.launch { cards.delete(card.id) } }) { Text(stringResource(R.string.pv_delete_item)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.pd_cancel)) } },
        )
    }
    confirmDeletePicture?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDeletePicture = null },
            title = { Text(stringResource(R.string.studio_annot_delete_title)) },
            text = { Text(stringResource(R.string.studio_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDeletePicture = null; scope.launch { annotations?.delete(item.id) } }) { Text(stringResource(R.string.pv_delete_item)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeletePicture = null }) { Text(stringResource(R.string.pd_cancel)) } },
        )
    }
    confirmDeleteDrawing?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDeleteDrawing = null },
            title = { Text(stringResource(R.string.studio_drawing_delete_title)) },
            text = { Text(stringResource(R.string.studio_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDeleteDrawing = null; scope.launch { drawings?.delete(item.id) } }) { Text(stringResource(R.string.pv_delete_item)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteDrawing = null }) { Text(stringResource(R.string.pd_cancel)) } },
        )
    }
}

@Composable
private fun ToolCard(tool: CreativeTool, onClick: () -> Unit) {
    val title = stringResource(tool.title)
    val summary = stringResource(tool.summary)
    Card(
        Modifier.fillMaxWidth().clip(CardDefaults.shape).clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = "$title. $summary" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(tool.icon), contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun SavedCardRow(card: SavedCard, onEdit: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) =
    SavedItemRow(card.modifiedMillis, card.image, R.string.studio_card_item_desc, onEdit, onShare, onDelete)

/** One saved item of the gallery: its picture, its date, and Edit (when it is editable), Share and Delete. */
@Composable
private fun SavedItemRow(modifiedMillis: Long, image: File, @StringRes describe: Int, onEdit: (() -> Unit)?, onShare: () -> Unit, onDelete: () -> Unit) {
    val date = remember(modifiedMillis) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(modifiedMillis)) }
    val thumb: ImageBitmap? by produceState<ImageBitmap?>(null, image) {
        value = withContext(Dispatchers.IO) { CardImages.thumbnail(image, THUMB_PX)?.asImageBitmap() }
    }
    val description = stringResource(describe, date)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .semantics { contentDescription = description },
                ) {
                    thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp)) }
                }
                Text(date, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onEdit != null) TextButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.studio_card_edit), maxLines = 1) }
                TextButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.card_share), maxLines = 1) }
                TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pv_delete_item), maxLines = 1) }
            }
        }
    }
}

private const val THUMB_PX = 256
