package com.qalsmartkeyboard.ime.ai

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.qalsmartkeyboard.ime.CandidateStripView
import com.qalsmartkeyboard.ime.FakeEditor
import com.qalsmartkeyboard.ime.InputConnectionAdapter
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.KeyboardBar
import com.qalsmartkeyboard.ime.KeyboardController
import com.qalsmartkeyboard.ime.KeyboardSurface
import com.qalsmartkeyboard.ime.TextSink
import com.qalsmartkeyboard.ime.ComposerUpdate
import com.qalsmartkeyboard.ime.theme.ThemeCatalog
import com.qalsmartkeyboard.ime.toolbar.Tool
import com.qalsmartkeyboard.ime.toolbar.ToolContext
import com.qalsmartkeyboard.ime.toolbar.ToolHost
import com.qalsmartkeyboard.ime.toolbar.ToolPanel
import com.qalsmartkeyboard.ime.toolbar.ToolsController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * ADR 0041: the AI tools grid. Opened by the "AI" entry in place of the keys at their height, closed by Back; its tiles are
 * exactly the AI features that work (none where AI is not offered); locked tiles say so; signed out / offline / update is
 * one line with its action; a tile runs the same controller path as the retired AI row's chips and the AI panels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement for the labels
class AiToolsGridTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val d = ctx.resources.displayMetrics.density
    private val widthPx = (360 * d).toInt()
    private val button = "android.widget.Button"

    private class Sink : TextSink {
        override fun apply(update: ComposerUpdate) {}
        override fun sendEnterKey() {}
        override fun performEditorAction(actionId: Int) {}
        override fun wantsAutoCapital(capsFlags: Int) = false
    }

    private fun View.measureAndLayout(w: Int = widthPx) {
        measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun localized(lang: String): Context =
        ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply { setLocale(Locale(lang)) })

    /**
     * The service's composition of the AI tools in miniature: the bar's AI client state, the tools controller, the grid in
     * place of the keys, and an [AiToolHost] that runs the real quick-action controller and the real AI panels' state.
     */
    private inner class Rig(text: String = "Hello. How are you", val profile: InputProfile = InputProfile.DEFAULT, val ui: Context = ctx) {
        val actions = FakeAiActions()
        val ed = FakeEditor(text)
        val adapter = InputConnectionAdapter { ed }.also { it.startSession(false, text.length, text.length) }
        val editor = EditorAiAccess(adapter) { adapter.finishComposition() }
        val resultPanel = AiToolbarView(ui)
        val quick = AiToolbarController(actions, editor, { it() }, {}, onChanged = { resultPanel.render() })
        val workspace = AiWorkspace(actions, editor, FakeClipboard(), AiLanguagePrefs.InMemory(), { it() }, { "am" }, { "en" })
        val bar = KeyboardBar(ui, CandidateStripView(ui), resultPanel)
        val calls = ArrayList<String>()
        val links = ArrayList<AiLink>()
        var emailFirst = false

        val aiHost = object : AiToolHost {
            override fun openTranslate() { calls += "translate" }
            override fun runQuick(action: AiQuickAction) = quick.run(action)
            override fun openAssist(action: AiAssistAction?) = workspace.openAssist(action)
            override fun openEmail() = workspace.openEmail()
        }
        val host = object : ToolHost {
            override fun openTranslate() = aiHost.openTranslate()
            override fun runAiTool(tool: AiTool) = tool.runOn(aiHost)
            override fun openEmoji() {}
            override fun startVoice() {}
            override fun openWordArt() {}
            override fun cycleWritingMode() {}
            override fun openLanguages() {}
            override fun openTheme() {}
            override fun openSettings() {}
            override fun closeOtherPanels() { calls += "closeOthers" }
        }
        val keys = KeyboardSurface(ui).also {
            it.controller = KeyboardController(Sink()) {}.apply { startField(InputProfile.from(InputType.TYPE_CLASS_TEXT, 0, false, 0)) }
        }
        val tools = ToolsController(host, { bar.toolContext() }, { AiToolSet.tiles(quick.availability, emailFirst) }) { sync() }
        val grid = AiToolsPanelView(ui).also {
            it.visibility = View.GONE
            it.heightProvider = { keys.preferredHeightPx() }
            it.tiles = { tools.aiTools() }
            it.availability = { quick.availability }
            it.onTool = { t -> tools.activateAi(t) }
            it.onLink = { l -> links += l }
            it.haptics = { false }
        }
        val column = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            addView(resultPanel)
            addView(bar)
            addView(keys)
            addView(grid)
        }

        init {
            resultPanel.controller = quick
            bar.fieldContext = { ToolContext(profile) }
            quick.onFieldStarted(profile, "com.example.chat")
            bar.onTool = { t -> tools.activate(t) }
            sync()
        }

        /** What the service's refreshViews does for this panel. */
        fun sync() {
            val open = tools.panel == ToolPanel.AI
            if (open && grid.visibility != View.VISIBLE) {
                quick.onToolbarOpened()
                grid.render()
            }
            grid.visibility = if (open) View.VISIBLE else View.GONE
            keys.visibility = if (tools.panel == ToolPanel.NONE) View.VISIBLE else View.GONE
            bar.strip.panelTitle = if (open) ui.getString(com.qalsmartkeyboard.ime.R.string.top_bar_ai_open) else null
            column.measureAndLayout()
        }

        fun openFromBar() = bar.strip.activate(CandidateStripView.Target.Shortcut(Tool.AI))
        fun tile(t: AiTool): View = grid.tileFor(t)!!
        fun labelOf(t: AiTool): TextView = (tile(t) as ViewGroup).getChildAt(1) as TextView
    }

    // ---------------------------------------------------------------- opening and closing

    @Test fun theAiEntryReplacesTheKeysAtTheirHeightAndBackReturns() {
        val r = Rig()
        val keysHeight = r.keys.measuredHeight
        assertTrue(keysHeight > 0)
        r.openFromBar()
        assertEquals(ToolPanel.AI, r.tools.panel)
        assertEquals(View.GONE, r.keys.visibility)
        assertEquals(View.VISIBLE, r.grid.visibility)
        assertEquals("the grid takes the key area's height", keysHeight, r.grid.measuredHeight)
        assertEquals("AI tools", r.bar.strip.slots().first { it.target == CandidateStripView.Target.Title }.label)
        // Opening reads the cached plan state only: no text, no request (a throttled plan refresh, as the AI row did).
        assertTrue(r.actions.submitted.isEmpty())
        assertFalse(r.ed.calls.any { it == "getTextBeforeCursor" || it == "getSelectedText" })
        assertEquals(1, r.actions.refreshCount)
        // The system Back (and the bar's back arrow, which toggles) returns to the keys.
        assertTrue(r.tools.back())
        assertEquals(View.VISIBLE, r.keys.visibility)
        assertEquals(View.GONE, r.grid.visibility)
        r.openFromBar()
        r.tools.toggleTools()
        assertEquals(ToolPanel.NONE, r.tools.panel)
    }

    @Test fun theToolsPanelsAiTileOpensTheSameGrid() {
        val r = Rig()
        r.tools.toggleTools()
        assertTrue(r.tools.tiles().any { it.tool == Tool.AI })
        r.tools.activate(Tool.AI)
        assertEquals(ToolPanel.AI, r.tools.panel)
        assertEquals(View.VISIBLE, r.grid.visibility)
    }

    // ---------------------------------------------------------------- tiles

    @Test fun theTilesAreTheWorkingAiFeaturesFourPerRowInOrder() {
        val r = Rig()
        r.openFromBar()
        assertEquals(
            listOf(
                AiTool.TRANSLATE, AiTool.GRAMMAR, AiTool.REWRITE, AiTool.SIMPLIFY,
                AiTool.SHORTEN, AiTool.EXPAND, AiTool.CLARIFY, AiTool.FORMAT,
                AiTool.REPLY, AiTool.EMAIL, AiTool.EXPLAIN, AiTool.SUMMARIZE,
                AiTool.PROFESSIONAL, AiTool.FRIENDLY, AiTool.POLITE, AiTool.CONCISE,
                AiTool.APOLOGETIC, AiTool.HONORIFIC, AiTool.ROMANTIC, AiTool.CONFIDENT,
                AiTool.FUNNY, AiTool.PERSUASIVE, AiTool.ASSIST,
            ),
            r.grid.shown.map { it.tool },
        )
        // Every quick action is one served transform task; every Assist tile one Assist action; nothing else.
        assertEquals(AiQuickAction.entries.toSet(), AiTool.entries.mapNotNull { it.quick }.toSet())
        assertEquals(
            setOf(AiAssistAction.REPLY, AiAssistAction.EXPLAIN, AiAssistAction.SUMMARIZE, AiAssistAction.FORMAT),
            AiTool.entries.mapNotNull { it.assist }.toSet(),
        )
        val first = r.tile(AiTool.TRANSLATE)
        val fifth = r.tile(AiTool.REPLY)
        assertTrue("row 2 below row 1", (fifth.parent as View).top > (first.parent as View).top)
        assertEquals((first.parent as View).top, (r.tile(AiTool.SIMPLIFY).parent as View).top)
        assertTrue(first.height >= (48 * d).toInt())
        // Labels and TalkBack names: a Button per tile; a tone says it rewrites.
        assertEquals("Fix grammar", r.labelOf(AiTool.GRAMMAR).text.toString())
        assertEquals("Friendly", r.labelOf(AiTool.FRIENDLY).text.toString())
        assertEquals("Rewrite, tone: Friendly", r.tile(AiTool.FRIENDLY).contentDescription.toString())
        assertEquals("Write Email", r.tile(AiTool.EMAIL).contentDescription.toString())
        assertEquals(button, r.tile(AiTool.GRAMMAR).createAccessibilityNodeInfo().className)
        assertNull("no status line when nothing blocks", r.grid.noticeView)
    }

    @Test fun anEmailBodyPutsWriteEmailFirst() {
        val r = Rig()
        r.emailFirst = true
        r.openFromBar()
        assertEquals(AiTool.EMAIL, r.grid.shown.first().tool)
        assertEquals(AiTool.entries.size, r.grid.shown.size)
    }

    @Test fun noAiToolsWhereTheFieldDoesNotAllowAi() {
        val kinds = listOf(
            InputProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0, false, 0),
            InputProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0, false, 0),
            InputProfile.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false, 0), // incognito
        )
        for (p in kinds) {
            val r = Rig(profile = p)
            assertFalse(r.bar.toolContext().aiReady)
            r.openFromBar()
            r.tools.activate(Tool.AI)
            assertEquals("$p", ToolPanel.NONE, r.tools.panel)
            assertTrue(r.tools.aiTools().isEmpty())
            assertTrue(r.tools.tiles().none { it.tool == Tool.AI || it.tool == Tool.TRANSLATE })
            assertTrue(r.actions.submitted.isEmpty())
        }
    }

    @Test fun lockedTilesComeFromTheCachedPlanAndStillRun() {
        val r = Rig()
        r.actions.availability = AiAvailability(entitled = setOf(AiTaskKind.TRANSLATE, AiTaskKind.GRAMMAR, AiTaskKind.REWRITE))
        r.openFromBar()
        val locked = r.grid.shown.filter { it.locked }.map { it.tool }.toSet()
        assertEquals(
            setOf(
                AiTool.EMAIL, AiTool.REPLY, AiTool.EXPLAIN, AiTool.SUMMARIZE, AiTool.FORMAT,
                AiTool.PROFESSIONAL, AiTool.FRIENDLY, AiTool.POLITE, AiTool.CONCISE, AiTool.APOLOGETIC, AiTool.HONORIFIC,
                AiTool.ROMANTIC, AiTool.CONFIDENT, AiTool.FUNNY, AiTool.PERSUASIVE,
            ),
            locked,
        )
        assertFalse("Assist offers included actions", AiTool.ASSIST in locked)
        assertEquals("Rewrite, tone: Friendly, locked", r.tile(AiTool.FRIENDLY).contentDescription.toString())
        // A locked quick action still opens and says why (the upgrade link); nothing is sent.
        r.tile(AiTool.FRIENDLY).performClick()
        assertEquals(ToolPanel.NONE, r.tools.panel)
        assertEquals(AiFailure.NotEntitled, (r.quick.panel as AiPanel.Failed).failure)
        assertEquals(View.VISIBLE, r.resultPanel.visibility)
        assertTrue(r.actions.submitted.isEmpty())
        // Unknown plan state never locks.
        assertTrue(AiToolSet.tiles(AiAvailability()).none { it.locked })
    }

    @Test fun signedOutIsOneLineWithSignInAndNoLocks() {
        val r = Rig()
        r.actions.availability = AiAvailability(block = AiBlock.SIGNED_OUT)
        r.openFromBar()
        val line = r.grid.noticeView as ViewGroup
        val text = line.getChildAt(0) as TextView
        assertEquals("Sign in to use AI", text.text.toString())
        val action = line.getChildAt(1) as TextView
        assertEquals(button, action.createAccessibilityNodeInfo().className)
        action.performClick()
        assertEquals(listOf(AiLink.SIGN_IN), r.links)
        assertTrue("one compact line", line.measuredHeight <= (56 * d).toInt())
        assertTrue(r.grid.shown.none { it.locked })
        // A tile still answers with the sign-in panel above the bar, sending nothing.
        r.tile(AiTool.GRAMMAR).performClick()
        assertEquals(AiFailure.SignInRequired, (r.quick.panel as AiPanel.Failed).failure)
        assertTrue(r.actions.submitted.isEmpty())
    }

    @Test fun offlineAndUpdateAreOneLineEach() {
        val off = Rig()
        off.actions.availability = AiAvailability(block = AiBlock.OFFLINE)
        off.openFromBar()
        val offLine = off.grid.noticeView as ViewGroup
        assertEquals("Offline: AI needs a connection", (offLine.getChildAt(0) as TextView).text.toString())
        assertEquals("no action to take offline", 1, offLine.childCount)
        val upd = Rig()
        upd.actions.availability = AiAvailability(block = AiBlock.UPDATE_REQUIRED)
        upd.openFromBar()
        (upd.grid.noticeView as ViewGroup).getChildAt(1).performClick()
        assertEquals(listOf(AiLink.UPDATE_APP), upd.links)
    }

    @Test fun theRemainingTranslationAllowanceIsAQuietLine() {
        val r = Rig()
        r.actions.availability = AiAvailability(quota = mapOf(AiTaskKind.TRANSLATE to AiQuotaHint(3, 10, AiPeriod.MONTHLY, null)))
        r.openFromBar()
        val line = r.grid.noticeView as ViewGroup
        assertEquals("Translate: 3 of 10 left this month", (line.getChildAt(0) as TextView).text.toString())
        assertEquals(1, line.childCount)
    }

    // ---------------------------------------------------------------- running a tile

    @Test fun aQuickTileRunsTheSameControllerPathAsTheOldChip() {
        val r = Rig("Hello. How are you")
        r.openFromBar()
        r.tile(AiTool.GRAMMAR).performClick()
        // Back to the keys; the request is the old Grammar chip's: the sentence before the cursor, a fresh key.
        assertEquals(ToolPanel.NONE, r.tools.panel)
        assertEquals(View.VISIBLE, r.keys.visibility)
        val sent = r.actions.submitted.single().request
        assertEquals(AiTask.Grammar, sent.task)
        assertEquals("How are you", sent.text)
        assertTrue(r.quick.panel is AiPanel.Working)
        assertEquals("the working panel opens above the bar", View.VISIBLE, r.resultPanel.visibility)
        r.actions.answer(0, FakeAiActions.ok("How are you?"))
        assertEquals(AiApplied.REPLACED, r.quick.replace())
        assertEquals("Hello. How are you?", r.ed.text.toString())
        // A tone tile is TONE_ADJUST with its tone.
        r.openFromBar()
        r.tile(AiTool.HONORIFIC).performClick()
        assertEquals(AiTask.Rewrite(AiTone.HONORIFIC), r.actions.submitted[1].request.task)
    }

    @Test fun replyExplainAndSummariseOpenAssistOnThatActionAndSendNothing() {
        for ((tool, action) in listOf(AiTool.REPLY to AiAssistAction.REPLY, AiTool.EXPLAIN to AiAssistAction.EXPLAIN, AiTool.SUMMARIZE to AiAssistAction.SUMMARIZE, AiTool.FORMAT to AiAssistAction.FORMAT)) {
            val r = Rig()
            r.openFromBar()
            r.tile(tool).performClick()
            assertEquals(AiScreen.ASSIST, r.workspace.screen)
            assertEquals(action, r.workspace.assist.action)
            assertTrue(r.actions.submitted.isEmpty())
        }
        val r = Rig()
        r.openFromBar()
        r.tile(AiTool.EMAIL).performClick()
        assertEquals(AiScreen.EMAIL, r.workspace.screen)
        r.openFromBar()
        r.tile(AiTool.ASSIST).performClick()
        assertEquals(AiScreen.ASSIST, r.workspace.screen)
        r.openFromBar()
        r.tile(AiTool.TRANSLATE).performClick()
        assertEquals("translate", r.calls.last())
        assertTrue(r.actions.submitted.isEmpty())
    }

    // ---------------------------------------------------------------- every language and theme

    @Test fun labelsFitTwoLinesAt360dpInEveryUiLanguage() {
        for (lang in listOf("en", "am", "ti", "om")) {
            val ui = localized(lang)
            val r = Rig(ui = ui)
            r.openFromBar()
            r.grid.measureAndLayout()
            for (e in r.grid.shown) {
                val label = r.labelOf(e.tool)
                assertNotNull(label.layout)
                assertTrue("$lang ${e.tool} '${label.text}' lines ${label.lineCount}", label.lineCount <= 2)
                for (i in 0 until label.lineCount) assertEquals("$lang ${e.tool} '${label.text}' is not cut", 0, label.layout.getEllipsisCount(i))
            }
            assertEquals(ui.getString(com.qalsmartkeyboard.ime.R.string.top_bar_ai_open), r.bar.strip.slots().first { it.target == CandidateStripView.Target.Title }.label)
        }
    }

    @Test fun everyThemeDrawsTheGrid() {
        for (t in ThemeCatalog.all) {
            val r = Rig()
            r.actions.availability = AiAvailability(block = AiBlock.SIGNED_OUT, entitled = setOf(AiTaskKind.GRAMMAR))
            r.grid.theme = t
            r.openFromBar()
            r.grid.measureAndLayout()
            r.grid.draw(Canvas(Bitmap.createBitmap(widthPx, maxOf(1, r.grid.height), Bitmap.Config.ARGB_8888)))
        }
    }
}
