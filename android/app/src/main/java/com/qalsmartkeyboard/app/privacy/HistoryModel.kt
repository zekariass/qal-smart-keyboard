package com.qalsmartkeyboard.app.privacy

import com.qalsmartkeyboard.api.ApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Whether the keyboard asks the server to save its AI results (`saveToHistory: true`). It mirrors
 * "history preference on and `SAVE_AI_HISTORY` granted" on this device, so the toolbar can read it
 * without a network call. The server still decides (it also needs the `HISTORY_SYNC` entitlement).
 * Off by default, off after sign-out.
 */
interface HistoryFlag {
    var enabled: Boolean
}

class MemoryHistoryFlag(override var enabled: Boolean = false) : HistoryFlag

data class HistoryState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val retentionDays: Int = PrivacyApi.DEFAULT_RETENTION_DAYS,
    /** Latest `SAVE_AI_HISTORY` decision; null when the account never decided. */
    val consentGranted: Boolean? = null,
    /** Null while unknown (not loaded, or the plan could not be read). */
    val plan: PlanInfo? = null,
    val items: List<HistoryEntry> = emptyList(),
    val nextCursor: String? = null,
    val itemsLoaded: Boolean = false,
    val loadingMore: Boolean = false,
    val busy: Boolean = false,
    val message: PrivacyMessage? = null,
) {
    /** D41: history is on (or about to be) but the plan has no `HISTORY_SYNC`, so nothing will be saved. */
    val planLacksHistory: Boolean get() = plan?.hasHistorySync == false
}

/**
 * The history screen's logic (A2.6, B5.7, ADR 0007 D41).
 *
 * **Enable** = consent first (`PUT /me/consents/SAVE_AI_HISTORY`, only when not already granted),
 * then the preference (`historyEnabled` + `historyRetentionDays` in the `/me/preferences` document),
 * then the local [HistoryFlag]. **Disable** = preference off (and the flag) first, then the consent is
 * withdrawn, so turning history off in one place also withdraws the consent the switch stands for.
 * Whether the plan has `HISTORY_SYNC` is read from `/me/entitlements`; the screen warns when it lacks
 * it (the server then saves nothing) but does not block the switch.
 *
 * Listing works whatever the setting is, so a person who turned history off can still review and
 * delete what is left. History texts live only in this state, never in a log.
 */
class HistoryModel(private val api: PrivacyApi, private val flag: HistoryFlag) {
    // The device-local flag is the best guess until the server has been asked.
    private val _state = MutableStateFlow(HistoryState(enabled = flag.enabled, consentGranted = if (flag.enabled) true else null))
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    /** Settings, plan and the first page. Each part fails on its own (a missing plan does not hide the list). */
    suspend fun load() {
        _state.update { it.copy(loading = true, message = null) }
        var message: PrivacyMessage? = null
        try {
            val prefs = api.historyPrefs()
            val consent = consentGranted()
            applyFlag(prefs.enabled && consent == true)
            _state.update { it.copy(enabled = prefs.enabled, retentionDays = prefs.retentionDays, consentGranted = consent) }
        } catch (e: ApiError) {
            message = e.toPrivacyMessage()
        }
        val plan = try {
            api.plan()
        } catch (e: ApiError) {
            null // deletion pending (403), offline: no warning rather than a wrong one
        }
        _state.update { it.copy(plan = plan) }
        loadItems()
        _state.update { it.copy(loading = false, loaded = message == null, message = message ?: it.message) }
    }

    /** Cheap check for app start and the dashboard: prefs and consent only, keeps the keyboard's flag right on this device. */
    suspend fun refreshFlag() {
        try {
            val prefs = api.historyPrefs()
            val consent = consentGranted()
            applyFlag(prefs.enabled && consent == true)
            _state.update { it.copy(enabled = prefs.enabled, retentionDays = prefs.retentionDays, consentGranted = consent) }
        } catch (_: ApiError) {
            // keep the last known value
        }
    }

    private suspend fun consentGranted(): Boolean? =
        api.consents().filter { it.code == ConsentCatalog.SAVE_AI_HISTORY }.maxByOrNull { it.recordedAtMs ?: 0L }?.granted

    private fun applyFlag(on: Boolean) {
        flag.enabled = on
    }

    suspend fun enable(retentionDays: Int = _state.value.retentionDays) {
        if (!validRetention(retentionDays) || _state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        try {
            if (_state.value.consentGranted != true) {
                api.setConsent(ConsentCatalog.SAVE_AI_HISTORY, true, ConsentsModel.SURFACE)
                _state.update { it.copy(consentGranted = true) }
            }
            api.saveHistoryPrefs(true, retentionDays)
            applyFlag(true)
            _state.update { it.copy(busy = false, enabled = true, retentionDays = retentionDays) }
        } catch (e: ApiError) {
            _state.update { it.copy(busy = false, message = e.toPrivacyMessage()) }
        }
    }

    suspend fun disable() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        try {
            api.saveHistoryPrefs(false, _state.value.retentionDays)
            applyFlag(false)
            _state.update { it.copy(enabled = false) }
            if (_state.value.consentGranted == true) {
                api.setConsent(ConsentCatalog.SAVE_AI_HISTORY, false, ConsentsModel.SURFACE)
                _state.update { it.copy(consentGranted = false) }
            }
            _state.update { it.copy(busy = false) }
        } catch (e: ApiError) {
            _state.update { it.copy(busy = false, message = e.toPrivacyMessage()) }
        }
    }

    /** Saves a new retention (1..365 days) even while history is off, so enabling later uses it. */
    suspend fun setRetention(days: Int) {
        if (!validRetention(days) || _state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        try {
            api.saveHistoryPrefs(_state.value.enabled, days)
            _state.update { it.copy(busy = false, retentionDays = days) }
        } catch (e: ApiError) {
            _state.update { it.copy(busy = false, message = e.toPrivacyMessage()) }
        }
    }

    private fun validRetention(days: Int): Boolean {
        if (days in PrivacyApi.RETENTION_RANGE) return true
        _state.update { it.copy(message = PrivacyMessage.RetentionInvalid) }
        return false
    }

    suspend fun loadItems() {
        try {
            val page = api.history(null)
            _state.update { it.copy(items = page.items, nextCursor = page.nextCursor, itemsLoaded = true) }
        } catch (e: ApiError) {
            _state.update { it.copy(message = e.toPrivacyMessage()) }
        }
    }

    suspend fun loadMore() {
        val cursor = _state.value.nextCursor ?: return
        if (_state.value.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        try {
            val page = api.history(cursor)
            _state.update { it.copy(loadingMore = false, items = it.items + page.items, nextCursor = page.nextCursor) }
        } catch (e: ApiError) {
            _state.update { it.copy(loadingMore = false, message = e.toPrivacyMessage()) }
        }
    }

    suspend fun toggleFavorite(id: String) {
        val item = _state.value.items.firstOrNull { it.id == id } ?: return
        try {
            val updated = api.setFavorite(id, !item.favorite)
            _state.update { s -> s.copy(items = s.items.map { if (it.id == id) updated else it }) }
        } catch (e: ApiError) {
            _state.update { it.copy(message = e.toPrivacyMessage()) }
        }
    }

    suspend fun delete(id: String) {
        try {
            api.deleteHistoryItem(id)
            _state.update { s -> s.copy(items = s.items.filterNot { it.id == id }) }
        } catch (e: ApiError.Http) {
            if (e.status == 404) {
                _state.update { s -> s.copy(items = s.items.filterNot { it.id == id }) } // already gone
            } else {
                _state.update { it.copy(message = e.toPrivacyMessage()) }
            }
        } catch (e: ApiError) {
            _state.update { it.copy(message = e.toPrivacyMessage()) }
        }
    }

    /** Deletes every item, favourites included (idempotent on the server). The screen asks first. */
    suspend fun deleteAll() {
        _state.update { it.copy(busy = true, message = null) }
        try {
            api.clearHistory()
            _state.update { it.copy(busy = false, items = emptyList(), nextCursor = null) }
        } catch (e: ApiError) {
            _state.update { it.copy(busy = false, message = e.toPrivacyMessage()) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}
