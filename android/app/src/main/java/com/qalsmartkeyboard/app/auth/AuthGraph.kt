package com.qalsmartkeyboard.app.auth

import android.content.Context
import android.os.Build
import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.CallLogger
import com.qalsmartkeyboard.api.ClientInfo
import com.qalsmartkeyboard.api.ClientSurface
import com.qalsmartkeyboard.api.SessionManager
import com.qalsmartkeyboard.api.TransportConfig
import com.qalsmartkeyboard.app.BuildConfig
import com.qalsmartkeyboard.data.secure.EncryptedFileStore
import com.qalsmartkeyboard.data.secure.KeystoreKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Process-wide wiring of the API client and the session (A2.2). The tokens, the account kind and the
 * installation id live in `noBackupFilesDir/auth` (excluded from backups and device transfer),
 * encrypted with their own Keystore key alias, separate from the personal dictionary key.
 */
class AuthGraph private constructor(context: Context) {
    private val _updateRequired = MutableStateFlow(false)

    /** Set when any call got a 426: the app is below the platform minimum. */
    val updateRequired: StateFlow<Boolean> = _updateRequired.asStateFlow()

    private val files = EncryptedFileStore(File(context.noBackupFilesDir, "auth"), KeystoreKeys.aesGcm(KEY_ALIAS))

    val session = SessionManager(EncryptedTokenStore(files))

    val clients = ApiClients(
        TransportConfig(
            baseUrl = BuildConfig.API_BASE_URL,
            client = ClientInfo(BuildConfig.VERSION_NAME, ClientSurface.COMPANION),
            logger = CallLogger { _, status, _ -> if (status == 426) _updateRequired.value = true },
        ),
        session,
    )

    val repository = AuthRepository(
        clients = clients,
        kinds = AccountKindStore(files),
        installationId = InstallationIdStore(files)::get,
        attestation = AttestationProviders.create(context),
        device = DeviceInfo(appVersion = BuildConfig.VERSION_NAME, osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
    )

    companion object {
        /** Separate from the personal dictionary key (`userdict`); non-exportable, no user authentication. */
        const val KEY_ALIAS = "qal.auth.tokens.v1"

        @Volatile private var instance: AuthGraph? = null

        fun get(context: Context): AuthGraph =
            instance ?: synchronized(this) { instance ?: AuthGraph(context.applicationContext).also { instance = it } }
    }
}
