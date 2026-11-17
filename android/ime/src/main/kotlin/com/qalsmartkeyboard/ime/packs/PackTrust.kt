package com.qalsmartkeyboard.ime.packs

import uniffi.qal_keyboard_core.KeyStatus
import uniffi.qal_keyboard_core.TrustedKey

/** A pinned pack-signing public key from the build configuration. */
data class TrustKey(
    val id: String,
    val publicKeyHex: String,
    /** True for keys whose private half is in the repo or on a developer machine (never a release anchor). */
    val devOnly: Boolean,
    val status: KeyStatus = KeyStatus.ACTIVE,
) {
    fun toCore() = TrustedKey(id, hexToBytes(publicKeyHex), status)
}

/**
 * Where the pack trust anchors come from and when they may be used (plan A1.6).
 *
 * The keys are generated into [PackTrustConfig] at build time by the `generate<Variant>PackTrustConfig`
 * Gradle task in `android/ime/build.gradle.kts`; they are never read from a file next to the packs.
 * Debug builds trust the DEV key named in `assets/packs/seed-packs.json`. A release build needs a
 * keys file (`-Pqal.packTrustKeysFile=...`); the build fails if only DEV keys are configured unless
 * `-Pqal.allowDevPackKeys=true` is given. [check] repeats the rule at run time as defence in depth.
 */
object PackTrust {
    sealed interface Decision {
        data object Ok : Decision
        data class Refused(val reason: String) : Decision
    }

    /**
     * [debuggable]: the app is a debuggable build. [allowDevKeysInRelease]: the explicit build
     * property. Refuses an empty key set, and DEV keys in a non-debuggable build unless allowed.
     */
    fun check(keys: List<TrustKey>, debuggable: Boolean, allowDevKeysInRelease: Boolean): Decision = when {
        keys.isEmpty() -> Decision.Refused("no trusted pack keys configured")
        !debuggable && !allowDevKeysInRelease && keys.any { it.devOnly } ->
            Decision.Refused("DEV pack signing key configured in a release build")
        else -> Decision.Ok
    }

    /** The keys compiled into this build. */
    fun configured(): List<TrustKey> = PackTrustConfig.KEYS
}

internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "odd hex length" }
    return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
