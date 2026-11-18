package com.qalsmartkeyboard.entitlements;

import java.time.Instant;
import java.util.UUID;

/**
 * Port through which {@code GET /me/entitlements} embeds the signed entitlement token (contract
 * {@code EntitlementToken}). The signer (B4.4) implements it; the read API (B4.5) only calls it with the entitlements it
 * has just resolved, so both views of one request agree.
 */
public interface EntitlementTokenIssuer {

	/**
	 * @param entitlements what the user is entitled to now
	 * @param deviceId     the device the access token is bound to, or null
	 */
	Issued issue(ResolvedEntitlements entitlements, UUID deviceId);

	/** The contract's {@code EntitlementToken}. */
	record Issued(String jws, String keyId, Instant expiresAt, Instant offlineGraceUntil) {
	}
}
