package com.qalsmartkeyboard.languages.pack;

import java.util.Objects;

/**
 * A container failed verification. {@link #detail()} is a fixed English sentence: it never contains pack content, key ids
 * or parser messages (rule N2, pack spec section 10).
 */
public final class PackRejectedException extends RuntimeException {

	private final PackRejectReason reason;

	PackRejectedException(PackRejectReason reason, String detail) {
		super(detail, null, false, false);
		this.reason = Objects.requireNonNull(reason);
	}

	public PackRejectReason reason() {
		return reason;
	}

	public String detail() {
		return getMessage();
	}
}
