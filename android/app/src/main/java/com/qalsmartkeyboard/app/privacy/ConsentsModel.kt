package com.qalsmartkeyboard.app.privacy

import com.qalsmartkeyboard.api.ApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Local consequences of a decision (stop the keyboard sending `saveToHistory`, and so on). */
interface ConsentEffects {
    suspend fun withdrawn(code: String) {}
    suspend fun granted(code: String) {}

    object None : ConsentEffects
}

data class ConsentsState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** Latest decision per consent code (what `GET /me/consents` says). */
    val current: Map<String, ConsentRecord> = emptyMap(),
    /** The code whose decision is on its way. */
    val busy: String? = null,
    val message: PrivacyMessage? = null,
    /** The consent that was just withdrawn: the screen shows what that did. */
    val justWithdrawn: String? = null,
    val history: List<ConsentRecord> = emptyList(),
    val historyNext: String? = null,
    val historyLoading: Boolean = false,
    val historyLoaded: Boolean = false,
) {
    fun granted(code: String): Boolean? = current[code]?.granted
}

/**
 * The consent screens' logic (A2.6, B3.5): the latest state of every consent, grant and
 * withdrawal (`PUT /me/consents/{code}`, `details.surface = settings`) and the person's own
 * decision history (oldest first, paged). Suspend functions, so tests run them directly.
 *
 * Terms and privacy notice are not offered for withdrawal; if one is sent anyway the server's
 * 422 `CONSENT_NOT_REVOCABLE` becomes [PrivacyMessage.NotRevocable]. Consent details, versions
 * and timestamps are never logged.
 */
class ConsentsModel(private val api: PrivacyApi, private val effects: ConsentEffects = ConsentEffects.None) {
    private val _state = MutableStateFlow(ConsentsState())
    val state: StateFlow<ConsentsState> = _state.asStateFlow()

    suspend fun load() {
        _state.update { it.copy(loading = true, message = null) }
        try {
            val latest = api.consents().groupBy { it.code }.mapValues { (_, v) -> v.maxBy { r -> r.recordedAtMs ?: 0L } }
            _state.update { it.copy(loading = false, loaded = true, current = latest) }
        } catch (e: ApiError) {
            _state.update { it.copy(loading = false, message = e.toPrivacyMessage()) }
        }
    }

    suspend fun set(code: String, granted: Boolean) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = code, message = null, justWithdrawn = null) }
        try {
            val record = api.setConsent(code, granted, SURFACE)
            _state.update { it.copy(current = it.current + (code to record)) }
            if (granted) effects.granted(code) else effects.withdrawn(code)
            _state.update { it.copy(busy = null, justWithdrawn = if (granted) null else code, historyLoaded = false) }
        } catch (e: ApiError) {
            _state.update { it.copy(busy = null, message = e.toPrivacyMessage()) }
        }
    }

    fun dismissWithdrawn() = _state.update { it.copy(justWithdrawn = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** First page of the decision history, replacing what is shown. */
    suspend fun loadHistory() {
        _state.update { it.copy(historyLoading = true, message = null) }
        try {
            val page = api.consentHistory(null)
            _state.update { it.copy(historyLoading = false, historyLoaded = true, history = page.items, historyNext = page.nextCursor) }
        } catch (e: ApiError) {
            _state.update { it.copy(historyLoading = false, message = e.toPrivacyMessage()) }
        }
    }

    suspend fun loadMoreHistory() {
        val cursor = _state.value.historyNext ?: return
        if (_state.value.historyLoading) return
        _state.update { it.copy(historyLoading = true) }
        try {
            val page = api.consentHistory(cursor)
            _state.update { it.copy(historyLoading = false, history = it.history + page.items, historyNext = page.nextCursor) }
        } catch (e: ApiError) {
            _state.update { it.copy(historyLoading = false, message = e.toPrivacyMessage()) }
        }
    }

    companion object {
        const val SURFACE = "settings"
    }
}
