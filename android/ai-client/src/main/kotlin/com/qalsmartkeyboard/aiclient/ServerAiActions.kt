package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.api.ApiErrors
import com.qalsmartkeyboard.ime.LanguageCatalog
import com.qalsmartkeyboard.ime.ai.AiActions
import com.qalsmartkeyboard.ime.ai.AiAvailability
import com.qalsmartkeyboard.ime.ai.AiBlock
import com.qalsmartkeyboard.ime.ai.AiCall
import com.qalsmartkeyboard.ime.ai.AiDestination
import com.qalsmartkeyboard.ime.ai.AiFailure
import com.qalsmartkeyboard.ime.ai.AiOutcome
import com.qalsmartkeyboard.ime.ai.AiRequest
import com.qalsmartkeyboard.sync.AppRule
import com.qalsmartkeyboard.sync.DestinationProfile
import com.qalsmartkeyboard.sync.DestinationStore
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response

/** Whether the device has a usable network. Cached and instant; [addListener] fires on changes. */
interface Connectivity {
    fun isOnline(): Boolean
    fun addListener(listener: () -> Unit): AutoCloseable
}

/** The destination profiles and per-app rules of A2.4's local store, as a snapshot (the store is encrypted disk). */
fun interface DestinationSource {
    fun load(): Pair<List<DestinationProfile>, List<AppRule>>

    companion object {
        fun of(store: DestinationStore) = DestinationSource { store.profiles() to store.rules() }
    }
}

/**
 * The server-backed [AiActions] (A2.5): `POST /ai/translate` and `POST /ai/transform` over the
 * app's OkHttp stack (bearer, client headers, refresh authenticator). [aiClient] carries the 8 s
 * hard timeout of the contract for the keyboard. Calls are asynchronous (OkHttp's dispatcher), so
 * [submit] returns at once and cancelling aborts the socket.
 *
 * Nothing here sends user text except [submit]; [refresh] only reads account metadata. Text,
 * results and tokens are never logged.
 */
class ServerAiActions(
    private val aiClient: OkHttpClient,
    private val baseUrl: String,
    private val session: SessionInfo,
    private val connectivity: Connectivity,
    private val destinationSource: DestinationSource,
    private val entitlements: EntitlementManager,
    private val updateRequired: () -> Boolean,
    private val background: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "qal-ai-refresh").apply { isDaemon = true } },
) : AiActions {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var snapshot: Pair<List<DestinationProfile>, List<AppRule>> = emptyList<DestinationProfile>() to emptyList()
    private val connectivityHandle: AutoCloseable = connectivity.addListener { changed() }

    init {
        entitlements.onChanged = { changed() }
        // Disk reads (the encrypted token and destination files) happen here, never on the main thread.
        background.execute {
            entitlements.loadCached()
            snapshot = destinationSource.load()
            changed()
        }
    }

    fun changed() = listeners.forEach { it() }

    override val availability: AiAvailability
        get() {
            val online = connectivity.isOnline()
            val block = when {
                updateRequired() -> AiBlock.UPDATE_REQUIRED
                !session.signedIn() -> AiBlock.SIGNED_OUT
                !online -> AiBlock.OFFLINE
                else -> AiBlock.NONE
            }
            return AiAvailability(block, entitlements.quota, entitlements.planLabel, entitlements.gates(online).onDeviceModels)
        }

    override fun destinations(): List<AiDestination> {
        val profiles = snapshot.first.sortedWith(compareBy({ it.sortOrder }, { it.label })).map(::toDestination)
        return profiles + PLAIN_LANGUAGES.map(::plain)
    }

    override fun defaultDestination(packageName: String?): AiDestination {
        val (profiles, rules) = snapshot
        val rule = packageName?.let { pkg -> rules.firstOrNull { it.enabled && it.packageName == pkg } }
        if (rule != null) {
            rule.profileLocalId?.let { id -> profiles.firstOrNull { it.localId == id }?.let { return toDestination(it) } }
            rule.language?.let { return plain(it) }
        }
        return profiles.minWithOrNull(compareBy({ it.sortOrder }, { it.label }))?.let(::toDestination) ?: plain("en")
    }

    override fun submit(request: AiRequest, onOutcome: (AiOutcome) -> Unit): AiCall {
        if (!connectivity.isOnline()) {
            onOutcome(AiOutcome.Failure(AiFailure.Offline))
            return AiCall { }
        }
        val call = aiClient.newCall(AiWire.request(baseUrl, request))
        // OkHttp also marks a call cancelled when its call timeout fires, so only the user's cancel is silent.
        val userCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (userCancelled.get()) return
                onOutcome(AiOutcome.Failure(AiWire.failure(ApiErrors.map(e))))
            }

            override fun onResponse(call: Call, response: Response) {
                val outcome = try {
                    response.use { read(request, it) }
                } catch (e: IOException) {
                    if (userCancelled.get()) return
                    AiOutcome.Failure(AiWire.failure(ApiErrors.map(e)))
                }
                onOutcome(outcome)
            }
        })
        return AiCall {
            userCancelled.set(true)
            call.cancel()
        }
    }

    private fun read(request: AiRequest, r: Response): AiOutcome {
        val body = r.body?.string()
        if (!r.isSuccessful) return AiOutcome.Failure(AiWire.failure(ApiErrors.fromHttp(r.code, body, r.headers.toMultimap())))
        val result = body?.let { AiWire.result(request.task, it) } ?: return AiOutcome.Failure(AiFailure.Other)
        entitlements.recordUsage(request.task.kind, result.remaining, result.resetsAtMs)
        return AiOutcome.Success(result)
    }

    override fun refresh() {
        background.execute {
            snapshot = destinationSource.load()
            entitlements.refresh()
            changed()
        }
    }

    override fun addAvailabilityListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun close() = connectivityHandle.close()

    private fun toDestination(p: DestinationProfile) = AiDestination(
        id = p.localId,
        label = p.label,
        language = p.destinationLanguage,
        isProfile = true,
        serverProfileId = p.serverId,
        formality = p.formality,
        addresseeGender = p.addresseeGender,
        style = p.defaultStyle,
        glossaryId = p.glossaryId,
    )

    private fun plain(language: String) = AiDestination("lang:$language", LanguageCatalog.displayName(language), language)

    companion object {
        /** Destinations listed besides the user's profiles: the languages the keyboard types. */
        val PLAIN_LANGUAGES = listOf("en", "am", "ti", "om")
    }
}
