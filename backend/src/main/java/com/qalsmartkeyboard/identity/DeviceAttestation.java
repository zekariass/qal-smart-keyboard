package com.qalsmartkeyboard.identity;

import java.time.Instant;

/**
 * A device-integrity verdict to store on a device row.
 *
 * @param provider {@code PLAY_INTEGRITY} or {@code APP_ATTEST}; {@code null} when the platform has no verifier
 * @param status   {@code VALID}, {@code FAILED} or {@code UNSUPPORTED}
 */
public record DeviceAttestation(String provider, String status, Instant checkedAt) {
}
