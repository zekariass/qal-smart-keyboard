package com.qalsmartkeyboard.app.onboarding

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qalsmartkeyboard.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Survives rotation and, through [saved], process death: the current step and the language draft
 * are kept in the `SavedStateHandle` (ADR 0007 D12), so coming back from system settings after
 * Android killed the app resumes the same step. The logic lives in [OnboardingModel].
 */
class OnboardingViewModel(
    private val repository: SettingsRepository,
    saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val restored = OnboardingSaver.restore(saved)
    private val model = OnboardingModel(repository, restored)
    val state: StateFlow<OnboardingState> = model.state

    /** Stored "completed or skipped" flag. */
    val completed: Flow<Boolean> get() = repository.onboardingCompleted

    init {
        OnboardingSaver.bind(saved, viewModelScope, model.state)
        // A restored flow carries on where it was; only a fresh one starts from the stored settings.
        if (restored == null) restart()
    }

    fun restart() { viewModelScope.launch { model.start() } }
    fun onSystemState(ime: ImeStatus) = model.onSystemState(ime)
    fun next() { viewModelScope.launch { model.next() } }
    fun back() = model.back()
    fun skip() { viewModelScope.launch { model.finish() } }
    fun edit(change: (LanguageDraft) -> LanguageDraft) = model.edit(change)
}

/** Reads the facts about our IME from the system (no text, no per-app data). */
object ImeStatusReader {
    fun read(context: Context): ImeStatus {
        val pkg = context.packageName
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = ImeStatus.isEnabled(imm.enabledInputMethodList.map { it.packageName }, pkg)
        val default = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return ImeStatus(enabled = enabled, selected = ImeStatus.isSelected(default, pkg))
    }
}
