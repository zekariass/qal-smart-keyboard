package com.qalsmartkeyboard.api

/** Which process surface makes the call (`X-Client-Surface`, contract enum). */
enum class ClientSurface(val headerValue: String) {
    /** The input method service. */
    KEYBOARD("KEYBOARD"),

    /** The companion app (settings, onboarding, account, billing). */
    COMPANION("COMPANION"),
}

/**
 * How this build was distributed (ADR 0045, ADR 0047): the Android `play` flavour is [GOOGLE_PLAY], `direct` is
 * [DIRECT_DOWNLOAD]. The backend has no header for it: billing sends [apiValue] as `distributionChannel`
 * (`GET /billing/options`) and [salesChannel] as `salesChannel` (`POST /billing/orders`, `GET /billing/products`).
 * It only selects which approved market policy applies; the server re-checks everything.
 */
enum class DistributionChannel(val apiValue: String, val salesChannel: String) {
    GOOGLE_PLAY("GOOGLE_PLAY", "ANDROID_GOOGLE_PLAY"),
    DIRECT_DOWNLOAD("DIRECT_DOWNLOAD", "ANDROID_DIRECT"),
    ;

    companion object {
        /** The channel of an Android product flavour name (`play`, `direct`); null for anything else. */
        fun ofFlavor(flavor: String): DistributionChannel? = when (flavor) {
            "play" -> GOOGLE_PLAY
            "direct" -> DIRECT_DOWNLOAD
            else -> null
        }
    }
}

/**
 * What every request says about the client (contract: `X-Client-Platform`, `X-App-Version`,
 * `X-Client-Surface` on every request). [appVersion] comes from `BuildConfig`/`versionName`
 * of the app module; this module never reads it itself. [distributionChannel] is the build flavour's channel (billing
 * calls carry it in their query or body, see [DistributionChannel]); null in builds that never buy.
 */
data class ClientInfo(
    val appVersion: String,
    val surface: ClientSurface,
    val platform: String = PLATFORM_ANDROID,
    val distributionChannel: DistributionChannel? = null,
) {
    companion object {
        const val PLATFORM_ANDROID = "ANDROID"
    }
}
