package com.qalsmartkeyboard.app.onboarding

import com.qalsmartkeyboard.data.InputMode
import com.qalsmartkeyboard.data.KeyboardSettings
import com.qalsmartkeyboard.data.LanguageRules
import com.qalsmartkeyboard.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Steps of the first-run flow, in order. */
enum class OnboardingStep { PRIVACY, ENABLE, SELECT, LANGUAGES, TUTORIAL }

/** What the system says about our IME right now. */
data class ImeStatus(val enabled: Boolean = false, val selected: Boolean = false) {
    companion object {
        /** `Settings.Secure.DEFAULT_INPUT_METHOD` is "package/service"; ours starts with our package. */
        fun isSelected(defaultInputMethodId: String?, packageName: String): Boolean =
            defaultInputMethodId?.substringBefore('/') == packageName

        fun isEnabled(enabledPackages: Collection<String>, packageName: String): Boolean = packageName in enabledPackages
    }
}

/** Pure step rules (no Android, no I/O). */
object OnboardingFlow {
    /** Enable and select must be done before moving on with "Continue"; the user can still skip the whole flow. */
    fun canContinue(step: OnboardingStep, ime: ImeStatus): Boolean = when (step) {
        OnboardingStep.ENABLE -> ime.enabled
        OnboardingStep.SELECT -> ime.enabled && ime.selected
        else -> true
    }

    /**
     * Moves forward over steps the system already satisfies and back when a prerequisite was lost
     * (for example the keyboard was disabled again while the picker step was showing).
     */
    fun reconcile(step: OnboardingStep, ime: ImeStatus): OnboardingStep {
        var s = step
        while (true) {
            s = when {
                s == OnboardingStep.ENABLE && ime.enabled -> OnboardingStep.SELECT
                s == OnboardingStep.SELECT && !ime.enabled -> OnboardingStep.ENABLE
                s == OnboardingStep.SELECT && ime.selected -> OnboardingStep.LANGUAGES
                else -> return s
            }
        }
    }

    fun next(step: OnboardingStep): OnboardingStep = OnboardingStep.entries.getOrElse(step.ordinal + 1) { step }
    fun previous(step: OnboardingStep): OnboardingStep = OnboardingStep.entries.getOrElse(step.ordinal - 1) { step }
}

/**
 * The language choice being edited: all known languages in the user's order, which are enabled, and
 * the input mode per language. The primary language is the first enabled one (this is also how the
 * IME and the sync items define it). Nothing is saved until [applyTo] is committed.
 */
data class LanguageDraft(
    val order: List<String>,
    val enabled: Set<String>,
    val modes: Map<String, InputMode>,
) {
    val primary: String? get() = order.firstOrNull { it in enabled }

    fun toggle(code: String): LanguageDraft = when {
        code !in order -> this
        code in enabled -> if (enabled.size <= 1) this else copy(enabled = enabled - code)
        else -> copy(enabled = enabled + code)
    }

    /** Enables [code] and puts it first. */
    fun makePrimary(code: String): LanguageDraft =
        if (code !in order) this else copy(order = listOf(code) + (order - code), enabled = enabled + code)

    fun move(code: String, delta: Int): LanguageDraft {
        val from = order.indexOf(code)
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, order.lastIndex)
        val list = order.toMutableList().apply { add(to, removeAt(from)) }
        return copy(order = list)
    }

    fun withMode(code: String, mode: InputMode): LanguageDraft =
        if (mode in LanguageRules.allowedModes(code)) copy(modes = modes + (code to mode)) else this

    /**
     * Writes the draft into [s]. A language whose mode was changed forgets its remembered layout, so
     * the keyboard opens in the chosen mode (the IME prefers the remembered layout over the setting).
     */
    fun applyTo(s: KeyboardSettings): KeyboardSettings {
        val byCode = s.languages.associateBy { it.languageCode }
        val languages = order.mapNotNull { c -> byCode[c]?.let { l -> l.copy(enabled = c in enabled, inputMode = modes[c] ?: l.inputMode) } }
        val changedMode = languages.filter { byCode[it.languageCode]?.inputMode != it.inputMode }.map { it.languageCode }.toSet()
        return s.copy(languages = languages, lastModes = s.lastModes - changedMode)
    }

    companion object {
        fun from(s: KeyboardSettings) = LanguageDraft(
            order = s.languages.map { it.languageCode },
            enabled = s.enabledLanguages.toSet(),
            modes = s.languages.associate { it.languageCode to it.inputMode },
        )
    }
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.PRIVACY,
    val ime: ImeStatus = ImeStatus(),
    val draft: LanguageDraft = LanguageDraft.from(KeyboardSettings.DEFAULT),
    /** True once the flow was completed or skipped (the host then leaves the flow). */
    val finished: Boolean = false,
) {
    val canContinue: Boolean get() = OnboardingFlow.canContinue(step, ime)
}

/** Onboarding state machine over [SettingsRepository]; Android-free so it is JVM-tested. */
class OnboardingModel(
    private val repository: SettingsRepository,
    /** State restored after process death (see [OnboardingSaver]); null on a fresh start. */
    initial: OnboardingState? = null,
) {
    private val _state = MutableStateFlow(initial ?: OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /** Starts (or restarts) the flow with the saved language choice as the draft. */
    suspend fun start() {
        _state.update { OnboardingState(ime = it.ime, draft = it.draft) } // reset at once (finished = false)
        val draft = LanguageDraft.from(repository.current())
        _state.update { it.copy(draft = draft) }
    }

    /** Call on resume and window focus with the current system facts. */
    fun onSystemState(ime: ImeStatus) {
        _state.update { s ->
            if (s.ime == ime) s
            else s.copy(ime = ime, step = if (s.step == OnboardingStep.PRIVACY) s.step else OnboardingFlow.reconcile(s.step, ime))
        }
    }

    /** Moves to the next step; on the language step the choice is saved first. At the end it finishes. */
    suspend fun next() {
        val s = _state.value
        if (!s.canContinue) return
        if (s.step == OnboardingStep.LANGUAGES) repository.update { s.draft.applyTo(it) }
        if (s.step == OnboardingStep.TUTORIAL) return finish()
        _state.update { it.copy(step = OnboardingFlow.reconcile(OnboardingFlow.next(it.step), it.ime)) }
    }

    fun back() {
        _state.update { it.copy(step = OnboardingFlow.previous(it.step)) }
    }

    fun edit(change: (LanguageDraft) -> LanguageDraft) {
        _state.update { it.copy(draft = change(it.draft)) }
    }

    /** Done (after the tutorial) or skipped: remember it so the flow is not shown again; it can be re-run from settings. */
    suspend fun finish() {
        repository.setOnboardingCompleted(true)
        _state.update { it.copy(finished = true) }
    }
}
