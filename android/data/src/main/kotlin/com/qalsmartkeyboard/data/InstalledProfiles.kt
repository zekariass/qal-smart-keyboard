package com.qalsmartkeyboard.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * [ProfileCatalog] of the transliteration profiles in the packs the pack manager loaded (A1.6 seam
 * of A1.5). Unknown (`null`) until [publish] is called, so settings are left alone while packs are
 * still loading or when loading failed.
 */
class InstalledProfileCatalog : ProfileCatalog {
    @Volatile
    private var byLanguage: Map<String, List<ProfileRef>>? = null

    /** The profiles of the loaded packs; a language without one is known to have none. */
    fun publish(profiles: List<ProfileRef>) {
        byLanguage = profiles.groupBy { it.language }
    }

    override fun activeProfiles(language: String): List<ProfileRef>? = byLanguage?.let { it[language] ?: emptyList() }
}

/**
 * "Profile changed" flag (F14) for the settings screen: set when a profile fallback was applied,
 * cleared by [acknowledge] once the user has been told. Changes pile up until acknowledged, so a
 * second app start does not hide the first one.
 */
class ProfileChangeNotice {
    private val state = MutableStateFlow<List<ProfileChange>>(emptyList())

    /** The unacknowledged changes; observe from the UI. */
    val changes: StateFlow<List<ProfileChange>> get() = state

    val profileChanged: Boolean get() = state.value.isNotEmpty()

    fun report(changes: List<ProfileChange>) {
        if (changes.isEmpty()) return
        state.update { old -> (old + changes).distinct() }
    }

    fun acknowledge() {
        state.value = emptyList()
    }
}
