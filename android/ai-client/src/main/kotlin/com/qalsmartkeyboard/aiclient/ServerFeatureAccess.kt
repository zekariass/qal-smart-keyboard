package com.qalsmartkeyboard.aiclient

/** Whether this device may use one boolean platform feature right now, and if not, why (the app shows the reason). */
enum class FeatureAccess {
    /** The signed token grants it (valid, or offline inside its grace period), or the plan hints from the server say so. */
    ALLOWED,

    /** Nobody is signed in. */
    SIGNED_OUT,

    /** The account's plan does not include the feature. */
    NOT_ON_PLAN,

    /** Not known yet: no plan answer since the app started and no token that grants it. Connecting once refreshes it. */
    NEEDS_CHECK,
}

/**
 * The on-device premium gate of one boolean platform feature ([featureCode], e.g. [CARD_MAKER]): the same mechanism as
 * [ServerThemeAccess] (the cached, verified entitlement token, ADR 0013), plus the plan hints of `/me/entitlements`
 * (`accessEnabled`) for a feature the server does not (yet) write into the offline token. Answers from memory: no disk,
 * no network, no blocking. Basic use of a tool is never gated by this; only its premium extras are.
 *
 * @param changes registers a listener for "plan state or token changed" (the AI client's availability listeners)
 */
class ServerFeatureAccess(
    val featureCode: String,
    private val entitlements: EntitlementManager,
    private val session: SessionInfo,
    private val connectivity: Connectivity,
    private val changes: (() -> Unit) -> AutoCloseable,
) {
    val access: FeatureAccess
        get() {
            if (!session.signedIn()) return FeatureAccess.SIGNED_OUT
            val gates = entitlements.gates(connectivity.isOnline())
            if (featureCode in gates.openFeatures) return FeatureAccess.ALLOWED
            val hint = entitlements.entitledFeatureCodes
            return when {
                hint != null && featureCode in hint -> FeatureAccess.ALLOWED
                // The token grants it but its gate is shut (online past exp): connect once for a fresh token.
                featureCode in gates.grantedFeatures -> FeatureAccess.NEEDS_CHECK
                hint != null -> FeatureAccess.NOT_ON_PLAN
                else -> FeatureAccess.NEEDS_CHECK
            }
        }

    fun addListener(listener: () -> Unit): AutoCloseable = changes(listener)

    companion object {
        /** `CARD_MAKER` (V27): the quote and greeting card maker's premium templates. */
        const val CARD_MAKER = "CARD_MAKER"

        /** `IMAGE_ANNOTATOR`: the image annotator's advanced tools (blur, pixelate, step markers, spotlight). */
        const val IMAGE_ANNOTATOR = "IMAGE_ANNOTATOR"
    }
}
