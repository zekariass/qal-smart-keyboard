package com.qalsmartkeyboard.app.onboarding

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qalsmartkeyboard.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Survives rotation; the logic lives in [OnboardingModel]. */
class OnboardingViewModel(private val repository: SettingsRepository) : ViewModel() {
    private val model = OnboardingModel(repository)
    val state: StateFlow<OnboardingState> = model.state

    /** Stored "completed or skipped" flag. */
    val completed: Flow<Boolean> get() = repository.onboardingCompleted

    init { restart() }

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
