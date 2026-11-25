package com.qalsmartkeyboard.ime.sticker

/** The bundled starter packs (T8.7). Premium: gated by the platform feature `STICKER_MAKER`. */
enum class StickerPackId(val key: String) { GREETINGS("greetings"), CELEBRATIONS("celebrations"), REACTIONS("reactions") }

/** One designed sticker of a bundled pack: [id] is stable (it names the rendered file), [label] is the spoken name. */
class PackSticker(val id: String, val label: String, val spec: StickerSpec)

class StickerPack(val id: StickerPackId, val stickers: List<PackSticker>)

/**
 * Three small packs of eight stickers, authored in code: an emoji (the system's own emoji font) and a caption on a shape and
 * colour of our own. No third-party image or font is bundled (N10); the am captions are a DRAFT awaiting the native-speaker
 * review (N9), like the UI strings.
 */
object StickerPacks {
    /** Bumped whenever a design changes, so the rendered files of the previous design are not reused. */
    const val VERSION = 1

    private val WHITE = 0xFFFFFFFF.toInt()
    private val DARK = 0xFF263238.toInt()

    private fun s(id: String, emoji: String, caption: String, shape: StickerShape, fill: Int, ink: Int = StickerColours.contrastOn(fill)) =
        PackSticker(id, "$emoji $caption", EmojiStickerSpec(emoji, caption, ink, shape, fill))

    val GREETINGS = StickerPack(
        StickerPackId.GREETINGS,
        listOf(
            s("hello", "\uD83D\uDC4B", "Hello", StickerShape.CIRCLE, 0xFFFFE082.toInt()),
            s("morning", "\u2600\uFE0F", "Good morning", StickerShape.ROUNDED, 0xFFFFCC80.toInt()),
            s("night", "\uD83C\uDF19", "Good night", StickerShape.ROUNDED, 0xFF283593.toInt()),
            s("thanks", "\uD83D\uDE4F", "Thank you", StickerShape.BURST, 0xFFA5D6A7.toInt()),
            s("welcome", "\uD83E\uDD17", "Welcome", StickerShape.CIRCLE, 0xFF80DEEA.toInt()),
            s("sorry", "\uD83D\uDE14", "Sorry", StickerShape.ROUNDED, 0xFFCFD8DC.toInt()),
            s("bye", "\uD83D\uDC4B", "Bye!", StickerShape.CIRCLE, 0xFFCE93D8.toInt()),
            s("wishes", "\uD83D\uDC90", "Best wishes", StickerShape.BURST, 0xFFF8BBD0.toInt()),
        ),
    )

    val CELEBRATIONS = StickerPack(
        StickerPackId.CELEBRATIONS,
        listOf(
            s("newyear", "\uD83C\uDF3C", "መልካም አዲስ ዓመት", StickerShape.BURST, 0xFFFFEE58.toInt()),
            s("meskel", "\uD83D\uDD25", "መልካም መስቀል", StickerShape.ROUNDED, 0xFFFFAB91.toInt()),
            s("genna", "\uD83C\uDF84", "መልካም ገና", StickerShape.ROUNDED, 0xFF2E7D32.toInt()),
            s("timket", "\uD83D\uDCA7", "መልካም ጥምቀት", StickerShape.CIRCLE, 0xFF4FC3F7.toInt()),
            s("fasika", "\uD83D\uDC23", "መልካም ፋሲካ", StickerShape.BURST, 0xFFFFE082.toInt()),
            s("eid", "\uD83C\uDF19", "Eid Mubarak", StickerShape.CIRCLE, 0xFF00695C.toInt()),
            s("congrats", "\uD83C\uDF89", "እንኳን ደስ አላችሁ", StickerShape.BURST, 0xFFF48FB1.toInt()),
            s("coffee", "\u2615", "ቡና ጠጡ", StickerShape.ROUNDED, 0xFF6D4C41.toInt()),
        ),
    )

    val REACTIONS = StickerPack(
        StickerPackId.REACTIONS,
        listOf(
            s("lol", "\uD83D\uDE02", "LOL", StickerShape.CIRCLE, 0xFFFFEE58.toInt()),
            s("great", "\uD83D\uDC4D", "Great", StickerShape.ROUNDED, 0xFFA5D6A7.toInt()),
            s("love", "\uD83D\uDE0D", "Love it", StickerShape.BURST, 0xFFF48FB1.toInt()),
            s("wow", "\uD83D\uDE2E", "Wow", StickerShape.CIRCLE, 0xFF90CAF9.toInt()),
            s("sad", "\uD83D\uDE22", "So sad", StickerShape.ROUNDED, 0xFFB0BEC5.toInt()),
            s("yay", "\uD83D\uDE4C", "Yay!", StickerShape.BURST, 0xFFFFCC80.toInt()),
            s("hmm", "\uD83E\uDD14", "Hmm", StickerShape.CIRCLE, 0xFFD1C4E9.toInt()),
            s("fire", "\uD83D\uDD25", "አሪፍ", StickerShape.ROUNDED, DARK, WHITE),
        ),
    )

    /** The packs in display order. */
    val ALL: List<StickerPack> = listOf(GREETINGS, CELEBRATIONS, REACTIONS)

    fun pack(id: StickerPackId): StickerPack = ALL.first { it.id == id }

    fun find(packId: String, stickerId: String): PackSticker? =
        ALL.firstOrNull { it.id.key == packId }?.stickers?.firstOrNull { it.id == stickerId }

    /** The file name a rendered pack sticker is kept under (versioned: a changed design is rendered again). */
    fun fileName(pack: StickerPackId, stickerId: String): String = "${pack.key}_${stickerId}_v$VERSION${StickerFormat.EXT}"
}
