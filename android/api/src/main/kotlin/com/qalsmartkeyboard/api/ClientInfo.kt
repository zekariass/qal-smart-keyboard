package com.qalsmartkeyboard.api

/** Which process surface makes the call (`X-Client-Surface`, contract enum). */
enum class ClientSurface(val headerValue: String) {
    /** The input method service. */
    KEYBOARD("KEYBOARD"),

    /** The companion app (settings, onboarding, account, billing). */
    COMPANION("COMPANION"),
}

/**
 * What every request says about the client (contract: `X-Client-Platform`, `X-App-Version`,
 * `X-Client-Surface` on every request). [appVersion] comes from `BuildConfig`/`versionName`
 * of the app module; this module never reads it itself.
 */
data class ClientInfo(
    val appVersion: String,
    val surface: ClientSurface,
    val platform: String = PLATFORM_ANDROID,
) {
    companion object {
        const val PLATFORM_ANDROID = "ANDROID"
    }
}
