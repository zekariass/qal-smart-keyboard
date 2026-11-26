package com.qalsmartkeyboard.app.creative.backup

import android.content.Context
import com.qalsmartkeyboard.aiclient.SessionAccessInfo
import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.api.generated.apis.CreativeBackupApi
import com.qalsmartkeyboard.app.auth.AuthGraph
import com.qalsmartkeyboard.app.privacy.ConsentCatalog
import com.qalsmartkeyboard.app.privacy.PrivacyGraph
import com.qalsmartkeyboard.ime.draw.DrawingStore
import java.io.File

/**
 * Process-wide wiring of the opt-in cloud backup of the Draw gallery (T8.15 S3): the generated `CreativeBackupApi` over the
 * app's shared client (bearer, refresh, client headers), the device's drawing store (the very directory the keyboard writes),
 * and the record of what was uploaded, in `noBackupFilesDir/creative-backup/record.json` (ids and numbers; excluded from
 * Auto Backup and device transfer). The account is the access token's `sub`, so a record is only ever used by the account
 * that wrote it.
 */
class CreativeBackupGraph private constructor(context: Context) {
    private val auth = AuthGraph.get(context)
    private val session = SessionAccessInfo(auth.session)

    val remote: BackupRemote = ApiBackupRemote(auth.clients.api(::CreativeBackupApi))

    val engine = BackupEngine(
        local = DrawingStore(File(context.filesDir, "drawings")),
        remote = remote,
        records = FileBackupRecordStore(recordFile(context)),
        userId = { session.subject() },
    )

    /** The consent decision goes through the same call as the privacy screens' switches (`PUT /me/consents/CREATIVE_BACKUP`). */
    val consent: BackupConsentPort = object : BackupConsentPort {
        override suspend fun set(granted: Boolean) {
            try {
                PrivacyGraph.get(context).api.setConsent(ConsentCatalog.CREATIVE_BACKUP, granted, SURFACE)
            } catch (e: ApiError) {
                throw BackupException(e.toBackupError())
            }
        }
    }

    companion object {
        /** `details.surface` of the consent decision made on the studio's card. */
        const val SURFACE = "creative_studio"

        private fun recordFile(context: Context) = File(File(context.noBackupFilesDir, "creative-backup"), "record.json")

        /**
         * Forgets what this device uploaded (sign-out, account deletion, the consent withdrawn on the privacy screen). The cloud
         * copies stay on the server until deleted or the consent is withdrawn. Touches only the record file.
         */
        fun forgetRecord(context: Context) = FileBackupRecordStore(recordFile(context.applicationContext)).clear()

        @Volatile private var instance: CreativeBackupGraph? = null

        fun get(context: Context): CreativeBackupGraph =
            instance ?: synchronized(this) { instance ?: CreativeBackupGraph(context.applicationContext).also { instance = it } }
    }
}
