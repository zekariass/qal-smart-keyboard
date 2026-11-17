package com.qalsmartkeyboard.data

/** An ACTIVE transliteration profile as known locally (from installed packs, later the synced catalog). */
data class ProfileRef(
    val code: String,
    val language: String,
    val activatedAtEpochMs: Long,
    /** Profile version of the pack it came from, when known. */
    val version: String? = null,
    /** True when the profile is reviewed (APPROVED), false for DRAFT data. */
    val approved: Boolean = false,
)

/** What profiles are active right now. */
fun interface ProfileCatalog {
    /** Active profiles of [language]; null when that is not known yet (packs still loading). */
    fun activeProfiles(language: String): List<ProfileRef>?
}

/** [language]'s profile [from] was unavailable and is now [to] (null: no active profile at all). */
data class ProfileChange(val language: String, val from: String, val to: String?)

/** The settings to use, plus the profile fallbacks the UI should tell the user about. */
data class ResolvedSettings(val settings: KeyboardSettings, val changes: List<ProfileChange>) {
    /** "Profile changed" flag for the UI. */
    val profileChanged: Boolean get() = changes.isNotEmpty()
}

/**
 * F14 / SD 10.2: when the selected transliteration profile is retired or unavailable, fall back to
 * the newest active profile of the same language and report it. It never touches the language
 * order or the enabled flags, so a retired profile cannot block reordering.
 */
object ProfileResolver {
    fun resolve(settings: KeyboardSettings, catalog: ProfileCatalog): ResolvedSettings {
        val changes = ArrayList<ProfileChange>()
        val languages = settings.languages.map { l ->
            val code = l.transliterationProfileCode
            if (!l.enabled || l.inputMode != InputMode.PHONETIC_LATIN_TO_NATIVE || code == null) return@map l
            val active = catalog.activeProfiles(l.languageCode) ?: return@map l // unknown yet: leave as is
            if (active.any { it.code == code }) return@map l
            val newest = active.filter { it.language == l.languageCode }.maxByOrNull { it.activatedAtEpochMs }?.code
            changes += ProfileChange(l.languageCode, code, newest)
            l.copy(transliterationProfileCode = newest)
        }
        return ResolvedSettings(settings.copy(languages = languages), changes)
    }
}
