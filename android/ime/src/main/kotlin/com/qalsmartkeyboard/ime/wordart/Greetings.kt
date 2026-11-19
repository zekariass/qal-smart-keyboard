package com.qalsmartkeyboard.ime.wordart

/** The occasions a Word Art style can greet; a style with an occasion can add the built-in greeting phrase. */
enum class Occasion {
    BIRTHDAY, LOVE, WEDDING, CONGRATS, THANKS, HOLIDAY, NEW_YEAR, CHRISTMAS, EASTER, MESKEL, TIMKET, EID, IRREECHA, ANNOUNCEMENT,
}

/** One greeting line in one language. [draft] phrases still wait for the native-speaker language panel (rule N9). */
class Greeting(val text: String, val draft: Boolean)

/**
 * Built-in greeting phrases (am, ti, om, en) as a plain data table.
 *
 * **Every non-English phrase is DRAFT (rule N9)**: written by the team's tooling, not yet reviewed by native speakers of
 * the language panel. They are short, neutral and non-sectarian. The table is the only place the phrases live, so the panel
 * can correct a line (or replace the table with a pack file) without touching the engine or the UI. The UI marks a
 * draft phrase in the style card ("draft wording").
 *
 * Specific doubts for the panel: Tigrinya birthday/holiday wording (`ሰናይ` vs `ሓጎስ`/`ብሩኽ`), Afaan Oromoo `Ayyaana Ciccimoo`
 * (Easter) and `Irreechaa`, `Timkataa`, `Masqalaa` spellings, and gender/number forms of the Amharic congratulations.
 */
object Greetings {
    private fun p(am: String, ti: String, om: String, en: String) = mapOf("am" to am, "ti" to ti, "om" to om, "en" to en)

    // DRAFT: every non-English entry (N9), including the Ge'ez ones.
    private val table: Map<Occasion, Map<String, String>> = mapOf(
        Occasion.BIRTHDAY to p("መልካም ልደት", "ሰናይ ልደት", "Guyyaa Dhaloota Gammachuu", "Happy Birthday"),
        Occasion.LOVE to p("በፍቅር", "ብፍቕሪ", "Jaalalaan", "With Love"),
        Occasion.WEDDING to p("መልካም ሠርግ", "ሰናይ መርዓ", "Gammachuu Cidhaa", "Happy Wedding"),
        Occasion.CONGRATS to p("እንኳን ደስ አላችሁ", "እንቋዕ ሓጎሰኩም", "Baga Gammaddan", "Congratulations"),
        Occasion.THANKS to p("አመሰግናለሁ", "የቐንየለይ", "Galatoomaa", "Thank You"),
        Occasion.HOLIDAY to p("መልካም በዓል", "ሰናይ በዓል", "Ayyaana Gammachuu", "Happy Holidays"),
        Occasion.NEW_YEAR to p("መልካም አዲስ ዓመት", "ሰናይ ሓዱሽ ዓመት", "Baga Bara Haaraan Isin Ga'e", "Happy New Year"),
        Occasion.CHRISTMAS to p("መልካም ገና", "ብሩኽ በዓል ልደት", "Ayyaana Dhalootaa Gammachuu", "Merry Christmas"),
        Occasion.EASTER to p("መልካም ፋሲካ", "ሰናይ ትንሳኤ", "Ayyaana Ciccimoo Gammachuu", "Happy Easter"),
        Occasion.MESKEL to p("መልካም መስቀል", "ሰናይ መስቀል", "Ayyaana Masqalaa Gammachuu", "Happy Meskel"),
        Occasion.TIMKET to p("መልካም ጥምቀት", "ሰናይ ጥምቀት", "Ayyaana Timkataa Gammachuu", "Happy Timket"),
        Occasion.EID to p("ኢድ ሙባረክ", "ዒድ ሙባረክ", "Iid Mubaarak", "Eid Mubarak"),
        Occasion.IRREECHA to p("መልካም ኢሬቻ", "ሰናይ ኢሬቻ", "Ayyaana Irreechaa Gammachuu", "Happy Irreecha"),
        Occasion.ANNOUNCEMENT to p("ማስታወቂያ", "ምልክታ", "Beeksisa", "Announcement"),
    )

    /** The phrase for [occasion] in [language] (am, ti, om, en; anything else falls back to English). */
    fun phrase(occasion: Occasion, language: String): Greeting? {
        val row = table[occasion] ?: return null
        val lang = if (language in LANGUAGES) language else "en"
        val text = row[lang] ?: return null
        return Greeting(text, draft = lang != "en")
    }

    val LANGUAGES = listOf("am", "ti", "om", "en")

    /** The grey placeholder text shown in the previews while the user has typed nothing (a name, as Word Art is mostly names). DRAFT outside English. */
    fun sample(language: String): String = when (language) {
        "am" -> "ሰላም"
        "ti" -> "ሰላም"
        "om" -> "Caalaa"
        else -> "Your Name"
    }
}
