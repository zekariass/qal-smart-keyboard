package com.qalsmartkeyboard.sync

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.ApiErrors
import com.qalsmartkeyboard.api.apiCall
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The few JSON calls sync makes, over the app's shared OkHttp client (bearer, client headers,
 * refresh authenticator, correlation-id logging). Hand-written instead of the generated models
 * because a full-replace PUT must carry every member of the server document, including the ones
 * this app does not edit and a generated model of an older contract would drop.
 *
 * Failures are the same [ApiError]s as everywhere (a 409 is `ApiError.Http` with code
 * `REVISION_CONFLICT`). Bodies, paths with ids and tokens are never logged here.
 */
class SyncHttp(private val clients: ApiClients) {
    private val base = clients.config.baseUrl.trimEnd('/') + "/api/v1"

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement? = call("GET", path, query, null)

    suspend fun put(path: String, body: JsonElement): JsonElement? = call("PUT", path, emptyMap(), body)

    suspend fun post(path: String, body: JsonElement): JsonElement? = call("POST", path, emptyMap(), body)

    suspend fun delete(path: String) {
        call("DELETE", path, emptyMap(), null)
    }

    /** True while the session holds tokens (guest or registered). */
    fun hasSession(): Boolean = clients.session.tokens() != null

    private suspend fun call(method: String, path: String, query: Map<String, String>, body: JsonElement?): JsonElement? = apiCall {
        val url = (base + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url)
            .method(method, body?.let { Json.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON) })
            .header("Accept", "application/json")
            .build()
        clients.okHttpClient.newCall(request).execute().use { r ->
            val text = r.body?.string()
            if (!r.isSuccessful) throw ApiErrors.fromHttp(r.code, text, r.headers.toMultimap())
            if (r.code == 204 || text.isNullOrBlank()) return@apiCall null
            try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                throw ApiError.Unexpected(r.code, e)
            }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
