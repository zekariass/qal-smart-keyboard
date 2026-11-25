package com.qalsmartkeyboard.app.creative.cards

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.creative.CreativeToolHost
import com.qalsmartkeyboard.app.creative.activityViewModel
import com.qalsmartkeyboard.app.creative.rememberCardMakerAccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The card maker as a Creative studio tool: the host of [CardMakerScreen]. It owns what needs Android: the system photo
 * picker (no permission, the app only receives the one picture the user picks, and reads it locally), the share sheet, the
 * entitlement (`CARD_MAKER`) and the off-thread rendering of the preview.
 */
@Composable
fun CardMakerRoute(host: CreativeToolHost, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm = activityViewModel(CardMakerViewModel.create)
    val resolve = remember(context) { { id: Int -> context.getString(id) } }
    LaunchedEffect(Unit) { vm.ensureStarted(resolve) }
    val access by rememberCardMakerAccess()
    val editor = vm.editor
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmNew by remember { mutableStateOf(false) }
    BackHandler(onBack = host.onBack)

    // The preview is the renderer's own bitmap (at 60% of the export size, laid out exactly as the export).
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    val doc = editor.doc
    val photo = editor.photo
    LaunchedEffect(doc, photo) {
        delay(PREVIEW_DEBOUNCE_MS)
        preview = vm.preview(doc, photo, PREVIEW_SCALE).asImageBitmap()
    }
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(Unit) {
        for (t in CardTemplates.all) if (t.id !in thumbs) thumbs[t.id] = vm.templateThumbnail(t, resolve, THUMB_SCALE).asImageBitmap()
    }

    val saved = stringResource(R.string.card_saved)
    val full = stringResource(R.string.card_gallery_full, CardStore.MAX_ITEMS)
    val saveFailed = stringResource(R.string.card_save_failed)
    val shareFailed = stringResource(R.string.card_share_failed)
    val photoFailed = stringResource(R.string.card_bg_photo_failed)
    val shareTitle = stringResource(R.string.card_share_title)
    fun say(message: String) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
    }
    fun handle(outcome: CardOutcome) {
        when (outcome) {
            CardOutcome.Locked -> host.onUnlock()
            CardOutcome.Saved -> say(saved)
            CardOutcome.GalleryFull -> say(full)
            CardOutcome.Failed -> say(saveFailed)
            is CardOutcome.Shareable -> if (!CardShare.share(context, outcome.file, outcome.format, shareTitle)) say(shareFailed)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { if (!vm.usePhoto(uri)) say(photoFailed) }
    }

    CardMakerScreen(
        editor = editor,
        access = access,
        preview = preview,
        templateThumbs = thumbs,
        onPickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onSave = { scope.launch { handle(vm.save(access)) } },
        onShare = { scope.launch { handle(vm.stageForShare(access)) } },
        onUnlock = host.onUnlock,
        onBack = host.onBack,
        onNew = { if (editor.dirty) confirmNew = true else vm.newCard(resolve) },
        resolve = resolve,
        modifier = modifier,
        snackbar = snackbar,
    )
    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text(stringResource(R.string.card_new_title)) },
            text = { Text(stringResource(R.string.card_new_body)) },
            confirmButton = { TextButton(onClick = { confirmNew = false; vm.newCard(resolve) }) { Text(stringResource(R.string.card_new)) } },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text(stringResource(R.string.pd_cancel)) } },
        )
    }
}

private const val PREVIEW_SCALE = 0.6f
private const val THUMB_SCALE = 0.2f
private const val PREVIEW_DEBOUNCE_MS = 40L
