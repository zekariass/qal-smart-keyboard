package com.qalsmartkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ADR 0032: every translation (`values-*`) of the app's and the keyboard's strings matches the English default: the same
 * translatable keys, the same format arguments per key, no unescaped apostrophe or double quote (aapt drops or rejects
 * them), no stray `%`, and every plurals key with the CLDR categories am, ti and om need (one, other).
 */
class StringResourcesTest {
    private class Entry(val raw: String, val translatable: Boolean, val plurals: Map<String, String>?)

    private val modules = listOf(File("src/main/res"), File("../ime/src/main/res"))

    private val stringRe = Regex("""<string\s+name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val pluralsRe = Regex("""<plurals\s+name="([^"]+)"([^>]*)>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
    private val itemRe = Regex("""<item\s+quantity="([a-z]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
    private val argRe = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc]|%%|%n""")
    private val cdataRe = Regex("""<!\[CDATA\[.*?]]>""", RegexOption.DOT_MATCHES_ALL)

    private fun parse(f: File): Map<String, Entry> {
        val text = f.readText()
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f) // well-formed XML
        val out = LinkedHashMap<String, Entry>()
        stringRe.findAll(text).forEach { m ->
            out[m.groupValues[1]] = Entry(m.groupValues[3], "translatable=\"false\"" !in m.groupValues[2], null)
        }
        pluralsRe.findAll(text).forEach { m ->
            val items = itemRe.findAll(m.groupValues[3]).associate { it.groupValues[1] to it.groupValues[2] }
            out[m.groupValues[1]] = Entry("", "translatable=\"false\"" !in m.groupValues[2], items)
        }
        return out
    }

    private fun args(raw: String) = argRe.findAll(raw.replace(cdataRe, "")).map { it.value }.filter { it != "%%" && it != "%n" }.sorted().toList()

    /** Problems a raw string body has: an apostrophe or double quote without a backslash, text starting with @ or ?, a stray %. */
    private fun problems(raw: String): List<String> {
        val b = raw.replace(cdataRe, "")
        val p = ArrayList<String>()
        var i = 0
        while (i < b.length) {
            when (b[i]) {
                '\\' -> i++
                '\'' -> p += "unescaped apostrophe"
                '"' -> p += "unescaped double quote"
            }
            i++
        }
        val t = b.trim()
        if (t.startsWith("@") || t.startsWith("?")) p += "starts with @ or ?"
        if ('%' in argRe.replace(b, "")) p += "stray %"
        return p
    }

    private fun translations(res: File) = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!
        .map { File(it, "strings.xml") }.filter { it.isFile }.sortedBy { it.path }

    @Test fun theLaunchLanguagesAreTranslatedInBothModules() {
        for (res in modules) {
            val names = translations(res).map { it.parentFile!!.name }
            assertTrue("$res: $names", names.containsAll(listOf("values-am", "values-ti", "values-om")))
        }
    }

    @Test fun translationsMatchTheDefaultStrings() {
        val errors = ArrayList<String>()
        for (res in modules) {
            val base = parse(File(res, "values/strings.xml"))
            base.forEach { (k, e) -> problems(e.raw).forEach { errors += "$res values/$k: $it" } }
            val wanted = base.filterValues { it.translatable }
            for (f in translations(res)) {
                val dir = "$res ${f.parentFile!!.name}"
                val tr = parse(f)
                (wanted.keys - tr.keys).forEach { errors += "$dir: missing $it" }
                (tr.keys - wanted.keys).forEach { errors += "$dir: extra or not translatable: $it" }
                for ((k, t) in tr) {
                    val b = wanted[k] ?: continue
                    if ((b.plurals == null) != (t.plurals == null)) {
                        errors += "$dir/$k: string vs plurals"
                        continue
                    }
                    if (b.plurals != null) {
                        val q = t.plurals!!
                        if (!q.keys.containsAll(listOf("one", "other"))) errors += "$dir/$k: plurals need one and other, has ${q.keys}"
                        q.forEach { (quantity, body) ->
                            if (args(body) != args(b.plurals["other"].orEmpty())) errors += "$dir/$k[$quantity]: format arguments differ"
                            problems(body).forEach { errors += "$dir/$k[$quantity]: $it" }
                        }
                        continue
                    }
                    if (args(t.raw) != args(b.raw)) errors += "$dir/$k: format arguments ${args(t.raw)} != ${args(b.raw)}"
                    if (t.raw.isBlank()) errors += "$dir/$k: empty"
                    problems(t.raw).forEach { errors += "$dir/$k: $it" }
                }
            }
        }
        assertEquals(errors.joinToString("\n"), 0, errors.size)
    }

    @Test fun theCheckerCatchesTheUsualMistakes() {
        assertEquals(listOf("unescaped apostrophe"), problems("Qindaa'ina"))
        assertEquals(emptyList<String>(), problems("Qindaa\\'ina"))
        assertEquals(listOf("stray %"), problems("50% off"))
        assertEquals(emptyList<String>(), problems("Key height: %1\$d%%"))
        assertEquals(listOf("%1\$s", "%2\$d"), args("%2\$d of %1\$s, 100%%"))
    }
}
