package com.qalsmartkeyboard.aigateway.spi;

import java.util.Objects;

/**
 * User-supplied or provider-produced content. The only way text crosses the SPI, and {@code toString()} never prints it,
 * so a request, result or exception that is logged by accident leaks a length at most (SD 19 logging hygiene, R-5).
 */
public record UserText(String value) {

	public UserText {
		Objects.requireNonNull(value, "value");
	}

	public static UserText of(String value) {
		return new UserText(value);
	}

	/** Unicode code points, the unit CHARACTER metering counts. */
	public int characters() {
		return value.codePointCount(0, value.length());
	}

	@Override
	public String toString() {
		return "UserText[" + characters() + " chars]";
	}
}
