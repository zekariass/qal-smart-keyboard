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

    /** The build's trust keys, or null if the trust configuration is not acceptable ([PackTrust.check]). */
    fun trustedKeys(context: Context, log: (String) -> Unit = {}): List<TrustKey>? {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val keys = PackTrust.configured()
        val decision = PackTrust.check(keys, debuggable, PackTrustConfig.ALLOW_DEV_KEYS_IN_RELEASE)
        if (decision is PackTrust.Decision.Refused) {
            log("pack trust refused: ${decision.reason}")
            return null
        }
        return keys
    }

    fun versionStore(context: Context) = VersionStore(File(root(context), "versions.txt"))

    /** The update journal shared with the app-side updater (A2.3). */
    fun journal(context: Context, log: (String) -> Unit = {}) = PackJournal(root(context), installedDir(context), log = log)

    /**
     * The pack loader for this app, or null if the build's trust configuration is not acceptable
     * ([PackTrust.check]): then the keyboard stays on ABC rather than trusting a DEV key.
     */
    fun create(context: Context, log: (String) -> Unit): PackRuntime? {
        val keys = trustedKeys(context, log) ?: return null
        val versions = versionStore(context)
        return PackRuntime(
            PackManager(
                trust = keys,
                bundled = { bundledFiles(context) },
                installedDir = installedDir(context),
                versions = versions,
                log = log,
            ),
            journal(context, log),
            versions,
        )
    }
}

/**
 * [PackManager] plus the update journal (A2.3): rolls back a swapped-in pack that fails to load or
 * that crashed the previous starts, then loads again. Call [load] off the main thread.
 */
class PackRuntime(val manager: PackManager, val journal: PackJournal, private val versions: VersionStore) {
    /** A load and the swapped-in packs that await [confirm] (the engine has to be shown first). */
    class Loaded(val result: LoadResult, val awaitingConfirmation: List<PendingSwap>)

    fun load(): Loaded {
        versions.reload() // the updater may have changed the floors since the last load (D34)
        journal.beforeLoad(versions)
        var result = manager.load()
        var after = journal.afterLoad(result, versions)
        if (after.needsReload) {
            // A swap was rolled back: the restored pack is on disk now, load once more.
            result.engine.close()
            result = manager.load()
            after = journal.afterLoad(result, versions)
        }
        return Loaded(result, after.awaitingConfirmation)
    }

    /** The engine built by [loaded] was used successfully: the update is healthy, `.prev` can go. */
    fun confirm(loaded: Loaded) = journal.confirm(loaded.awaitingConfirmation)
}
