package com.qalsmartkeyboard.languages;

/** Used when no write credential for the pack bucket is configured: uploads and publishing fail, nothing is stored. */
final class UnconfiguredPackObjectStore implements PackObjectStore {

	private static final String MESSAGE = "Pack storage is not configured: set qal.languages.artifacts.endpoint and "
			+ "qal.languages.publishing.storage.access-key/secret-key (QAL_PACKS_S3_WRITE_ACCESS_KEY, _SECRET_KEY)";

	@Override
	public String put(String key, byte[] bytes) {
		throw new IllegalStateException(MESSAGE);
	}

	@Override
	public byte[] get(String location, long maxBytes) {
		throw new IllegalStateException(MESSAGE);
	}
}
