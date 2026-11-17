package com.qalsmartkeyboard.identity.otp;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phone-number rules for OTP sign-in.
 *
 * <ul>
 * <li>Every number must be strict E.164 as in the contract ({@code ^\+[1-9][0-9]{6,14}$}): no spaces, no national
 * prefix. Clients normalise before sending.</li>
 * <li>Ethiopia (+251): only mobile numbers can receive an SMS, so the national significant number must be 9 digits
 * starting with 9 (Ethio Telecom) or 7 (Safaricom Ethiopia): {@code ^\+251[79][0-9]{8}$}. Landlines (+251 11 ...)
 * and wrong lengths are rejected before any SMS is paid for.</li>
 * </ul>
 * libphonenumber is deliberately not used: the contract's E.164 pattern plus the +251 mobile rule covers the primary
 * market, the SMS aggregator rejects unroutable international numbers, and the per-number and per-IP limits bound the
 * cost of junk numbers. Revisit if per-country rules for diaspora markets become necessary.
 */
final class PhoneNumbers {

	static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,14}$");
	static final Pattern ETHIOPIAN_MOBILE = Pattern.compile("^\\+251[79][0-9]{8}$");

	private PhoneNumbers() {
	}

	/** @return a static, safe reason when the number is not acceptable; empty when it is */
	static Optional<String> rejectionReason(String e164) {
		if (e164 == null || !E164.matcher(e164).matches()) {
			return Optional.of("Must be an E.164 number such as +251911234567.");
		}
		if (e164.startsWith("+251") && !ETHIOPIAN_MOBILE.matcher(e164).matches()) {
			return Optional.of("Must be an Ethiopian mobile number: +251 followed by 9 digits starting with 9 or 7.");
		}
		return Optional.empty();
	}

	/** Low-cardinality metric tag: {@code ET} for +251, otherwise {@code INTL}. */
	static String destination(String e164) {
		return e164.startsWith("+251") ? "ET" : "INTL";
	}
}
