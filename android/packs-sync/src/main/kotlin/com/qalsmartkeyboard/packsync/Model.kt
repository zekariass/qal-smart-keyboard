package com.qalsmartkeyboard.packsync

/**
 * Discovery metadata of one pack (contract `LanguageResourceManifest`). Untrusted: the downloaded
 * container's own signed manifest decides; this only says what to download and what to expect.
 */
data class ResourceOffer(
    val id: String,
    val languageCode: String,
    val resourceType: String,
    val version: String,
    val platform: String,
    val channel: String,
    val artifactUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val containerSizeBytes: Long?,
    val baseResourceId: String?,
    val signatureKeyId: String,
    val minAppVersion: String?,
) {
    val slot: String get() = com.qalsmartkeyboard.ime.packs.VersionStore.slot(languageCode, resourceType, platform)
}

/** A key as announced by `GET /resource-signing-keys`. Only its status is used (revocation); it is never trusted. */
data class KeyAnnouncement(val keyId: String, val status: String)

/** The discovery API as the updater needs it. Blocking; throws `com.qalsmartkeyboard.api.ApiError` subclasses or IOException. */
interface PackDiscovery {
    fun offers(languageCode: String, channel: String, appVersion: String): List<ResourceOffer>
    fun signingKeys(): List<KeyAnnouncement>
}

/** What a run was asked to do. */
data class UpdateRequest(
    /** Enabled languages (A1.5 settings). */
    val languages: List<String>,
    /** `STABLE`, `BETA` or `INTERNAL`. */
    val channel: String,
    val appVersion: String,
)

/** Why a candidate was not installed. Only these codes, the resource id and the file name are logged. */
enum class FailReason {
    COOLDOWN,
    SWAP_IN_FLIGHT,
    URL_REJECTED,
    NOT_ENOUGH_SPACE,
    NETWORK,
    HTTP_STATUS,
    TOO_LARGE,
    TRUNCATED,
    DISK_WRITE,

    /** The core refused the container (code from `RejectReason`, e.g. BAD_SIGNATURE, CHECKSUM_MISMATCH, REVOKED_KEY). */
    VERIFY_FAILED,

    /** The signed manifest does not match the discovery entry (id, version, slot, hash, size or key). */
    MANIFEST_MISMATCH,
    DELTA_NOT_SUPPORTED,
    APP_TOO_OLD,
    DOWNGRADE,
    PAYLOAD_UNUSABLE,
    SWAP_FAILED,
}

data class Installed(val file: String, val resourceId: String, val version: String)
data class Failure(val resourceId: String, val version: String, val reason: FailReason, val detail: String? = null)

enum class RunStatus {
    /** The run completed (individual candidates may still have failed). */
    DONE,

    /** Another check was running; nothing was done. */
    BUSY,

    /** The build's trust configuration is not acceptable; no update is possible. */
    NO_TRUST,

    /** The discovery API could not be reached or answered with an error. */
    DISCOVERY_FAILED,
}

data class UpdateReport(
    val status: RunStatus,
    val installed: List<Installed> = emptyList(),
    val failures: List<Failure> = emptyList(),
    /** True when a later retry (backoff) may succeed: a network or server problem. */
    val retryable: Boolean = false,
)
