package com.qalsmartkeyboard.languages;

import java.util.List;

/** The contract's {@code ReleaseChannel}. A channel sees itself and every more stable one. */
enum ReleaseChannel {
	INTERNAL(List.of("INTERNAL", "BETA", "STABLE")), BETA(List.of("BETA", "STABLE")), STABLE(List.of("STABLE"));

	private final List<String> visible;

	ReleaseChannel(List<String> visible) {
		this.visible = visible;
	}

	List<String> visibleChannels() {
		return visible;
	}
}
