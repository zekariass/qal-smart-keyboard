package com.qalsmartkeyboard.usage;

/** Life cycle of a usage reservation: RESERVED, then exactly one of SETTLED or RELEASED (final). */
public enum ReservationStatus {
	/** Units are held against the allowance and/or credits; settle or release closes it. */
	RESERVED,
	/** Actual usage was charged. */
	SETTLED,
	/** Nothing was charged; the held units and credits went back. */
	RELEASED
}
