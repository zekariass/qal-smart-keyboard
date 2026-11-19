package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.api.SessionManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.ByteString.Companion.decodeBase64

/**
 * [SessionInfo] from the claims of the current access token (`sub`, `did`). The client only reads
 * its own token's payload to know who it is; it does not trust it for anything else (the server
 * verifies every call). A token that does not parse gives null, which fails the entitlement token
 * checks closed. [addListener] is the session's own listener: the companion app and the keyboard share one session
 * (ADR 0035), so a sign-in or sign-out in either reaches the other at once.
 */
class SessionAccessInfo(private val session: SessionManager) : SessionInfo {
    override fun signedIn() = session.tokens() != null

    override fun subject() = claim("sub")

    override fun deviceId() = claim("did")

    override fun addListener(listener: () -> Unit): AutoCloseable = session.addListener(listener)

    private fun claim(name: String): String? {
        val jwt = session.tokens()?.accessToken ?: return null
        val payload = jwt.split('.').getOrNull(1)?.decodeBase64()?.utf8() ?: return null
        return try {
            ((Json.parseToJsonElement(payload) as? JsonObject)?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }
}
