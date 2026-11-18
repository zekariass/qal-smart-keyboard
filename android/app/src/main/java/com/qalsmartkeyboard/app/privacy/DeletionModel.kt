package com.qalsmartkeyboard.app.privacy

import com.qalsmartkeyboard.api.ApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DeletionPhase {
    /** Not read yet. */
    UNKNOWN,

    /** No deletion pending: the screen explains and asks for confirmation. */
    IDLE,
    REQUESTING,

    /** Scheduled; this session is still signed in (guests, or before the wipe), so it can be cancelled here. */
    PENDING,
    CANCELLING,

    /** Scheduled, and this device was signed out and its account data wiped. Sign in again to cancel. */
    SCHEDULED_SIGNED_OUT,
}

data class DeletionState(
    val phase: DeletionPhase = DeletionPhase.UNKNOWN,
    val info: DeletionInfo? = null,
    val message: PrivacyMessage? = null,
)

/** What happens on this device once the server accepted a deletion request. */
interface LocalWipe {
    /**
     * Forgets everything this device cached for the account: sync state and synced profiles and rules,
     * the history flag, the remembered export. [signOut]: also ends the session and deletes the tokens.
     * Typing (layouts, language packs, the personal dictionary, keyboard settings) is untouched.
     */
    suspend fun afterDeletionRequested(signOut: Boolean)
}

/**
 * Account deletion (A2.6, B6.2): `POST /me/deletion-request` with `confirm: true` after the person typed the
 * confirmation word; `GET` for the pending request (scheduled date, store subscriptions to cancel in
 * the store); `DELETE` cancels during the 14-day window (409 once the purge started).
 *
 * **Local effect.** A registered account is signed out on this device right after the request and its
 * cached account data wiped ([LocalWipe]); signing in again within the window shows the pending
 * request and offers "Cancel deletion". A guest keeps the session (a guest cannot sign in again to
 * cancel) but its cached data is wiped the same way. Nothing the person typed is touched.
 */
class DeletionModel(private val api: PrivacyApi, private val wipe: LocalWipe) {
    private val _state = MutableStateFlow(DeletionState())
    val state: StateFlow<DeletionState> = _state.asStateFlow()

    suspend fun load() {
        if (_state.value.phase == DeletionPhase.SCHEDULED_SIGNED_OUT) return
        try {
            val info = api.pendingDeletion()
            _state.update { it.copy(phase = if (info != null) DeletionPhase.PENDING else DeletionPhase.IDLE, info = info, message = null) }
        } catch (e: ApiError) {
            _state.update { it.copy(message = e.toPrivacyMessage(), phase = if (it.phase == DeletionPhase.UNKNOWN) DeletionPhase.IDLE else it.phase) }
        }
    }

    /**
     * [typed] must equal [expected] (case and surrounding spaces ignored), else nothing is sent.
     * [isGuest]: the guest session stays signed in.
     */
    suspend fun request(typed: String, expected: String, isGuest: Boolean) {
        val phase = _state.value.phase
        if (phase == DeletionPhase.REQUESTING || phase == DeletionPhase.PENDING || phase == DeletionPhase.SCHEDULED_SIGNED_OUT) return
        if (!typed.trim().equals(expected.trim(), ignoreCase = true) || expected.isBlank()) {
            _state.update { it.copy(message = PrivacyMessage.WordMismatch) }
            return
        }
        _state.update { it.copy(phase = DeletionPhase.REQUESTING, message = null) }
        try {
            val info = api.requestDeletion()
            wipe.afterDeletionRequested(signOut = !isGuest)
            _state.update { it.copy(phase = if (isGuest) DeletionPhase.PENDING else DeletionPhase.SCHEDULED_SIGNED_OUT, info = info) }
        } catch (e: ApiError.Http) {
            if (e.status == 409 && e.code == "DELETION_PENDING") {
                _state.update { it.copy(phase = DeletionPhase.IDLE) }
                load() // already pending: show it
            } else {
                _state.update { it.copy(phase = DeletionPhase.IDLE, message = e.toPrivacyMessage()) }
            }
        } catch (e: ApiError) {
            _state.update { it.copy(phase = DeletionPhase.IDLE, message = e.toPrivacyMessage()) }
        }
    }

    suspend fun cancel() {
        if (_state.value.phase != DeletionPhase.PENDING) return
        _state.update { it.copy(phase = DeletionPhase.CANCELLING, message = null) }
        try {
            api.cancelDeletion()
            _state.update { it.copy(phase = DeletionPhase.IDLE, info = null) }
        } catch (e: ApiError.Http) {
            when (e.status) {
                404 -> _state.update { it.copy(phase = DeletionPhase.IDLE, info = null) } // nothing pending any more
                409 -> {
                    // The purge has started and cannot be cancelled: show the request as it is now.
                    _state.update { it.copy(phase = DeletionPhase.PENDING) }
                    load()
                    _state.update { it.copy(message = e.toPrivacyMessage()) }
                }
                else -> _state.update { it.copy(phase = DeletionPhase.PENDING, message = e.toPrivacyMessage()) }
            }
        } catch (e: ApiError) {
            _state.update { it.copy(phase = DeletionPhase.PENDING, message = e.toPrivacyMessage()) }
        }
    }

    /** Leaves the "scheduled" screen of a signed-out device. */
    fun acknowledge() = _state.update { DeletionState(phase = DeletionPhase.UNKNOWN) }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}
