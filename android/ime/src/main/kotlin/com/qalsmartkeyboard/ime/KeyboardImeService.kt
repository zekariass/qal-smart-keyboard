package com.qalsmartkeyboard.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import com.qalsmartkeyboard.ime.packs.PackStorage
import uniffi.qal_keyboard_core.KeyboardEngine

/**
 * IME lifecycle and EditorInfo inspection (SD 6.2 `KeyboardImeService`). Keys go through the Rust
 * core ([CoreComposer]) and reach the editor through [InputConnectionAdapter]. Typed text is only
 * ever passed to the InputConnection and the candidate strip; it is never logged, stored or sent
 * anywhere (rules N1, N2). Nothing is learned from any field yet; [InputProfile.learningAllowed]
 * is honoured by the user dictionary in A1.8.
 */
class KeyboardImeService : InputMethodService() {
    private var surface: KeyboardSurface? = null
    private var strip: CandidateStripView? = null
    private var engine: KeyboardEngine? = null
    private val main = Handler(Looper.getMainLooper())

    private val editorOps = object : EditorOps {
        private val ic: InputConnection? get() = currentInputConnection
        override fun beginBatchEdit() { ic?.beginBatchEdit() }
        override fun endBatchEdit() { ic?.endBatchEdit() }
        override fun getTextBeforeCursor(n: Int): CharSequence? = ic?.getTextBeforeCursor(n, 0)
        override fun deleteSurroundingText(beforeLength: Int) = ic?.deleteSurroundingText(beforeLength, 0) ?: false
        override fun commitText(text: String) = ic?.commitText(text, 1) ?: false
        override fun setComposingText(text: String) = ic?.setComposingText(text, 1) ?: false
        override fun finishComposingText() = ic?.finishComposingText() ?: false
        override fun sendKey(keyCode: Int) {
            val c = ic ?: return
            c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        override fun performEditorAction(actionId: Int) { ic?.performEditorAction(actionId) }
        override fun getCursorCapsMode(reqModes: Int): Int {
            val type = currentInputEditorInfo?.inputType ?: return 0
            return if (type == InputType.TYPE_NULL) 0 else ic?.getCursorCapsMode(reqModes) ?: 0
        }
    }

    private val adapter = InputConnectionAdapter { if (currentInputConnection != null) editorOps else null }
    private val controller = KeyboardController(adapter) { refreshViews() }

    override fun onCreate() {
        super.onCreate()
        adapter.onCompositionAbandoned = { controller.onCompositionAbandoned() }
        loadCoreInBackground()
    }

    /**
     * Loads the native core and the bundled packs off the main thread (SD 17.1: no disk I/O on the
     * key path). Until it is done the keyboard types ABC through [PassthroughComposer].
     */
    private fun loadCoreInBackground() {
        val appContext = applicationContext
        Thread({
            val created = try {
                PackStorage.create(appContext) { Log.w(TAG, it) }?.load()?.engine
            } catch (e: Exception) {
                // Bad trust key, missing native library, ...: keep typing ABC. No content is logged.
                Log.e(TAG, "core unavailable: ${e.javaClass.simpleName}")
                null
            } catch (e: LinkageError) {
                Log.e(TAG, "core unavailable: ${e.javaClass.simpleName}")
                null
            }
            if (created != null) {
                main.post {
                    engine = created
                    controller.composer = CoreComposer(created)
                }
            }
        }, "qal-pack-loader").start()
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        super.onDestroy()
    }

    // The keyboard is small; never take over the screen with the extract UI.
    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Transparent, non-touchable space above the strip where long-press popups are drawn. */
    private var headroom: View? = null
    private var inputRoot: View? = null

    override fun onCreateInputView(): View {
        val d = resources.displayMetrics.density
        val s = CandidateStripView(this).also {
            it.onSelect = { i -> controller.selectCandidate(i) }
            strip = it
        }
        val overlay = PopupOverlay(this)
        val k = KeyboardSurface(this).also {
            it.controller = controller
            it.overlay = overlay
            surface = it
        }
        val space = View(this).also { headroom = it }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(space, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (POPUP_HEADROOM_DP * d).toInt()))
            addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(k, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        return OverlayStack(this).apply {
            addView(column)
            addView(overlay)
            inputRoot = this
            refreshViews()
        }
    }

    /** The app only sees the part of the window below the headroom; the headroom does not take touches. */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val root = inputRoot
        val room = headroom
        if (root != null && room != null && root.height > 0) {
            val loc = IntArray(2)
            room.getLocationInWindow(loc)
            val top = loc[1] + room.height
            outInsets.contentTopInsets = top
            outInsets.visibleTopInsets = top
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val info = attribute ?: return
        adapter.startSession(
            isTypeNull = info.inputType == InputType.TYPE_NULL,
            initialSelStart = info.initialSelStart,
            initialSelEnd = info.initialSelEnd,
        )
        controller.composer.reset()
        controller.startField(
            InputProfile.from(
                info.inputType,
                info.imeOptions,
                hasCustomAction = info.actionLabel != null,
                customActionId = info.actionId,
            ),
        )
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        controller.refreshAutoCapital()
        refreshViews()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        adapter.onSelectionUpdate(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Keyboard hidden: the editor keeps the composition as plain text.
        adapter.finishComposition()
        controller.onCompositionAbandoned()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        adapter.finishComposition()
        controller.onCompositionAbandoned()
        super.onFinishInput()
    }

    private fun refreshViews() {
        strip?.let {
            it.visibility = if (controller.showCandidateStrip) View.VISIBLE else View.GONE
            it.show(controller.candidates, controller.candidatesAreDraft)
        }
        surface?.onLayoutChanged()
    }

    private companion object {
        const val TAG = "QalIme"
        const val POPUP_HEADROOM_DP = 190f
    }
}
