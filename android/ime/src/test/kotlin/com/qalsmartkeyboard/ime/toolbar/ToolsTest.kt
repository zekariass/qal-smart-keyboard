package com.qalsmartkeyboard.ime.toolbar

import android.text.InputType as T
import android.view.inputmethod.EditorInfo as E
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.ai.AiAvailability
import com.qalsmartkeyboard.ime.ai.AiTool
import com.qalsmartkeyboard.ime.ai.AiToolEntry
import com.qalsmartkeyboard.ime.ai.AiToolSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0035 / 0041: which tools each field offers (tiles and bar shortcuts) and what choosing one does. */
class ToolsTest {
    private fun profile(type: Int, options: Int = 0) = InputProfile.from(type, options, false, 0)
    private val text = profile(T.TYPE_CLASS_TEXT)

    /** A plain text field in am/ti with AI attached, voice installed and writing modes loaded. */
    private val full = ToolContext(text, aiReady = true, voiceOffered = true, writingModes = true)

    private fun tools(c: ToolContext) = ToolSet.tiles(c).map { it.tool }

    @Test fun aTextFieldOffersEveryToolInTileOrder() {
        assertEquals(
            listOf(
                Tool.TRANSLATE, Tool.AI, Tool.CLIPBOARD, Tool.EMOJI, Tool.VOICE, Tool.WORD_ART,
                Tool.WRITING_MODE, Tool.LANGUAGES, Tool.THEME, Tool.SETTINGS,
            ),
            tools(full),
        )
        assertTrue(ToolSet.tiles(full).none { it.locked })
    }

    @Test fun omAndEnHaveNoWritingModeTile() {
        // om, en (and Arabic) have one writing mode: the controller offers no options, so no tile and no pill.
        val c = full.copy(writingModes = false)
        assertFalse(Tool.WRITING_MODE in tools(c))
        assertTrue(Tool.LANGUAGES in tools(c))
    }

    @Test fun passwordFieldsOfferNoAiClipboardEmojiOrWordArt() {
        for (type in listOf(
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            T.TYPE_CLASS_NUMBER or T.TYPE_NUMBER_VARIATION_PASSWORD,
        )) {
            // The controller never offers AI there; voice is off in password fields (VoiceController.fieldAllowsVoice).
            val c = ToolContext(profile(type))
            assertFalse("$type", c.clipboardAllowed)
            assertEquals("$type", listOf(Tool.LANGUAGES, Tool.THEME, Tool.SETTINGS), tools(c))
            assertEquals("$type", listOf(Tool.SETTINGS, Tool.THEME), ToolSet.shortcutCandidates(c).map { it.tool })
        }
    }

    @Test fun emailUrlNumberAndPhoneFieldsKeepTheClipboardButNotAiOrEmoji() {
        for (type in listOf(
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_URI,
            T.TYPE_CLASS_NUMBER,
            T.TYPE_CLASS_PHONE,
        )) {
            val p = profile(type)
            assertFalse(p.allowsAi)
            val c = ToolContext(p, voiceOffered = true)
            assertEquals("$type", listOf(Tool.CLIPBOARD, Tool.VOICE, Tool.LANGUAGES, Tool.THEME, Tool.SETTINGS), tools(c))
        }
    }

    @Test fun incognitoAndRawFieldsOfferNoClipboard() {
        assertFalse(ToolContext(profile(T.TYPE_CLASS_TEXT, E.IME_FLAG_NO_PERSONALIZED_LEARNING)).clipboardAllowed)
        assertFalse(ToolContext(profile(T.TYPE_NULL)).clipboardAllowed)
        assertTrue(ToolContext(text).clipboardAllowed)
    }

    @Test fun lockedAiToolsAreStillOfferedAndMarked() {
        val c = full.copy(aiBlocked = true)
        val tiles = ToolSet.tiles(c).associate { it.tool to it.locked }
        assertEquals(true, tiles[Tool.TRANSLATE])
        assertEquals(true, tiles[Tool.AI])
        assertEquals(false, tiles[Tool.CLIPBOARD])
        val plan = ToolSet.tiles(full.copy(aiLocked = true)).associate { it.tool to it.locked }
        assertEquals(true, plan[Tool.AI])
        assertEquals(false, plan[Tool.TRANSLATE])
        assertEquals(true, ToolSet.shortcutCandidates(full.copy(aiLocked = true)).first { it.tool == Tool.AI }.locked)
    }

    @Test fun shortcutsComeInPriorityOrderAndOnlyAsManyAsFit() {
        assertEquals(
            listOf(Tool.TRANSLATE, Tool.AI, Tool.CLIPBOARD, Tool.SETTINGS, Tool.THEME, Tool.EMOJI),
            ToolSet.shortcutCandidates(full).map { it.tool },
        )
        assertEquals(listOf(Tool.TRANSLATE, Tool.AI, Tool.CLIPBOARD), ToolSet.shortcuts(full, 3).map { it.tool })
        assertTrue(ToolSet.shortcuts(full, 0).isEmpty())
        assertTrue(ToolSet.shortcuts(full, -2).isEmpty())
        // Voice is never a shortcut: the microphone has its own place.
        assertTrue(ToolSet.shortcutCandidates(full).none { it.tool == Tool.VOICE })
    }

    // ---------------------------------------------------------------- controller

    private class Host : ToolHost {
        val calls = ArrayList<String>()
        override fun openTranslate() { calls += "translate" }
        override fun runAiTool(tool: AiTool) { calls += "ai $tool" }
        override fun openEmoji() { calls += "emoji" }
        override fun startVoice() { calls += "voice" }
        override fun openWordArt() { calls += "wordart" }
        override fun openWritingModes() { calls += "modes" }
        override fun openLanguages() { calls += "languages" }
        override fun openTheme() { calls += "theme" }
        override fun openSettings() { calls += "settings" }
        override fun closeOtherPanels() { calls += "closeOthers" }
    }

    private class Rig(var context: ToolContext, var ai: List<AiToolEntry> = AiToolSet.tiles(AiAvailability())) {
        val host = Host()
        var changes = 0
        val c = ToolsController(host, { context }, { ai }) { changes++ }
    }

    @Test fun theToolsButtonOpensAndClosesThePanel() {
        val r = Rig(full)
        r.c.toggleTools()
        assertEquals(ToolPanel.TOOLS, r.c.panel)
        assertEquals(listOf("closeOthers"), r.host.calls) // voice, emoji, AI rows and panels close first
        r.c.toggleTools()
        assertEquals(ToolPanel.NONE, r.c.panel)
        assertEquals(2, r.changes)
        assertFalse(r.c.back())
    }

    @Test fun backClosesAnOpenPanel() {
        val r = Rig(full)
        r.c.toggleTools()
        assertTrue(r.c.back())
        assertEquals(ToolPanel.NONE, r.c.panel)
    }

    @Test fun eachTileClosesThePanelAndOpensItsTool() {
        val expected = mapOf(
            Tool.TRANSLATE to "translate", Tool.EMOJI to "emoji",
            Tool.VOICE to "voice", Tool.WORD_ART to "wordart", Tool.WRITING_MODE to "modes", Tool.LANGUAGES to "languages",
            Tool.THEME to "theme", Tool.SETTINGS to "settings",
        )
        for ((tool, call) in expected) {
            val r = Rig(full)
            r.c.toggleTools()
            r.c.activate(tool)
            assertEquals("$tool", ToolPanel.NONE, r.c.panel)
            assertEquals("$tool", call, r.host.calls.last())
        }
    }

    @Test fun theClipboardTileSwitchesToTheClipboardPanel() {
        val r = Rig(full)
        r.c.toggleTools()
        r.c.activate(Tool.CLIPBOARD)
        assertEquals(ToolPanel.CLIPBOARD, r.c.panel)
        r.c.toggleTools() // the bar's back arrow
        assertEquals(ToolPanel.NONE, r.c.panel)
        // The shortcut opens it straight from the keys.
        r.c.activate(Tool.CLIPBOARD)
        assertEquals(ToolPanel.CLIPBOARD, r.c.panel)
    }

    @Test fun aToolTheFieldDoesNotOfferDoesNothing() {
        val r = Rig(ToolContext(profile(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD)))
        for (t in listOf(Tool.TRANSLATE, Tool.AI, Tool.CLIPBOARD, Tool.EMOJI, Tool.WORD_ART, Tool.VOICE, Tool.WRITING_MODE)) {
            r.c.activate(t)
        }
        r.c.openClipboard()
        assertTrue("no AI tools where AI is not offered", r.c.aiTools().isEmpty())
        assertTrue(r.host.calls.toString(), r.host.calls.isEmpty())
        assertEquals(ToolPanel.NONE, r.c.panel)
        r.c.activate(Tool.SETTINGS)
        assertEquals(listOf("settings"), r.host.calls)
    }

    @Test fun lockedToolsStillOpenSoTheyCanSayWhy() {
        val r = Rig(full.copy(aiBlocked = true, aiLocked = true))
        r.c.activate(Tool.TRANSLATE)
        r.c.activate(Tool.AI)
        assertEquals(listOf("translate", "closeOthers"), r.host.calls)
        assertEquals(ToolPanel.AI, r.c.panel)
    }

    // ---------------------------------------------------------------- the AI tools grid (ADR 0041)

    @Test fun theAiEntryOpensTheAiToolsGridFromTheBarOrTheToolsPanel() {
        val r = Rig(full)
        r.c.activate(Tool.AI) // the bar's shortcut
        assertEquals(ToolPanel.AI, r.c.panel)
        assertEquals(listOf("closeOthers"), r.host.calls) // voice, emoji, translate and the AI panels close first
        assertTrue(r.c.back()) // the system Back: back to the keys
        assertEquals(ToolPanel.NONE, r.c.panel)
        r.c.toggleTools()
        r.c.activate(Tool.AI) // the tools panel's tile switches to the AI grid
        assertEquals(ToolPanel.AI, r.c.panel)
        r.c.toggleTools() // the bar's back arrow
        assertEquals(ToolPanel.NONE, r.c.panel)
    }

    @Test fun anAiTileClosesTheGridAndRunsItsTool() {
        for (tool in AiTool.entries) {
            val r = Rig(full)
            r.c.activate(Tool.AI)
            r.c.activateAi(tool)
            assertEquals("$tool", ToolPanel.NONE, r.c.panel)
            assertEquals("$tool", "ai $tool", r.host.calls.last())
        }
    }

    @Test fun aStaleAiTileTapDoesNothing() {
        val r = Rig(full)
        r.c.activateAi(AiTool.GRAMMAR) // the grid is not open
        r.c.activate(Tool.AI)
        r.ai = r.ai.filter { it.tool != AiTool.EMAIL }
        r.c.activateAi(AiTool.EMAIL) // not offered any more
        assertEquals(ToolPanel.AI, r.c.panel)
        r.context = ToolContext(profile(T.TYPE_CLASS_TEXT or T.TYPE_TEXT_VARIATION_PASSWORD)) // the field changed
        r.c.activateAi(AiTool.GRAMMAR)
        assertEquals(listOf("closeOthers"), r.host.calls)
    }
}
