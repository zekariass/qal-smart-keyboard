package com.qalsmartkeyboard.ime

import uniffi.qal_keyboard_core.CoreException
import uniffi.qal_keyboard_core.EngineConfig
import uniffi.qal_keyboard_core.KeyStatus
import uniffi.qal_keyboard_core.KeyboardEngine
import uniffi.qal_keyboard_core.TrustedKey

/**
 * TEMPORARY bundled-pack loading (plan A1.3). Plan step A1.6 (pack manager: installed-version
 * bookkeeping, version check, corrupt-pack fallback to the bundled copy) replaces this file.
 *
 * The trust anchor is the DEV signing key named in `assets/packs/seed-packs.json`
 * (`signingKeyIsDevOnly: true`): good enough to exercise the DRAFT seed packs, never a release
 * configuration. Every pack still goes through the core's full verification (Ed25519 signature,
 * SHA-256, limits, rollback slots); a pack that fails is skipped and logged without content.
 */
object BundledPacks {
    const val ASSET_DIR = "packs"
    const val INDEX_FILE = "seed-packs.json"

    /** Candidates per update; the strip shows as many as fit. */
    const val MAX_CANDIDATES = 8u

    /** What `seed-packs.json` says: the signing key and the pack files, in load order. */
    data class Index(val keyId: String, val publicKey: ByteArray, val files: List<String>)

    /**
     * Creates an engine trusting [index]'s key and loads every listed pack from [read].
     * [log] receives one line per failure: file name and error type only (no content, N2).
     * Throws only if the engine itself cannot be created (for example a malformed key).
     */
    fun createEngine(index: Index, read: (String) -> ByteArray, log: (String) -> Unit): KeyboardEngine {
        val engine = KeyboardEngine(
            EngineConfig(
                maxCandidates = MAX_CANDIDATES,
                trustedKeys = listOf(TrustedKey(index.keyId, index.publicKey, KeyStatus.ACTIVE)),
                packLimits = null,
            ),
        )
        for (file in index.files) {
            try {
                engine.loadPack(read(file))
            } catch (e: CoreException) {
                log("pack $file rejected: ${describe(e)}")
            } catch (e: java.io.IOException) {
                log("pack $file unreadable: ${e.javaClass.simpleName}")
            }
        }
        return engine
    }

    private fun describe(e: CoreException): String = when (e) {
        is CoreException.PackRejected -> "PackRejected/${e.reason}"
        else -> e.javaClass.simpleName
    }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
