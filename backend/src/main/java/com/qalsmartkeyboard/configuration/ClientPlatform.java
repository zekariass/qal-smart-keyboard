package com.qalsmartkeyboard.configuration;

import java.util.Optional;

/** The contract's {@code ClientPlatformEnum}; the name doubles as the {@code platform_scope} column value. */
enum ClientPlatform {
	ANDROID, IOS, WEB;

	/** Strict, case-sensitive parse of an {@code X-Client-Platform} value (the contract enum is upper case). */
	static Optional<ClientPlatform> parse(String value) {
		if (value == null) {
			return Optional.empty();
		}
		for (ClientPlatform p : values()) {
			if (p.name().equals(value)) {
				return Optional.of(p);
			}
		}
		return Optional.empty();
	}
}
