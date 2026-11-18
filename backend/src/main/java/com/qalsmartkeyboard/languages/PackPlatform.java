package com.qalsmartkeyboard.languages;

import java.util.List;

/** The contract's {@code ClientPlatformEnum}, as the pack platforms a client of that platform can install. */
enum PackPlatform {
	ANDROID(List.of("SHARED", "ANDROID")), IOS(List.of("SHARED", "IOS")), WEB(List.of("SHARED"));

	private final List<String> packPlatforms;

	PackPlatform(List<String> packPlatforms) {
		this.packPlatforms = packPlatforms;
	}

	List<String> packPlatforms() {
		return packPlatforms;
	}
}
