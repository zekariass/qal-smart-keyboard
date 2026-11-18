package com.qalsmartkeyboard.identity.otp;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code qal.auth.phone.*} (ADR 0007 D48).
 *
 * @param allowedPrefixes E.164 prefixes (with {@code +}) a number must start with to get a sign-in code; default
 *                        {@code +2519} (Ethio Telecom mobiles: the SMS provider accepts only 2519XXXXXXXX). Widening to
 *                        Safaricom Ethiopia is config only: {@code QAL_AUTH_PHONE_ALLOWED_PREFIXES=+2519,+2517}
 */
@ConfigurationProperties("qal.auth.phone")
record PhoneAuthProperties(@DefaultValue("+2519") List<String> allowedPrefixes) {

	PhoneAuthProperties {
		allowedPrefixes = allowedPrefixes.stream().map(String::trim).filter(c -> !c.isEmpty()).toList();
		if (allowedPrefixes.isEmpty()) {
			throw new IllegalArgumentException("qal.auth.phone.allowed-prefixes must list at least one prefix");
		}
		for (String prefix : allowedPrefixes) {
			if (!prefix.matches("\\+[1-9][0-9]{0,13}")) {
				throw new IllegalArgumentException(
						"qal.auth.phone.allowed-prefixes entries must be E.164 prefixes such as +2519: " + prefix);
			}
		}
	}
}
