package com.qalsmartkeyboard.api

import java.util.concurrent.atomic.AtomicReference

/**
 * The access/refresh pair of the current session. Always read and replaced as one value so a
 * reader can never see the access token of one rotation with the refresh token of another.
 * [toString] never shows the tokens.
 */
class TokenPair(val accessToken: String, val refreshToken: String) {
    override fun toString(): String = "TokenPair(redacted)"

    override fun equals(other: Any?): Boolean =
        other is TokenPair && other.accessToken == accessToken && other.refreshToken == refreshToken

    override fun hashCode(): Int = 31 * accessToken.hashCode() + refreshToken.hashCode()
}

/**
 * Where the session tokens live. Implementations must make [save] atomic for the pair and keep
 * only the newest pair (ADR 0007 D8: clients keep only the newest refresh token).
 * Calls are blocking and may come from any OkHttp thread.
 *
 * [InMemoryTokenStore] is for tests and the local stack. The app uses the Android Keystore backed
 * `EncryptedTokenStore` in `:app` (A2.2).
 */
interface TokenStore {
    fun load(): TokenPair?
    fun save(pair: TokenPair)
    fun clear()
}

class InMemoryTokenStore(initial: TokenPair? = null) : TokenStore {
    private val ref = AtomicReference(initial)
    override fun load(): TokenPair? = ref.get()
    override fun save(pair: TokenPair) = ref.set(pair)
    override fun clear() = ref.set(null)
}
