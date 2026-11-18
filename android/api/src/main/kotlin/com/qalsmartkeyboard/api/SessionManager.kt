package com.qalsmartkeyboard.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the session ended without the user asking for it. */
enum class SignedOutReason {
    /** Refresh token reuse detected: the whole token family was revoked (`REFRESH_TOKEN_REUSED`). */
    REFRESH_TOKEN_REUSED,

    /** The guest account was merged, upgraded, ended or suspended (`GUEST_SESSION_INVALID`). */
    GUEST_SESSION_INVALID,

    /** The refresh token was rejected (expired, revoked or unknown). */
    REFRESH_REJECTED,

    /** The account cannot sign in any more (e.g. `ACCOUNT_SUSPENDED` on refresh). */
    ACCOUNT_BLOCKED,

    /** The user (or the app) signed out. */
    SIGNED_OUT,
}

sealed interface SessionState {
    data object SignedIn : SessionState
    data class SignedOut(val reason: SignedOutReason?) : SessionState
}

/**
 * Single owner of the session: the [TokenStore] plus an observable [state] the UI watches to
 * show the signed-out state. [lock] serialises token refreshes (ADR 0007 D8).
 */
class SessionManager(private val store: TokenStore) {
    private val _state = MutableStateFlow<SessionState>(
        if (store.load() != null) SessionState.SignedIn else SessionState.SignedOut(null),
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    internal val lock = Any()

    fun tokens(): TokenPair? = store.load()

    /** Stores the pair from a sign-in, guest creation or refresh response (replaces the old pair). */
    fun signedIn(pair: TokenPair) {
        store.save(pair)
        _state.value = SessionState.SignedIn
    }

    /** Clears the tokens and publishes the signed-out state. */
    fun signedOut(reason: SignedOutReason) {
        store.clear()
        _state.value = SessionState.SignedOut(reason)
    }
}
