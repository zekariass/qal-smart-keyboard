package com.qalsmartkeyboard.app.creative.cards

import androidx.annotation.StringRes
import com.qalsmartkeyboard.app.R

/** The text a template starts with (string resources, so it follows the app language). [from] 0: no starting sender line. */
data class CardSample(@StringRes val headline: Int, @StringRes val body: Int, @StringRes val from: Int = 0)

/**
 * One bundled layout (T8.7). Authored in code as data: the shapes are drawn by [CardRenderer], the colours are plain
 * numbers, the text families are the system's; no third-party image or font is used (N10). [premium] templates need the
 * `CARD_MAKER` entitlement; the free ones work for everyone, offline.
 */
data class CardTemplate(
    val id: String,
    @StringRes val nameRes: Int,
    val premium: Boolean,
    val sample: CardSample,
    val background: CardBackground,
    val text: CardTextStyle,
    val frame: CardFrame,
    val layout: TemplateLayout,
    /** The colour of the decoration ([TemplateLayout.decor]) and the frame. */
    val accent: Int,
) {
    /** A new card from this template, with its starting texts resolved by [resolve] (a string resource to its text). */
    fun newDoc(resolve: (Int) -> String, aspect: CardAspect = CardAspect.SQUARE, format: ExportFormat = ExportFormat.PNG) = CardDoc(
        templateId = id,
        headline = resolve(sample.headline),
        body = resolve(sample.body),
        from = if (sample.from != 0) resolve(sample.from) else "",
        background = background,
        text = text,
        frame = frame,
        aspect = aspect,
        format = format,
    )
}

private fun argb(rgb: Long) = (0xFF000000L or rgb).toInt()

/** The colours the editor offers. Premium gradients are part of `CARD_MAKER`; everything else is free. */
object CardPalette {
    val solids: List<Int> = listOf(
        0xFFFFFFL, 0xFFF8E1L, 0xECEFF1L, 0x121212L, 0x1A237EL, 0x00796BL, 0x2E7D32L,
        0xF9A825L, 0xEF6C00L, 0xC62828L, 0xD81B60L, 0x6A1B9AL, 0x0288D1L, 0x4E342EL,
    ).map(::argb)

    val textColors: List<Int> = listOf(
        0x212121L, 0xFFFFFFL, 0xFFF8E1L, 0xF9A825L, 0xC62828L, 0x1A237EL, 0x00796BL, 0x2E7D32L, 0xAD1457L, 0x6A1B9AL,
    ).map(::argb)

    class GradientPreset(val id: String, val premium: Boolean, val colors: List<Int>, val angleDeg: Int) {
        fun toBackground() = CardBackground.Gradient(id, colors, angleDeg)
    }

    private fun g(id: String, premium: Boolean, angle: Int, vararg rgb: Long) = GradientPreset(id, premium, rgb.map(::argb), angle)

    val gradients: List<GradientPreset> = listOf(
        g("sunset", false, 135, 0xFF9A8BL, 0xFF6A88L, 0xFF99ACL),
        g("ocean", false, 160, 0x2193B0L, 0x6DD5EDL),
        g("forest", false, 135, 0x134E5EL, 0x71B280L),
        g("berry", false, 135, 0x8E2DE2L, 0xD81B60L),
        g("dawn", false, 90, 0xF6D365L, 0xFDA085L),
        g("sky", false, 100, 0x0288D1L, 0x4FC3F7L),
        g("fire", false, 45, 0xEF6C00L, 0xC62828L),
        g("mint", false, 135, 0x11998EL, 0x38EF7DL),
        g("gold", true, 135, 0xFFD54FL, 0xF9A825L, 0xE65100L),
        g("royal", true, 135, 0x1A237EL, 0x4A148CL),
        g("aurora", true, 135, 0x00796BL, 0x3949ABL, 0x6A1B9AL),
        g("rose_gold", true, 135, 0xF4C4A8L, 0xE08E9AL),
        g("night", true, 180, 0x0D1B2AL, 0x1B263BL, 0x415A77L),
        g("tricolour", true, 0, 0x2E7D32L, 0xF9A825L, 0xC62828L),
    )

    fun gradient(id: String): GradientPreset? = gradients.firstOrNull { it.id == id }

    val patterns: List<CardBackground.Pattern> = listOf(
        CardBackground.Pattern(PatternKind.DOTS, argb(0xFFF8E1), argb(0xF9A825)),
        CardBackground.Pattern(PatternKind.DOTS, argb(0x1A237E), argb(0x5C6BC0)),
        CardBackground.Pattern(PatternKind.STRIPES, argb(0xE3F2FD), argb(0x90CAF9)),
        CardBackground.Pattern(PatternKind.STRIPES, argb(0x00796B), argb(0x26A69A)),
        CardBackground.Pattern(PatternKind.CHECKS, argb(0xFFFFFF), argb(0xECEFF1)),
        CardBackground.Pattern(PatternKind.CHECKS, argb(0x4E342E), argb(0x6D4C41)),
        CardBackground.Pattern(PatternKind.WAVES, argb(0x0288D1), argb(0x4FC3F7)),
        CardBackground.Pattern(PatternKind.WAVES, argb(0xFFF8E1), argb(0xFFE0B2)),
        CardBackground.Pattern(PatternKind.CONFETTI, argb(0xFFFFFF), argb(0xEF6C00)),
        CardBackground.Pattern(PatternKind.CONFETTI, argb(0x6A1B9A), argb(0xF9A825)),
    )
}

/** The bundled templates: eight free, eight premium. The order is the order of the picker. */
object CardTemplates {
    private val greeting = TemplateLayout(
        headline = TextSlot(0.12f, 0.40f, 0.125f, 0.05f, bold = true),
        body = TextSlot(0.42f, 0.78f, 0.062f, 0.03f),
        from = TextSlot(0.82f, 0.92f, 0.045f, 0.025f, italic = true),
    )
    private val quote = TemplateLayout(
        headline = TextSlot(0.07f, 0.17f, 0.05f, 0.025f, bold = true),
        body = TextSlot(0.20f, 0.76f, 0.085f, 0.032f, italic = true, lineSpacing = 1.2f),
        from = TextSlot(0.80f, 0.92f, 0.05f, 0.025f),
        marginX = 0.12f,
    )
    private val banner = TemplateLayout(
        headline = TextSlot(0.16f, 0.42f, 0.14f, 0.05f, bold = true),
        body = TextSlot(0.45f, 0.80f, 0.06f, 0.03f),
        from = TextSlot(0.84f, 0.93f, 0.045f, 0.025f, italic = true),
    )

    private fun s(h: Int, b: Int, f: Int = 0) = CardSample(h, b, f)
    private val birthday = s(R.string.card_s_birthday_h, R.string.card_s_birthday_b, R.string.card_s_from)
    private val congrats = s(R.string.card_s_congrats_h, R.string.card_s_congrats_b, R.string.card_s_from)
    private val thanks = s(R.string.card_s_thanks_h, R.string.card_s_thanks_b, R.string.card_s_from)
    private val holiday = s(R.string.card_s_holiday_h, R.string.card_s_holiday_b, R.string.card_s_from)
    private val quoteSample = s(R.string.card_s_quote_h, R.string.card_s_quote_b, R.string.card_s_quote_from)
    private val morning = s(R.string.card_s_morning_h, R.string.card_s_morning_b)
    private val sympathy = s(R.string.card_s_sympathy_h, R.string.card_s_sympathy_b, R.string.card_s_from)
    private val plain = s(R.string.card_s_plain_h, R.string.card_s_plain_b)
    private val wedding = s(R.string.card_s_wedding_h, R.string.card_s_wedding_b, R.string.card_s_from)
    private val love = s(R.string.card_s_love_h, R.string.card_s_love_b, R.string.card_s_from)
    private val night = s(R.string.card_s_night_h, R.string.card_s_night_b)

    private fun grad(id: String) = CardPalette.gradient(id)!!.toBackground()
    private fun solid(rgb: Long) = CardBackground.Solid(argb(rgb))
    private fun text(rgb: Long, bold: Boolean = false, font: CardFont = CardFont.SANS, shadow: Boolean = false) =
        CardTextStyle(font = font, color = argb(rgb), bold = bold, shadow = shadow)

    private fun t(
        id: String, name: Int, premium: Boolean, sample: CardSample, bg: CardBackground, style: CardTextStyle,
        frame: CardFrame, layout: TemplateLayout, decor: CardDecor, accent: Long,
    ) = CardTemplate(id, name, premium, sample, bg, style, frame, layout.copy(decor = decor), argb(accent))

    val all: List<CardTemplate> = listOf(
        // Free.
        t("birthday", R.string.card_t_birthday, false, birthday, grad("sunset"), text(0xFFFFFF, shadow = true), CardFrame.NONE, greeting, CardDecor.CONFETTI, 0xFFFFFF),
        t("congrats", R.string.card_t_congrats, false, congrats, solid(0x1A237E), text(0xFFF8E1), CardFrame.THIN, greeting, CardDecor.STARS, 0xF9A825),
        t("thanks", R.string.card_t_thanks, false, thanks, solid(0xFFF8E1), text(0x00796B, font = CardFont.SERIF), CardFrame.THIN, greeting, CardDecor.DOTS_ARC, 0x00796B),
        t("holiday", R.string.card_t_holiday, false, holiday, grad("dawn"), text(0xFFFFFF, shadow = true), CardFrame.NONE, greeting, CardDecor.SUN, 0xFFFFFF),
        t("quote", R.string.card_t_quote, false, quoteSample, solid(0x121212), text(0xFFF8E1, font = CardFont.SERIF), CardFrame.NONE, quote, CardDecor.QUOTE_MARKS, 0xF9A825),
        t("morning", R.string.card_t_morning, false, morning, grad("sky"), text(0xFFFFFF, shadow = true), CardFrame.NONE, greeting, CardDecor.SUN, 0xFFF8E1),
        t("sympathy", R.string.card_t_sympathy, false, sympathy, solid(0xECEFF1), text(0x212121, font = CardFont.SERIF), CardFrame.THIN, greeting, CardDecor.NONE, 0x90A4AE),
        t("plain", R.string.card_t_plain, false, plain, solid(0xFFFFFF), text(0x212121), CardFrame.NONE, greeting, CardDecor.NONE, 0x212121),
        // Premium (`CARD_MAKER`).
        t("gold_birthday", R.string.card_t_gold_birthday, true, birthday, grad("gold"), text(0x1A237E, bold = true), CardFrame.DOUBLE, greeting, CardDecor.CONFETTI, 0x1A237E),
        t("wedding", R.string.card_t_wedding, true, wedding, grad("rose_gold"), text(0xFFFFFF, font = CardFont.SERIF, shadow = true), CardFrame.CORNERS, greeting, CardDecor.HEARTS, 0xFFFFFF),
        t("love", R.string.card_t_love, true, love, grad("berry"), text(0xFFFFFF, font = CardFont.CURSIVE), CardFrame.ROUNDED, greeting, CardDecor.HEARTS, 0xFFF8E1),
        t("sunrise", R.string.card_t_sunrise, true, holiday, grad("fire"), text(0xFFFFFF, bold = true, shadow = true), CardFrame.DOTTED, greeting, CardDecor.SUN, 0xFFD54F),
        t("royal_quote", R.string.card_t_royal_quote, true, quoteSample, grad("royal"), text(0xFFD54F, font = CardFont.SERIF), CardFrame.DOUBLE, quote, CardDecor.QUOTE_MARKS, 0xFFD54F),
        t("night_stars", R.string.card_t_night_stars, true, night, grad("night"), text(0xFFFFFF, font = CardFont.SANS_LIGHT), CardFrame.CORNERS, greeting, CardDecor.STARS, 0xFFD54F),
        t("tricolour", R.string.card_t_tricolour, true, congrats, grad("tricolour"), text(0xFFFFFF, bold = true, shadow = true), CardFrame.THIN, banner, CardDecor.RIBBON, 0xFFFFFF),
        t("elegant_thanks", R.string.card_t_elegant_thanks, true, thanks, grad("aurora"), text(0xFFF8E1, font = CardFont.SERIF), CardFrame.ROUNDED, greeting, CardDecor.DOTS_ARC, 0xFFF8E1),
    )

    val free: List<CardTemplate> get() = all.filter { !it.premium }
    val premium: List<CardTemplate> get() = all.filter { it.premium }

    fun find(id: String?): CardTemplate? = all.firstOrNull { it.id == id }

    /** The template new cards start from. */
    val default: CardTemplate get() = all.first()
}
