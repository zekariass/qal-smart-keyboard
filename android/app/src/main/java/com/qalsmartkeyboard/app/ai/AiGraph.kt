package com.qalsmartkeyboard.app.ai

import android.content.Context
import com.qalsmartkeyboard.aiclient.AndroidConnectivity
import com.qalsmartkeyboard.aiclient.DestinationSource
import com.qalsmartkeyboard.aiclient.EntitlementManager
import com.qalsmartkeyboard.aiclient.EntitlementTokenStore
import com.qalsmartkeyboard.aiclient.EntitlementTokenVerifier
import com.qalsmartkeyboard.aiclient.PinnedKeys
import com.qalsmartkeyboard.aiclient.ServerAiActions
import com.qalsmartkeyboard.aiclient.SessionAccessInfo
import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.CallLogger
import com.qalsmartkeyboard.api.ClientInfo
import com.qalsmartkeyboard.api.ClientSurface
import com.qalsmartkeyboard.api.TransportConfig
import com.qalsmartkeyboard.app.BuildConfig
import com.qalsmartkeyboard.app.auth.AuthGraph
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
        ),
        auth.session,
    )

    private val files = EncryptedFileStore(File(context.noBackupFilesDir, "ai"), KeystoreKeys.aesGcm(KEY_ALIAS))

    private val session = SessionAccessInfo(auth.session)

    private val connectivity = AndroidConnectivity(context)

    // Plan hints and the token refresh are metadata calls: a few seconds of timeout is plenty.
    private val metaClient = clients.okHttpClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    private val entitlements = EntitlementManager(
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
    )

    companion object {
        const val KEY_ALIAS = "qal.ai.v1"

        /** SD 17.1: client hard timeout 8 s for AI calls. */
        const val AI_TIMEOUT_SECONDS = 8L

        @Volatile private var instance: AiGraph? = null

        fun get(context: Context): AiGraph =
            instance ?: synchronized(this) { instance ?: AiGraph(context.applicationContext).also { instance = it } }
    }
}
