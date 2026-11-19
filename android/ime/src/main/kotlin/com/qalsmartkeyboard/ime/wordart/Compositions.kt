package com.qalsmartkeyboard.ime.wordart

/** FSI ... PDI: keeps right-to-left text from reordering the ornaments around it (and the ornaments from reordering it). */
internal const val FSI = "⁨"
internal const val PDI = "⁩"

internal fun isolate(line: String, info: ScriptInfo): String = if (info.hasRtl && line.isNotEmpty()) "$FSI$line$PDI" else line

/** Letters separated by [sep]; words by three separators. Clusters (marks, emoji, flags) are never split. */
internal fun spaced(text: String, sep: String): String =
    text.split('\n').joinToString("\n") { line ->
        line.split(' ').joinToString(sep.repeat(3)) { word -> Graphemes.split(word).joinToString(sep) }
    }

/** A line wrapped in ornaments: [left] and [right] around each line of text, optional [top] and [bottom] lines. */
internal class Frame(
    val left: String = "",
    val right: String = "",
    val top: String? = null,
    val bottom: String? = null,
    val map: LetterMap? = null,
    val spacedWith: String? = null,
) {
    fun render(text: String, info: ScriptInfo): String {
        val rows = text.replace("\r", "").split('\n').map { raw ->
            var s = raw.trim()
            if (s.isEmpty()) return@map ""
            if (map != null) s = map.map(s)
            if (spacedWith != null) s = spaced(s, spacedWith)
            left + isolate(s, info) + right
        }
        return buildList { top?.let { add(it) }; addAll(rows); bottom?.let { add(it) } }.joinToString("\n")
    }
}
