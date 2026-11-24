package com.qalsmartkeyboard.ime.toolbar

import com.qalsmartkeyboard.ime.FieldKind
import com.qalsmartkeyboard.ime.InputProfile
import com.qalsmartkeyboard.ime.KeyLayoutChoice
import com.qalsmartkeyboard.ime.ai.AiTool
import com.qalsmartkeyboard.ime.ai.AiToolEntry

/**
 * The keyboard's tools (ADR 0035). Each is a tile of the tools panel; the first few that fit are also pinned to the
 * idle bar as shortcuts ([ToolSet.shortcuts]). [AI] is the one AI entry (ADR 0041): the "AI" mark opens the AI tools grid
 * ([ToolPanel.AI]) in place of the keys, where AI Assist, Write Email and the quick actions are tiles.
 *
 * A new tool (a keyboard-layout tile, ...) is one enum value, a label ([toolLabel]), a place in [ToolSet] and a branch in
 * [ToolsController.activate].
 */
enum class Tool(val icon: ToolIcon) {
    TRANSLATE(ToolIcon.TRANSLATE),
    AI(ToolIcon.AI),
    CLIPBOARD(ToolIcon.CLIPBOARD),
    EMOJI(ToolIcon.EMOJI),
    VOICE(ToolIcon.MIC),
    WORD_ART(ToolIcon.WORD_ART),
    WRITING_MODE(ToolIcon.WRITING_MODE),

    /** ADR 0043: the key layouts of the current language ([ToolPanel.LAYOUT]). */
    LAYOUT(ToolIcon.LAYOUT),

    /** T8.3 deterministic utilities (ADR 0048): free, unmetered, on-device; each opens a panel in place of the keys. */
    CALCULATOR(ToolIcon.CALCULATOR),
    UNIT_CONVERTER(ToolIcon.UNITS),

    /** The converter panel on its Currency tab (offline rates snapshot only). */
    CURRENCY(ToolIcon.CURRENCY),
    CALENDAR(ToolIcon.CALENDAR),
    QR(ToolIcon.QR),

    /** T8.4 Draw & Sketch (ADR 0048): free, on-device; the canvas takes the key area ([ToolPanel.DRAW]). */
    DRAW(ToolIcon.DRAW),
    LANGUAGES(ToolIcon.LANGUAGES),
    THEME(ToolIcon.THEME),
    SETTINGS(ToolIcon.SETTINGS),
}

/** One tile or shortcut: [locked] = the cached plan state says it needs sign-in, an update or a plan (ADR 0031). */
data class ToolEntry(val tool: Tool, val locked: Boolean = false)

/**
 * What decides which tools a field offers. Read from state the keyboard already holds (the field's `EditorInfo`
 * profile, the attached AI client's cached plan state, the voice and writing-mode state); nothing here reads text or
 * touches the network.
 */
data class ToolContext(
    val profile: InputProfile,
    /** The AI client is attached and AI is allowed in this field ([com.qalsmartkeyboard.ime.ai.AiToolbarController.visible]). */
    val aiReady: Boolean = false,
    /** Signed out, update needed or offline: the AI tools open, but show why they cannot run. */
    val aiBlocked: Boolean = false,
    /** The cached plan excludes translation / every AI tool (unknown never locks). */
    val translateLocked: Boolean = false,
    val aiLocked: Boolean = false,
    /** The microphone is offered (setting on, a speech service installed, the field allows it). */
    val voiceOffered: Boolean = false,
    /** am/ti in a text field with at least two writing modes loaded. */
    val writingModes: Boolean = false,
    /** The emoji panel can open (its data is bundled; false only before the service wired it). */
    val emojiReady: Boolean = true,
    /** ADR 0043: the current language offers at least two key layouts here (Latin arrangements, Fidel variants). */
    val layouts: Boolean = false,
) {
    /**
     * Clipboard: every field except passwords, fields that ask for no personalised learning (incognito) and raw
     * key-event fields. Its history is the user's own copied text, so it stays out of the places where the keyboard
     * must not show or remember anything.
     */
    val clipboardAllowed: Boolean
        get() = profile.kind != FieldKind.PASSWORD && profile.learningAllowed && !profile.noTextModel

    /**
     * T8.3 utilities (calculator, converters, calendar, QR): every field that takes committed text except passwords and raw
     * key-event fields. They never read the field on their own and are never plan-gated (R-8, ADR 0048).
     */
    val utilitiesAllowed: Boolean
        get() = profile.kind != FieldKind.PASSWORD && !profile.noTextModel
}

object ToolSet {
    /** The tools panel in tile order (4 columns): the AI tools, then typing tools, then the keyboard's own settings. */
    fun tiles(c: ToolContext): List<ToolEntry> = buildList {
        if (c.aiReady) {
            add(ToolEntry(Tool.TRANSLATE, c.aiBlocked || c.translateLocked))
            add(ToolEntry(Tool.AI, c.aiBlocked || c.aiLocked))
        }
        if (c.clipboardAllowed) add(ToolEntry(Tool.CLIPBOARD))
        if (c.profile.allowsEmoji && c.emojiReady) add(ToolEntry(Tool.EMOJI))
        if (c.voiceOffered) add(ToolEntry(Tool.VOICE))
        if (c.profile.allowsEmoji && c.emojiReady) add(ToolEntry(Tool.WORD_ART))
        if (c.writingModes) add(ToolEntry(Tool.WRITING_MODE))
        if (c.layouts) add(ToolEntry(Tool.LAYOUT))
        if (c.utilitiesAllowed) {
            add(ToolEntry(Tool.CALCULATOR))
            add(ToolEntry(Tool.UNIT_CONVERTER))
            add(ToolEntry(Tool.CURRENCY))
            add(ToolEntry(Tool.CALENDAR))
            add(ToolEntry(Tool.QR))
            add(ToolEntry(Tool.DRAW))
        }
        add(ToolEntry(Tool.LANGUAGES))
        add(ToolEntry(Tool.THEME))
        add(ToolEntry(Tool.SETTINGS))
    }

    /**
     * The bar's pinned shortcuts in priority order: Translate, AI, Clipboard, Settings, Theme, Emoji — all of them,
     * the middle scrolling sideways when they do not fit. Voice is not here: the microphone has its own place at the
     * bar's right end.
     */
    fun shortcutCandidates(c: ToolContext): List<ToolEntry> = buildList {
        if (c.aiReady) {
            add(ToolEntry(Tool.TRANSLATE, c.aiBlocked || c.translateLocked))
            add(ToolEntry(Tool.AI, c.aiBlocked || c.aiLocked))
        }
        if (c.clipboardAllowed) add(ToolEntry(Tool.CLIPBOARD))
        add(ToolEntry(Tool.SETTINGS))
        add(ToolEntry(Tool.THEME))
        if (c.profile.allowsEmoji && c.emojiReady) add(ToolEntry(Tool.EMOJI))
    }

}

/** What the keyboard does when a tool is chosen (implemented by the service; faked in tests). */
interface ToolHost {
    /**
     * Translate mode (ADR 0037): the translate panel above the bar, the keys typing into its box. When it is already
     * open (the active Translate shortcut tapped again) it closes, like Gboard's.
     */
    fun openTranslate()

    /** A tile of the AI tools grid was chosen (the grid has closed): run it ([AiTool.runOn]). */
    fun runAiTool(tool: AiTool)
    fun openEmoji()
    fun startVoice()
    fun openWordArt()

    /** The bar's Writing mode tile: cycles the language's writing modes (am/ti: Phonetic → Fidel → English). */
    fun cycleWritingMode()
    fun openLanguages()
    fun openTheme()
    fun openSettings()

    /**
     * ADR 0043: the Layout panel picked [choice]: apply and save it for the current language (the panel has closed; any
     * open composition was committed when the tools panel opened).
     */
    fun applyLayout(choice: KeyLayoutChoice) {}

    /** A tool panel (tools, clipboard, AI tools) is about to open: voice, emoji, translate and the AI panels close first. */
    fun closeOtherPanels()
}

/**
 * Which tool panel replaces the key area: the tools grid, the clipboard, the AI tools grid (ADR 0041), the current
 * language's key layouts (ADR 0043), or one of the T8.3 utilities (calculator, unit and currency converter, calendar, QR) and Draw & Sketch (T8.4).
 */
enum class ToolPanel { NONE, TOOLS, CLIPBOARD, AI, LAYOUT, CALCULATOR, CONVERTER, CALENDAR, QR, DRAW }

/**
 * The tools, clipboard and AI tools panels' open state, free of Views (JVM-tested). Only one is open at a time; any
 * tool chosen from a grid closes it first, so the tool opens in place of the keys or the bar as it does elsewhere.
 */
class ToolsController(
    private val host: ToolHost,
    private val context: () -> ToolContext,
    /** The AI tools grid's tiles for the focused field. */
    private val aiTiles: () -> List<AiToolEntry> = { emptyList() },
    private val onChanged: () -> Unit = {},
) {
    var panel: ToolPanel = ToolPanel.NONE
        private set

    val isOpen: Boolean get() = panel != ToolPanel.NONE

    /** True when the converter panel was opened by the Currency tile (it then shows its Currency tab). */
    var converterOnCurrency: Boolean = false
        private set

    /** The tiles for the focused field. */
    fun tiles(): List<ToolEntry> = ToolSet.tiles(context())

    /** The AI tools grid's tiles; none where the field does not offer AI (passwords, incognito, ...). */
    fun aiTools(): List<AiToolEntry> = if (context().aiReady) aiTiles() else emptyList()

    /** The bar's tools button: opens the tools panel, or closes whichever panel is open. */
    fun toggleTools() {
        if (isOpen) close() else open(ToolPanel.TOOLS)
    }

    /** The clipboard shortcut or tile. Ignored where the field does not allow it. */
    fun openClipboard() {
        if (!context().clipboardAllowed) return
        open(ToolPanel.CLIPBOARD)
    }

    private fun open(p: ToolPanel) {
        if (panel == p) return
        host.closeOtherPanels()
        panel = p
        onChanged()
    }

    /** Back to the keys (the bar's back arrow, a new field, the keyboard hidden). */
    fun close() {
        if (panel == ToolPanel.NONE) return
        panel = ToolPanel.NONE
        onChanged()
    }

    /** The system Back: closes an open panel; false when none was open. */
    fun back(): Boolean {
        if (!isOpen) return false
        close()
        return true
    }

    /**
     * A tile or shortcut was tapped. A tool the field does not offer (a stale tap after a field change) does nothing.
     * Locked tools still open: their panel or row shows why they cannot run and the link to fix it.
     */
    fun activate(tool: Tool) {
        val c = context()
        val offered = tiles().any { it.tool == tool } || ToolSet.shortcutCandidates(c).any { it.tool == tool }
        if (!offered) return
        when (tool) {
            Tool.CLIPBOARD -> {
                openClipboard()
                return
            }
            Tool.AI -> {
                open(ToolPanel.AI)
                return
            }
            Tool.LAYOUT -> {
                open(ToolPanel.LAYOUT)
                return
            }
            Tool.CALCULATOR -> {
                open(ToolPanel.CALCULATOR)
                return
            }
            Tool.UNIT_CONVERTER, Tool.CURRENCY -> {
                converterOnCurrency = tool == Tool.CURRENCY
                open(ToolPanel.CONVERTER)
                return
            }
            Tool.CALENDAR -> {
                open(ToolPanel.CALENDAR)
                return
            }
            Tool.QR -> {
                open(ToolPanel.QR)
                return
            }
            Tool.DRAW -> {
                open(ToolPanel.DRAW)
                return
            }
            else -> Unit
        }
        close()
        when (tool) {
            Tool.TRANSLATE -> host.openTranslate()
            Tool.EMOJI -> host.openEmoji()
            Tool.VOICE -> host.startVoice()
            Tool.WORD_ART -> host.openWordArt()
            Tool.WRITING_MODE -> host.cycleWritingMode()
            Tool.LANGUAGES -> host.openLanguages()
            Tool.THEME -> host.openTheme()
            Tool.SETTINGS -> host.openSettings()
            Tool.CLIPBOARD, Tool.AI, Tool.LAYOUT, Tool.CALCULATOR, Tool.UNIT_CONVERTER, Tool.CURRENCY, Tool.CALENDAR, Tool.QR, Tool.DRAW -> Unit
        }
    }

    /**
     * ADR 0043: a layout of the Layout panel was chosen: the panel closes (back to the keys, now in that layout) and the
     * host applies it. A stale tap (the panel has closed) does nothing; Back leaves the layout as it was.
     */
    fun chooseLayout(choice: KeyLayoutChoice) {
        if (panel != ToolPanel.LAYOUT) return
        close()
        host.applyLayout(choice)
    }

    /**
     * A tile of the AI tools grid was tapped: the grid closes (back to the keys, where a quick action's result panel opens
     * above the bar) and the tool runs; Assist, Email and Translate open their panels. A stale tap (the grid has closed,
     * the field no longer offers AI) does nothing.
     */
    fun activateAi(tool: AiTool) {
        if (panel != ToolPanel.AI) return
        if (aiTools().none { it.tool == tool }) return
        close()
        host.runAiTool(tool)
    }
}
