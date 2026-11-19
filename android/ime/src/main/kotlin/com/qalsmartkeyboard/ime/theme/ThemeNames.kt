package com.qalsmartkeyboard.ime.theme

import android.content.Context
import com.qalsmartkeyboard.ime.R

/**
 * Theme names in the UI language (ADR 0032). [KeyboardTheme.name] stays the English name (ids and tests use it);
 * screens show [name]. A theme without a resource here shows its English name.
 */
object ThemeNames {
    internal val RES: Map<String, Int> = mapOf(
        "classic_light" to R.string.theme_name_classic_light,
        "classic_dark" to R.string.theme_name_classic_dark,
        "minimal_white" to R.string.theme_name_minimal_white,
        "midnight" to R.string.theme_name_midnight,
        "forest_green" to R.string.theme_name_forest_green,
        "ocean_blue" to R.string.theme_name_ocean_blue,
        "lavender" to R.string.theme_name_lavender,
        "soft_pink" to R.string.theme_name_soft_pink,
        "premium_purple" to R.string.theme_name_premium_purple,
        "amoled_black" to R.string.theme_name_amoled_black,
        "emerald" to R.string.theme_name_emerald,
        "sunset" to R.string.theme_name_sunset,
        "aurora" to R.string.theme_name_aurora,
        "elegant_gold" to R.string.theme_name_elegant_gold,
        "modern_gradient" to R.string.theme_name_modern_gradient,
        "graphite" to R.string.theme_name_graphite,
        "desert_sand" to R.string.theme_name_desert_sand,
        "deep_sea" to R.string.theme_name_deep_sea,
        "berry_dusk" to R.string.theme_name_berry_dusk,
        "mint_frost" to R.string.theme_name_mint_frost,
        "soft_grey" to R.string.theme_name_soft_grey,
        "cream" to R.string.theme_name_cream,
        "warm_beige" to R.string.theme_name_warm_beige,
        "sky" to R.string.theme_name_sky,
        "peach" to R.string.theme_name_peach,
        "rose" to R.string.theme_name_rose,
        "ice_blue" to R.string.theme_name_ice_blue,
        "sage" to R.string.theme_name_sage,
        "coral" to R.string.theme_name_coral,
        "tibeb" to R.string.theme_name_tibeb,
        "deep_navy" to R.string.theme_name_deep_navy,
        "slate" to R.string.theme_name_slate,
        "charcoal" to R.string.theme_name_charcoal,
        "teal_night" to R.string.theme_name_teal_night,
        "plum" to R.string.theme_name_plum,
        "cocoa" to R.string.theme_name_cocoa,
        "meskel" to R.string.theme_name_meskel,
        "night_gradient" to R.string.theme_name_night_gradient,
        "sunrise_gradient" to R.string.theme_name_sunrise_gradient,
        "pastel_rainbow" to R.string.theme_name_pastel_rainbow,
    )

    fun name(c: Context, theme: KeyboardTheme): String = RES[theme.id]?.let(c::getString) ?: theme.name
}
