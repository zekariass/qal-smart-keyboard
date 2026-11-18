package com.qalsmartkeyboard.usage;

import java.util.UUID;

/**
 * A settle was asked for a reservation that is already closed in a way that cannot be replayed: it was RELEASED (for
 * example by the expiry sweeper), or SETTLED with a different number of units. Nothing was charged by this call. This
 * is a programming or timing error of the caller (the reservation outlived its {@code expiresAt}), not a client error.
 */
public class ReservationClosedException extends IllegalStateException {

	private final UUID reservationId;
	private final ReservationStatus status;

	public ReservationClosedException(UUID reservationId, ReservationStatus status, String message) {
		super(message);
		this.reservationId = reservationId;
		this.status = status;
	}

	public UUID reservationId() {
		return reservationId;
	}

	public ReservationStatus status() {
		return status;
	}
}
