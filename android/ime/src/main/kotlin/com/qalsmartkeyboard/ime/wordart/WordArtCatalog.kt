package com.qalsmartkeyboard.ime.wordart

import com.qalsmartkeyboard.ime.wordart.WordArtCategory.*

/**
 * Every Word Art style, as data. Adding one means adding a line here; nothing else changes (the lists, the previews and the
 * tests read this table).
 *
 * Three families, by what they may touch:
 *  - letter styles (Unicode Mathematical Alphanumerics and friends) and combining overlays rewrite ASCII letters and digits
 *    only: Amharic, Tigrinya (Ge'ez), Arabic and any other script pass through unchanged, and the style is offered as
 *    "Latin letters only" for such text. Combining overlays on Ethiopic were not verified to render, so they stay Latin-only.
 *  - spacing styles work for every script except the joining and complex-shaping ones (Arabic, Indic, Thai, ...).
 *  - compositions (ornament wraps, dividers, brackets, banners) never touch the letters, so they are safe for every script;
 *    right-to-left lines are wrapped in bidi isolates.
 */
internal object WordArtCatalog {
    val all: List<WordArtStyle> by lazy { letterStyles() + compositions() }

    // ------------------------------------------------------------------ letter, mark and spacing styles

    private fun latinSupport(info: ScriptInfo): Support = when {
        !info.hasLatin -> Support.NONE
        info.onlyAsciiLetters && !info.hasEthiopic && info.otherLetters == 0 -> Support.FULL
        else -> Support.PARTIAL
    }

    private fun letter(id: String, label: String, map: LetterMap, popular: Boolean = false) =
        WordArtStyle(id, LETTERS, label, StyleKind.LETTER, popular = popular, altersLetters = true, supportFn = ::latinSupport, fn = { t, _ -> map.map(t) })

    private fun mark(id: String, label: String, mark: Int) =
        WordArtStyle(id, LETTERS, label, StyleKind.MARK, altersLetters = true, supportFn = ::latinSupport, fn = { t, _ -> addMark(t, mark) })

    /** The overlay goes after each ASCII letter or digit; a mark the user's text already carries stays attached to its letter. */
    private fun addMark(text: String, mark: Int): String {
        val sb = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            sb.appendCodePoint(cp)
            val ascii = cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code
            if (ascii) sb.appendCodePoint(mark)
        }
        return sb.toString()
    }

    private fun spacing(id: String, label: String, sep: String, popular: Boolean = false) =
        WordArtStyle(
            id, LETTERS, label, StyleKind.SPACING, popular = popular,
            supportFn = {
                when {
                    it.shapingSensitive -> Support.NONE
                    it.asciiAlnum + it.nonAsciiLetters == 0 -> Support.NONE
                    else -> Support.FULL
                }
            },
            fn = { t, _ -> spaced(t, sep) },
        )

    private fun letterStyles(): List<WordArtStyle> = listOf(
        letter("bold", "Bold", LetterMaps.BOLD, popular = true),
        letter("italic", "Italic", LetterMaps.ITALIC),
        letter("bold_italic", "Bold italic", LetterMaps.BOLD_ITALIC, popular = true),
        letter("script", "Script", LetterMaps.SCRIPT),
        letter("bold_script", "Bold script", LetterMaps.BOLD_SCRIPT, popular = true),
        letter("fraktur", "Fraktur", LetterMaps.FRAKTUR),
        letter("bold_fraktur", "Bold fraktur", LetterMaps.BOLD_FRAKTUR),
        letter("double_struck", "Double-struck", LetterMaps.DOUBLE_STRUCK),
        letter("monospace", "Monospace", LetterMaps.MONOSPACE),
        letter("sans", "Sans-serif", LetterMaps.SANS),
        letter("sans_bold", "Sans-serif bold", LetterMaps.SANS_BOLD),
        letter("sans_italic", "Sans-serif italic", LetterMaps.SANS_ITALIC),
        letter("sans_bold_italic", "Sans-serif bold italic", LetterMaps.SANS_BOLD_ITALIC),
        letter("circled", "Circled", LetterMaps.CIRCLED, popular = true),
        letter("negative_circled", "Black circled", LetterMaps.NEGATIVE_CIRCLED),
        letter("squared", "Squared", LetterMaps.SQUARED),
        letter("negative_squared", "Black squared", LetterMaps.NEGATIVE_SQUARED),
        letter("parenthesized", "Parenthesized", LetterMaps.PARENTHESIZED),
        letter("fullwidth", "Wide", LetterMaps.FULLWIDTH),
        letter("small_caps", "Small capitals", LetterMaps.SMALL_CAPS, popular = true),
        mark("underline", "Underline", LetterMaps.COMBINING_UNDERLINE),
        mark("double_underline", "Double underline", LetterMaps.COMBINING_DOUBLE_UNDERLINE),
        mark("strikethrough", "Strikethrough", LetterMaps.COMBINING_STRIKETHROUGH),
        mark("slash", "Slashed", LetterMaps.COMBINING_SLASH),
        mark("overline", "Overline", LetterMaps.COMBINING_OVERLINE),
        spacing("spaced", "Spaced letters", " ", popular = true),
        spacing("spaced_thin", "Thin spaced letters", " "),
        spacing("spaced_dots", "Dotted letters", "·"),
    )

    // ------------------------------------------------------------------ compositions

    private fun comp(
        id: String, cat: WordArtCategory, label: String, f: Frame,
        occasion: Occasion? = null, popular: Boolean = false,
    ) = WordArtStyle(
        id, cat, label, StyleKind.COMPOSITION, occasion, popular, altersLetters = f.map != null,
        supportFn = { if (f.spacedWith != null && it.shapingSensitive) Support.NONE else Support.FULL },
        fn = f::render,
    )

    /** `sym text sym` */
    private fun wrap(sym: String, map: LetterMap? = null) = Frame(left = "$sym ", right = " $sym", map = map)

    /** The same line above and below the text. */
    private fun banner(line: String, map: LetterMap? = null) = Frame(top = line, bottom = line, map = map)

    private fun compositions(): List<WordArtStyle> = buildList {
        // Popular: works with every script, Ge'ez included.
        add(comp("sparkles", POPULAR, "Sparkles", wrap("✨"), popular = true))
        add(comp("flowers", POPULAR, "Flowers", wrap("✿"), popular = true))
        add(comp("star_wave", POPULAR, "Star wave", Frame("★彡 ", " 彡★"), popular = true))
        add(comp("kawaii", POPULAR, "Petals", Frame("❀•°• ", " •°•❀"), popular = true))
        add(comp("hearts", POPULAR, "Hearts", wrap("♡"), popular = true))
        add(comp("lenticular", POPULAR, "Brackets 【】", Frame("【", "】"), popular = true))
        add(comp("corner_brackets", POPULAR, "Corner brackets 『』", Frame("『", "』")))
        add(comp("tortoise", POPULAR, "Tortoise brackets 〔〕", Frame("〔", "〕")))
        add(comp("soft_frame", POPULAR, "Soft frame", Frame(top = "╭─────✦─────╮", bottom = "╰─────✦─────╯"), popular = true))
        add(comp("double_rule", POPULAR, "Double rule", banner("══════ ❖ ══════")))
        add(comp("star_banner", POPULAR, "Star banner", banner("✦ ✦ ✦ ✦ ✦ ✦ ✦"), popular = true))
        add(comp("geez_ornament", POPULAR, "Ge'ez ornament ፠፨", Frame("፠ ፨ ", " ፨ ፠"), popular = true))
        add(comp("geez_section", POPULAR, "Ge'ez section marks", banner("፨ ፨ ፨ ፨ ፨ ፨")))
        add(comp("geez_stops", POPULAR, "Ge'ez word stops", Frame("፡፡ ", " ፡፡")))

        // Birthday
        add(comp("bday_cake", BIRTHDAY, "Birthday cake", wrap("🎂"), Occasion.BIRTHDAY, popular = true))
        add(comp("bday_party", BIRTHDAY, "Party", Frame("🎉🎂 ", " 🎂🎉", map = LetterMaps.BOLD), Occasion.BIRTHDAY))
        add(comp("bday_balloons", BIRTHDAY, "Balloons", banner("🎈🎈🎈🎈🎈"), Occasion.BIRTHDAY))
        add(comp("bday_gift", BIRTHDAY, "Gift", Frame("🎁✨ ", " ✨🎁", map = LetterMaps.BOLD_SCRIPT), Occasion.BIRTHDAY))
        add(comp("bday_confetti", BIRTHDAY, "Confetti", Frame("·.·´¯`·.·•🎊 ", " 🎊•·.·´¯`·.·"), Occasion.BIRTHDAY))
        add(comp("bday_banner", BIRTHDAY, "Birthday banner", banner("🎈🎉🎂🎉🎈", LetterMaps.BOLD), Occasion.BIRTHDAY))
        add(comp("bday_stars", BIRTHDAY, "Celebration", Frame("🥳 ✧ ", " ✧ 🥳", map = LetterMaps.BOLD_SCRIPT), Occasion.BIRTHDAY))

        // Love
        add(comp("love_hearts", LOVE, "Red hearts", wrap("❤️"), Occasion.LOVE, popular = true))
        add(comp("love_rose", LOVE, "Roses", wrap("🌹"), Occasion.LOVE))
        add(comp("love_cupid", LOVE, "Cupid", wrap("💘"), Occasion.LOVE))
        add(comp("love_infinity", LOVE, "Forever", Frame("♡ ∞ ", " ∞ ♡"), Occasion.LOVE))
        add(comp("love_banner", LOVE, "Love banner", banner("💕 💕 💕 💕 💕", LetterMaps.BOLD_SCRIPT), Occasion.LOVE))
        add(comp("love_swirl", LOVE, "Swirl", wrap("❥", LetterMaps.BOLD_SCRIPT), Occasion.LOVE))
        add(comp("love_kiss", LOVE, "Kisses", Frame("😘💋 ", " 💋😘"), Occasion.LOVE))

        // Wedding
        add(comp("wed_rings", WEDDING, "Rings", wrap("💍"), Occasion.WEDDING, popular = true))
        add(comp("wed_flowers", WEDDING, "Bouquet", wrap("💐"), Occasion.WEDDING))
        add(comp("wed_bells", WEDDING, "Wedding bells", Frame("🔔💒 ", " 💒🔔"), Occasion.WEDDING))
        add(comp("wed_elegant", WEDDING, "Elegant", banner("· · ───── ♡ ───── · ·", LetterMaps.BOLD_SCRIPT), Occasion.WEDDING))
        add(comp("wed_floral", WEDDING, "Floral", wrap("❦", LetterMaps.SCRIPT), Occasion.WEDDING))
        add(comp("wed_dove", WEDDING, "Doves", wrap("🕊️"), Occasion.WEDDING))

        // Congratulations
        add(comp("cong_party", CONGRATS, "Party popper", wrap("🎉"), Occasion.CONGRATS, popular = true))
        add(comp("cong_trophy", CONGRATS, "Trophy", wrap("🏆"), Occasion.CONGRATS))
        add(comp("cong_stars", CONGRATS, "Stars", Frame("⭐🌟 ", " 🌟⭐"), Occasion.CONGRATS))
        add(comp("cong_clap", CONGRATS, "Applause", Frame("👏👏 ", " 👏👏"), Occasion.CONGRATS))
        add(comp("cong_banner", CONGRATS, "Congratulations banner", banner("🎊 🎉 🎊 🎉 🎊", LetterMaps.BOLD), Occasion.CONGRATS))
        add(comp("cong_sparkle", CONGRATS, "Sparkle", Frame("✨🎊 ", " 🎊✨", map = LetterMaps.BOLD_SCRIPT), Occasion.CONGRATS))

        // Thank you
        add(comp("thx_pray", THANKS, "Thanks", wrap("🙏"), Occasion.THANKS, popular = true))
        add(comp("thx_heart", THANKS, "Warm heart", wrap("💛"), Occasion.THANKS))
        add(comp("thx_flowers", THANKS, "Tulips", wrap("🌷"), Occasion.THANKS))
        add(comp("thx_soft", THANKS, "Soft smile", Frame("☺ ✿ ", " ✿ ☺"), Occasion.THANKS))
        add(comp("thx_banner", THANKS, "Thank-you banner", banner("🙏 ♡ 🙏 ♡ 🙏", LetterMaps.BOLD_SCRIPT), Occasion.THANKS))

        // Holidays and festivals: decorative only, never a religious statement
        add(comp("hol_enkutatash", HOLIDAYS, "Enkutatash, Adey Abeba flower", wrap("🌼"), Occasion.NEW_YEAR, popular = true))
        add(comp("hol_enkutatash_geez", HOLIDAYS, "Enkutatash, Ge'ez", Frame("፠ 🌼 ", " 🌼 ፠"), Occasion.NEW_YEAR))
        add(comp("hol_enkutatash_banner", HOLIDAYS, "Enkutatash banner", banner("🌼 🌼 🌼 🌼 🌼", LetterMaps.BOLD), Occasion.NEW_YEAR))
        add(comp("hol_meskel", HOLIDAYS, "Meskel", Frame("🔥✝️ ", " ✝️🔥"), Occasion.MESKEL))
        add(comp("hol_meskel_flames", HOLIDAYS, "Meskel flames", banner("🔥 🔥 🔥 🔥 🔥"), Occasion.MESKEL))
        add(comp("hol_genna", HOLIDAYS, "Genna, Christmas", wrap("🎄"), Occasion.CHRISTMAS))
        add(comp("hol_genna_stars", HOLIDAYS, "Genna stars", Frame("🎄✨ ", " ✨🎄"), Occasion.CHRISTMAS))
        add(comp("hol_timket", HOLIDAYS, "Timket", Frame("💧✝️ ", " ✝️💧"), Occasion.TIMKET))
        add(comp("hol_fasika", HOLIDAYS, "Fasika, Easter", wrap("🕊️"), Occasion.EASTER))
        add(comp("hol_fasika_spring", HOLIDAYS, "Fasika spring", Frame("🌷🕊️ ", " 🕊️🌷"), Occasion.EASTER))
        add(comp("hol_eid", HOLIDAYS, "Eid", wrap("🌙"), Occasion.EID))
        add(comp("hol_eid_lights", HOLIDAYS, "Eid lights", Frame("🌙✨ ", " ✨🌙"), Occasion.EID))
        add(comp("hol_irreecha", HOLIDAYS, "Irreecha", wrap("🌿"), Occasion.IRREECHA))
        add(comp("hol_irreecha_grass", HOLIDAYS, "Irreecha grass", Frame("🌾🌿 ", " 🌿🌾"), Occasion.IRREECHA))
        add(comp("hol_colors", HOLIDAYS, "Green, yellow, red", Frame("💚💛❤️ ", " ❤️💛💚"), Occasion.HOLIDAY))
        add(comp("hol_lights", HOLIDAYS, "Festive lights", Frame("✨🌟 ", " 🌟✨"), Occasion.HOLIDAY))
        add(comp("hol_confetti", HOLIDAYS, "Confetti", wrap("🎊"), Occasion.HOLIDAY))
        add(comp("hol_geez_lines", HOLIDAYS, "Ge'ez festive lines", banner("፨፨፨፨፨፨፨፨፨፨"), Occasion.HOLIDAY))

        // Announcements
        add(comp("ann_megaphone", ANNOUNCEMENTS, "Megaphone", wrap("📢"), Occasion.ANNOUNCEMENT, popular = true))
        add(comp("ann_alert", ANNOUNCEMENTS, "Alert", wrap("‼️"), Occasion.ANNOUNCEMENT))
        add(comp("ann_bar", ANNOUNCEMENTS, "Bars", banner("▬▬▬▬▬▬▬▬▬▬", LetterMaps.BOLD), Occasion.ANNOUNCEMENT))
        add(comp("ann_bell", ANNOUNCEMENTS, "Bell", wrap("🔔"), Occasion.ANNOUNCEMENT))
        add(comp("ann_star", ANNOUNCEMENTS, "Stars", banner("★ ★ ★ ★ ★"), Occasion.ANNOUNCEMENT))
        add(comp("ann_brackets", ANNOUNCEMENTS, "Bold brackets", Frame("【", "】", map = LetterMaps.BOLD), Occasion.ANNOUNCEMENT))
        add(comp("ann_arrows", ANNOUNCEMENTS, "Arrows", Frame("▶ ", " ◀"), Occasion.ANNOUNCEMENT))
        add(comp("ann_geez", ANNOUNCEMENTS, "Ge'ez section mark", wrap("፠"), Occasion.ANNOUNCEMENT))
        add(comp("ann_news", ANNOUNCEMENTS, "News", wrap("📰"), Occasion.ANNOUNCEMENT))

        // Social captions
        add(comp("soc_aesthetic", SOCIAL, "Aesthetic", Frame("✧･ﾟ: *✧･ﾟ:* ", " *:･ﾟ✧*:･ﾟ✧")))
        add(comp("soc_dots", SOCIAL, "Dots", Frame("• • • ", " • • •")))
        add(comp("soc_arrows", SOCIAL, "Chevrons", Frame("»»— ", " —««")))
        add(comp("soc_tilde", SOCIAL, "Tilde", wrap("~•~")))
        add(comp("soc_lines", SOCIAL, "Dashes", banner("┈┈┈┈┈┈┈┈┈┈")))
        add(comp("soc_quote", SOCIAL, "Quote", Frame("❝ ", " ❞")))
        add(comp("soc_underline", SOCIAL, "Underbar", Frame(bottom = "▔▔▔▔▔▔▔▔")))
        add(comp("soc_heartbeat", SOCIAL, "Happy eyes", wrap("♥‿♥")))
        add(
            WordArtStyle(
                "soc_hashtag", SOCIAL, "Hashtag", StyleKind.COMPOSITION,
                supportFn = { Support.FULL },
                fn = { t, _ -> hashtag(t) },
            ),
        )

        // Names
        add(comp("name_crown", NAMES, "Crown", wrap("👑"), popular = true))
        add(comp("name_royal", NAMES, "Royal", wrap("♛")))
        add(comp("name_flower", NAMES, "Blossom", wrap("❀")))
        add(comp("name_signature", NAMES, "Signature", wrap("~", LetterMaps.BOLD_SCRIPT)))
        add(comp("name_badge", NAMES, "Badge", Frame("『 ", " 』", map = LetterMaps.BOLD)))
        add(comp("name_star", NAMES, "Star", wrap("☆")))
        add(comp("name_spaced", NAMES, "Spaced name", Frame("• ", " •", spacedWith = " ")))
        add(comp("name_geez", NAMES, "Ge'ez name ornament", Frame("፠ ", " ፠")))
        add(comp("name_soft", NAMES, "Soft waves", banner("﹏﹏﹏﹏﹏")))
        add(comp("name_diamond", NAMES, "Diamonds", wrap("◆", LetterMaps.BOLD)))
    }

    /** `#` plus the words joined, each Latin word capitalised; punctuation dropped, any script kept. */
    private fun hashtag(text: String): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { w ->
            val sb = StringBuilder()
            var i = 0
            while (i < w.length) {
                val cp = w.codePointAt(i)
                i += Character.charCount(cp)
                val t = Character.getType(cp).toByte()
                val mark = t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK || t == Character.ENCLOSING_MARK
                val joiner = cp == 0x200D || cp == 0x200C
                // A joiner only counts between letters (Ge'ez and Indic text uses them), never at the edge or after an emoji.
                if (Character.isLetterOrDigit(cp) || cp == '_'.code || mark || (joiner && sb.isNotEmpty())) sb.appendCodePoint(cp)
            }
            while (sb.isNotEmpty() && (sb.last().code == 0x200D || sb.last().code == 0x200C)) sb.setLength(sb.length - 1)
            val s = sb.toString()
            if (s.isNotEmpty() && s[0] in 'a'..'z') s[0].uppercaseChar() + s.substring(1) else s
        }.filter { it.isNotEmpty() }
        return if (words.isEmpty()) "" else "#" + words.joinToString("")
    }
}
