package com.qalsmartkeyboard.ime.emoji

import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The panel's categories in tab order (Gboard / Samsung convention). [group] is the Unicode `emoji-test.txt`
 * group the category shows (null for [RECENT]); [glyph] is the emoji drawn on its tab.
 */
enum class EmojiCategory(val group: String?, val glyph: String) {
    RECENT(null, ""),
    SMILEYS("Smileys & Emotion", "😀"),
    PEOPLE("People & Body", "👋"),
    ANIMALS("Animals & Nature", "🐻"),
    FOOD("Food & Drink", "🍔"),
    TRAVEL("Travel & Places", "🚗"),
    ACTIVITIES("Activities", "⚽"),
    OBJECTS("Objects", "💡"),
    SYMBOLS("Symbols", "🔣"),
    FLAGS("Flags", "🏁");

    companion object {
        fun fromGroup(name: String): EmojiCategory? = entries.firstOrNull { it.group == name }
    }
}

/**
 * One emoji of the panel. [text] is the fully-qualified sequence (ZWJ sequences and flags included);
 * [variants] are its five uniform skin-tone forms, light to dark, or empty. [index] is the position in the
 * bundled data (the localised annotation files use it).
 */
class EmojiEntry(
    val text: String,
    val name: String,
    val category: EmojiCategory,
    val variants: List<String>,
    val keywords: List<String>,
    val index: Int,
) {
    val hasTones: Boolean get() = variants.isNotEmpty()
    override fun toString() = "EmojiEntry($text)"
}

/** What a grid cell shows and inserts: [text] is [entry]'s base form or a skin-tone form of it. */
class EmojiCell(val text: String, val entry: EmojiEntry)

/** Localised names and keywords of one language, indexed by [EmojiEntry.index]. */
class EmojiAnnotations(val names: List<String>, val keywords: List<List<String>>) {
    /** Lower-cased search terms per entry index: the name first, then the keywords. */
    val terms: List<List<String>> = names.indices.map { i ->
        buildList {
            val n = names[i]
            if (n.isNotEmpty()) add(n.lowercase())
            for (k in keywords[i]) if (k.isNotEmpty()) add(k.lowercase())
        }
    }
}

/**
 * The emoji the device can draw, in panel order. Built off the main thread on the panel's first open
 * ([EmojiLoader]); never part of the keyboard's start-up path.
 */
class EmojiCatalog(val entries: List<EmojiEntry>, englishTerms: List<List<String>>) {
    private val englishByIndex = HashMap<Int, List<String>>().also { m -> entries.forEachIndexed { i, e -> m[e.index] = englishTerms[i] } }
    private val byCategory: Map<EmojiCategory, List<EmojiEntry>> = entries.groupBy { it.category }
    private val byText = HashMap<String, EmojiEntry>().also { m ->
        for (e in entries) {
            m[e.text] = e
            for (v in e.variants) m[v] = e
        }
    }
    private val annotations = ConcurrentHashMap<String, EmojiAnnotations>()

    fun inCategory(c: EmojiCategory): List<EmojiEntry> = byCategory[c].orEmpty()

    /** The entry for [text], which may be one of its skin-tone variants. */
    fun entryFor(text: String): EmojiEntry? = byText[text]

    /** Lower-cased English terms (name, keywords) of [e]. */
    fun englishTerms(e: EmojiEntry): List<String> = englishByIndex[e.index].orEmpty()

    fun hasLanguage(lang: String) = annotations.containsKey(lang)

    fun putLanguage(lang: String, a: EmojiAnnotations) {
        annotations[lang] = a
    }

    fun language(lang: String): EmojiAnnotations? = annotations[lang]
}

/** Where the bundled data is read from (the APK's assets; a test supplies strings). */
fun interface EmojiSource {
    fun open(name: String): InputStream?
}

/** Remembers which emoji the device font cannot draw, so the glyph checks run once per system build. */
interface GlyphCache {
    fun load(): Set<String>?
    fun save(unsupported: Set<String>)

    object None : GlyphCache {
        override fun load(): Set<String>? = null
        override fun save(unsupported: Set<String>) {}
    }
}

/** Parses the generated assets (`android/ime/scripts/build_emoji_data.py`) and filters out emoji the device cannot draw. */
class EmojiLoader(
    private val source: EmojiSource,
    /** True when the device can draw the sequence as one glyph (`Paint.hasGlyph`); runs on the loading thread. */
    private val supported: (String) -> Boolean = { true },
    private val cache: GlyphCache = GlyphCache.None,
) {
    private class Raw(val text: String, val name: String, val category: EmojiCategory, val variants: List<String>, val keywords: List<String>, val index: Int)

    fun load(): EmojiCatalog {
        val raw = ArrayList<Raw>()
        val stream = source.open("emoji.tsv") ?: return EmojiCatalog(emptyList(), emptyList())
        var category: EmojiCategory? = null
        var index = 0
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("# ")) continue // (a line starting with # alone is the keycap emoji)
                if (line[0] == '@') {
                    category = EmojiCategory.fromGroup(line.substring(1).trimEnd('\r'))
                    continue
                }
                val f = line.trimEnd('\r').split('\t')
                if (f.size < 2) continue
                val c = category
                if (c != null) {
                    raw += Raw(
                        f[0], f[1], c,
                        f.getOrNull(3).orEmpty().split(' ').filter { it.isNotEmpty() },
                        f.getOrNull(2).orEmpty().split('|').filter { it.isNotEmpty() },
                        index,
                    )
                }
                index++
            }
        }
        val missing = cache.load() ?: raw.filter { !supported(it.text) }.mapTo(HashSet()) { it.text }.also { cache.save(it) }
        val kept = raw.filter { it.text !in missing }
        val entries = kept.map { EmojiEntry(it.text, it.name, it.category, it.variants, it.keywords, it.index) }
        val terms = kept.map { r -> (listOf(r.name) + r.keywords).filter { it.isNotEmpty() }.map { it.lowercase() } }
        return EmojiCatalog(entries, terms)
    }

    /** Loads the annotations of [lang] (am, ti, om); null when the language has no file. */
    fun loadLanguage(lang: String): EmojiAnnotations? {
        val stream = source.open("emoji-$lang.tsv") ?: return null
        val names = ArrayList<String>()
        val kws = ArrayList<List<String>>()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("# ")) continue // (a line starting with # alone is the keycap emoji)
                val f = line.trimEnd('\r').split('\t')
                names += f[0]
                kws += f.getOrNull(1).orEmpty().split('|').filter { it.isNotEmpty() }
            }
        }
        return EmojiAnnotations(names, kws)
    }
}
