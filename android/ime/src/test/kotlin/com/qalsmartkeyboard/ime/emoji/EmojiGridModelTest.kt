package com.qalsmartkeyboard.ime.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiGridModelTest {
    private fun cells(n: Int) = (0 until n).map { EmojiCell("e$it", EmojiEntry("e$it", "n$it", EmojiCategory.SMILEYS, emptyList(), emptyList(), it)) }

    // 4 columns, header 10, cell 20, width 100 (cell width 25).
    private val model = EmojiGridModel(
        listOf(EmojiSection(EmojiCategory.RECENT, emptyList()), EmojiSection(EmojiCategory.SMILEYS, cells(9)), EmojiSection(EmojiCategory.FOOD, cells(4))),
        cols = 4, headerH = 10f, cellH = 20f,
    )

    @Test fun layoutHeightsAddUp() {
        // recent: header + message row; smileys: header + 3 rows; food: header + 1 row.
        assertEquals(10f + 20f + 10f + 60f + 10f + 20f, model.totalHeight, 0.001f)
        assertEquals(0f, model.sectionTop(0), 0.001f)
        assertEquals(30f, model.sectionTop(1), 0.001f)
        assertEquals(100f, model.sectionTop(2), 0.001f)
    }

    @Test fun sectionAtFollowsTheScroll() {
        assertEquals(0, model.sectionAt(0f))
        assertEquals(0, model.sectionAt(29f))
        assertEquals(1, model.sectionAt(30f))
        assertEquals(1, model.sectionAt(99f))
        assertEquals(2, model.sectionAt(150f))
    }

    @Test fun hitTesting() {
        // y 40..60 is the first cell row of smileys (header 30..40).
        assertEquals(1 to 0, model.cellAt(10f, 45f, 100f))
        assertEquals(1 to 3, model.cellAt(99f, 45f, 100f))
        assertEquals(1 to 8, model.cellAt(10f, 85f, 100f)) // third row has one cell
        assertNull(model.cellAt(60f, 85f, 100f)) // empty slot of the last row
        assertNull(model.cellAt(10f, 35f, 100f)) // header
        assertNull(model.cellAt(10f, 15f, 100f)) // the empty-section message row
        assertNull(model.cellAt(10f, 500f, 100f))
    }

    @Test fun firstRowAtFindsTheVisibleRow() {
        assertEquals(0, model.firstRowAt(0f))
        assertEquals(1, model.firstRowAt(10f)) // header 0..10 is above
        assertEquals(model.rows.size, model.firstRowAt(10_000f))
    }

    @Test fun maxScrollNeverNegative() {
        assertEquals(0f, model.maxScroll(10_000f), 0.001f)
        assertEquals(model.totalHeight - 50f, model.maxScroll(50f), 0.001f)
    }
}
