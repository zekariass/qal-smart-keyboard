package com.qalsmartkeyboard.packsync

import com.qalsmartkeyboard.ime.packs.RejectReason
import com.qalsmartkeyboard.ime.packs.TrustKey
import uniffi.qal_keyboard_core.CoreException
import uniffi.qal_keyboard_core.EngineConfig
import uniffi.qal_keyboard_core.KeyStatus
import uniffi.qal_keyboard_core.KeyboardEngine
import uniffi.qal_keyboard_core.PackInfo

/**
 * Verifies a container with the core engine (`load_pack`, the path the pack manager uses) on a
 * throw-away engine, so a bad pack never touches anything.
 *
 * Trust is the build-time [trust] list only (A1.6, ADR 0007 D10). The server can only make it
 * stricter: a key id in [serverRevoked] that is also in [trust] is verified as REVOKED. A key the
 * server announces and the build does not know stays unknown (`UNKNOWN_KEY`).
 */
class PackVerifier(private val trust: List<TrustKey>, private val maxCandidates: UInt = 8u) {
    sealed interface Verdict {
        class Ok(val info: PackInfo) : Verdict
        class Rejected(val code: String) : Verdict
    }

    fun verify(container: ByteArray, serverRevoked: Set<String>): Verdict {
        val keys = trust.map { if (it.id in serverRevoked) it.copy(status = KeyStatus.REVOKED) else it }.map { it.toCore() }
        val engine = KeyboardEngine(EngineConfig(maxCandidates, keys, null))
        return try {
            Verdict.Ok(engine.loadPack(container))
        } catch (e: CoreException) {
            Verdict.Rejected(
                when (e) {
                    is CoreException.PackRejected -> RejectReason.entries.firstOrNull { it.name == e.reason.name }?.name ?: "OTHER"
                    else -> "OTHER"
                },
            )
        } finally {
            engine.close()
        }
    }
}
