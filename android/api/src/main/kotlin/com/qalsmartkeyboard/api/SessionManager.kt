package com.qalsmartkeyboard.api

import java.util.concurrent.CopyOnWriteArrayList
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

    /**
     * The server refused the access token itself (`UNAUTHENTICATED` with `WWW-Authenticate: Bearer error="invalid_token"`:
     * a signing key it no longer has, or a closed account) and a refresh did not produce one it accepts (ADR 0035).
     */
    ACCESS_TOKEN_REJECTED,

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
 *
 * The companion app and the keyboard live in one process and share this one instance (ADR 0035). [state] is a
 * [StateFlow] for coroutine code; [addListener] is the plain-callback form for the keyboard side, and unlike [state] it
 * also fires when one signed-in session replaces another (an account switch, a guest linked to an account, a token
 * rotation), which a [StateFlow] conflates away.
 */
class SessionManager(private val store: TokenStore) {
    private val _state = MutableStateFlow<SessionState>(
        if (store.load() != null) SessionState.SignedIn else SessionState.SignedOut(null),
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    internal val lock = Any()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun tokens(): TokenPair? = store.load()

    /**
     * [listener] runs after every [signedIn] and [signedOut], synchronously on the thread that changed the session
     * (possibly an OkHttp thread holding [lock] during a refresh). It must return quickly, must not block or call the
     * network, and reads the new state from [tokens]/[state]. Close the handle to remove it.
     */
    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Stores the pair from a sign-in, guest creation or refresh response (replaces the old pair). */
    fun signedIn(pair: TokenPair) {
        store.save(pair)
        _state.value = SessionState.SignedIn
        notifyListeners()
    }

    /** Clears the tokens and publishes the signed-out state. */
    fun signedOut(reason: SignedOutReason) {
        store.clear()
        _state.value = SessionState.SignedOut(reason)
        notifyListeners()
    }

    private fun notifyListeners() = listeners.forEach { it() }
}
