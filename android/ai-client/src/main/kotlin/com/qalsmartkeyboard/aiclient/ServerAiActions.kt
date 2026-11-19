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
import com.qalsmartkeyboard.ime.ai.AiTaskKind
import java.util.concurrent.TimeUnit
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
 * Client call timeouts per operation (ADR 0031). The quick keyboard actions keep the contract's 8 s (SD 17.1). The
 * long-form features have longer server budgets (ADR 0027 §6: SUMMARIZE, EXPLAIN_TEXT and SMART_REPLY 12 s per attempt /
 * 20 s deadline, DRAFT_DOCUMENT 20 s / 30 s); the client waits for the server's deadline plus [MARGIN_SECONDS] for the
 * network and the server's own work around the provider call, so a slow answer arrives instead of a client timeout.
 */
object AiTimeouts {
    const val FAST_SECONDS = 8L
    const val MARGIN_SECONDS = 5L
    const val LONG_FORM_SECONDS = 20L + MARGIN_SECONDS
    const val EMAIL_SECONDS = 30L + MARGIN_SECONDS

    fun callTimeoutSeconds(kind: AiTaskKind): Long = when (kind) {
        AiTaskKind.TRANSLATE, AiTaskKind.GRAMMAR, AiTaskKind.REWRITE, AiTaskKind.TONE -> FAST_SECONDS
        AiTaskKind.EXPLAIN, AiTaskKind.SUMMARIZE, AiTaskKind.REPLY -> LONG_FORM_SECONDS
        AiTaskKind.EMAIL -> EMAIL_SECONDS
    }
}

/**
 * The server-backed [AiActions] (A2.5, ADR 0031): `POST /ai/translate`, `/ai/transform`, `/ai/replies` and `/ai/drafts`
 * over the app's OkHttp stack (bearer, client headers, refresh authenticator). [aiClient] carries the 8 s hard timeout
 * of the contract for the keyboard's quick actions; long-form requests use a copy with [AiTimeouts]' longer call
 * timeout (same connection pool and dispatcher). Calls are asynchronous (OkHttp's dispatcher), so [submit] returns at
 * once and cancelling aborts the socket.
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
    private val lastDestinations: LastDestinationStore? = null,
    /** True only while the user has history turned on (A2.6); read per request, must not block. Default: never. */
    private val saveToHistory: () -> Boolean = { false },
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
            lastDestinations?.load()
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
            return AiAvailability(block, entitlements.quota, entitlements.planLabel, entitlements.gates(online).onDeviceModels, entitlements.entitled)
        }

    override fun destinations(): List<AiDestination> {
        val profiles = snapshot.first.sortedWith(compareBy({ it.sortOrder }, { it.label })).map(::toDestination)
        return profiles + PLAIN_LANGUAGES.map(::plain)
    }

    /**
     * Precedence: 1. the A2.4 app rule for this app (enabled; its profile if it still exists, else its
     * language); 2. the destination the user last picked in this app on this device, if it still exists;
     * 3. the first profile (sort order, label); 4. English.
     */
    override fun defaultDestination(packageName: String?): AiDestination {
        val (profiles, rules) = snapshot
        val rule = packageName?.let { pkg -> rules.firstOrNull { it.enabled && it.packageName == pkg } }
        if (rule != null) {
            rule.profileLocalId?.let { id -> profiles.firstOrNull { it.localId == id }?.let { return toDestination(it) } }
            rule.language?.let { return plain(it) }
        }
        lastDestinations?.lastFor(packageName)?.let { id ->
            profiles.firstOrNull { it.localId == id }?.let { return toDestination(it) }
            if (id.startsWith("lang:") && id.removePrefix("lang:") in PLAIN_LANGUAGES) return plain(id.removePrefix("lang:"))
        }
        return profiles.minWithOrNull(compareBy({ it.sortOrder }, { it.label }))?.let(::toDestination) ?: plain("en")
    }

    override fun submit(request: AiRequest, onOutcome: (AiOutcome) -> Unit): AiCall {
        if (!connectivity.isOnline()) {
            onOutcome(AiOutcome.Failure(AiFailure.Offline))
            return AiCall { }
        }
        val call = clientFor(request.task.kind).newCall(AiWire.request(baseUrl, request, saveToHistory()))
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

    private val longFormClient: OkHttpClient by lazy { withTimeout(AiTimeouts.LONG_FORM_SECONDS) }
    private val emailClient: OkHttpClient by lazy { withTimeout(AiTimeouts.EMAIL_SECONDS) }

    private fun withTimeout(seconds: Long): OkHttpClient {
        val b = aiClient.newBuilder().callTimeout(seconds, TimeUnit.SECONDS)
        // A read timeout shorter than the call timeout would end a slow answer early; never shorten it.
        if (aiClient.readTimeoutMillis in 1 until seconds * 1000) b.readTimeout(seconds, TimeUnit.SECONDS)
        return b.build()
    }

    /** The client whose call timeout fits [kind] ([AiTimeouts]); the quick actions use [aiClient] as configured. */
    internal fun clientFor(kind: AiTaskKind): OkHttpClient = when (AiTimeouts.callTimeoutSeconds(kind)) {
        AiTimeouts.EMAIL_SECONDS -> emailClient
        AiTimeouts.LONG_FORM_SECONDS -> longFormClient
        else -> aiClient
    }

    private fun read(request: AiRequest, r: Response): AiOutcome {
        val body = r.body?.string()
        if (!r.isSuccessful) return AiOutcome.Failure(AiWire.failure(ApiErrors.fromHttp(r.code, body, r.headers.toMultimap())))
        val result = body?.let { AiWire.result(request.task, it) } ?: return AiOutcome.Failure(AiFailure.Other)
        entitlements.recordUsage(request.task.kind, result.remaining, result.resetsAtMs)
        return AiOutcome.Success(result)
    }

    override fun appRuleDestination(packageName: String?): AiDestination? {
        val (profiles, rules) = snapshot
        val rule = packageName?.let { pkg -> rules.firstOrNull { it.enabled && it.packageName == pkg } } ?: return null
        rule.profileLocalId?.let { id -> profiles.firstOrNull { it.localId == id }?.let { return toDestination(it) } }
        return rule.language?.let(::plain)
    }

    override fun rememberDestination(packageName: String?, destination: AiDestination) {
        val store = lastDestinations ?: return
        if (packageName == null) return
        background.execute { store.remember(packageName, destination.id) } // encrypted disk write, off the caller's thread
    }

    override fun refresh() {
        background.execute { refreshNow() }
    }

    /**
     * Blocking [refresh] for a background worker (`SyncWorker`): reloads the destination snapshot and
     * refreshes plan state and the offline token (the entitlement manager throttles the calls).
     */
    fun refreshNow() {
        snapshot = destinationSource.load()
        entitlements.refresh()
        changed()
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
