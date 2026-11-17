package com.qalsmartkeyboard.ime.packs

import com.qalsmartkeyboard.data.ProfileRef
import uniffi.qal_keyboard_core.CoreException
import uniffi.qal_keyboard_core.ResourceType
import uniffi.qal_keyboard_core.TranslitProfileStatus

/**
 * Settings profile code for a core profile id: the id upper-cased with non-alphanumerics as `_`
 * (`am-phonetic` -> `AM_PHONETIC`), matching the code alphabet `:data` accepts.
 */
fun profileCodeOf(profileId: String): String = profileId.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')

/**
 * The active transliteration profiles of the packs the manager loaded (A1.5/A1.6 seam): one per
 * language with an active TRANSLITERATION_RULES pack, identified by the core's `translit_profile`
 * (profile id, version, review status). A pack whose profile does not parse is skipped.
 * Hand the result to `SettingsRepository.applyInstalledProfiles` off the main thread.
 */
fun LoadResult.installedProfiles(): List<ProfileRef> = active
    .filter { it.info.resourceType == ResourceType.TRANSLITERATION_RULES }
    .mapNotNull { pack ->
        try {
            val p = engine.translitProfile(pack.info.languageCode)
            ProfileRef(
                code = profileCodeOf(p.profileId),
                language = pack.info.languageCode,
                // One active profile per language; the pack's version stands in for the activation time.
                activatedAtEpochMs = 0L,
                version = p.profileVersion,
                approved = p.status == TranslitProfileStatus.APPROVED,
            )
        } catch (_: CoreException) {
            null
        }
    }
