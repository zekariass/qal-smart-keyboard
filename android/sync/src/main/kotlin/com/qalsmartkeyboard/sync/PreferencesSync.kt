package com.qalsmartkeyboard.sync

import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.NumeralStyle
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.data.ThemeMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Sync of the keyboard's slice of `/me/preferences` (B3.3): `numeralStyle`, `keyboardTheme`
 * (our [ThemeMode] names) and `hapticsEnabled`. Every other member of the document (translation,
 * history, voice, calendar, analytics ...) belongs to other screens and is carried through from the
 * server document untouched, because the PUT replaces the whole document.
 *
 * **Merge rule (field level, three-way).** The base is the field value in the document last synced
 * (the schema defaults before the first sync). For each owned field: if the user changed it on this
 * device since the last sync (local != base) the local value wins, even if the server changed it
 * too; otherwise the server value wins (adopted locally); a field the server does not have keeps
 * the local value. `keyHeightPercent`, `lastModes` and the pack settings are device-local and never
 * sent.
 */
class PreferencesSync internal constructor(
    private val repo: SettingsRepository,
    private val doc: DocSync,
) {
    internal suspend fun run(): DocResult {
        val start = repo.current()
        val local = ownedOf(start)
        val base = doc.lastDoc()?.let { d -> FIELDS.associateWith { f -> d[f] ?: DEFAULTS[f] } } ?: DEFAULTS
        return doc.run { server ->
            val merged = FIELDS.associateWith { f ->
                val l = local[f]
                if (l != base[f]) l else (server[f] ?: l)
            }
            val needsPut = FIELDS.any { f -> merged[f] != server[f] && !(server[f] == null && local[f] == base[f]) }
            val body = JsonObject(server.filterKeys { it != "revision" && it != "updatedAt" } + merged.filterValues { it != null }.mapValues { it.value!! })
            DocPlan(body, needsPut) { applyLocal(start, merged) }
        }
    }

    private suspend fun applyLocal(start: KeyboardSettings, merged: Map<String, JsonElement?>) {
        repo.update { cur ->
            var s = cur
            // Each field only if the user did not change it while the sync ran (then it stays local and wins next time).
            (merged["numeralStyle"] as? JsonPrimitive)?.contentOrNull?.let { n ->
                val v = NumeralStyle.entries.firstOrNull { it.name == n }
                if (v != null && cur.numeralStyle == start.numeralStyle) s = s.copy(numeralStyle = v)
            }
            (merged["keyboardTheme"] as? JsonPrimitive)?.contentOrNull?.let { n ->
                val v = ThemeMode.entries.firstOrNull { it.name == n }
                if (v != null && cur.theme == start.theme) s = s.copy(theme = v)
            }
            (merged["hapticsEnabled"] as? JsonPrimitive)?.booleanOrNull?.let { v ->
                if (cur.hapticsEnabled == start.hapticsEnabled) s = s.copy(hapticsEnabled = v)
            }
            s
        }
    }

    companion object {
        internal val FIELDS = listOf("numeralStyle", "keyboardTheme", "hapticsEnabled")

        private fun ownedOf(s: KeyboardSettings): Map<String, JsonElement?> = mapOf(
            "numeralStyle" to JsonPrimitive(s.numeralStyle.name),
            "keyboardTheme" to JsonPrimitive(s.theme.name),
            "hapticsEnabled" to JsonPrimitive(s.hapticsEnabled),
        )

        private val DEFAULTS: Map<String, JsonElement?> = ownedOf(KeyboardSettings.DEFAULT)

        fun create(repo: SettingsRepository, http: SyncHttp, state: SyncStateStore) =
            PreferencesSync(repo, DocSync(http, state, "/me/preferences", "prefs"))
    }
}
