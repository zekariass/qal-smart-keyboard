package com.qalsmartkeyboard.app.ai

import android.content.Context
import com.qalsmartkeyboard.aiclient.AiTimeouts
import com.qalsmartkeyboard.aiclient.AndroidConnectivity
import com.qalsmartkeyboard.aiclient.DestinationSource
import com.qalsmartkeyboard.aiclient.EntitlementManager
import com.qalsmartkeyboard.aiclient.EntitlementTokenStore
import com.qalsmartkeyboard.aiclient.EntitlementTokenVerifier
import com.qalsmartkeyboard.aiclient.LastDestinationStore
import com.qalsmartkeyboard.aiclient.PinnedKeys
import com.qalsmartkeyboard.aiclient.ServerAiActions
import com.qalsmartkeyboard.aiclient.ServerFxRateSource
import com.qalsmartkeyboard.aiclient.ServerThemeAccess
import com.qalsmartkeyboard.aiclient.SessionAccessInfo
import com.qalsmartkeyboard.aiclient.TranslationCatalogStore
import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.CallLogger
import com.qalsmartkeyboard.api.ClientInfo
import com.qalsmartkeyboard.api.ClientSurface
import com.qalsmartkeyboard.api.TransportConfig
import com.qalsmartkeyboard.app.BuildConfig
import com.qalsmartkeyboard.app.auth.AuthGraph
import com.qalsmartkeyboard.app.privacy.PrivacyGraph
import com.qalsmartkeyboard.app.sync.SyncGraph
import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import com.qalsmartkeyboard.data.secure.KeystoreKeys
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Process-wide wiring of the keyboard's AI client (A2.5). Same session as the companion app
 * ([AuthGraph.session]) but its own [ApiClients] with `X-Client-Surface: KEYBOARD` and the
 * contract's 8 s client timeout for AI calls. The cached entitlement token lives in
 * `noBackupFilesDir/ai`, encrypted with its own Keystore alias. Built on a background thread by the
 * keyboard service ([com.qalsmartkeyboard.ime.ai.AiActionsHolder]); never on the main thread.
 */
class AiGraph private constructor(context: Context) {
    @Volatile private var updateRequired = false

    private val auth = AuthGraph.get(context)

    private val clients = ApiClients(
        TransportConfig(
            baseUrl = BuildConfig.API_BASE_URL,
            client = ClientInfo(BuildConfig.VERSION_NAME, ClientSurface.KEYBOARD),
            logger = CallLogger { _, status, _ -> if (status == 426) updateRequired = true },
            extraCleartextHosts = BaseUrlGuard.debugCleartextHosts(BuildConfig.API_BASE_URL, BuildConfig.DEBUG),
        ),
        auth.session,
    )

    private val files = EncryptedFileStore(File(context.noBackupFilesDir, "ai"), KeystoreKeys.aesGcm(KEY_ALIAS))

    private val session = SessionAccessInfo(auth.session)

    private val connectivity = AndroidConnectivity(context)

    // Plan hints and the token refresh are metadata calls: a few seconds of timeout is plenty.
    private val metaClient = clients.okHttpClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    /** Also refreshed by the paywall after a verified purchase (ADR 0047), so the keyboard unlocks at once. */
    val entitlements = EntitlementManager(
        http = metaClient,
        baseUrl = BuildConfig.API_BASE_URL,
        session = session,
        verifier = EntitlementTokenVerifier(PinnedKeys.fromJwks(BuildConfig.ENTITLEMENT_JWKS)),
        store = EntitlementTokenStore(files),
    )

    val actions: ServerAiActions = ServerAiActions(
        aiClient = clients.okHttpClient.newBuilder().callTimeout(AI_TIMEOUT_SECONDS, TimeUnit.SECONDS).build(),
        baseUrl = BuildConfig.API_BASE_URL,
        session = session,
        connectivity = connectivity,
        destinationSource = DestinationSource.of(SyncGraph.get(context).destinations),
        entitlements = entitlements,
        updateRequired = { updateRequired || auth.updateRequired.value },
        lastDestinations = LastDestinationStore(files),
        // A2.6: only while the person turned history on (the privacy screens set it); the server still checks consent and plan.
        saveToHistory = { PrivacyGraph.get(context).historyFlag.enabled },
        // ADR 0040: the translation language catalogue's device copy (language names only: a plain file).
        catalogStore = TranslationCatalogStore(File(context.noBackupFilesDir, "translation-catalog.json")),
    )

    /** Premium keyboard themes (`PREMIUM_THEMES`), answered from the cached verified entitlement token. */
    val themeAccess = ServerThemeAccess(entitlements, session, connectivity) { listener -> actions.addAvailabilityListener(listener) }

    /**
     * The currency converter's rates (`GET /tools/fx-rates`): public data, fetched without the session over the transport's
     * bearer-free client, at the same base URL as the AI calls (both flavours). Null when the build has no base URL.
     */
    val fxRates: ServerFxRateSource? =
        BuildConfig.API_BASE_URL.takeIf { it.isNotBlank() }?.let { ServerFxRateSource(clients.anonymousClient, it) }

    companion object {
        const val KEY_ALIAS = "qal.ai.v1"

        /**
         * SD 17.1: client hard timeout 8 s for the quick AI calls. The long-form operations (explain, summarise, replies,
         * e-mail) get longer ones from `ServerAiActions` ([AiTimeouts], ADR 0031).
         */
        const val AI_TIMEOUT_SECONDS = AiTimeouts.FAST_SECONDS

        @Volatile private var instance: AiGraph? = null

        fun get(context: Context): AiGraph =
            instance ?: synchronized(this) { instance ?: AiGraph(context.applicationContext).also { instance = it } }
    }
}
