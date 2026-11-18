package com.qalsmartkeyboard.app.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.OutputStream

/** Survives rotation; the logic is in the four models. */
class PrivacyViewModel(private val graph: PrivacyGraph) : ViewModel() {
    val consents = ConsentsModel(graph.api, graph.effects)
    val history = HistoryModel(graph.api, graph.historyFlag)
    val export = ExportModel(graph.api, graph.exportMemory)
    val deletion = DeletionModel(graph.api, graph.wipe)

    private var polling: Job? = null

    fun loadDashboard() {
        viewModelScope.launch { consents.load() }
        viewModelScope.launch { deletion.load() }
        viewModelScope.launch { history.refreshFlag() }
    }

    fun refreshHistoryFlag() { viewModelScope.launch { history.refreshFlag() } }

    fun loadConsents() { viewModelScope.launch { consents.load() } }
    fun setConsent(code: String, granted: Boolean) { viewModelScope.launch { consents.set(code, granted) } }
    fun loadConsentHistory() { viewModelScope.launch { consents.loadHistory() } }
    fun moreConsentHistory() { viewModelScope.launch { consents.loadMoreHistory() } }

    fun loadHistory() { viewModelScope.launch { history.load() } }
    fun enableHistory(days: Int) { viewModelScope.launch { history.enable(days) } }
    fun disableHistory() { viewModelScope.launch { history.disable() } }
    fun setRetention(days: Int) { viewModelScope.launch { history.setRetention(days) } }
    fun moreHistory() { viewModelScope.launch { history.loadMore() } }
    fun toggleFavorite(id: String) { viewModelScope.launch { history.toggleFavorite(id) } }
    fun deleteHistoryItem(id: String) { viewModelScope.launch { history.delete(id) } }
    fun deleteAllHistory() { viewModelScope.launch { history.deleteAll() } }

    /** Opens the export screen: picks up the last export and polls while it runs. */
    fun openExport() {
        polling?.cancel()
        polling = viewModelScope.launch {
            export.resume()
            export.pollUntilDone()
        }
    }

    fun requestExport() {
        polling?.cancel()
        polling = viewModelScope.launch {
            export.request()
            export.pollUntilDone()
        }
    }

    fun saveExport(open: () -> OutputStream?, discard: () -> Unit) {
        viewModelScope.launch { export.download(open, discard) }
    }

    fun loadDeletion() { viewModelScope.launch { deletion.load() } }
    fun requestDeletion(typed: String, expected: String, isGuest: Boolean) { viewModelScope.launch { deletion.request(typed, expected, isGuest) } }
    fun cancelDeletion() { viewModelScope.launch { deletion.cancel() } }
}
