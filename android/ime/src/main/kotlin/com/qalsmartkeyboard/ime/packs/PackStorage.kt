package com.qalsmartkeyboard.ime.packs

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File

/**
 * Android wiring of the [PackManager]: bundled packs from the `assets/packs` directory (`.pack` files), installed packs
 * from `<noBackupFilesDir>/packs/installed` directory, versions in `<noBackupFilesDir>/packs/versions.txt`.
 * `no_backup` keeps a restored backup from bringing back installed packs without their version
 * floor (or the reverse). A2.3 writes downloaded packs into [installedDir] (temp file, verify, then
 * rename to a `.pack` name).
 */
object PackStorage {
    const val ASSET_DIR = "packs"

    fun root(context: Context) = File(context.noBackupFilesDir, "packs")
    fun installedDir(context: Context) = File(root(context), "installed")

    fun bundledFiles(context: Context): List<PackFile> {
        val assets = context.applicationContext.assets
        val names = assets.list(ASSET_DIR)?.filter { it.endsWith(".pack") }?.sorted() ?: emptyList()
        return names.map { n ->
            PackFile(n, PackOrigin.BUNDLED, read = { assets.open("$ASSET_DIR/$n").use { it.readBytes() } })
        }
    }

    /**
     * The manager for this app, or null if the build's trust configuration is not acceptable
     * ([PackTrust.check]): then the keyboard stays on ABC rather than trusting a DEV key.
     */
    fun create(context: Context, log: (String) -> Unit): PackManager? {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val keys = PackTrust.configured()
        val decision = PackTrust.check(keys, debuggable, PackTrustConfig.ALLOW_DEV_KEYS_IN_RELEASE)
        if (decision is PackTrust.Decision.Refused) {
            log("pack trust refused: ${decision.reason}")
            return null
        }
        return PackManager(
            trust = keys,
            bundled = { bundledFiles(context) },
            installedDir = installedDir(context),
            versions = VersionStore(File(root(context), "versions.txt")),
            log = log,
        )
    }
}
