package com.qalsmartkeyboard.ime.draw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8.15: every drawing the T8.4 build saved (codec version 1) must still load unchanged. The two fixture files were written
 * by the version-1 code itself before the format was extended and are committed as they were.
 */
class DrawingCodecCompatTest {
    @Test fun theVersionOneFixturesAreReallyVersionOne() {
        for (f in listOf(DrawV1Fixtures.FULL, DrawV1Fixtures.EMPTY)) {
            val b = DrawV1Fixtures.bytes(f)
            assertEquals(0x51, b[0].toInt())
            assertEquals("version", 1, ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF))
        }
    }

    @Test fun aVersionOneDrawingLoadsUnchanged() {
        val full = DrawingCodec.decode(DrawV1Fixtures.bytes(DrawV1Fixtures.FULL))
        assertTrue(full is DecodeResult.Ok)
        assertEquals(DrawV1Fixtures.full(), (full as DecodeResult.Ok).drawing)
        val empty = DrawingCodec.decode(DrawV1Fixtures.bytes(DrawV1Fixtures.EMPTY))
        assertEquals(DrawV1Fixtures.empty(), (empty as DecodeResult.Ok).drawing)
    }
}
