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
import com.qalsmartkeyboard.ime.wordart.WordArtBar
import com.qalsmartkeyboard.ime.wordart.WordArtPanelView
import com.qalsmartkeyboard.ime.ai.AiActions
import com.qalsmartkeyboard.ime.ai.AiActionsHolder
import com.qalsmartkeyboard.ime.ai.AiApplied
import com.qalsmartkeyboard.ime.ai.AiEmailHints
import com.qalsmartkeyboard.ime.ai.AiFieldBar
import com.qalsmartkeyboard.ime.ai.AiLanguagePrefs
import com.qalsmartkeyboard.ime.ai.AiLanguages
import com.qalsmartkeyboard.ime.ai.AiLink
import com.qalsmartkeyboard.ime.ai.AiPanelView
import com.qalsmartkeyboard.ime.ai.AiWorkspace
import com.qalsmartkeyboard.ime.ai.SystemAiClipboard
import com.qalsmartkeyboard.ime.ai.AiToolbarController
import com.qalsmartkeyboard.ime.ai.AiToolbarView
import com.qalsmartkeyboard.ime.ai.EditorAiAccess
import com.qalsmartkeyboard.ime.ai.TranslateController
import com.qalsmartkeyboard.ime.ai.TranslateLanguagesView
import com.qalsmartkeyboard.ime.ai.TranslationLanguages
import com.qalsmartkeyboard.ime.ai.TranslatePanelView
import com.qalsmartkeyboard.ime.ai.TranslatePicker
import com.qalsmartkeyboard.ime.ai.forEditor
import com.qalsmartkeyboard.ime.editor.EditorBuffer
import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess
import com.qalsmartkeyboard.ime.theme.ResolvedTheme
import com.qalsmartkeyboard.ime.theme.ThemeAccess
import com.qalsmartkeyboard.ime.theme.ThemeAccessHolder
import com.qalsmartkeyboard.ime.theme.ThemeResolver
import com.qalsmartkeyboard.ime.userdict.UserDictionary
import com.qalsmartkeyboard.ime.voice.PlatformVoiceProvider
import com.qalsmartkeyboard.ime.voice.VoiceController
import com.qalsmartkeyboard.ime.voice.VoiceHost
import com.qalsmartkeyboard.ime.voice.VoiceIntents
import com.qalsmartkeyboard.ime.voice.VoicePanelView
import com.qalsmartkeyboard.ime.voice.VoicePrefs
import com.qalsmartkeyboard.ime.voice.RoutedVoiceHost
import com.qalsmartkeyboard.ime.voice.VoiceTarget
import com.qalsmartkeyboard.ime.packs.PackRuntime
import com.qalsmartkeyboard.ime.toolbar.Clip
import com.qalsmartkeyboard.ime.toolbar.ClipboardHistory
import com.qalsmartkeyboard.ime.toolbar.ClipboardPanelView
import com.qalsmartkeyboard.ime.toolbar.Motion
import com.qalsmartkeyboard.ime.toolbar.SystemClipboardSource
import com.qalsmartkeyboard.ime.toolbar.Tool
import com.qalsmartkeyboard.ime.toolbar.ToolContext
import com.qalsmartkeyboard.ime.toolbar.ToolHost
import com.qalsmartkeyboard.ime.toolbar.LayoutPanelView
import com.qalsmartkeyboard.ime.toolbar.ToolPanel
import com.qalsmartkeyboard.ime.toolbar.ToolsController
import com.qalsmartkeyboard.ime.toolbar.ToolsPanelView
import com.qalsmartkeyboard.ime.ai.AiAssistAction
import com.qalsmartkeyboard.ime.ai.AiQuickAction
import com.qalsmartkeyboard.ime.ai.AiTool
import com.qalsmartkeyboard.ime.ai.AiToolHost
import com.qalsmartkeyboard.ime.ai.AiToolSet
import com.qalsmartkeyboard.ime.ai.AiToolsPanelView
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
    private var wordArtPanel: WordArtPanelView? = null
    private var wordArtBar: WordArtBar? = null
    private var voicePanel: VoicePanelView? = null
    private var toolsPanel: ToolsPanelView? = null
    private var layoutPanel: LayoutPanelView? = null
    private var aiToolsPanel: AiToolsPanelView? = null
    private var clipPanel: ClipboardPanelView? = null
    private var seenToolPanel = ToolPanel.NONE
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
        override fun getTextAfterCursor(n: Int): CharSequence? = ic?.getTextAfterCursor(n, 0)
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
        override fun getAllText(maxChars: Int): CharSequence? =
            ic?.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply { hintMaxChars = maxChars }, 0)?.text
        override fun selectAll(): Boolean = ic?.performContextMenuAction(android.R.id.selectAll) ?: false
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
        wordArtPanel?.theme = t
        wordArtBar?.theme = t
        voicePanel?.theme = t
        toolsPanel?.theme = t
        layoutPanel?.theme = t
        aiToolsPanel?.theme = t
        clipPanel?.theme = t
        aiView?.theme = t
        aiPanelView?.theme = t
        aiFieldBar?.theme = t
        translatePanel?.theme = t
        translateLanguages?.theme = t
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
            saveSettings = { transform -> snapshot.launchUpdate(transform) },
        )
    }
    private val userDictionary by lazy { UserDictionary.get(applicationContext) }
    private val controller by lazy {
        KeyboardController(
            adapter, settingsPrefs, settingsPrefs, userDictionary,
            SettingsEmojiStore({ snapshot.current }, { change -> snapshot.launchUpdate(change) }), emojiLoader, emojiExecutor,
            clipboard = ::copyToClipboard,
        ) { refreshViews() }
    }

    /**
     * Word Art's Copy (ADR 0030): a plain-text clip. It is deliberately not marked sensitive (the user will paste it into
     * other apps); on Android 13+ the system shows its own "Copied" confirmation, and the panel shows a short one too.
     */
    private fun copyToClipboard(text: String): Boolean = try {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Word Art", text))
        true
    } catch (e: RuntimeException) {
        false
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

    // Voice typing (ADR 0029): the platform's speech service over IPC, only on a mic tap while the keyboard is shown.
    // The installed services are read off the main thread ([refreshVoiceInBackground]); until then there is no mic.
    // Recognised text is never logged or stored (N2).
    private val voiceProvider by lazy { PlatformVoiceProvider(applicationContext) }
    @Volatile private var voiceNoticeSeen = false
    private val voicePrefs = object : VoicePrefs {
        override val enabled: Boolean get() = snapshot.current.voiceTypingEnabled
        override fun chosen(keyboardLanguage: String): String? = snapshot.current.voiceLanguages[keyboardLanguage]
        override fun choose(keyboardLanguage: String, tag: String) {
            if (snapshot.current.voiceLanguages[keyboardLanguage] == tag) return
            snapshot.launchUpdate { it.copy(voiceLanguages = it.voiceLanguages + (keyboardLanguage to tag)) }
        }
        override val noticeShown: Boolean get() = voiceNoticeSeen || snapshot.current.voiceNoticeShown
        override fun noticeSeen() {
            voiceNoticeSeen = true
            snapshot.launchUpdate { it.copy(voiceNoticeShown = true) }
        }
    }
    private val fieldVoiceHost = object : VoiceHost {
        override val keyboardLanguage: String get() = controller.language
        override fun hasPermission(): Boolean =
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        override fun requestPermission() {
            try {
                startActivity(VoiceIntents.permission(packageName))
            } catch (e: RuntimeException) {
                Log.w(TAG, "cannot open the microphone permission screen: ${e.javaClass.simpleName}")
            }
        }
        override fun beforeVoice() {
            controller.commitComposition()
            tools.close()
            controller.emoji.close()
            closeAiPanels()
            closeTranslate()
        }
        override fun textBeforeCursor(): CharSequence? = adapter.editorOps()?.getTextBeforeCursor(VOICE_CONTEXT_CHARS)
        override fun wantsCapital(): Boolean = controller.wantsCapitalAtCursor()
        override fun commitVoiceText(text: String) = controller.commitVoiceText(text)
    }

    // ADR 0038: dictation into the keyboard's own fields (the translate box, an AI panel field, Word Art's text) uses the
    // same controller, recogniser and permission flow; only the target changes. Before listening, the composition is
    // finished into that field and its panel stays open.
    private val voiceHost = RoutedVoiceHost(fieldVoiceHost) {
        controller.commitComposition()
        tools.close()
    }
    private val voice: VoiceController by lazy {
        VoiceController(voiceHost, voiceProvider, voicePrefs) {
            if (!voice.isOpen) voiceHost.target = null
            refreshViews()
        }
    }

    /**
     * The microphone of one of the keyboard's own fields: dictation into [buffer] at its cursor starts; tapped again while
     * it listens for that field, it stops and inserts. Without the microphone permission the app's permission screen
     * opens instead (ADR 0029), and nothing stays routed.
     */
    private fun startFieldVoice(buffer: EditorBuffer) {
        if (voice.isOpen) {
            if (voiceHost.target?.buffer === buffer) {
                voice.onMicTapped()
                return
            }
            voice.cancel()
        }
        voiceHost.target = VoiceTarget(buffer)
        voice.onMicTapped()
        if (!voice.isOpen) voiceHost.target = null
        refreshViews()
    }

    /** The bar's microphone: in translate mode it dictates into the box (the keys type there too), else into the app's field. */
    private fun onBarMic() {
        val tr = translate
        if (!voice.isOpen && tr != null && tr.isOpen && tr.picker == TranslatePicker.NONE) {
            startFieldVoice(tr.input.buffer)
            return
        }
        if (!voice.isOpen) voiceHost.target = null
        voice.onMicTapped()
    }

    /** [b] is still a field on screen that the keys type into (else dictation into it is cancelled). */
    private fun fieldOnScreen(b: EditorBuffer): Boolean {
        val tr = translate
        if (tr != null && tr.isOpen && tr.picker == TranslatePicker.NONE && tr.input.buffer === b) return true
        if (aiWorkspace?.typingField?.buffer === b) return true
        return controller.emoji.typingWordArt && controller.emoji.wordArt.buffer === b
    }

    /** Dictation is running into [b]. */
    private fun dictatingInto(b: EditorBuffer?): Boolean = b != null && voice.isOpen && voiceHost.target?.buffer === b

    // Tools panel and clipboard panel (ADR 0035). The clipboard history lives in memory only and dies with the process.
    private val clipHistory = ClipboardHistory()
    private val clipSource by lazy {
        SystemClipboardSource(
            this, clipHistory,
            historyOn = { snapshot.current.clipboardHistoryEnabled },
            focusedField = { controller.profile },
            onChanged = { main.post { if (tools.panel == ToolPanel.CLIPBOARD) clipPanel?.render() } },
        )
    }

    /** The current clip read when the clipboard panel opened (shown even with the history off); dropped on close. */
    private var clipCurrent: String? = null

    /** The field's tools without the AI part (the bar adds it). */
    private fun fieldToolContext() = ToolContext(
        profile = controller.profile,
        voiceOffered = voice.micVisible,
        writingModes = controller.writingModeOptions.isNotEmpty(),
        layouts = controller.layoutOptions().size >= 2,
    )

    private val toolHost: ToolHost = object : ToolHost {
        override fun openTranslate() {
            val tr = translate ?: return
            if (tr.isOpen) {
                closeTranslate() // the active shortcut tapped again: back to typing in the field
                return
            }
            voice.cancel()
            controller.emoji.close()
            closeAiPanels()
            aiToolbar?.close()
            tr.open()
            refreshViews()
        }
        override fun runAiTool(tool: AiTool) = tool.runOn(aiToolHost)
        override fun openEmoji() {
            voice.cancel()
            closeAiPanels()
            closeTranslate()
            controller.emoji.open(controller.profile.allowsEmoji)
            refreshViews()
        }
        override fun startVoice() {
            if (!voice.isOpen) {
                voiceHost.target = null
                voice.onMicTapped()
            }
        }
        override fun openWordArt() {
            openEmoji()
            controller.emoji.openWordArt()
        }
        override fun openWritingModes() {
            translate?.closePicker()
            strip?.openModeChooser()
        }
        override fun openLanguages() = openCompanion(AiLink.LANGUAGES)
        override fun openTheme() = openCompanion(AiLink.THEMES)
        override fun openSettings() = openCompanion(AiLink.SETTINGS)
        override fun applyLayout(choice: KeyLayoutChoice) {
            controller.selectLayout(choice)
            refreshViews()
        }
        override fun closeOtherPanels() {
            controller.commitComposition()
            voice.cancel()
            controller.emoji.close()
            closeAiPanels()
            closeTranslate()
        }
    }

    /** The AI tools grid's tiles (ADR 0041): the same flows as the retired AI row's chips and the AI panels. */
    private val aiToolHost: AiToolHost = object : AiToolHost {
        override fun openTranslate() = toolHost.openTranslate()
        override fun runQuick(action: AiQuickAction) {
            val ai = aiToolbar ?: return
            voice.cancel()
            controller.emoji.close()
            closeAiPanels()
            closeTranslate()
            ai.run(action) // its working / review / error panel opens above the bar
            refreshViews()
        }
        override fun openAssist(action: AiAssistAction?) = openAiPanel(email = false, action = action)
        override fun openEmail() = openAiPanel(email = true)
    }

    /** The field looks like an e-mail body (a mail app's compose field): Write Email is the AI tools' first tile. */
    private var aiEmailFirst = false

    private val tools: ToolsController by lazy {
        ToolsController(
            toolHost,
            { bar?.toolContext() ?: fieldToolContext() },
            // The cached plan state only: opening the grid makes no call of its own (ADR 0041).
            aiTiles = { aiToolbar?.let { AiToolSet.tiles(it.availability, aiEmailFirst) } ?: emptyList() },
        ) {
            if (tools.panel != ToolPanel.CLIPBOARD) clipCurrent = null
            refreshViews()
        }
    }

    /** Starts or stops remembering copies with the "Clipboard history" setting; turning it off forgets every clip. */
    private fun applyClipboardSetting() {
        if (snapshot.current.clipboardHistoryEnabled) {
            clipSource.start()
        } else {
            clipSource.stop()
            clipHistory.clear()
        }
    }

    /** The clipboard panel's clips: the history (the current clip joined it on open), or only the current clip when off. */
    private fun clipEntries(): List<Clip> =
        if (snapshot.current.clipboardHistoryEnabled) clipHistory.items()
        else listOfNotNull(clipCurrent?.let { Clip(0L, it, System.currentTimeMillis()) })

    /** A clip was tapped: back to the keys, and the clip goes into the field (the composition is finished first). */
    private fun pasteClip(c: Clip) {
        tools.close()
        controller.pasteText(c.text)
    }

    /** Re-reads which speech services are installed (a PackageManager query), then shows or hides the mic. */
    private fun refreshVoiceInBackground() {
        val provider = voiceProvider
        Thread({
            provider.refresh()
            main.post { refreshViews() }
        }, "qal-voice-services").start()
    }

    // AI toolbar (A2.5): built off the main thread (the app side opens Keystore-backed files), then
    // attached. Until then, or when the app registered none, the keyboard has no toolbar.
    private var aiView: AiToolbarView? = null
    private var aiToolbar: AiToolbarController? = null
    private var aiActions: AiActions? = null

    /** Re-renders an open AI panel when the account or the plan state changes (ADR 0035: sign-in in the app). */
    private var aiPanelsHandle: AutoCloseable? = null

    // AI panels (ADR 0031): AI Assist and Write Email take the key area. The state and the views are created on the first
    // open, never on the keyboard's start-up path.
    private var aiWorkspace: AiWorkspace? = null
    private var aiPanelView: AiPanelView? = null
    private var aiFieldBar: AiFieldBar? = null
    private var aiPanelDirty = false
    private var aiShownLanguage: String? = null
    private var column: KeyboardColumn? = null

    // Translate mode (ADR 0037): its state is created with the AI client; the panel above the bar and the language list in
    // place of the keys are built with the input view and stay hidden until opened.
    private var translate: TranslateController? = null
    private var translatePanel: TranslatePanelView? = null
    private var translateLanguages: TranslateLanguagesView? = null
    private var translateDirty = false
    private var seenTranslatePicker = TranslatePicker.NONE

    /** The direction memory, the preferred destination and the e-mail sender name: device-local settings (ADR 0031). */
    private val aiPrefs = object : AiLanguagePrefs {
        override val preferredTarget: String? get() = snapshot.current.aiPreferredTarget
        override fun lastTarget(source: String): String? = snapshot.current.aiTranslatePairs[source]
        override fun rememberTarget(source: String, destinationId: String) {
            val pairs = snapshot.current.aiTranslatePairs
            if (pairs[source] == destinationId && pairs.keys.lastOrNull() == source) return
            // Moved to the end: the order is the recency of the language lists (ADR 0040).
            snapshot.launchUpdate { it.copy(aiTranslatePairs = (it.aiTranslatePairs - source) + (source to destinationId)) }
        }
        override fun recentLanguages(): List<String> = AiLanguagePrefs.recentFrom(snapshot.current.aiTranslatePairs)
        override val senderName: String get() = snapshot.current.emailSenderName
        override fun rememberSenderName(name: String) {
            snapshot.launchUpdate { it.copy(emailSenderName = name) }
        }
    }

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
                    aiActions = actions
                    TranslationLanguages.current = actions.translationCatalog() // ADR 0040: the names the views show
                    val toolbar = AiToolbarController(
                        actions = actions,
                        editor = EditorAiAccess(adapter) { controller.commitComposition() },
                        post = { block -> main.post(block) },
                        copyToClipboard = ::copyResult,
                        onChanged = {
                            aiView?.render()
                            syncActiveTools() // the AI entry is active while the result panel is shown
                        },
                    )
                    aiToolbar = toolbar
                    val tr = TranslateController(
                        actions = actions,
                        editor = EditorAiAccess(adapter) { controller.commitComposition() },
                        post = { block -> main.post(block) },
                        copyToClipboard = ::copyResult,
                        keyboardLanguage = { controller.language },
                        prefs = aiPrefs,
                        refresh = { toolbar.onToolbarOpened() },
                        onChanged = {
                            translateDirty = true
                            refreshViews()
                        },
                        onInputChanged = { translatePanel?.renderInput() },
                    )
                    tr.onLeaveByKey = { toolHost.openEmoji() } // the emoji key leaves translate mode for the emoji panel
                    translate = tr
                    translatePanel?.controller = tr
                    translateLanguages?.controller = tr
                    controller.fieldTyping = ::fieldTyping
                    aiPanelsHandle = actions.addAvailabilityListener {
                        main.post {
                            val catalog = actions.translationCatalog()
                            if (catalog != TranslationLanguages.current) {
                                TranslationLanguages.current = catalog
                                if (translate?.isOpen == true) translateDirty = true
                            }
                            if (aiWorkspace?.isOpen == true) aiPanelDirty = true
                            if (translate?.isOpen == true) translateDirty = true
                            if (tools.panel == ToolPanel.AI) aiToolsPanel?.render() // the notice line and the locks
                            if (aiPanelDirty || translateDirty) refreshViews()
                        }
                    }
                    aiView?.controller = toolbar
                    toolbar.onFieldStarted(controller.profile, currentInputEditorInfo?.packageName)
                    tr.onFieldStarted(controller.profile, currentInputEditorInfo?.packageName)
                    aiView?.render() // the bar shows the AI entry now (its height does not change)
                }
            }
        }, "qal-ai-attach").start()
    }

    /** The AI panels' state, created on the first open (null until the app registered the AI client). */
    private fun workspace(): AiWorkspace? {
        aiWorkspace?.let { return it }
        val actions = aiActions ?: return null
        val ws = AiWorkspace(
            actions = actions,
            editor = EditorAiAccess(adapter) { controller.commitComposition() },
            clipboard = SystemAiClipboard(this),
            prefs = aiPrefs,
            post = { block -> main.post(block) },
            keyboardLanguage = { controller.language },
            // AI Assist's Translate goes where the translate panel's direction points (one source of truth, ADR 0037).
            translateDefault = { translate?.destination?.language ?: AiLanguages.defaultTarget(controller.language) },
            onChanged = {
                aiPanelDirty = true
                refreshViews()
            },
        )
        aiWorkspace = ws
        controller.fieldTyping = ::fieldTyping
        return ws
    }

    /** The panel field the keys type into: the translate box while translate mode is on, else an AI panel field. */
    private fun fieldTyping(): FieldTyping? = translate?.fieldTyping() ?: aiWorkspace?.fieldTyping()

    /** Leaves translate mode: a running call is cancelled, the box's text (and an open composition in it) dropped. */
    private fun closeTranslate() {
        val tr = translate ?: return
        if (!tr.isOpen) return
        tr.close()
        controller.onCompositionAbandoned()
    }

    /** What Insert did: when the field had changed meanwhile, the translation went in at the cursor (say so). */
    private fun onTranslateApplied(applied: AiApplied) {
        if (applied == AiApplied.INSERTED_ORIGINAL_CHANGED) Toast.makeText(ui, R.string.ai_inserted_instead, Toast.LENGTH_SHORT).show()
    }

    /** Creates the panel and the typing strip and puts them in the keyboard column (first open only). */
    private fun ensureAiPanelViews(ws: AiWorkspace) {
        if (aiPanelView != null) return
        val col = column ?: return
        val keys = surface ?: return
        val strip = AiFieldBar(ui).also {
            it.workspace = ws
            it.visibility = View.GONE
            it.onDone = {
                controller.commitComposition() // still routed to the field: the open composition goes in at its cursor
                ws.endTyping()
            }
            it.onMic = { ws.typingField?.buffer?.let(::startFieldVoice) }
            it.field.editor.beforeEdit = { controller.commitComposition() }
            it.field.editor.haptics = { controller.prefs.hapticsEnabled }
            it.field.clipboard = SystemAiClipboard(ui).forEditor()
            it.theme = resolvedTheme.theme
        }
        col.addView(strip, col.indexOfChild(keys), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val panel = AiPanelView(ui).also {
            it.workspace = ws
            it.visibility = View.GONE
            it.heightProvider = { aiPanelHeightPx(keys) }
            it.onLink = ::openCompanion
            it.onClose = { closeAiPanels() }
            it.onApplied = ::onAiApplied
            it.theme = resolvedTheme.theme
        }
        col.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        aiFieldBar = strip
        aiPanelView = panel
    }

    /**
     * An AI tools tile (Assist, Email, or Reply / Explain / Summarise in Assist): the panel takes the key area; voice, emoji
     * and the quick actions' panel close first.
     */
    private fun openAiPanel(email: Boolean, action: AiAssistAction? = null) {
        val ws = workspace() ?: return
        voice.cancel()
        tools.close()
        closeTranslate()
        controller.emoji.close()
        aiToolbar?.close()
        aiToolbar?.onToolbarOpened() // the plan state behind the Premium marks (throttled, sends no text)
        ensureAiPanelViews(ws)
        if (email) ws.openEmail() else ws.openAssist(action)
        aiPanelDirty = true
        refreshViews()
    }

    /** Back to the keyboard: calls are cancelled and the panels' texts dropped. */
    private fun closeAiPanels() {
        val ws = aiWorkspace ?: return
        if (!ws.isOpen) return
        if (ws.typing) controller.onCompositionAbandoned()
        ws.close()
        aiPanelView?.reset()
        refreshViews()
    }

    private fun onAiApplied(applied: AiApplied) {
        when (applied) {
            AiApplied.FAILED -> return // the panel shows why and stays open
            AiApplied.INSERTED_ORIGINAL_CHANGED -> Toast.makeText(ui, R.string.ai_inserted_instead, Toast.LENGTH_SHORT).show()
            else -> Unit
        }
        closeAiPanels()
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
        refreshVoiceInBackground()
        adapter.onCompositionAbandoned = { controller.onCompositionAbandoned() }
        snapshot.onChange = {
            main.post {
                controller.onPreferencesChanged(); voice.onPreferencesChanged(); applyTheme(); applyClipboardSetting()
                strip?.micShown = voice.micVisible
            }
        }
        snapshot.start()
        applyClipboardSetting()
        AppLanguage.addListener(onAppLanguageChanged)
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
        AppLanguage.removeListener(onAppLanguageChanged)
        voice.cancel()
        clipSource.stop()
        clipHistory.clear()
        themeAccessHandle?.close()
        themeAccessHandle = null
        snapshot.stop()
        aiToolbar?.release()
        aiPanelsHandle?.close()
        aiPanelsHandle = null
        aiWorkspace?.discard()
        engine?.let { userDictionary.detach(it) }
        userDictionary.flush()
        // InputMethodService.onDestroy() still calls onFinishInputView(), which resets the composer: the native engine
        // must outlive it (crash "KeyboardEngine object has already been destroyed", seen 3x on the owner's Samsung).
        super.onDestroy()
        engine?.close()
        engine = null
    }

    // The keyboard is small; never take over the screen with the extract UI.
    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Transparent, non-touchable space above the strip where long-press popups are drawn. */
    private var headroom: View? = null
    private var inputRoot: View? = null

    /**
     * The context the keyboard's views and toasts are built on: the app's UI language (ADR 0032), which may differ from
     * the service's own configuration below Android 13. Renewed on every [onCreateInputView].
     */
    private var ui: Context = this

    /** The app's UI language changed (the app's settings): new labels and views. Main thread. */
    private val onAppLanguageChanged: () -> Unit = {
        main.post { if (inputRoot != null) setInputView(onCreateInputView()) }
    }

    override fun onCreateInputView(): View {
        ui = AppLanguage.uiContext(this)
        KeyboardLabels.load(ui) // layout key descriptions and language names in the UI language
        controller.onLabelsChanged()
        val d = resources.displayMetrics.density
        val s = CandidateStripView(ui).also {
            it.onSelect = { i -> controller.selectCandidate(i) }
            it.onLongSelect = { i ->
                val added = controller.addCandidateToDictionary(i)
                Toast.makeText(ui, if (added) R.string.user_dictionary_added else R.string.user_dictionary_not_added, Toast.LENGTH_SHORT).show()
            }
            it.onModeSelected = { kind -> controller.selectWritingMode(kind) }
            // The bar's left button: back from a translate language list, else the tools panel.
            it.onTools = {
                val tr = translate
                if (tr != null && tr.isOpen && tr.picker != TranslatePicker.NONE) tr.closePicker() else tools.toggleTools()
            }
            it.haptics = { controller.prefs.hapticsEnabled }
            strip = it
        }
        val ai = AiToolbarView(ui).also {
            it.controller = aiToolbar
            it.onLink = ::openCompanion
            aiView = it
        }
        val translateView = TranslatePanelView(ui).also {
            it.controller = translate
            it.onLink = ::openCompanion
            it.onClose = { closeTranslate() }
            it.onApplied = ::onTranslateApplied
            it.haptics = { controller.prefs.hapticsEnabled }
            // ADR 0038: a tap or a selection in the box first finishes the composition into it, at the old cursor.
            it.beforeEdit = { controller.commitComposition() }
            it.clipboard = SystemAiClipboard(ui).forEditor()
            translatePanel = it
        }
        // One bar above the keys: tools, pill, shortcuts (the "AI" entry opens the AI tools grid), candidates, mic.
        val b = KeyboardBar(ui, s, ai).also {
            it.fieldContext = ::fieldToolContext
            it.onTool = { t -> tools.activate(t) }
            bar = it
        }
        s.onMic = { onBarMic() }
        val overlay = PopupOverlay(ui).also { overlayView = it }
        val k = KeyboardSurface(ui).also {
            it.controller = controller
            it.overlay = overlay
            surface = it
        }
        val panel = EmojiPanelView(ui).also {
            it.controller = controller
            it.overlay = overlay
            it.visibility = View.GONE
            it.heightProvider = { k.preferredHeightPx() }
            emojiPanel = it
        }
        val search = EmojiSearchBar(ui).also {
            it.controller = controller
            it.visibility = View.GONE
            emojiSearch = it
        }
        val wordArt = WordArtPanelView(ui).also {
            it.controller = controller
            it.visibility = View.GONE
            it.heightProvider = { k.preferredHeightPx() }
            wordArtPanel = it
        }
        val wordArtStrip = WordArtBar(ui).also {
            it.controller = controller
            it.visibility = View.GONE
            it.onMic = { startFieldVoice(controller.emoji.wordArt.buffer) }
            it.field.editor.beforeEdit = { controller.commitComposition() }
            it.field.editor.haptics = { controller.prefs.hapticsEnabled }
            it.field.clipboard = SystemAiClipboard(ui).forEditor()
            wordArtBar = it
        }
        val voiceView = VoicePanelView(ui).also {
            it.visibility = View.GONE
            it.heightProvider = { k.preferredHeightPx() }
            it.controller = voice
            voicePanel = it
        }
        val toolsView = ToolsPanelView(ui).also {
            it.visibility = View.GONE
            it.heightProvider = { k.preferredHeightPx() }
            it.tiles = { tools.tiles() }
            it.onTool = { t -> tools.activate(t) }
            it.haptics = { controller.prefs.hapticsEnabled }
            toolsPanel = it
        }
        val layoutView = LayoutPanelView(ui).also {
            it.visibility = View.GONE
            it.heightProvider = { k.preferredHeightPx() }
            it.options = { controller.layoutOptions() }
            it.onChoose = { c -> tools.chooseLayout(c) }
            it.haptics = { controller.prefs.hapticsEnabled }
            layoutPanel = it
        }
        val aiToolsView = AiToolsPanelView(ui).also {
            it.visibility = View.GONE
            it.heightProvider = { aiPanelHeightPx(k) }
            it.tiles = { tools.aiTools() }
            it.availability = { aiToolbar?.availability }
            it.onTool = { t -> tools.activateAi(t) }
            it.onLink = ::openCompanion
            it.haptics = { controller.prefs.hapticsEnabled }
            aiToolsPanel = it
        }
        val languagesView = TranslateLanguagesView(ui).also {
            it.controller = translate
            it.heightProvider = { k.preferredHeightPx() }
            it.haptics = { controller.prefs.hapticsEnabled }
            translateLanguages = it
        }
        seenTranslatePicker = TranslatePicker.NONE
        translateDirty = true
        val clipView = ClipboardPanelView(ui).also {
            it.visibility = View.GONE
            it.heightProvider = { k.preferredHeightPx() }
            it.entries = ::clipEntries
            it.historyOn = { snapshot.current.clipboardHistoryEnabled }
            it.onPaste = ::pasteClip
            it.onDelete = { c -> clipHistory.remove(c.id); clipPanel?.render() }
            it.onClearAll = { clipHistory.clear(); clipPanel?.render() }
            it.haptics = { controller.prefs.hapticsEnabled }
            clipPanel = it
        }
        seenToolPanel = ToolPanel.NONE
        val space = View(ui).also { headroom = it }
        // A new input view: the AI panel views (if any) belonged to the old one and are created again on the next open.
        aiPanelView = null
        aiFieldBar = null
        aiWorkspace?.discard()
        val column = KeyboardColumn(ui, space, (POPUP_HEADROOM_DP * d).toInt()) { k.backgroundFill }.also { this.column = it }.apply {
            addView(space, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (POPUP_HEADROOM_DP * d).toInt()))
            addView(ai, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(translateView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(wordArtStrip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            // The translate language list sits where the keys are; above them while the keys type into its search (ADR 0040).
            addView(languagesView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(k, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(wordArt, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(voiceView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(toolsView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(layoutView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(aiToolsView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(clipView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val root = OverlayStack(ui).apply {
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

    /**
     * ADR 0044: the height of the AI panels in place of the keys (the AI tools grid, AI Assist, Write Email): the key area
     * plus a little more, the whole keyboard capped to a share of the window ([KeyboardMetrics.aiPanelHeightPx]). Read on
     * every measure from stable values (the keys' layout height, the bar's fixed height, the inset padding already applied
     * to the column, the display size), never from the panel's own size: one height change per switch, no layout loop.
     */
    private fun aiPanelHeightPx(keys: KeyboardSurface): Int {
        val dm = ui.resources.displayMetrics
        val padding = column?.let { it.paddingTop + it.paddingBottom } ?: 0
        return KeyboardMetrics.aiPanelHeightInWindowPx(keys.preferredHeightPx(), dm.density, dm.widthPixels, dm.heightPixels, padding)
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
        voice.onFieldStarted(VoiceController.fieldAllowsVoice(profile, info.privateImeOptions), sameField = restarting)
        tools.close() // a new field: the tools, clipboard and AI tools panels start closed
        // A new field: an open AI panel and its texts never carry over (and a running call is cancelled).
        if (aiWorkspace?.isOpen == true) closeAiPanels()
        aiEmailFirst = profile.allowsAi && AiEmailHints.looksLikeEmailBody(info.packageName, info.inputType, info.hintText)
        aiToolbar?.onFieldStarted(profile, info.packageName)
        // A new field leaves translate mode; the direction starts again from Detect and the app's rule (ADR 0031).
        translate?.onFieldStarted(profile, info.packageName)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        surface?.let { styleNavigationBar(it) }
        checkPackUpdates()
        refreshVoiceInBackground() // a speech service may have been installed or removed
        applyTheme() // the entitlement token may have expired since the last time
        controller.refreshAutoCapital()
        clipHistory.prune()
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

    /**
     * Back closes the voice picker, then voice typing; it steps out of the emoji search, then out of the panel, before
     * it dismisses the keyboard.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Voice typing is on top of everything (also while it dictates into a panel's field, which stays open).
        if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0 && voice.isOpen) {
            if (voice.pickerOpen) voice.closePicker() else voice.backToKeyboard()
            return true
        }
        // The tools, clipboard and AI tools panels: Back returns to the keys.
        if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0 && tools.back()) return true
        // Translate mode: Back closes a language list, then the panel.
        val tr = translate
        if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0 && tr != null && tr.isOpen) {
            if (tr.picker != TranslatePicker.NONE) tr.closePicker() else closeTranslate()
            refreshViews()
            return true
        }
        val ws = aiWorkspace
        if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0 && ws != null && ws.isOpen) {
            // AI panels: typing → panel, result → inputs, inputs → keyboard.
            if (ws.typing) {
                controller.commitComposition()
                ws.endTyping()
            } else if (!ws.back()) {
                return super.onKeyDown(keyCode, event)
            }
            if (!ws.isOpen) aiPanelView?.reset()
            refreshViews()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0 && controller.emoji.back()) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Keyboard hidden: the editor keeps the composition as plain text; voice typing never listens while hidden.
        voice.onKeyboardHidden()
        adapter.finishComposition()
        controller.emoji.close()
        controller.onCompositionAbandoned()
        tools.close()
        clipHistory.prune()
        aiToolbar?.onKeyboardHidden()
        closeAiPanels()
        closeTranslate()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        voice.cancel()
        adapter.finishComposition()
        controller.onCompositionAbandoned()
        userDictionary.saveSoon()
        super.onFinishInput()
    }

    private fun refreshViews() {
        // Dictation into a field that left the screen (Done, Back, the panel closed, a language list) stops (ADR 0038).
        voiceHost.target?.let { t ->
            if (voice.isOpen && !fieldOnScreen(t.buffer)) {
                voice.cancel() // renders again
                return
            }
        }
        // The one bar (mode pill, AI entry, candidates, settings gear) is always shown so the keyboard
        // keeps one height; candidates and the DRAFT tag only where the strip applies (phonetic, not passwords).
        // AI panels (ADR 0031): a panel takes the key area at the same height; while one of its fields is typed into, the
        // keys are back and the field strip takes the bar's place (like Word Art).
        val ws = aiWorkspace
        val aiPanelShown = ws?.showsPanel == true && aiPanelView != null
        val aiTyping = ws?.typing == true && aiFieldBar != null
        strip?.let {
            it.visibility = View.VISIBLE
            // While an AI field is typed into the bar is hidden; its candidates would also close the AI row.
            val candidates = controller.showCandidateStrip && !aiTyping
            it.show(
                if (candidates) controller.candidates else emptyList(), candidates && controller.candidatesAreDraft,
                autocorrect = if (candidates) controller.autocorrectIndex else -1, rtl = controller.candidatesRtl,
            )
            it.setModes(controller.writingModeOptions, controller.writingMode)
            it.closeModeChooser()
        }
        // Emoji panel (ADR 0028): the panel takes the key area, or (search) the keys are back with the search strip in the bar's place.
        val mode = controller.emoji.mode
        val inPanel = mode == EmojiMode.PANEL
        val inSearch = mode == EmojiMode.SEARCH
        // Word Art (ADR 0030): its panel takes the key area too; while its text field is typed into the keys are back with its strip.
        val inWordArt = mode == EmojiMode.WORD_ART
        val inWordArtTyping = mode == EmojiMode.WORD_ART_TYPING
        // Voice typing (ADR 0029): its panel takes the key area at the same height; the bar's mic shows its state.
        val voiceOpen = voice.isOpen
        strip?.micShown = voice.micVisible
        strip?.micActive = voiceOpen
        voicePanel?.visibility = if (voiceOpen) View.VISIBLE else View.GONE
        if (voiceOpen) voicePanel?.render()
        // Tools, clipboard and AI tools panels (ADR 0035, 0041): they take the key area too; the bar shows a back arrow and
        // their title.
        val toolPanel = tools.panel
        toolsPanel?.visibility = if (toolPanel == ToolPanel.TOOLS) View.VISIBLE else View.GONE
        clipPanel?.visibility = if (toolPanel == ToolPanel.CLIPBOARD) View.VISIBLE else View.GONE
        aiToolsPanel?.visibility = if (toolPanel == ToolPanel.AI) View.VISIBLE else View.GONE
        layoutPanel?.visibility = if (toolPanel == ToolPanel.LAYOUT) View.VISIBLE else View.GONE
        if (toolPanel != seenToolPanel) {
            seenToolPanel = toolPanel
            val rise = 8f * resources.displayMetrics.density
            when (toolPanel) {
                ToolPanel.TOOLS -> toolsPanel?.let { it.render(); Motion.reveal(it, fromYPx = rise) }
                ToolPanel.LAYOUT -> layoutPanel?.let { it.render(); Motion.reveal(it, fromYPx = rise) }
                ToolPanel.AI -> aiToolsPanel?.let {
                    // The user opened the AI tools: the cached plan state is rendered at once; a throttled background
                    // refresh may update it (ADR 0007 D40, as the AI row did). No text is read or sent.
                    aiToolbar?.onToolbarOpened()
                    it.render()
                    Motion.reveal(it, fromYPx = rise)
                }
                ToolPanel.CLIPBOARD -> clipPanel?.let {
                    clipCurrent = clipSource.currentText() // the user opened the panel: the current clip is read now
                    it.render()
                    Motion.reveal(it, fromYPx = rise)
                }
                ToolPanel.NONE -> Unit
            }
        }
        // Translate mode (ADR 0037): its panel above the bar; a language list takes the key area like a tool panel.
        val tr = translate
        val translateOpen = tr != null && tr.isOpen
        val picker = if (tr != null && translateOpen) tr.picker else TranslatePicker.NONE
        val picking = picker != TranslatePicker.NONE
        // ADR 0040: while the list's search is typed, the keys show again under a shorter list.
        val searchingLanguages = picking && tr?.searching == true
        translatePanel?.let {
            val was = it.visibility == View.VISIBLE
            val show = translateOpen && !picking
            it.visibility = if (show) View.VISIBLE else View.GONE
            if (show && (translateDirty || !was)) {
                it.render()
                if (!was && seenTranslatePicker == TranslatePicker.NONE) it.reveal()
            }
        }
        translateLanguages?.let {
            it.visibility = if (picking) View.VISIBLE else View.GONE
            if (picking && (picker != seenTranslatePicker || translateDirty)) it.render()
        }
        seenTranslatePicker = picker
        translateDirty = false
        strip?.panelTitle = when {
            toolPanel == ToolPanel.TOOLS -> ui.getString(R.string.tools_title)
            toolPanel == ToolPanel.CLIPBOARD -> ui.getString(R.string.tool_clipboard)
            toolPanel == ToolPanel.AI -> ui.getString(R.string.top_bar_ai_open)
            toolPanel == ToolPanel.LAYOUT -> ui.getString(R.string.layout_panel_title, LanguageCatalog.displayName(controller.language))
            picker == TranslatePicker.SOURCE -> ui.getString(R.string.translate_from_title)
            picker == TranslatePicker.DESTINATION -> ui.getString(R.string.translate_into_title)
            else -> null
        }
        syncActiveTools()
        bar?.sync() // the field's shortcuts (clipboard, emoji, voice) follow the field and the settings
        surface?.visibility = if (inPanel || inWordArt || voiceOpen || aiPanelShown || toolPanel != ToolPanel.NONE || (picking && !searchingLanguages)) View.GONE else View.VISIBLE
        emojiPanel?.visibility = if (inPanel) View.VISIBLE else View.GONE
        bar?.visibility = if (inSearch || inWordArtTyping || aiTyping) View.GONE else View.VISIBLE
        aiFieldBar?.let {
            it.visibility = if (aiTyping) View.VISIBLE else View.GONE
            if (aiTyping) {
                it.render()
                it.micShown = voice.micVisible
                it.micActive = dictatingInto(ws?.typingField?.buffer)
            }
        }
        aiPanelView?.let {
            val was = it.visibility == View.VISIBLE
            it.visibility = if (aiPanelShown) View.VISIBLE else View.GONE
            if (aiPanelShown && (aiPanelDirty || !was)) {
                aiPanelDirty = false
                it.render()
            }
        }
        // The Detect source of the translation direction follows the keyboard language.
        if (aiShownLanguage != controller.language) {
            aiShownLanguage = controller.language
            translate?.onKeyboardLanguageChanged()
        }
        emojiSearch?.visibility = if (inSearch) View.VISIBLE else View.GONE
        wordArtPanel?.visibility = if (inWordArt) View.VISIBLE else View.GONE
        wordArtBar?.visibility = if (inWordArtTyping) View.VISIBLE else View.GONE
        if (mode != seenEmojiMode) {
            if (inPanel) emojiPanel?.onOpened()
            seenEmojiMode = mode
        }
        if (inPanel) emojiPanel?.refresh()
        if (inSearch) emojiSearch?.render()
        if (inWordArt) wordArtPanel?.refresh()
        if (inWordArtTyping) wordArtBar?.let {
            it.render()
            it.micShown = voice.micVisible
            it.micActive = dictatingInto(controller.emoji.wordArt.buffer)
        }
        surface?.onLayoutChanged()
    }

    /** The shortcuts drawn active: their panel is open (emoji, an AI panel or the quick actions' result, translate). */
    private fun syncActiveTools() {
        val mode = controller.emoji.mode
        val ws = aiWorkspace
        strip?.setActiveTools(
            buildSet {
                if (mode == EmojiMode.PANEL || mode == EmojiMode.SEARCH || mode == EmojiMode.WORD_ART || mode == EmojiMode.WORD_ART_TYPING) add(Tool.EMOJI)
                if (ws?.isOpen == true || aiView?.visibility == View.VISIBLE) add(Tool.AI)
                if (translate?.isOpen == true) add(Tool.TRANSLATE)
            },
        )
    }

    companion object {
        private const val TAG = "QalIme"

        /** Intent extra on the launch intent of the companion app: the [AiLink] name to show. */
        const val EXTRA_OPEN_LINK = "com.qalsmartkeyboard.OPEN_LINK"
        private const val POPUP_HEADROOM_DP = 190f

        /** Characters read before the cursor to space dictated text (never stored). */
        private const val VOICE_CONTEXT_CHARS = 4
    }
}
