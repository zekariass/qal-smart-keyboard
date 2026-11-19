package com.qalsmartkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the app and the keyboard name the Ethiopian typing languages in every UI language (ADR 0032, glossary
 * `docs/l10n/ui-glossary-am-ti-om.md`): Afaan Oromoo by its self-name (Afaan Oromoo, in Ge'ez አፋን ኦሮሞ / ኣፋን ኦሮሞ), never
 * an exonym such as ኦሮምኛ or "Oromiffa"; Tigrinya and Amharic always told apart.
 */
class LanguageNamesTest {
    private val modules = listOf(File("src/main/res"), File("../ime/src/main/res"))
    private val locales = listOf("values", "values-am", "values-ti", "values-om")
    private val stringRe = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    private fun strings(module: File, locale: String): Map<String, String> =
        stringRe.findAll(File(module, "$locale/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }

    /** The self-name of Afaan Oromoo as each UI language writes it. */
    private fun oromoSelfName(locale: String) = when (locale) {
        "values-am" -> "አፋን ኦሮሞ"
        "values-ti" -> "ኣፋን ኦሮሞ"
        else -> "Afaan Oromoo"
    }

    @Test fun noUiLanguageUsesAnExonymForAfaanOromoo() {
        val exonyms = listOf("ኦሮምኛ", "ኦሮሚኛ", "Oromiffa", "Oromifa", "Oromigna", "Galla")
        for (module in modules) for (locale in locales) {
            for ((key, value) in strings(module, locale)) {
                for (x in exonyms) assertTrue("$module/$locale $key uses $x", !value.contains(x, ignoreCase = true))
            }
        }
    }

    @Test fun afaanOromooIsNamedByItsSelfNameEverywhere() {
        val keys = mapOf(modules[0] to listOf("lang_om", "wordart_app_lang_om"), modules[1] to listOf("language_name_om"))
        for ((module, names) in keys) for (locale in locales) {
            val s = strings(module, locale)
            for (k in names) {
                val v = s.getValue(k)
                assertTrue("$module/$locale $k = $v", v.startsWith(oromoSelfName(locale)))
            }
        }
    }

    @Test fun tigrinyaIsNeverNamedLikeAmharic() {
        val pairs = mapOf(
            modules[0] to listOf("lang_ti" to "lang_am", "lang_ti_short" to "lang_am_short", "wordart_app_lang_ti" to "wordart_app_lang_am"),
            modules[1] to listOf("language_name_ti" to "language_name_am"),
        )
        for ((module, list) in pairs) for (locale in locales) {
            val s = strings(module, locale)
            for ((ti, am) in list) assertNotEquals("$module/$locale $ti", s.getValue(am), s.getValue(ti))
        }
        // In Ge'ez UIs Tigrinya keeps its own name ትግርኛ (not a translation of "Tigrinya").
        for (locale in listOf("values-am", "values-ti")) assertEquals("ትግርኛ", strings(modules[1], locale).getValue("language_name_ti"))
    }
}
