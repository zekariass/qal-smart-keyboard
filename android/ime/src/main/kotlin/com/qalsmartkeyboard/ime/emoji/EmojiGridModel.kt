package com.qalsmartkeyboard.ime.emoji

/** One section of the panel's scrolling grid: a category header and its cells (the Recent section may be empty). */
class EmojiSection(val category: EmojiCategory, val cells: List<EmojiCell>)

/**
 * Geometry of the scrolling emoji grid: every section is a header row followed by rows of [cols] cells (an empty
 * section shows one message row). Pure arithmetic, so scrolling, tab jumps and hit-testing are unit tested.
 * All sizes are in pixels; y is measured from the top of the content.
 */
class EmojiGridModel(val sections: List<EmojiSection>, val cols: Int, val headerH: Float, val cellH: Float) {
    /** A header row (`cellStart` -1), a row of cells starting at `cellStart`, or the empty-section message (`cellStart` -2). */
    class Row(val section: Int, val cellStart: Int, val top: Float, val height: Float)

    val rows: List<Row>
    val totalHeight: Float
    private val sectionTops = FloatArray(sections.size)

    init {
        val out = ArrayList<Row>()
        var y = 0f
        sections.forEachIndexed { s, sec ->
            sectionTops[s] = y
            out += Row(s, -1, y, headerH)
            y += headerH
            if (sec.cells.isEmpty()) {
                out += Row(s, -2, y, cellH)
                y += cellH
            } else {
                var i = 0
                while (i < sec.cells.size) {
                    out += Row(s, i, y, cellH)
                    y += cellH
                    i += cols
                }
            }
        }
        rows = out
        totalHeight = y
    }

    fun sectionTop(section: Int): Float = sectionTops.getOrElse(section) { 0f }

    /** The section containing content position [y] (the one whose header is the last at or above it). */
    fun sectionAt(y: Float): Int {
        var found = 0
        for (s in sectionTops.indices) if (sectionTops[s] <= y) found = s else break
        return found
    }

    /** Index into [rows] of the first row ending below [y] (binary search). */
    fun firstRowAt(y: Float): Int {
        var lo = 0
        var hi = rows.size - 1
        var ans = rows.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (rows[mid].top + rows[mid].height > y) { ans = mid; hi = mid - 1 } else lo = mid + 1
        }
        return ans
    }

    /** The cell at content position ([x], [y]) for a grid [width] wide, as (section, index in the section), or null. */
    fun cellAt(x: Float, y: Float, width: Float): Pair<Int, Int>? {
        if (y < 0 || x < 0 || x >= width) return null
        val r = rows.getOrNull(firstRowAt(y)) ?: return null
        if (r.cellStart < 0) return null
        val col = (x / (width / cols)).toInt().coerceIn(0, cols - 1)
        val i = r.cellStart + col
        return if (i < sections[r.section].cells.size) r.section to i else null
    }

    fun maxScroll(viewportH: Float): Float = (totalHeight - viewportH).coerceAtLeast(0f)
}
