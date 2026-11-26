package com.qalsmartkeyboard.app.creative.backup

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.launch

/**
 * Keeps the backup card's [CreativeBackupModel] for the activity: a backup or restore that has started keeps running (in
 * [viewModelScope]) when the person leaves the studio and shows its result when they come back. Every action is started by
 * a tap on the card; nothing here runs by itself.
 */
class CreativeBackupViewModel(val model: CreativeBackupModel) : ViewModel() {
    fun refresh(account: BackupAccount) { viewModelScope.launch { model.refresh(account) } }

    fun turnOn() { viewModelScope.launch { model.turnOn() } }

    fun turnOff() { viewModelScope.launch { model.turnOff() } }

    fun backUp() { viewModelScope.launch { model.backUp() } }

    fun restore() { viewModelScope.launch { model.restore() } }

    fun deleteCloud() { viewModelScope.launch { model.deleteCloud() } }

    fun dismissResult() = model.dismissResult()

    companion object {
        val create: CreationExtras.() -> CreativeBackupViewModel = {
            val graph = CreativeBackupGraph.get(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application)
            CreativeBackupViewModel(CreativeBackupModel(graph.engine, graph.remote, graph.consent))
        }
    }
}
