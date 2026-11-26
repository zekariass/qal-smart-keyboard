package com.qalsmartkeyboard.ime.draw

/**
 * The drawings behind the version-1 fixture files in `src/test/resources/draw/` (T8.15). The files were written by the T8.4
 * code (`DrawingCodec.VERSION == 1`) before the format changed; they are committed as they were, so a later reader is tested
 * against what really is on the owner's phone. These objects are what the files must decode to.
 */
object DrawV1Fixtures {
    const val FULL = "/draw/v1_full.qdraw"
    const val EMPTY = "/draw/v1_empty.qdraw"

    private fun p(x: Float, y: Float, pressure255: Int) = Pt(x, y, pressure255 / 255f)

    fun full() = Drawing(
        width = 360f,
        height = 240f,
        background = DrawBackground(0xFFFFF8E1.toInt(), BackgroundPattern.RULED),
        strokes = listOf(
            Stroke(listOf(p(10f, 10f, 255), p(50.5f, 20.25f, 128), p(90f, 60f, 51)), 0xFF000000.toInt(), 4f, DrawTool.PEN),
            Stroke(listOf(p(5f, 100f, 255), p(200f, 110f, 255), p(300f, 100.5f, 255)), 0xFFE53935.toInt(), 8f, DrawTool.HIGHLIGHTER),
            Stroke(listOf(p(40f, 5f, 255), p(45f, 70f, 255)), 0xFF000000.toInt(), 14f, DrawTool.ERASER),
            Stroke(listOf(p(150f, 150f, 255)), 0xFF1E88E5.toInt(), 2f, DrawTool.PEN),
        ),
        meta = linkedMapOf("k" to "v", "note" to "አማርኛ"),
    )

    fun empty() = Drawing(120f, 80f, DrawBackground.TRANSPARENT)

    fun bytes(resource: String): ByteArray =
        DrawV1Fixtures::class.java.getResourceAsStream(resource)!!.use { it.readBytes() }
}
