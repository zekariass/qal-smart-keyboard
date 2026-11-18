package com.qalsmartkeyboard.notifications.sms;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEVELOPMENT ONLY SMS provider ({@code qal.notifications.sms.provider=capture}, ADR 0007 D28): sends nothing, keeps the
 * last message per number in memory (bounded, lost on restart) so a local client test can read the OTP through
 * {@link DevSmsCaptureController}. Logs a masked number and the length only, never the body. Refused under the
 * {@code prod}, {@code production} and {@code staging} profiles (see {@link SmsConfiguration}).
 */
final class CapturingSmsProvider implements SmsProvider {

	private static final Logger log = LoggerFactory.getLogger(CapturingSmsProvider.class);
	static final int MAX_ENTRIES = 1000;

	record Captured(String phone, String body, Instant receivedAt) {

		@Override
		public String toString() {
			return "Captured[phone=" + PhoneMask.mask(phone) + ", body=<redacted>]";
		}
	}

	private final Map<String, Captured> last = new LinkedHashMap<>();

	@Override
	public String id() {
		return "capture";
	}

	@Override
	public void send(String toE164, String body) {
		synchronized (last) {
			last.remove(toE164);
			last.put(toE164, new Captured(toE164, body, Instant.now()));
			while (last.size() > MAX_ENTRIES) {
				last.remove(last.keySet().iterator().next());
			}
		}
		log.info("Mock SMS to {} captured in memory ({} characters, body not logged)", PhoneMask.mask(toE164),
				body.length());
	}

	Optional<Captured> last(String toE164) {
		synchronized (last) {
			return Optional.ofNullable(last.get(toE164));
		}
	}
}
