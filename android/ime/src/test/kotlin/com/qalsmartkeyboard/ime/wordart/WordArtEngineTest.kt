package com.qalsmartkeyboard.ime.wordart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure style engine: mappings, script handling, grapheme safety. */
class WordArtEngineTest {
    private fun style(id: String) = WordArt.style(id) ?: error("no style $id")
    private fun render(id: String, text: String, o: WordArtOptions = WordArtOptions()) = WordArt.render(style(id), text, o)
    private fun cps(s: String) = s.codePoints().toArray().toList()

    private val ethiopic = "ሰላም ለሁላችሁ"
    private val tigrinya = "ሓጎስ ልደት"
    private val arabic = "مرحبا بكم"
    private val oromo = "Ayyaana Gammachuu"
    private val family = "👨‍👩‍👧"

    // ------------------------------------------------------------------ Latin mappings

    @Test fun boldItalicAndSansAlphabetsAreTheUnicodeBlocks() {
        assertEquals("𝐀𝐚𝟎", LetterMaps.BOLD.map("Aa0"))
        assertEquals("𝐴𝑎", LetterMaps.ITALIC.map("Aa"))
        assertEquals("𝑨𝒂", LetterMaps.BOLD_ITALIC.map("Aa"))
        assertEquals("𝖠𝖺𝟢", LetterMaps.SANS.map("Aa0"))
        assertEquals("𝗔𝗮𝟬", LetterMaps.SANS_BOLD.map("Aa0"))
        assertEquals("𝘈𝘢", LetterMaps.SANS_ITALIC.map("Aa"))
        assertEquals("𝘼𝙖", LetterMaps.SANS_BOLD_ITALIC.map("Aa"))
        assertEquals("𝙰𝚊𝟶", LetterMaps.MONOSPACE.map("Aa0"))
    }

    @Test fun italicHasTheReservedHoleForSmallH() {
        assertEquals("ℎ", LetterMaps.ITALIC.map("h"))
        assertEquals("𝐻", LetterMaps.ITALIC.map("H")) // capital H is in the block
    }

    @Test fun scriptUsesTheLetterlikeExceptions() {
        val caps = mapOf('B' to "ℬ", 'E' to "ℰ", 'F' to "ℱ", 'H' to "ℋ", 'I' to "ℐ", 'L' to "ℒ", 'M' to "ℳ", 'R' to "ℛ")
        for ((c, expected) in caps) assertEquals("script $c", expected, LetterMaps.SCRIPT.map(c.toString()))
        assertEquals("ℯ", LetterMaps.SCRIPT.map("e"))
        assertEquals("ℊ", LetterMaps.SCRIPT.map("g"))
        assertEquals("ℴ", LetterMaps.SCRIPT.map("o"))
        // A letter without an exception is the plain block (A is 1D49C, a is 1D4B6).
        assertEquals("𝒜𝒶", LetterMaps.SCRIPT.map("Aa"))
        // Script has no digits.
        assertEquals("123", LetterMaps.SCRIPT.map("123"))
    }

    @Test fun frakturUsesTheLetterlikeExceptions() {
        val caps = mapOf('C' to "ℭ", 'H' to "ℌ", 'I' to "ℑ", 'R' to "ℜ", 'Z' to "ℨ")
        for ((c, expected) in caps) assertEquals("fraktur $c", expected, LetterMaps.FRAKTUR.map(c.toString()))
        assertEquals("𝔄𝔞", LetterMaps.FRAKTUR.map("Aa"))
    }

    @Test fun doubleStruckUsesTheLetterlikeExceptionsAndHasDigits() {
        val caps = mapOf('C' to "ℂ", 'H' to "ℍ", 'N' to "ℕ", 'P' to "ℙ", 'Q' to "ℚ", 'R' to "ℝ", 'Z' to "ℤ")
        for ((c, expected) in caps) assertEquals("double-struck $c", expected, LetterMaps.DOUBLE_STRUCK.map(c.toString()))
        assertEquals("𝔸𝕒𝟘", LetterMaps.DOUBLE_STRUCK.map("Aa0"))
    }

    @Test fun boldScriptAndBoldFrakturHaveNoHoles() {
        for (c in 'A'..'Z') assertNotEquals(c.toString(), LetterMaps.BOLD_SCRIPT.map(c.toString()))
        // every letter is a distinct supplementary-plane character in the block
        val all = ('A'..'Z').map { LetterMaps.BOLD_SCRIPT[it] } + ('a'..'z').map { LetterMaps.BOLD_SCRIPT[it] }
        assertEquals(52, all.toSet().size)
        assertTrue(all.all { it in 0x1D4D0..0x1D503 })
        val fr = ('A'..'Z').map { LetterMaps.BOLD_FRAKTUR[it] } + ('a'..'z').map { LetterMaps.BOLD_FRAKTUR[it] }
        assertTrue(fr.all { it in 0x1D56C..0x1D59F })
    }

    @Test fun everyMathAlphabetMapsEachLetterToADistinctCharacter() {
        val maps = listOf(
            LetterMaps.BOLD, LetterMaps.ITALIC, LetterMaps.BOLD_ITALIC, LetterMaps.SCRIPT, LetterMaps.BOLD_SCRIPT, LetterMaps.FRAKTUR,
            LetterMaps.BOLD_FRAKTUR, LetterMaps.DOUBLE_STRUCK, LetterMaps.SANS, LetterMaps.SANS_BOLD, LetterMaps.SANS_ITALIC,
            LetterMaps.SANS_BOLD_ITALIC, LetterMaps.MONOSPACE, LetterMaps.CIRCLED, LetterMaps.FULLWIDTH,
        )
        for (m in maps) {
            val image = ('A'..'Z').map { m[it] } + ('a'..'z').map { m[it] }
            assertEquals(52, image.toSet().size)
            assertTrue(image.none { it < 128 })
        }
    }

    @Test fun circledSquaredAndFullwidth() {
        assertEquals("Ⓐⓐ", LetterMaps.CIRCLED.map("Aa"))
        assertEquals("①⑨⓪", LetterMaps.CIRCLED.map("190"))
        assertEquals("🅐🅐", LetterMaps.NEGATIVE_CIRCLED.map("Aa")) // capitals only block: lower case folds up
        assertEquals("❶❾⓿", LetterMaps.NEGATIVE_CIRCLED.map("190"))
        assertEquals("🄰🄰", LetterMaps.SQUARED.map("Aa"))
        assertEquals("🅰🆉", LetterMaps.NEGATIVE_SQUARED.map("Az"))
        assertEquals("⒜⒜⑴", LetterMaps.PARENTHESIZED.map("Aa1"))
        assertEquals("Ａａ０９", LetterMaps.FULLWIDTH.map("Aa09"))
    }

    @Test fun smallCapsKeepsCapitalsAndHasTheBlockLetters() {
        assertEquals("ʜᴇʟʟᴏ", LetterMaps.SMALL_CAPS.map("hello"))
        assertEquals("Hᴇʟʟᴏ", LetterMaps.SMALL_CAPS.map("Hello"))
        assertEquals("ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ", LetterMaps.SMALL_CAPS.map("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test fun nonAsciiPassesThroughEveryMap() {
        val text = "$ethiopic $arabic é 日本 😀 !?"
        for (s in WordArt.styles.filter { it.kind == StyleKind.LETTER }) {
            val out = s.apply(text)
            assertEquals("${s.id} changes only ASCII", text, out) // text has no ASCII letters or digits
        }
        assertEquals("ሰላም 𝐀", LetterMaps.BOLD.map("ሰላም A"))
    }

    @Test fun markStylesAddAnOverlayAfterAsciiOnly() {
        assertEquals("a̲b̲ ሰላም", style("underline").apply("ab ሰላም"))
        assertEquals("a̶ 1̶", style("strikethrough").apply("a 1"))
        assertEquals("a̸", style("slash").apply("a"))
        assertEquals("a̅", style("overline").apply("a"))
        assertEquals("a̳", style("double_underline").apply("a"))
    }

    // ------------------------------------------------------------------ script detection

    @Test fun scriptDetection() {
        assertEquals(Script.LATIN, ScriptInfo.of("Hello 123").primary)
        assertEquals(Script.ETHIOPIC, ScriptInfo.of(ethiopic).primary)
        assertEquals(Script.ETHIOPIC, ScriptInfo.of(tigrinya).primary)
        assertEquals(Script.LATIN, ScriptInfo.of(oromo).primary)
        assertEquals(Script.RTL_JOINING, ScriptInfo.of(arabic).primary)
        assertEquals(Script.RTL, ScriptInfo.of("שלום").primary)
        assertEquals(Script.OTHER, ScriptInfo.of("日本").primary)
        assertEquals(Script.MIXED, ScriptInfo.of("Selam ሰላም").primary)
        assertEquals(Script.EMPTY, ScriptInfo.of("").primary)
        assertEquals(Script.EMPTY, ScriptInfo.of("😀 !").primary)
        assertTrue(ScriptInfo.of(arabic).hasRtl)
        assertTrue(ScriptInfo.of(arabic).hasJoining)
        assertTrue(ScriptInfo.of(arabic).shapingSensitive)
        assertTrue(ScriptInfo.of("हिन्दी").shapingSensitive)
        assertFalse(ScriptInfo.of(ethiopic).shapingSensitive)
        assertFalse(ScriptInfo.of(ethiopic).hasRtl)
        assertTrue(ScriptInfo.of("፠ ፨ ። ፡").hasEthiopic)
    }

    @Test fun supportOfLetterStyles() {
        val bold = style("bold")
        assertEquals(Support.FULL, bold.support(ScriptInfo.of("Hello 2026")))
        assertEquals(Support.NONE, bold.support(ScriptInfo.of(ethiopic)))
        assertEquals(Support.NONE, bold.support(ScriptInfo.of(arabic)))
        assertEquals(Support.PARTIAL, bold.support(ScriptInfo.of("Selam ሰላም")))
        assertEquals(Support.PARTIAL, bold.support(ScriptInfo.of("café")))
        assertEquals(Support.NONE, bold.support(ScriptInfo.EMPTY))
        assertEquals("ሰላም", bold.apply("ሰላም")) // unsupported: unchanged
        assertEquals("Selam \u0000".length, 7.coerceAtMost(7))
        assertEquals("𝐒𝐞𝐥𝐚𝐦 ሰላም", bold.apply("Selam ሰላም"))
    }

    @Test fun ethiopicIsNeverGivenCombiningMarks() {
        for (id in listOf("underline", "double_underline", "strikethrough", "slash", "overline")) {
            assertEquals(Support.NONE, style(id).support(ScriptInfo.of(ethiopic)))
            assertEquals(ethiopic, style(id).apply(ethiopic))
        }
    }

    @Test fun joiningScriptsGetNoMarksNoSpacingAndNoLetterStyles() {
        val info = ScriptInfo.of(arabic)
        for (s in WordArt.styles) {
            val allowed = s.kind == StyleKind.COMPOSITION && !(s.id == "name_spaced")
            if (!allowed) assertEquals("${s.id} must not touch Arabic", Support.NONE, s.support(info))
            else assertEquals(s.id, Support.FULL, s.support(info))
        }
        for (s in WordArt.styles.filter { it.kind != StyleKind.COMPOSITION }) assertEquals(arabic, s.apply(arabic))
    }

    // ------------------------------------------------------------------ compositions

    @Test fun ethiopicTextIsKeptCodePointForCodePointInsideEveryComposition() {
        for (text in listOf(ethiopic, tigrinya, "ሀ ሁ ሂ ሃ ሄ ህ ሆ", "ሰላም\nሓጎስ")) {
            for (s in WordArt.styles.filter { it.kind == StyleKind.COMPOSITION && it.id != "soc_hashtag" && it.id != "name_spaced" }) {
                val out = s.apply(text)
                for (line in text.split("\n")) assertTrue("${s.id}: '$line' kept in '$out'", out.contains(line))
                assertEquals("${s.id} must not add isolates for Ge'ez", -1, out.indexOf(FSI))
            }
        }
    }

    @Test fun framesWrapEachLineAndAddBorders() {
        assertEquals("✨ Selam ✨", style("sparkles").apply("Selam"))
        assertEquals("★彡 Selam 彡★", style("star_wave").apply("Selam"))
        assertEquals("╭─────✦─────╮\nሰላም\n╰─────✦─────╯", style("soft_frame").apply("ሰላም"))
        assertEquals("✨ a ✨\n✨ b ✨", style("sparkles").apply("a\nb"))
        assertEquals("፠ ፨ ሰላም ፨ ፠", style("geez_ornament").apply("ሰላም"))
        assertEquals("【ሰላም】", style("lenticular").apply("ሰላም"))
    }

    @Test fun framesWithLetterAlphabetsRestyleOnlyLatin() {
        val out = style("bday_banner").apply("Abebe ሰላም")
        assertTrue(out.contains("𝐀𝐛𝐞𝐛𝐞 ሰላም"))
        assertTrue(out.startsWith("🎈🎉🎂🎉🎈\n") && out.endsWith("\n🎈🎉🎂🎉🎈"))
    }

    @Test fun rtlLinesAreWrappedInIsolates() {
        val out = style("sparkles").apply(arabic)
        assertEquals("✨ $FSI$arabic$PDI ✨", out)
        assertEquals("【$FSI$arabic$PDI】", style("lenticular").apply(arabic))
        // Hebrew (RTL but not joining) as well; and a mixed line is isolated as a whole.
        assertEquals("♡ ${FSI}שלום$PDI ♡", style("hearts").apply("שלום"))
        // Latin and Ge'ez get none.
        assertFalse(style("sparkles").apply("Selam").contains(FSI))
    }

    @Test fun spacedLettersKeepClustersWhole() {
        assertEquals("S e l a m", style("spaced").apply("Selam"))
        assertEquals("S e l a m   S e l a m", style("spaced").apply("Selam Selam"))
        assertEquals("ሰ ላ ም", style("spaced").apply("ሰላም"))
        // Ethiopic with a combining mark (U+135F) stays one cluster.
        val withMark = "ሰ፟"
        assertEquals("ሰ፟ ላ", style("spaced").apply("$withMark" + "ላ"))
        // Emoji sequences (ZWJ family, flag, skin tone, keycap) are never torn.
        assertEquals("a $family b", style("spaced").apply("a${family}b"))
        assertEquals("a 🇪🇹 b", style("spaced").apply("a🇪🇹b"))
        assertEquals("a 👍🏽 b", style("spaced").apply("a👍🏽b"))
        assertEquals("a 1️⃣ b", style("spaced").apply("a1️⃣b"))
        assertEquals("a b", style("spaced_thin").apply("ab"))
        assertEquals("a·b", style("spaced_dots").apply("ab"))
    }

    @Test fun graphemeSplitting() {
        assertEquals(listOf("a", "b"), Graphemes.split("ab"))
        assertEquals(listOf("é", "x"), Graphemes.split("éx"))
        assertEquals(listOf(family, "a"), Graphemes.split(family + "a"))
        assertEquals(listOf("🇪🇹", "🇺🇸"), Graphemes.split("🇪🇹🇺🇸"))
        assertEquals(listOf("🏴󠁧󠁢󠁥󠁮󠁧󠁿"), Graphemes.split("🏴󠁧󠁢󠁥󠁮󠁧󠁿"))
        assertEquals(emptyList<String>(), Graphemes.split(""))
    }

    @Test fun hashtag() {
        assertEquals("#HappyBirthdayAbebe", style("soc_hashtag").apply("happy birthday, Abebe!"))
        assertEquals("#ሰላምለሁላችሁ", style("soc_hashtag").apply(ethiopic))
        assertEquals("", style("soc_hashtag").apply("  !! "))
    }

    // ------------------------------------------------------------------ safety over every style

    private val samples = listOf(
        "Abebe Kebede", "ሰላም ለሁላችሁ", "ሓጎስ ልደት ፠ ፨ ።", "Ayyaana Dhaloota Gammachuu", arabic, "שלום", "日本語 テスト", "हिन्दी", "Hello ሰላም $arabic",
        "😀 ${family} 🇪🇹 👍🏽", "á é", "ሰ፟", "line one\nline two", "𝐁𝐨𝐥𝐝 ℬ", "12:30", "",
    )

    @Test fun noStyleProducesUnpairedSurrogates() {
        for (s in WordArt.styles) for (t in samples) {
            val out = s.apply(t)
            var i = 0
            while (i < out.length) {
                val c = out[i]
                if (Character.isHighSurrogate(c)) {
                    assertTrue("${s.id} high surrogate at end for '$t'", i + 1 < out.length)
                    assertTrue("${s.id} unpaired high for '$t'", Character.isLowSurrogate(out[i + 1]))
                    i += 2
                } else {
                    assertFalse("${s.id} stray low surrogate for '$t'", Character.isLowSurrogate(c))
                    i++
                }
            }
        }
    }

    @Test fun emojiAndGraphemeClustersSurviveEveryStyle() {
        val text = "ሰላም $family 🇪🇹 👍🏽"
        for (s in WordArt.styles) {
            val out = s.apply(text)
            if (s.support(ScriptInfo.of(text)) == Support.NONE || s.id == "soc_hashtag") continue
            for (cluster in listOf(family, "🇪🇹", "👍🏽")) {
                assertTrue("${s.id} keeps $cluster whole in '$out'", Graphemes.split(out).contains(cluster))
            }
        }
    }

    @Test fun stylesAreFastEnough() {
        val text = "Abebe Kebede ሰላም ለሁላችሁ ".repeat(8)
        val t0 = System.nanoTime()
        repeat(20) { for (s in WordArt.styles) s.apply(text) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("every style 20 times over 200 chars took ${ms}ms", ms < 2000)
    }

    @Test fun emptyTextDoesNotCrashAndCompositionsStayInert() {
        for (s in WordArt.styles) s.apply("")
        assertEquals("", style("soc_hashtag").apply(""))
    }

    // ------------------------------------------------------------------ catalogue

    @Test fun catalogueIsLargeAndConsistent() {
        val styles = WordArt.styles
        assertTrue("at least 40 styles, got ${styles.size}", styles.size >= 40)
        assertEquals("ids are unique", styles.size, styles.map { it.id }.toSet().size)
        for (c in WordArtCategory.entries) assertTrue("category $c has styles", WordArt.stylesIn(c).size >= 3)
        assertTrue(WordArt.stylesIn(WordArtCategory.POPULAR).all { it.popular })
        // Ge'ez users always have something: compositions exist in every category except the letter styles.
        for (c in WordArtCategory.entries.filter { it != WordArtCategory.LETTERS }) {
            assertTrue("$c has a style for Ge'ez", WordArt.stylesIn(c).any { it.support(ScriptInfo.of(ethiopic)) == Support.FULL })
        }
        for (id in listOf("hol_enkutatash", "hol_meskel", "hol_genna", "hol_timket", "hol_fasika", "hol_eid", "hol_irreecha")) assertTrue(id, WordArt.style(id) != null)
        assertTrue(style("hol_enkutatash").apply("x").contains("🌼"))
        assertTrue(style("hol_meskel").apply("x").contains("🔥") && style("hol_meskel").apply("x").contains("✝"))
        assertTrue(style("hol_timket").apply("x").contains("💧"))
        assertTrue(style("hol_eid").apply("x").contains("🌙"))
        assertTrue(style("hol_irreecha").apply("x").contains("🌿"))
        assertTrue(style("hol_genna").apply("x").contains("🎄"))
        assertTrue(style("hol_fasika").apply("x").contains("🕊"))
    }

    // ------------------------------------------------------------------ greetings

    @Test fun greetingPhraseIsAddedInTheChosenLanguage() {
        assertEquals("🎂 መልካም ልደት 🎂\n🎂 ሰላም 🎂", render("bday_cake", "ሰላም", WordArtOptions("am", true)).text)
        assertEquals("🎂 Happy Birthday 🎂\n🎂 Abebe 🎂", render("bday_cake", "Abebe", WordArtOptions("en", true)).text)
        assertEquals("🎂 Guyyaa Dhaloota Gammachuu 🎂\n🎂 Caalaa 🎂", render("bday_cake", "Caalaa", WordArtOptions("om", true)).text)
        assertEquals("🎂 ሰናይ ልደት 🎂\n🎂 ሰላም 🎂", render("bday_cake", "ሰላም", WordArtOptions("ti", true)).text)
        // Greeting alone (no name typed) is allowed; an unknown language falls back to English.
        assertEquals("🎂 Happy Birthday 🎂", render("bday_cake", "", WordArtOptions("fr", true)).text)
    }

    @Test fun greetingIsOffByDefaultAndOnlyForStylesWithAnOccasion() {
        assertEquals("🎂 Abebe 🎂", render("bday_cake", "Abebe").text)
        assertEquals("✨ Abebe ✨", render("sparkles", "Abebe", WordArtOptions("en", true)).text)
        assertEquals("𝐀𝐛𝐞𝐛𝐞", render("bold", "Abebe", WordArtOptions("en", true)).text)
    }

    @Test fun greetingLetterAlphabetStylesTheLatinPhraseButNotTheGeezOne() {
        val en = render("bday_banner", "Abebe", WordArtOptions("en", true)).text
        assertTrue(en.contains("𝐇𝐚𝐩𝐩𝐲 𝐁𝐢𝐫𝐭𝐡𝐝𝐚𝐲"))
        val am = render("bday_banner", "ሰላም", WordArtOptions("am", true)).text
        assertTrue(am.contains("መልካም ልደት\nሰላም"))
    }

    @Test fun everyOccasionHasAPhraseInEveryLanguageMarkedDraftExceptEnglish() {
        for (o in Occasion.entries) for (l in Greetings.LANGUAGES) {
            val g = Greetings.phrase(o, l) ?: error("no phrase for $o/$l")
            assertTrue(g.text.isNotBlank())
            assertEquals("$o/$l", l != "en", g.draft)
        }
        assertEquals(true, WordArt.phraseIsDraft(style("bday_cake"), WordArtOptions("am", true)))
        assertEquals(false, WordArt.phraseIsDraft(style("bday_cake"), WordArtOptions("en", true)))
        assertEquals(false, WordArt.phraseIsDraft(style("bday_cake"), WordArtOptions("am", false)))
    }

    @Test fun renderOfUnsupportedStyleReturnsTheBodyAndNone() {
        val r = render("bold", ethiopic)
        assertEquals(Support.NONE, r.support)
        assertEquals(ethiopic, r.text)
        assertEquals(ethiopic, r.plain)
    }

    @Test fun samplesExistInEveryLanguage() {
        for (l in Greetings.LANGUAGES) assertTrue(WordArt.sample(l).isNotBlank())
        assertEquals(Script.ETHIOPIC, ScriptInfo.of(WordArt.sample("am")).primary)
        assertEquals(Script.LATIN, ScriptInfo.of(WordArt.sample("om")).primary)
    }
}
