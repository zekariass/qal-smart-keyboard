package com.qalsmartkeyboard.privacy;

import java.util.Optional;

/** Stands in when no export bucket is configured (development, tests): every use fails, nothing is written anywhere. */
final class UnconfiguredExportObjectStore implements ExportObjectStore {

	private static IllegalStateException unavailable() {
		return new IllegalStateException("The export object store is not configured (qal.privacy.export.storage.*)");
	}

	@Override
	public void put(String key, byte[] bytes) {
		throw unavailable();
	}

	@Override
	public Optional<byte[]> get(String key, long maxBytes) {
		throw unavailable();
	}

	@Override
	public void delete(String key) {
		throw unavailable();
	}

	@Override
	public boolean configured() {
		return false;
	}
}
