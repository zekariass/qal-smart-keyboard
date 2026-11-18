package com.qalsmartkeyboard.usage;

import java.util.UUID;

/**
 * The outcome of {@link Metering#release}.
 *
 * @param status        RELEASED, or SETTLED when the reservation had already been settled (then nothing changed)
 * @param reason        the reason recorded on the reservation (the original one on a replay); null when SETTLED
 * @param releasedUnits units given back by this call (0 on a replay)
 * @param replayed      true when this call changed nothing because the reservation was already closed
 */
public record Release(UUID reservationId, ReservationStatus status, ReleaseReason reason, long releasedUnits,
		boolean replayed) {
}
