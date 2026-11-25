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
import com.qalsmartkeyboard.ime.ai.TranslationCatalog
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
 * Client call timeouts per operation (ADR 0031). Quick actions that route to an LLM wait for the server's deadline plus
 * [MARGIN_SECONDS] (ADR 0027 update: translate 8 s attempt / 12 s deadline, grammar, rewrite and tone 6 s / 10 s), so
 * translate waits [TRANSLATE_SECONDS] and the others [QUICK_LLM_SECONDS]. The long-form features have longer server budgets (ADR 0027 §6: SUMMARIZE, EXPLAIN_TEXT and SMART_REPLY 12 s per attempt /
 * 20 s deadline, DRAFT_DOCUMENT 20 s / 30 s); the client waits for the server's deadline plus [MARGIN_SECONDS] for the
 * network and the server's own work around the provider call, so a slow answer arrives instead of a client timeout.
 */
object AiTimeouts {
    /**
     * Connecting to the server (TCP and TLS), separate from the call timeouts below: a server that cannot be reached at
     * all (wrong address, down, a phone build pointing at the emulator's `10.0.2.2`) fails within this as
     * [AiFailure.Unreachable] instead of hanging until the call timeout and reading "That took too long".
     */
    const val CONNECT_SECONDS = 5L
    const val FAST_SECONDS = 8L
    const val TRANSLATE_SECONDS = 15L
    const val QUICK_LLM_SECONDS = 12L
    const val MARGIN_SECONDS = 5L
    const val LONG_FORM_SECONDS = 20L + MARGIN_SECONDS
    const val EMAIL_SECONDS = 30L + MARGIN_SECONDS

    /** Chat (T8.5): the server's budget is about 20 s per provider attempt, 30 s in total; the client waits about 35 s. */
    const val CHAT_SECONDS = 30L + MARGIN_SECONDS

    fun callTimeoutSeconds(kind: AiTaskKind): Long = when (kind) {
        AiTaskKind.TRANSLATE -> TRANSLATE_SECONDS
        AiTaskKind.GRAMMAR, AiTaskKind.REWRITE, AiTaskKind.TONE, AiTaskKind.FORMAT -> QUICK_LLM_SECONDS
        AiTaskKind.EXPLAIN, AiTaskKind.SUMMARIZE, AiTaskKind.REPLY -> LONG_FORM_SECONDS
        AiTaskKind.EMAIL -> EMAIL_SECONDS
        AiTaskKind.CHAT -> CHAT_SECONDS
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
 *
 * **Shared session (ADR 0035):** the keyboard and the companion app use one session. Every session change reaches the
 * availability listeners (the toolbar, the panels, the premium themes) without a restart. When the account itself
 * changes (sign-in, sign-out, another account) the previous account's plan state and entitlement token are dropped
 * and, when someone is signed in, fetched again for the new account in the background (a user action, never the
 * keyboard's start-up). A token rotation of the same account only re-renders.
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
    /** The call timeout in seconds per kind; tests shorten it. */
    private val callTimeouts: (AiTaskKind) -> Long = AiTimeouts::callTimeoutSeconds,
    /** The device's copy of the translation language catalogue (ADR 0040); null: the bundled five languages only. */
    private val catalogStore: TranslationCatalogStore? = null,
) : AiActions {
    /**
     * [aiClient] with the short connect timeout ([AiTimeouts.CONNECT_SECONDS], never longer than the caller's) and the
     * network interceptor that marks a request once a connection carries it, so a failure before that point is told
     * apart from a slow answer ([AiFailure.Unreachable] vs [AiFailure.Timeout]).
     */
    private val baseClient: OkHttpClient = aiClient.newBuilder()
        .connectTimeout(minOf(aiClient.connectTimeoutMillis.toLong().takeIf { it > 0 } ?: Long.MAX_VALUE, AiTimeouts.CONNECT_SECONDS * 1000), TimeUnit.MILLISECONDS)
        .addNetworkInterceptor(ConnectionMark.Interceptor)
        .build()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var snapshot: Pair<List<DestinationProfile>, List<AppRule>> = emptyList<DestinationProfile>() to emptyList()
    private val connectivityHandle: AutoCloseable = connectivity.addListener { changed() }

    /** The account the cached plan state belongs to (`sub`, or null when signed out); only touched on [background]. */
    private var account: String? = null

    @Volatile private var accountGeneration = 0L

    private val sessionHandle: AutoCloseable

    init {
        entitlements.onChanged = { changed() }
        // Disk reads (the encrypted token and destination files) happen here, never on the main thread.
        background.execute {
            account = currentAccount()
            entitlements.loadCached()
            catalogStore?.load()
            snapshot = destinationSource.load()
            lastDestinations?.load()
            changed()
        }
        // The listener runs on the thread that changed the session (maybe inside the refresh lock): hand over at once.
        sessionHandle = session.addListener { background.execute(::onSessionChanged) }
    }

    fun changed() = listeners.forEach { it() }

    private fun currentAccount(): String? = if (session.signedIn()) "user:" + session.subject() else null

    /** On [background]: a sign-in, sign-out, account switch or token rotation, in the app or the keyboard. */
    internal fun onSessionChanged() {
        val now = currentAccount()
        if (now != account) {
            account = now
            accountGeneration++
            entitlements.clear() // nothing of the previous account stays; notifies the listeners (signed in/out now)
            if (now != null) entitlements.refresh(force = true) // the new account's plan state and offline token
            catalogStore?.invalidate() // the catalogue follows the plan: fetched again on the next refresh
        }
        changed()
    }

    override val availability: AiAvailability
        get() {
            val online = connectivity.isOnline()
            val block = when {
                updateRequired() -> AiBlock.UPDATE_REQUIRED
                !session.signedIn() -> AiBlock.SIGNED_OUT
                !online -> AiBlock.OFFLINE
                else -> AiBlock.NONE
            }
            return AiAvailability(
                block, entitlements.quota, entitlements.planLabel, entitlements.gates(online).onDeviceModels, entitlements.entitled,
                accountGeneration,
                creditBalance = entitlements.creditBalance,
            )
        }

    override fun destinations(): List<AiDestination> {
        val profiles = snapshot.first.sortedWith(compareBy({ it.sortOrder }, { it.label })).map(::toDestination)
        val catalog = translationCatalog()
        val plain = (PLAIN_LANGUAGES.filter(catalog::isDestination) + catalog.destinations.map { it.code }).distinct()
        return profiles + plain.map(::plain)
    }

    override fun translationCatalog(): TranslationCatalog = catalogStore?.catalog ?: TranslationCatalog.BUNDLED

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
            if (id.startsWith("lang:") && translationCatalog().isDestination(id.removePrefix("lang:"))) return plain(id.removePrefix("lang:"))
        }
        return profiles.minWithOrNull(compareBy({ it.sortOrder }, { it.label }))?.let(::toDestination) ?: plain("en")
    }

    /**
     * Every path ends in exactly one [onOutcome] unless the user cancelled: offline (no request), a request that cannot
     * even be built (a broken base URL: [AiFailure.Other]), no connection made ([AiFailure.Unreachable]: refused,
     * unknown host, connect timeout), a connection that failed or timed out after the request went out
     * ([AiFailure.Offline] / [AiFailure.Timeout]), an HTTP error (its problem code), an unreadable answer
     * ([AiFailure.Other]). The UI's loading state therefore always ends. The `Idempotency-Key` is the request's id, so a
     * retry of the same request is replayed by the server, never charged twice (ADR 0031).
     */
    override fun submit(request: AiRequest, onOutcome: (AiOutcome) -> Unit): AiCall {
        if (!connectivity.isOnline()) {
            onOutcome(AiOutcome.Failure(AiFailure.Offline))
            return AiCall { }
        }
        val mark = ConnectionMark()
        val call = try {
            val built = AiWire.request(baseUrl, request, saveToHistory()).newBuilder().tag(ConnectionMark::class.java, mark).build()
            clientFor(request.task.kind).newCall(built)
        } catch (e: RuntimeException) {
            onOutcome(AiOutcome.Failure(AiFailure.Other)) // a malformed base URL: nothing was sent
            return AiCall { }
        }
        // OkHttp also marks a call cancelled when its call timeout fires, so only the user's cancel is silent.
        val userCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (userCancelled.get()) return
                onOutcome(AiOutcome.Failure(transportFailure(e, mark)))
            }

            override fun onResponse(call: Call, response: Response) {
                val outcome = try {
                    response.use { read(request, it) }
                } catch (e: IOException) {
                    if (userCancelled.get()) return
                    AiOutcome.Failure(transportFailure(e, mark))
                } catch (e: RuntimeException) {
                    AiOutcome.Failure(AiFailure.Other) // never leave the caller waiting on a parsing or bookkeeping bug
                }
                onOutcome(outcome)
            }
        })
        return AiCall {
            userCancelled.set(true)
            call.cancel()
        }
    }

    /** No connection ever carried the request: the server could not be reached. Otherwise the usual mapping. */
    private fun transportFailure(e: IOException, mark: ConnectionMark): AiFailure =
        if (!mark.connected) AiFailure.Unreachable else AiWire.failure(ApiErrors.map(e))

    private val clientsBySeconds = java.util.concurrent.ConcurrentHashMap<Long, OkHttpClient>()

    private fun withTimeout(seconds: Long): OkHttpClient {
        val b = baseClient.newBuilder().callTimeout(seconds, TimeUnit.SECONDS)
        // A read timeout shorter than the call timeout would end a slow answer early; never shorten it.
        if (baseClient.readTimeoutMillis in 1 until seconds * 1000) b.readTimeout(seconds, TimeUnit.SECONDS)
        return b.build()
    }

    /**
     * The client whose call timeout fits [kind] ([AiTimeouts]): [aiClient] with that call timeout and the short connect
     * timeout (same pool and dispatcher).
     */
    internal fun clientFor(kind: AiTaskKind): OkHttpClient =
        clientsBySeconds.getOrPut(callTimeouts(kind)) { withTimeout(callTimeouts(kind)) }

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
        refreshCatalog()
        changed()
    }

    /**
     * ADR 0040: the translation language catalogue, when online and not fetched lately (throttled by the store; `ETag`
     * revalidation). Metadata only: no user text. Blocking: on [background] or a worker.
     */
    fun refreshCatalog(force: Boolean = false) {
        val store = catalogStore ?: return
        if (!connectivity.isOnline()) return
        store.refresh(baseClient, baseUrl, force)
    }

    override fun addAvailabilityListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun close() {
        connectivityHandle.close()
        sessionHandle.close()
    }

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
        /**
         * Destinations listed first besides the user's profiles: the languages the keyboard types, Arabic included (ADR
         * 0034); the rest of the translation catalogue follows (ADR 0040). A pair the server cannot translate fails as
         * LanguageUnsupported, so nothing is faked.
         */
        val PLAIN_LANGUAGES = listOf("en", "am", "ti", "om", "ar")
    }
}
