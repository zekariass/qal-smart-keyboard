package com.qalsmartkeyboard.ime.sticker

import com.qalsmartkeyboard.ime.draw.Drawing
import com.qalsmartkeyboard.ime.draw.InkDefaults
import com.qalsmartkeyboard.ime.draw.Luma

/**
 * The pure model of the sticker maker (T8.7): what a sticker is made of, free of Android types so it is JVM-tested.
 * Everything is created and kept on the device (R-8); nothing here touches the network, logs a text or reads the host field.
 */
enum class StickerShape(val code: Int) {
    /** No background: the sticker has a transparent background. */
    NONE(0), CIRCLE(1), ROUNDED(2), BURST(3),
    ;

    companion object {
        fun fromCode(c: Int): StickerShape = entries.firstOrNull { it.code == c } ?: NONE
    }
}

/** The text sticker's size choice: the biggest the text may be, as a fraction of the sticker's safe area (it shrinks to fit). */
enum class StickerTextSize(val maxFraction: Float) { SMALL(0.22f), MEDIUM(0.34f), LARGE(0.52f) }

/** What kind of sticker the user made (the personal pack's index keeps it; it picks no behaviour). */
enum class StickerKind(val code: Char) {
    TEXT('t'), EMOJI('e'), DRAWING('d'), OTHER('x'),
    ;

    companion object {
        fun fromCode(c: Char): StickerKind = entries.firstOrNull { it.code == c } ?: OTHER
    }
}

/** One sticker, ready to be painted by the renderer at any size (the preview, a thumbnail, the 512 px export). */
sealed interface StickerSpec {
    val kind: StickerKind
    val shape: StickerShape

    /** The background colour (ARGB) of a non-[StickerShape.NONE] shape. */
    val fill: Int
}

/** Text only, centred and fitted: [text] may have several lines. */
data class TextStickerSpec(
    val text: String,
    val textColour: Int,
    override val shape: StickerShape = StickerShape.NONE,
    override val fill: Int = StickerColours.FILLS[0],
    val size: StickerTextSize = StickerTextSize.MEDIUM,
) : StickerSpec {
    override val kind get() = StickerKind.TEXT

    /** Never prints the text (it is the user's). */
    override fun toString() = "TextStickerSpec($shape, $size, ${text.length} chars)"
}

/** One emoji with an optional caption under it. */
data class EmojiStickerSpec(
    val emoji: String,
    val caption: String = "",
    val captionColour: Int = StickerColours.contrastOn(StickerColours.FILLS[0]),
    override val shape: StickerShape = StickerShape.NONE,
    override val fill: Int = StickerColours.FILLS[0],
) : StickerSpec {
    override val kind get() = StickerKind.EMOJI

    override fun toString() = "EmojiStickerSpec($shape, caption ${caption.length} chars)"
}

/** A drawing from Draw & Sketch (its ink cropped and centred), on a transparent background or its own paper colour. */
data class DrawingStickerSpec(val drawing: Drawing, val withBackground: Boolean = false) : StickerSpec {
    override val kind get() = StickerKind.DRAWING
    override val shape get() = StickerShape.NONE
    override val fill get() = drawing.background.colour

    override fun toString() = "DrawingStickerSpec(${drawing.strokes.size} strokes)"
}

object StickerColours {
    /** Text and caption colours: the Draw palette (black, white, then eight hues). */
    val INK: List<Int> = InkDefaults.PALETTE

    /** Background fills of a shaped sticker: soft and bright colours that stay readable under the ink colours. */
    val FILLS: List<Int> = listOf(
        0xFFFFFFFF.toInt(), 0xFFFFE082.toInt(), 0xFFFFAB91.toInt(), 0xFFA5D6A7.toInt(),
        0xFF80DEEA.toInt(), 0xFF90CAF9.toInt(), 0xFFCE93D8.toInt(), 0xFF263238.toInt(),
    )

    /** Black on light colours, white on dark ones. */
    fun contrastOn(argb: Int): Int = if (Luma.of(argb) < 0.5f) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
}

/** The size, format and limits of a sticker file. */
object StickerFormat {
    /** Stickers are square: the 512 x 512 px of the messaging apps' sticker formats. */
    const val SIZE_PX = 512
    const val MIME = "image/webp"
    const val EXT = ".webp"

    /** The personal pack holds this many stickers; making more is refused with a message until one is deleted. */
    const val MAX_STICKERS = 60

    /** Longest text of a text sticker and of a caption, in characters (code points). */
    const val MAX_TEXT = 60
    const val MAX_CAPTION = 24

    enum class Codec {
        /** `WEBP_LOSSLESS` (API 30+): exact pixels and alpha. */
        LOSSLESS,

        /** `WEBP` (API 18+, the module's minSdk is 24): lossy colour with a lossless-quality alpha channel. */
        LEGACY,
    }

    fun codecFor(sdk: Int): Codec = if (sdk >= 30) Codec.LOSSLESS else Codec.LEGACY

    /** Quality of the legacy codec (the lossless one ignores it). */
    const val LEGACY_QUALITY = 92

    /** The fraction of the sticker's side that text and art may use, so that nothing touches the edge of the shape. */
    fun safeFraction(shape: StickerShape): Float = when (shape) {
        StickerShape.NONE -> 0.92f
        StickerShape.ROUNDED -> 0.84f
        StickerShape.CIRCLE -> 0.68f
        StickerShape.BURST -> 0.60f
    }

    /** True when [bytes] start like a WebP file: `RIFF`, a size, `WEBP`. */
    fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()

    /** Code points of [s] (a surrogate pair or an emoji sequence's parts each count as the characters the user sees as one or more). */
    fun length(s: String): Int = s.codePointCount(0, s.length)
}
