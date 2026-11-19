package com.qalsmartkeyboard.sync

import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A small stateful stand-in for the backend: revisioned preferences / typing-language / app-rule
 * documents (409 `REVISION_CONFLICT` on a stale revision), destination profiles (CRUD), the rule
 * key, transliteration profiles, consents and the dictionary change feed (cursor = version
 * counter, tombstones, idempotent `clientChangeId`, last writer wins per word). Every request is
 * recorded (method, path, body) so tests can assert on what left the device.
 */
class FakeBackend {
    val server = MockWebServer().also { it.start() }
    val baseUrl: String get() = "http://localhost:${server.port}"

    class Req(val method: String, val path: String, val body: String)

    val requests = CopyOnWriteArrayList<Req>()

    // ---- documents
    val docs = HashMap<String, JsonObject>().apply {
        put("preferences", JsonObject(mapOf("revision" to JsonPrimitive(0))))
        put("typing", JsonObject(mapOf("revision" to JsonPrimitive(0), "languages" to JsonArray(emptyList()))))
        put("rules", JsonObject(mapOf("revision" to JsonPrimitive(0), "rules" to JsonArray(emptyList()))))
    }

    /** Called before every PUT of a document: lets a test change the server as another device would. */
    @Volatile var beforePut: ((String) -> Unit)? = null

    @Volatile var alwaysConflict = false

    /** Typing languages the server refuses (400 LANGUAGE_NOT_SUPPORTED), as the real backend refuses `ar` until it is activated. */
    val unsupportedLanguages: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf("ar"))

    // ---- profiles
    val profiles = LinkedHashMap<String, JsonObject>()
    val transliterationProfiles = HashMap<String, List<Pair<String, String>>>().apply {
        put("am", listOf("AM_PHONETIC_QWERTY_TO_ETHIOPIC" to "ACTIVE", "AM_OLD" to "RETIRED"))
        put("ti", listOf("TI_PHONETIC_QWERTY_TO_ETHIOPIC" to "ACTIVE"))
    }
    @Volatile var profilesApiStatus = 200

    // ---- key / consent / dictionary
    val ruleKey = ByteArray(32) { (it * 7 + 3).toByte() }
    var ruleKeyFetches = 0
    @Volatile var consentGranted = false
    @Volatile var guest = false

    private class Entry(var live: Boolean, var version: Long, var id: String)
    private val entries = LinkedHashMap<String, Entry>() // "lang|word"
    private val appliedIds = HashSet<String>()
    private var version = 0L
    val dictionaryWords: Set<String> get() = synchronized(this) { entries.filter { it.value.live }.keys }
    val tombstones: Set<String> get() = synchronized(this) { entries.filter { !it.value.live }.keys }
    @Volatile var resyncOnNextPull = false
    /** Push answers still to be dropped after applying (OkHttp resends once on a dropped connection, so tests use 2). */
    @Volatile var dropPushResponses = 0
    var pushRequestCount = 0
    var changesApplied = 0

    private fun json(code: Int, body: JsonElement): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun problem(status: Int, code: String, extra: Map<String, JsonElement> = emptyMap()): MockResponse =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/problem+json").setBody(
            JsonObject(mapOf("title" to JsonPrimitive(code), "status" to JsonPrimitive(status), "code" to JsonPrimitive(code)) + extra).toString(),
        )

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeBackend) {
                val path = request.requestUrl!!.encodedPath.removePrefix("/api/v1")
                val body = request.body.readUtf8()
                requests += Req(request.method!!, path, body)
                route(request, path, body)
            }
        }
    }

    private fun parse(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun docRoute(name: String, method: String, body: String): MockResponse {
        val cur = docs.getValue(name)
        val rev = (cur["revision"] as JsonPrimitive).intOrNull!!
        return when (method) {
            "GET" -> json(200, cur)
            "PUT" -> {
                beforePut?.invoke(name)
                val now = docs.getValue(name)
                val nowRev = (now["revision"] as JsonPrimitive).intOrNull!!
                val sent = parse(body)
                val sentRev = (sent["revision"] as JsonPrimitive).intOrNull
                if (name == "typing") {
                    // Like TypingLanguagesService: an unknown or inactive language is 400 with one field error per entry.
                    val errors = (sent["languages"] as? JsonArray).orEmpty().mapIndexedNotNull { i, e ->
                        val code = ((e as? JsonObject)?.get("languageCode") as? JsonPrimitive)?.contentOrNull
                        if (code !in unsupportedLanguages) return@mapIndexedNotNull null
                        JsonObject(
                            mapOf(
                                "field" to JsonPrimitive("languages[$i].languageCode"),
                                "code" to JsonPrimitive("LANGUAGE_NOT_SUPPORTED"),
                                "message" to JsonPrimitive("The language is not supported."),
                            ),
                        )
                    }
                    if (errors.isNotEmpty()) return problem(400, "LANGUAGE_NOT_SUPPORTED", mapOf("errors" to JsonArray(errors)))
                }
                if (alwaysConflict || sentRev != nowRev) {
                    return problem(409, "REVISION_CONFLICT", mapOf("currentRevision" to JsonPrimitive(nowRev)))
                }
                val next = JsonObject(sent + ("revision" to JsonPrimitive(nowRev + 1)))
                docs[name] = next
                json(200, next)
            }
            else -> MockResponse().setResponseCode(405)
        }.also { rev.hashCode() }
    }

    private fun route(request: RecordedRequest, path: String, body: String): MockResponse {
        val m = request.method!!
        return when {
            path == "/me/preferences" -> docRoute("preferences", m, body)
            path == "/me/typing-languages" -> docRoute("typing", m, body)
            path == "/me/app-destination-rules" -> docRoute("rules", m, body)
            path == "/me/app-destination-rules/key" -> {
                ruleKeyFetches++
                json(200, JsonObject(mapOf("keyId" to JsonPrimitive("k1"), "key" to JsonPrimitive(Base64.getEncoder().encodeToString(ruleKey)))))
            }
            path.startsWith("/languages/") && path.endsWith("/transliteration-profiles") -> {
                if (profilesApiStatus != 200) return problem(profilesApiStatus, "INTERNAL_ERROR")
                val lang = path.removePrefix("/languages/").substringBefore('/')
                val list = transliterationProfiles[lang] ?: return problem(404, "NOT_FOUND")
                json(
                    200,
                    JsonObject(
                        mapOf(
                            "items" to JsonArray(
                                list.mapIndexed { i, (code, status) ->
                                    JsonObject(mapOf("code" to JsonPrimitive(code), "version" to JsonPrimitive("1.$i.0"), "status" to JsonPrimitive(status), "languageCode" to JsonPrimitive(lang)))
                                },
                            ),
                        ),
                    ),
                )
            }
            path == "/me/destination-profiles" && m == "GET" -> json(200, JsonObject(mapOf("items" to JsonArray(profiles.values.toList()))))
            path == "/me/destination-profiles" && m == "POST" -> {
                val id = UUID.randomUUID().toString()
                val p = JsonObject(parse(body) + ("id" to JsonPrimitive(id)))
                profiles[id] = p
                json(201, p)
            }
            path.startsWith("/me/destination-profiles/") -> {
                val id = path.substringAfterLast('/')
                when (m) {
                    "PUT" -> if (id in profiles) json(200, JsonObject(parse(body) + ("id" to JsonPrimitive(id))).also { profiles[id] = it }) else problem(404, "NOT_FOUND")
                    "DELETE" -> if (profiles.remove(id) != null) MockResponse().setResponseCode(204) else problem(404, "NOT_FOUND")
                    else -> MockResponse().setResponseCode(405)
                }
            }
            path == "/me/consents" -> json(
                200,
                JsonObject(
                    mapOf(
                        "items" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(UUID.randomUUID().toString()), "consentCode" to JsonPrimitive("DICTIONARY_SYNC"),
                                        "consentVersion" to JsonPrimitive("1"), "isGranted" to JsonPrimitive(consentGranted), "recordedAt" to JsonPrimitive("2026-01-01T00:00:00Z"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            path == "/me/consents/DICTIONARY_SYNC" -> {
                consentGranted = (parse(body)["isGranted"] as JsonPrimitive).contentOrNull == "true"
                json(200, JsonObject(mapOf("id" to JsonPrimitive(UUID.randomUUID().toString()), "consentCode" to JsonPrimitive("DICTIONARY_SYNC"), "consentVersion" to JsonPrimitive("1"), "isGranted" to JsonPrimitive(consentGranted), "recordedAt" to JsonPrimitive("2026-01-01T00:00:00Z"))))
            }
            path == "/me/dictionary/changes" -> {
                if (guest) return problem(403, "GUEST_NOT_ALLOWED")
                if (!consentGranted) return problem(403, "CONSENT_REQUIRED", mapOf("consentCode" to JsonPrimitive("DICTIONARY_SYNC")))
                if (m == "GET") pull(request) else push(body)
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun pull(request: RecordedRequest): MockResponse {
        val since = request.requestUrl!!.queryParameter("since")
        val limit = request.requestUrl!!.queryParameter("limit")?.toIntOrNull() ?: 100
        if (resyncOnNextPull && since != null) {
            resyncOnNextPull = false
            return problem(410, "SYNC_RESYNC_REQUIRED")
        }
        val from = since?.toLongOrNull() ?: if (since == null) 0L else return problem(400, "BAD_REQUEST")
        val all = entries.entries.filter { it.value.version > from }.sortedBy { it.value.version }
        val page = all.take(limit)
        val token = (page.lastOrNull()?.value?.version ?: from).toString()
        return json(
            200,
            JsonObject(
                mapOf(
                    "changes" to JsonArray(
                        page.map { (k, e) ->
                            JsonObject(
                                mapOf(
                                    "op" to JsonPrimitive(if (e.live) "ADD" else "DELETE"), "languageCode" to JsonPrimitive(k.substringBefore('|')),
                                    "word" to JsonPrimitive(k.substringAfter('|')), "clientChangeId" to JsonPrimitive(e.id),
                                ),
                            )
                        },
                    ),
                    "syncToken" to JsonPrimitive(token), "hasMore" to JsonPrimitive(all.size > page.size),
                ),
            ),
        )
    }

    private fun push(body: String): MockResponse {
        pushRequestCount++
        var applied = 0
        for (c in (parse(body)["changes"] as JsonArray)) {
            val o = c.jsonObject
            val id = (o["clientChangeId"] as JsonPrimitive).content
            if (!appliedIds.add(id)) {
                applied++
                continue
            }
            val key = "${(o["languageCode"] as JsonPrimitive).content}|${(o["word"] as JsonPrimitive).content}"
            val add = (o["op"] as JsonPrimitive).content == "ADD"
            val e = entries[key]
            if (e == null || e.live != add) {
                entries[key] = Entry(add, ++version, id)
                changesApplied++
            }
            applied++
        }
        if (dropPushResponses > 0) {
            dropPushResponses--
            return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        return json(200, JsonObject(mapOf("applied" to JsonPrimitive(applied), "syncToken" to JsonPrimitive(version.toString()))))
    }

    /** Test helper: another device adds/deletes a word directly on the server. */
    fun serverWrite(language: String, word: String, add: Boolean) = synchronized(this) {
        entries["$language|$word"] = Entry(add, ++version, UUID.randomUUID().toString())
    }

    /** Test helper: the 180-day retention removed a tombstone (the word is simply absent now). */
    fun purge(language: String, word: String) { synchronized(this) { entries.remove("$language|$word") } }

    fun setDoc(name: String, build: (JsonObject) -> JsonObject) = synchronized(this) {
        val cur = docs.getValue(name)
        val next = build(cur)
        docs[name] = JsonObject(next + ("revision" to JsonPrimitive(((cur["revision"] as JsonPrimitive).intOrNull ?: 0) + 1)))
    }

    fun bodiesOf(method: String? = null): List<String> = requests.filter { method == null || it.method == method }.map { it.body }

    fun shutdown() = server.shutdown()
}
