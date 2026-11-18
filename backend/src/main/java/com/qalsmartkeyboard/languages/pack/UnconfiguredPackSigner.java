package com.qalsmartkeyboard.languages.pack;

/** Used when no signing key is configured (outside production-like profiles): every use fails, nothing is invented. */
public final class UnconfiguredPackSigner implements PackSigner {

	private static final String MESSAGE = "Pack signing is not configured: set qal.languages.publishing.signing "
			+ "(QAL_PACK_SIGNING_KEY_ID and QAL_PACK_SIGNING_PRIVATE_KEY)";

	@Override
	public String keyId() {
		throw new IllegalStateException(MESSAGE);
	}

	@Override
	public byte[] publicKey() {
		throw new IllegalStateException(MESSAGE);
	}

	@Override
	public byte[] sign(byte[] message) {
		throw new IllegalStateException(MESSAGE);
	}
}
