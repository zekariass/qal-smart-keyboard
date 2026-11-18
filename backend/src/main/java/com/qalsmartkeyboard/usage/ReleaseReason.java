package com.qalsmartkeyboard.usage;

/** Why a reservation was released ({@code usage_reservations.release_reason}). */
public enum ReleaseReason {
	/** The operation failed (provider error, guardrail failure, validation after reserve): no charge (SD 11.1). */
	FAILED,
	/** The caller abandoned the operation (client cancelled, session closed before any usage). */
	CANCELLED,
	/** The sweeper released it after {@code expires_at} (SD 15.3 step 6). */
	EXPIRED,
	/** Settled with zero actual units: nothing to charge. */
	NOTHING_USED
}
