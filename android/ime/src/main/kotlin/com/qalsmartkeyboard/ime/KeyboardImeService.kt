package com.qalsmartkeyboard.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.Toast
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.qalsmartkeyboard.ime.emoji.EmojiController
import com.qalsmartkeyboard.ime.emoji.EmojiExecutor
import com.qalsmartkeyboard.ime.emoji.EmojiLoader
import com.qalsmartkeyboard.ime.emoji.EmojiMode
import com.qalsmartkeyboard.ime.emoji.EmojiPanelView
import com.qalsmartkeyboard.ime.emoji.EmojiSearchBar
import com.qalsmartkeyboard.ime.emoji.FileGlyphCache
import com.qalsmartkeyboard.ime.emoji.SettingsEmojiStore
import com.qalsmartkeyboard.ime.ai.AiActionsHolder
import com.qalsmartkeyboard.ime.ai.AiLink
import com.qalsmartkeyboard.ime.ai.AiToolbarController
import com.qalsmartkeyboard.ime.ai.AiToolbarView
import com.qalsmartkeyboard.ime.ai.EditorAiAccess
import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess
import com.qalsmartkeyboard.ime.theme.ResolvedTheme
import com.qalsmartkeyboard.ime.theme.ThemeAccess
import com.qalsmartkeyboard.ime.theme.ThemeAccessHolder
import com.qalsmartkeyboard.ime.theme.ThemeResolver
import com.qalsmartkeyboard.ime.userdict.UserDictionary
import com.qalsmartkeyboard.ime.packs.PackRuntime
import com.qalsmartkeyboard.ime.packs.PackStorage
import java.util.concurrent.atomic.AtomicBoolean
import com.qalsmartkeyboard.ime.packs.installedProfiles
import kotlinx.coroutines.runBlocking
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.data.SettingsSnapshot
import uniffi.qal_keyboard_core.KeyboardEngine

/**
 * IME lifecycle and EditorInfo inspection (SD 6.2 `KeyboardImeService`). Keys go through the Rust
 * core ([CoreComposer]) and reach the editor through [InputConnectionAdapter]. Typed text is only
 * ever passed to the InputConnection and the candidate strip; it is never logged, stored or sent
 * anywhere (rules N1, N2). Words committed in fields that allow learning ([InputProfile.learnsWords])
 * go to the local encrypted [UserDictionary] (A1.8); it never touches the network.
 */
class KeyboardImeService : InputMethodService() {
    private var surface: KeyboardSurface? = null
    private var strip: CandidateStripView? = null
    private var bar: KeyboardBar? = null
    private var emojiPanel: EmojiPanelView? = null
    private var emojiSearch: EmojiSearchBar? = null
    private var seenEmojiMode = EmojiMode.CLOSED
    private var engine: KeyboardEngine? = null
    private var runtime: PackRuntime? = null
    private var loaded: PackRuntime.Loaded? = null
    @Volatile private var seenReloadToken: String? = null
    private val reloading = AtomicBoolean(false)
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
        override fun getSelectedText(): CharSequence? = ic?.getSelectedText(0)
        override fun setSelection(start: Int, end: Int) = ic?.setSelection(start, end) ?: false
        override fun getCursorCapsMode(reqModes: Int): Int {
            val type = currentInputEditorInfo?.inputType ?: return 0
            return if (type == InputType.TYPE_NULL) 0 else ic?.getCursorCapsMode(reqModes) ?: 0
        }
    }

    // Themes: the stored choice (settings snapshot) resolved against the premium entitlement (a port the app injects).
    @Volatile private var themeAccess: ThemeAccess? = null
    private var themeAccessHandle: AutoCloseable? = null
    private var overlayView: PopupOverlay? = null

    /** The theme being drawn right now (premium choices fall back to a free theme without the entitlement). */
    var resolvedTheme: ResolvedTheme = ThemeResolver.resolve(null, false, PremiumThemeAccess.NEEDS_CHECK)
        private set

    /** True when the drawn theme is a light one (navigation bar icon appearance follows it). */
    val isLightTheme: Boolean get() = resolvedTheme.theme.isLight

    /**
     * Resolves the chosen theme and hands it to the views; the next draw uses it (no restart). Main thread. Cheap:
     * it reads the cached settings snapshot and the in-memory entitlement answer, never the disk or the network.
     */
    private fun applyTheme() {
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val access = themeAccess?.premium ?: PremiumThemeAccess.NEEDS_CHECK
        val resolved = ThemeResolver.resolve(snapshot.current.theme, dark, access)
        resolvedTheme = resolved
        val t = resolved.theme
        surface?.theme = t
        strip?.theme = t
        overlayView?.theme = t
        emojiPanel?.theme = t
        emojiSearch?.theme = t
        aiView?.theme = t
    }

    private fun attachThemeAccessInBackground() {
        Thread({
            val access = try {
                ThemeAccessHolder.create()
            } catch (e: Exception) {
                Log.w(TAG, "theme access unavailable: ${e.javaClass.simpleName}")
                null
            }
            if (access != null) {
                main.post {
                    themeAccess = access
                    themeAccessHandle?.close()
                    themeAccessHandle = access.addListener { main.post { applyTheme() } }
                    applyTheme()
                }
            }
        }, "qal-theme-access").start()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme() // "System" follows the device's dark mode
    }

    private val adapter = InputConnectionAdapter { if (currentInputConnection != null) editorOps else null }
    // Settings (A1.5): a cached snapshot (defaults until the first load), updated off the main thread.
    private val snapshot by lazy { SettingsSnapshot(SettingsRepository.get(applicationContext)) }
    private val settingsPrefs by lazy {
        SettingsPreferences(
            { snapshot.current },
            saveLastMode = { lang, mode -> snapshot.launchUpdate { it.copy(lastModes = it.lastModes + (lang to mode)) } },
            saveWritingMode = { lang, mode -> snapshot.launchUpdate { it.copy(writingModes = it.writingModes + (lang to mode)) } },
        )
    }
    private val userDictionary by lazy { UserDictionary.get(applicationContext) }
    private val controller by lazy {
        KeyboardController(
            adapter, settingsPrefs, settingsPrefs, userDictionary,
            SettingsEmojiStore({ snapshot.current }, { change -> snapshot.launchUpdate(change) }), emojiLoader, emojiExecutor,
        ) { refreshViews() }
    }

    // Emoji panel (ADR 0028): nothing here touches the disk or the font until the panel is first opened. The loader parses
    // the bundled assets and asks `Paint.hasGlyph` on a worker thread; the answer is cached per system build.
    private val emojiLoader by lazy {
        val appContext = applicationContext
        val probe by lazy { android.graphics.Paint() }
        EmojiLoader(
            source = { name -> try { appContext.assets.open("emoji/$name") } catch (e: java.io.IOException) { null } },
            supported = { probe.hasGlyph(it) },
            cache = FileGlyphCache(java.io.File(appContext.cacheDir, "emoji-glyphs.txt"), "${Build.FINGERPRINT}|1"),
        )
    }
    private val emojiExecutor = object : EmojiExecutor {
        override fun <T> run(work: () -> T, done: (T) -> Unit) {
            Thread({
                val result = work()
                main.post { done(result) }
            }, "qal-emoji").start()
        }
    }

    // AI toolbar (A2.5): built off the main thread (the app side opens Keystore-backed files), then
    // attached. Until then, or when the app registered none, the keyboard has no toolbar.
    private var aiView: AiToolbarView? = null
    private var aiToolbar: AiToolbarController? = null

    private fun attachAiInBackground() {
        Thread({
            val actions = try {
                AiActionsHolder.create()
            } catch (e: Exception) {
                Log.w(TAG, "ai unavailable: ${e.javaClass.simpleName}")
                null
            }
            if (actions != null) {
                main.post {
                    if (aiToolbar != null) return@post
                    val toolbar = AiToolbarController(
                        actions = actions,
                        editor = EditorAiAccess(adapter) { controller.commitComposition() },
                        post = { block -> main.post(block) },
                        copyToClipboard = ::copyResult,
                        onChanged = { aiView?.render() },
                    )
                    aiToolbar = toolbar
                    aiView?.controller = toolbar
                    toolbar.onFieldStarted(controller.profile, currentInputEditorInfo?.packageName)
                    aiView?.render() // the bar shows the AI entry now (its height does not change)
                }
            }
        }, "qal-ai-attach").start()
    }

    /** Copies the shown result on the user's Copy tap; marked sensitive so the clipboard preview hides it (API 33+). */
    private fun copyResult(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText("", text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        cm.setPrimaryClip(clip)
    }

    /** Opens the companion app (sign-in, AI settings, plans, update, keyboard settings). It decides what to show from the extra. */
    private fun openCompanion(link: AiLink) {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launch.putExtra(EXTRA_OPEN_LINK, link.name)
        try {
            startActivity(launch)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot open the app: ${e.javaClass.simpleName}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        attachAiInBackground()
        attachThemeAccessInBackground()
        adapter.onCompositionAbandoned = { controller.onCompositionAbandoned() }
        snapshot.onChange = { main.post { controller.onPreferencesChanged(); applyTheme() } }
        snapshot.start()
        loadCoreInBackground()
    }

    /**
     * Loads the native core and the packs off the main thread (SD 17.1: no disk I/O on the key
     * path). Until it is done the keyboard types ABC through [PassthroughComposer]. Also used to
     * reload after the app-side updater (A2.3) installed or rolled back a pack: the new engine
     * replaces the old one on the main thread and the old one is closed.
     */
    private fun loadCoreInBackground() {
        if (!reloading.compareAndSet(false, true)) return
        val appContext = applicationContext
        Thread({
            var created: KeyboardEngine? = null
            var createdLoad: PackRuntime.Loaded? = null
            var hints: Map<String, PhoneticHints> = emptyMap()
            try {
                val rt = runtime ?: PackStorage.create(appContext) { Log.w(TAG, it) }?.also { runtime = it }
                seenReloadToken = rt?.journal?.reloadToken()
                val l = try {
                    rt?.load()
                } catch (e: Exception) {
                    // Bad trust key, missing native library, ...: keep typing ABC. No content is logged.
                    Log.e(TAG, "core unavailable: ${e.javaClass.simpleName}")
                    null
                } catch (e: LinkageError) {
                    Log.e(TAG, "core unavailable: ${e.javaClass.simpleName}")
                    null
                }
                if (l != null) {
                    createdLoad = l
                    created = l.result.engine
                    // A1.5/A1.6 seam: retired profile codes in the settings fall back to the loaded
                    // profile (F14); the "profile changed" flag is `SettingsRepository.profileChanges`.
                    // We are on the loader thread, so blocking here is fine.
                    try {
                        runBlocking { SettingsRepository.get(appContext).applyInstalledProfiles(l.result.installedProfiles()) }
                    } catch (e: Exception) {
                        Log.w(TAG, "profile fallback skipped: ${e.javaClass.simpleName}")
                    }
                }
                // The personal dictionary is read after the packs (off the main thread) and handed to the engine.
                try {
                    userDictionary.load()
                    created?.let { userDictionary.attach(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "user dictionary skipped: ${e.javaClass.simpleName}")
                }
                // Phonetic key hints: each letter typed alone into a fresh phonetic session of the new
                // engine, before it is handed to the main thread (no core call ever runs on the draw path).
                try {
                    created?.let { hints = CoreComposer.deriveHints(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "key hints skipped: ${e.javaClass.simpleName}")
                }
            } finally {
                reloading.set(false)
            }
            val fresh = created
            val freshHints = hints
            if (fresh != null) {
                main.post {
                    val old = engine
                    controller.composer.reset()
                    engine = fresh
                    loaded = createdLoad
                    controller.composer = CoreComposer(fresh, freshHints)
                    if (old != null) {
                        userDictionary.detach(old)
                        old.close()
                    }
                }
            }
        }, "qal-pack-loader").start()
    }

    /**
     * Called when the keyboard is shown. (1) A new update token from the app-side updater triggers a
     * reload. (2) The first time the keyboard is shown on an engine that holds a swapped-in pack,
     * the update counts as healthy (A2.3 health signal) and its `.prev` backup is dropped.
     */
    private fun checkPackUpdates() {
        val rt = runtime ?: return
        val current = loaded
        if (current != null && current.awaitingConfirmation.isNotEmpty() && engine != null) {
            loaded = PackRuntime.Loaded(current.result, emptyList())
            Thread({ rt.confirm(current) }, "qal-pack-confirm").start()
        }
        Thread({
            if (rt.journal.reloadToken() != seenReloadToken && !reloading.get()) loadCoreInBackground()
        }, "qal-pack-check").start()
    }

    override fun onDestroy() {
        themeAccessHandle?.close()
        themeAccessHandle = null
        snapshot.stop()
        aiToolbar?.release()
        engine?.let { userDictionary.detach(it) }
        userDictionary.flush()
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
            it.onLongSelect = { i ->
                val added = controller.addCandidateToDictionary(i)
                Toast.makeText(this, if (added) R.string.user_dictionary_added else R.string.user_dictionary_not_added, Toast.LENGTH_SHORT).show()
            }
            it.onSettings = { openCompanion(AiLink.SETTINGS) }
            it.onModeSelected = { kind -> controller.selectWritingMode(kind) }
            strip = it
        }
        val ai = AiToolbarView(this).also {
            it.controller = aiToolbar
            it.onLink = ::openCompanion
            aiView = it
        }
        // One bar above the keys: pill, AI entry (its actions open in the bar's middle), candidates, gear.
        val b = KeyboardBar(this, s, ai).also { bar = it }
        val overlay = PopupOverlay(this).also { overlayView = it }
        val k = KeyboardSurface(this).also {
            it.controller = controller
            it.overlay = overlay
            surface = it
        }
        val panel = EmojiPanelView(this).also {
            it.controller = controller
            it.overlay = overlay
            it.visibility = View.GONE
            it.heightProvider = { k.preferredHeightPx() }
            emojiPanel = it
        }
        val search = EmojiSearchBar(this).also {
            it.controller = controller
            it.visibility = View.GONE
            emojiSearch = it
        }
        val space = View(this).also { headroom = it }
        val column = KeyboardColumn(this, space, (POPUP_HEADROOM_DP * d).toInt()) { k.backgroundFill }.apply {
            addView(space, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (POPUP_HEADROOM_DP * d).toInt()))
            addView(ai, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(k, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val root = OverlayStack(this).apply {
            addView(column)
            addView(overlay)
            inputRoot = this
            applyTheme()
            ai.render()
            refreshViews()
        }
        // Edge-to-edge IME window (targetSdk 35+ on Android 15+): keep the keys clear of the
        // navigation bar (gesture or 3-button, bottom or side) and of landscape cutouts, while the
        // column keeps painting the keyboard background under them. The window's root insets are
        // read (the IME's own decor may consume or never forward them to the input view), and they
        // are zero where the system already shrinks the window above the bar (older releases), so
        // nothing is padded twice. Re-read on every insets dispatch and layout change (rotation,
        // navigation mode, density).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            applyInsetPadding(v, column, ViewCompat.getRootWindowInsets(v) ?: insets)
            insets
        }
        // Also after every layout of the window (the IME's own navigation-bar strip is added and
        // sized by the system after the input view), only changing the padding when it differs.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            ViewCompat.getRootWindowInsets(root)?.let { applyInsetPadding(root, column, it) }
        }
        styleNavigationBar(k)
        return root
    }

    private fun applyInsetPadding(root: View, column: View, insets: WindowInsetsCompat) {
        val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val p = KeyboardInsets.padding(
            intArrayOf(nav.left, nav.top, nav.right, nav.bottom),
            intArrayOf(cut.left, cut.top, cut.right, cut.bottom),
            imeNavBarHeight(root),
            root.resources.displayMetrics.density,
        )
        if (column.paddingLeft != p.left || column.paddingRight != p.right || column.paddingBottom != p.bottom) {
            // Posted: changing padding inside a layout pass would be ignored until the next one.
            root.post { column.setPadding(p.left, 0, p.right, p.bottom) }
        }
    }

    /**
     * Height of the navigation-bar strip the system adds to the IME window (Android 13+, the
     * `NavigationBarFrame` with the IME back / switcher buttons), 0 when there is none or it is
     * hidden. Found among the window's own views, outside the input view.
     */
    private fun imeNavBarHeight(root: View): Int {
        val decor = window?.window?.decorView as? ViewGroup ?: return 0
        fun find(g: ViewGroup, depth: Int): Int {
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c === root || c.visibility != View.VISIBLE) continue
                if (c.javaClass.simpleName == "NavigationBarFrame") return c.height
                if (c is ViewGroup && depth < 3) find(c, depth + 1).let { if (it > 0) return it }
            }
            return 0
        }
        return find(decor, 0)
    }

    /**
     * Navigation bar under the keyboard: its icons follow the keyboard palette (`dark`), and on
     * releases where the bar is not transparent it takes the keyboard background, so the keyboard
     * looks continuous. API 24-25 have no light navigation icons, so a light keyboard keeps the
     * system bar colour there.
     */
    private fun styleNavigationBar(k: KeyboardSurface) {
        val w = window?.window ?: return
        val dark = k.isDark
        try {
            if (Build.VERSION.SDK_INT < 35 && (dark || Build.VERSION.SDK_INT >= 26)) {
                @Suppress("DEPRECATION")
                w.navigationBarColor = k.backgroundFill
            }
            if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars = !dark
        } catch (e: RuntimeException) {
            Log.w(TAG, "navigation bar style skipped: ${e.javaClass.simpleName}")
        }
    }

    /** The app only sees the part of the window below the headroom; the headroom does not take touches. */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val root = inputRoot ?: return
        val room = headroom ?: return
        val loc = IntArray(2)
        room.getLocationInWindow(loc)
        val top = KeyboardInsets.contentTop(root.height, loc[1], room.height) ?: return
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
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
        val profile = InputProfile.from(
            info.inputType,
            info.imeOptions,
            hasCustomAction = info.actionLabel != null,
            customActionId = info.actionId,
        )
        controller.startField(profile)
        bar?.closeAi()
        aiToolbar?.onFieldStarted(profile, info.packageName)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        surface?.let { styleNavigationBar(it) }
        checkPackUpdates()
        applyTheme() // the entitlement token may have expired since the last time
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

    /** Back steps out of the emoji search, then out of the panel, before it dismisses the keyboard. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0 && controller.emoji.back()) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Keyboard hidden: the editor keeps the composition as plain text.
        adapter.finishComposition()
        controller.emoji.close()
        controller.onCompositionAbandoned()
        bar?.closeAi()
        aiToolbar?.onKeyboardHidden()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        adapter.finishComposition()
        controller.onCompositionAbandoned()
        userDictionary.saveSoon()
        super.onFinishInput()
    }

    private fun refreshViews() {
        // The one bar (mode pill, AI entry, candidates, settings gear) is always shown so the keyboard
        // keeps one height; candidates and the DRAFT tag only where the strip applies (phonetic, not passwords).
        strip?.let {
            it.visibility = View.VISIBLE
            val candidates = controller.showCandidateStrip
            it.show(if (candidates) controller.candidates else emptyList(), candidates && controller.candidatesAreDraft)
            it.setModes(controller.writingModeOptions, controller.writingMode)
            it.closeModeChooser()
        }
        // Emoji panel (ADR 0028): the panel takes the key area, or (search) the keys are back with the search strip in the bar's place.
        val mode = controller.emoji.mode
        val inPanel = mode == EmojiMode.PANEL
        val inSearch = mode == EmojiMode.SEARCH
        surface?.visibility = if (inPanel) View.GONE else View.VISIBLE
        emojiPanel?.visibility = if (inPanel) View.VISIBLE else View.GONE
        bar?.visibility = if (inSearch) View.GONE else View.VISIBLE
        emojiSearch?.visibility = if (inSearch) View.VISIBLE else View.GONE
        if (mode != seenEmojiMode) {
            if (inPanel) emojiPanel?.onOpened()
            seenEmojiMode = mode
        }
        if (inPanel) emojiPanel?.refresh()
        if (inSearch) emojiSearch?.render()
        surface?.onLayoutChanged()
    }

    companion object {
        private const val TAG = "QalIme"

        /** Intent extra on the launch intent of the companion app: the [AiLink] name to show. */
        const val EXTRA_OPEN_LINK = "com.qalsmartkeyboard.OPEN_LINK"
        private const val POPUP_HEADROOM_DP = 190f
    }
}
