package com.qalsmartkeyboard.entitlements;

/** Whether the account may hold entitlements at all. Anything but {@link #GRANTED} resolves to no plan. */
public enum AccessState {
	/** {@code account_status = ACTIVE} and not deleted. */
	GRANTED,
	SUSPENDED,
	/** DELETION_PENDING, DELETED or {@code deleted_at} set. */
	DELETED,
	/** The guest row was merged into another account (ADR 0010). */
	MERGED,
	/** Registration not completed. */
	PENDING,
	/** No such user. */
	UNKNOWN
}
