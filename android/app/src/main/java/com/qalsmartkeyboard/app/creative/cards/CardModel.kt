package com.qalsmartkeyboard.app.creative.cards

/**
 * The quote and greeting card's data model (T8.7): everything a card is, as plain Kotlin (no Android types), so the layout
 * maths, the gate and the saved-card JSON are JVM-tested. The renderer ([CardRenderer]) and the editor only read it.
 */

/** The fixed export sizes: the longest side is always 1080 or 1350 pixels, whatever the phone's screen is. */
enum class CardAspect(val id: String, val width: Int, val height: Int) {
    SQUARE("square", 1080, 1080),
    PORTRAIT("portrait", 1080, 1350);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SQUARE
    }
}

/** The image file the user chooses to export: PNG (the default, lossless) or JPEG (smaller). */
enum class ExportFormat(val id: String, val mime: String, val extension: String) {
    PNG("png", "image/png", "png"),
    JPEG("jpeg", "image/jpeg", "jpg");

    companion object {
        const val JPEG_QUALITY = 92

        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: PNG
    }
}

enum class CardTextAlign(val id: String) {
    START("start"), CENTER("center"), END("end");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: CENTER
    }
}

/**
 * The system font families the card text can use (the platform's own: [family] is an Android `Typeface` family name; no
 * font files are bundled, N10). Ge'ez/Fidel text always falls back to the system's Ethiopic font, so every choice renders
 * Amharic and Tigrinya; the family changes the Latin letters (Afaan Oromoo, English) and, where the system supplies it,
 * the weight and slant of the Fidel.
 */
enum class CardFont(val id: String, val family: String) {
    SANS("sans", "sans-serif"),
    SANS_LIGHT("sans_light", "sans-serif-light"),
    CONDENSED("condensed", "sans-serif-condensed"),
    SERIF("serif", "serif"),
    CURSIVE("cursive", "cursive"),
    CASUAL("casual", "casual"),
    MONO("mono", "monospace");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SANS
    }
}

/** How the text looks, for the whole card. [sizeScale] scales the largest size the text may take (it still has to fit its box). */
data class CardTextStyle(
    val font: CardFont = CardFont.SANS,
    val sizeScale: Float = 1f,
    val color: Int = 0xFF212121.toInt(),
    val align: CardTextAlign = CardTextAlign.CENTER,
    val bold: Boolean = false,
    val shadow: Boolean = false,
) {
    init {
        require(sizeScale in MIN_SCALE..MAX_SCALE) { "sizeScale $sizeScale" }
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 1.5f
    }
}

enum class PatternKind(val id: String) {
    DOTS("dots"), STRIPES("stripes"), CHECKS("checks"), WAVES("waves"), CONFETTI("confetti");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: DOTS
    }
}

/** What fills the card behind the text. Colours are ARGB ints. */
sealed interface CardBackground {
    data class Solid(val color: Int) : CardBackground

    /** [presetId] names the [CardPalette] gradient the colours came from (it decides whether the premium gate applies). */
    data class Gradient(val presetId: String, val colors: List<Int>, val angleDeg: Int) : CardBackground {
        init {
            require(colors.size in 2..3) { "a gradient has two or three colours" }
        }
    }

    data class Pattern(val kind: PatternKind, val base: Int, val accent: Int) : CardBackground

    /**
     * A picture the user picked from the device. [file] is its downscaled copy next to the saved card (null while it is
     * only in memory, before the first save); [dimPercent] darkens it so the text reads (0 to 70).
     */
    data class Photo(val file: String?, val dimPercent: Int = 30) : CardBackground {
        init {
            require(dimPercent in 0..MAX_DIM) { "dimPercent $dimPercent" }
        }
    }

    companion object {
        const val MAX_DIM = 70
    }
}

/** A border drawn inside the card. [THIN] is free; the others are premium extras (`CARD_MAKER`). */
enum class CardFrame(val id: String, val premium: Boolean) {
    NONE("none", false),
    THIN("thin", false),
    DOUBLE("double", true),
    ROUNDED("rounded", true),
    CORNERS("corners", true),
    DOTTED("dotted", true);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** One card: the template it started from, the texts and every option. The editable source of a saved card. */
data class CardDoc(
    val templateId: String,
    val headline: String = "",
    val body: String = "",
    val from: String = "",
    val background: CardBackground,
    val text: CardTextStyle = CardTextStyle(),
    val frame: CardFrame = CardFrame.NONE,
    val aspect: CardAspect = CardAspect.SQUARE,
    val format: ExportFormat = ExportFormat.PNG,
) {
    /** Nothing typed in any field. */
    val isBlank: Boolean get() = headline.isBlank() && body.isBlank() && from.isBlank()

    companion object {
        /** A field is cut to this many characters (keeps the card, the JSON and the layout bounded). */
        const val MAX_HEADLINE = 80
        const val MAX_BODY = 600
        const val MAX_FROM = 80
    }
}
