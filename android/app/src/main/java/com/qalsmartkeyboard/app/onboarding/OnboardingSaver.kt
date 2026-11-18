package com.qalsmartkeyboard.app.onboarding

import androidx.lifecycle.SavedStateHandle
import com.qalsmartkeyboard.data.InputMode
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.LanguageRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the onboarding step and the language draft in a [SavedStateHandle] (ADR 0007 D12), so the
 * flow resumes where it was after process death (typically: the user went to system settings to
 * enable the keyboard and Android reclaimed the app). Only plain strings are stored. The IME status
 * is not saved: it is read again from the system on resume. Restoring is lenient: anything
 * unknown or inconsistent falls back to the default instead of failing.
 */
object OnboardingSaver {
    internal const val KEY_STEP = "onboarding.step"
    internal const val KEY_ORDER = "onboarding.order"
    internal const val KEY_ENABLED = "onboarding.enabled"
    internal const val KEY_MODES = "onboarding.modes"

    fun save(handle: SavedStateHandle, s: OnboardingState) {
        handle[KEY_STEP] = s.step.name
        handle[KEY_ORDER] = ArrayList(s.draft.order)
        handle[KEY_ENABLED] = ArrayList(s.draft.enabled.sorted())
        handle[KEY_MODES] = ArrayList(s.draft.modes.map { (code, mode) -> "$code=${mode.name}" }.sorted())
    }

    /** The saved state, or null if nothing was saved (a fresh start). */
    fun restore(handle: SavedStateHandle): OnboardingState? {
        val stepName = handle.get<String>(KEY_STEP) ?: return null
        val step = OnboardingStep.entries.firstOrNull { it.name == stepName } ?: OnboardingStep.PRIVACY
        val default = LanguageDraft.from(KeyboardSettings.DEFAULT)
        val known = default.order
        val savedOrder = handle.get<ArrayList<String>>(KEY_ORDER).orEmpty().filter { it in known }.distinct()
        val order = savedOrder + known.filter { it !in savedOrder }
        val enabled = handle.get<ArrayList<String>>(KEY_ENABLED).orEmpty().filter { it in known }.toSet()
        val modes = HashMap(default.modes)
        for (entry in handle.get<ArrayList<String>>(KEY_MODES).orEmpty()) {
            val code = entry.substringBefore('=')
            val mode = InputMode.entries.firstOrNull { it.name == entry.substringAfter('=', "") } ?: continue
            if (code in known && mode in LanguageRules.allowedModes(code)) modes[code] = mode
        }
        return OnboardingState(
            step = step,
            draft = LanguageDraft(order, enabled.ifEmpty { default.enabled }, modes),
        )
    }

    /** Saves every state change. Returns the collecting job (cancelled with [scope]). */
    fun bind(handle: SavedStateHandle, scope: CoroutineScope, state: StateFlow<OnboardingState>): Job =
        scope.launch { state.collect { save(handle, it) } }
}
