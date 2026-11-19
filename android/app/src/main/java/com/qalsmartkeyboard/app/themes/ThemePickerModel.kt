package com.qalsmartkeyboard.app.themes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qalsmartkeyboard.data.SettingsRepository
import com.qalsmartkeyboard.ime.theme.KeyboardTheme
import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess
import com.qalsmartkeyboard.ime.theme.ThemeCatalog

/** Where the chosen theme id is stored (the `theme` setting; synced as `keyboardTheme`). */
fun interface ThemeStore {
    suspend fun set(id: String)
}

fun SettingsRepository.themeStore() = ThemeStore { id -> update { it.copy(theme = id) } }

/** The gallery's sections (ADR 0042), in display order; [SYSTEM] has no heading of its own (it leads the first row). */
enum class ThemeGroup { LIGHT, DARK, GRADIENT, PREMIUM }

/** What [ThemePickerModel.apply] did. */
enum class ApplyResult {
    /** Stored: the keyboard draws it from the next draw. */
    APPLIED,

    /** A premium theme without the entitlement: nothing was stored, the screen offers the plans. */
    NEEDS_PREMIUM,

    /** Not a theme this build knows. */
    UNKNOWN,
}

/**
 * The theme picker's logic, without UI: the theme being previewed and applying a theme. Previewing never touches
 * the store, so the keyboard keeps its theme until [apply] is called; [apply] refuses a premium theme unless the
 * entitlement allows it (the keyboard would draw the free fallback anyway).
 */
class ThemePickerModel(private val store: ThemeStore, initialPreview: String? = null) {
    /** The id shown in the large preview, or null for the list. [ThemeCatalog.SYSTEM_ID] previews "System". */
    var previewId: String? by mutableStateOf(initialPreview?.takeIf(::isKnown))
        private set

    fun showPreview(id: String) {
        if (isKnown(id)) previewId = id
    }

    fun closePreview() {
        previewId = null
    }

    /** A theme can be applied when it is free or the account may use premium themes. */
    fun canApply(id: String, access: PremiumThemeAccess): Boolean {
        if (id == ThemeCatalog.SYSTEM_ID) return true
        val theme = ThemeCatalog.find(id) ?: return false
        return !theme.isPremium || access == PremiumThemeAccess.ALLOWED
    }

    suspend fun apply(id: String, access: PremiumThemeAccess): ApplyResult {
        if (!isKnown(id)) return ApplyResult.UNKNOWN
        if (!canApply(id, access)) return ApplyResult.NEEDS_PREMIUM
        store.set(id)
        return ApplyResult.APPLIED
    }

    companion object {
        /** Premium themes form their own group; free ones are gradients, else light or dark by their background. */
        fun groupOf(theme: KeyboardTheme): ThemeGroup = when {
            theme.isPremium -> ThemeGroup.PREMIUM
            theme.isGradient -> ThemeGroup.GRADIENT
            theme.isLight -> ThemeGroup.LIGHT
            else -> ThemeGroup.DARK
        }

        /** The ids of one gallery section, in catalogue order; [ThemeGroup.LIGHT] starts with "System". */
        fun idsIn(group: ThemeGroup): List<String> =
            (if (group == ThemeGroup.LIGHT) listOf(ThemeCatalog.SYSTEM_ID) else emptyList()) +
                ThemeCatalog.all.filter { groupOf(it) == group }.map { it.id }

        fun isKnown(id: String) = id == ThemeCatalog.SYSTEM_ID || ThemeCatalog.find(id) != null

        /** The theme drawn in a card or preview for [id]: "System" shows Classic Light or Dark by the device mode. */
        fun previewTheme(id: String, systemDark: Boolean): KeyboardTheme =
            ThemeCatalog.find(id) ?: ThemeCatalog.classic(!systemDark)
    }
}
