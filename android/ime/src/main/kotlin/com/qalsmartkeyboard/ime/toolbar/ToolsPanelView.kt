package com.qalsmartkeyboard.ime.toolbar

import android.content.Context
import android.view.View

/**
 * The tools panel (ADR 0035): a [TileGridView] of the field's [Tool]s in place of the key area: Translate and the AI entry
 * (ADR 0041), then typing tools, then the keyboard's own settings. A locked tool carries a small lock and says so to
 * TalkBack. No ABC button: the back arrow in the bar and the system Back return to the keys, so the rows of tiles fit the
 * key area of a portrait phone without scrolling.
 */
class ToolsPanelView(context: Context) : TileGridView<Tool>(context) {
    /** The tools to show (refreshed on every open). */
    var tiles: () -> List<ToolEntry> = { emptyList() }

    /** A tile was tapped. */
    var onTool: (Tool) -> Unit = {}

    /** The tools shown by the last [render], in order (tests and accessibility checks). */
    var shown: List<ToolEntry> = emptyList()
        private set

    init {
        gridTiles = {
            tiles().also { shown = it }.map { GridTile(it.tool, it.tool.icon, toolLabel(context, it.tool), it.locked) }
        }
        onGridTile = { onTool(it) }
        render()
    }

    /** The tile view of [tool] in the current grid, or null. */
    fun tileFor(tool: Tool): View? = tileView(tool)
}
