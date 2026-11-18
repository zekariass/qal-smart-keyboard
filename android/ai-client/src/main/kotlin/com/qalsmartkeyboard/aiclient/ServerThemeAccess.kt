package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.ime.theme.PremiumThemeAccess
import com.qalsmartkeyboard.ime.theme.ThemeAccess

/**
 * The keyboard's premium-theme port over the cached, verified entitlement token (`PREMIUM_THEMES` offline gate,
 * ADR 0013). Answers from memory: no disk, no network, no blocking. The token's offline grace applies as for every
 * gate: past `exp` the gate stays open only while the device is offline, until `offlineGraceUntil`.
 *
 * @param changes registers a listener for "plan state or token changed" (the AI client's availability listeners)
 */
class ServerThemeAccess(
    private val entitlements: EntitlementManager,
    private val session: SessionInfo,
    private val connectivity: Connectivity,
    private val changes: (() -> Unit) -> AutoCloseable,
) : ThemeAccess {
    override val premium: PremiumThemeAccess
        get() {
            if (!session.signedIn()) return PremiumThemeAccess.SIGNED_OUT
            val gates = entitlements.gates(connectivity.isOnline())
            return when {
                gates.premiumThemes -> PremiumThemeAccess.ALLOWED
                // No token, past its grace, or the token says yes but is awaiting renewal online: connect once to check.
                gates.state == GateState.VOID || gates.premiumThemesOnPlan -> PremiumThemeAccess.NEEDS_CHECK
                else -> PremiumThemeAccess.NOT_ON_PLAN
            }
        }

    override fun addListener(listener: () -> Unit): AutoCloseable = changes(listener)
}
