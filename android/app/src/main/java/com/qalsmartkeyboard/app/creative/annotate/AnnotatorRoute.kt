package com.qalsmartkeyboard.app.creative.annotate

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.qalsmartkeyboard.app.R
import com.qalsmartkeyboard.app.creative.CreativeToolHost
import com.qalsmartkeyboard.app.creative.activityViewModel
import com.qalsmartkeyboard.app.creative.cards.CardOutcome
import com.qalsmartkeyboard.app.creative.cards.CardShare
import com.qalsmartkeyboard.app.creative.rememberAnnotatorAccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The image annotator as a Creative studio tool: the host of [AnnotatorScreen]. It owns what needs Android: the system
 * photo picker (no storage permission; the app receives only the one picture the user picks and reads it locally),
 * the share sheet, the entitlement (`IMAGE_ANNOTATOR`), the off-thread redacted preview and the draft autosave.
 */
@Composable
fun AnnotatorRoute(host: CreativeToolHost, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm = activityViewModel(AnnotatorViewModel.create)
    val access by rememberAnnotatorAccess()
    val editor = vm.editor
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val viewport = remember { AnnotViewport() }
    BackHandler(onBack = host.onBack)

    val doc = editor.doc
    // The redacted preview follows the redactions (the same Redactor as the export, on a smaller copy).
    val redactions = doc?.redactions
    LaunchedEffect(redactions, vm.base) {
        delay(PREVIEW_DEBOUNCE_MS) // a redaction being moved is recomputed when the finger rests, not on every frame
        vm.refreshPreview()
    }
    // The draft (objects only, no pixels) follows every change, a moment after it.
    LaunchedEffect(doc, editor.format) {
        delay(DRAFT_DEBOUNCE_MS)
        vm.saveDraft()
    }
    val preview = vm.preview
    val previewImage = remember(preview) { preview?.asImageBitmap() }

    val pickFailed = stringResource(R.string.annot_pick_failed)
    val draftFailed = stringResource(R.string.annot_draft_failed)
    val saved = stringResource(R.string.annot_saved)
    val full = stringResource(R.string.annot_gallery_full, AnnotationStore.MAX_ITEMS)
    val saveFailed = stringResource(R.string.annot_save_failed)
    val shareFailed = stringResource(R.string.annot_share_failed)
    val shareTitle = stringResource(R.string.annot_share_title)
    val tooMany = stringResource(R.string.annot_full, AnnotDoc.MAX_ITEMS)
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
        if (uri != null) scope.launch {
            if (vm.import(uri)) viewport.reset(editor.doc!!.width, editor.doc!!.height) else say(pickFailed)
        }
    }

    AnnotatorScreen(
        editor = editor,
        access = access,
        preview = previewImage,
        draftWaiting = vm.draftWaiting,
        onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onContinueDraft = {
            scope.launch {
                when (vm.restoreDraft()) {
                    DraftRestore.RESTORED -> editor.doc?.let { viewport.reset(it.width, it.height) }
                    DraftRestore.NONE, DraftRestore.UNREADABLE -> say(draftFailed)
                }
            }
        },
        onDiscardDraft = { scope.launch { vm.discardDraft() } },
        onExport = { kind ->
            scope.launch {
                when (kind) {
                    ExportKind.SAVE -> handle(vm.saveCopy(access))
                    ExportKind.SHARE -> {
                        val r = vm.stageForShare(access)
                        if (r == CardOutcome.Failed) say(shareFailed) else handle(r)
                    }
                }
            }
        },
        onSolidify = { vm.solidifyLocked(access) },
        onUnlock = host.onUnlock,
        onBack = host.onBack,
        onFull = { say(tooMany) },
        modifier = modifier,
        painter = vm.painter,
        viewport = viewport,
        snackbar = snackbar,
    )
}

private const val DRAFT_DEBOUNCE_MS = 600L
private const val PREVIEW_DEBOUNCE_MS = 60L
