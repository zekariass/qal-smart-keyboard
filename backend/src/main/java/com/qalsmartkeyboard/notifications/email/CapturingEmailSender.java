package com.qalsmartkeyboard.notifications.email;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEVELOPMENT ONLY e-mail provider ({@code qal.notifications.email.provider=capture}, ADR 0033, the same rules as the
 * SMS capture of ADR 0007 D28): sends nothing, keeps the last message per address in memory (bounded, lost on restart)
 * so a local client test can read the code through {@link DevEmailCaptureController}. Logs a masked address and the
 * length only. Refused under the {@code prod}, {@code production} and {@code staging} profiles.
 */
final class CapturingEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(CapturingEmailSender.class);
	static final int MAX_ENTRIES = 1000;

	record Captured(String to, String subject, String body, Instant receivedAt) {

		@Override
		public String toString() {
			return "Captured[to=" + EmailMask.mask(to) + ", body=<redacted>]";
		}
	}

	private final Map<String, Captured> last = new LinkedHashMap<>();

	@Override
	public String id() {
		return "capture";
	}

	@Override
	public void send(String to, String subject, String textBody) {
		String key = key(to);
		synchronized (last) {
			last.remove(key);
			last.put(key, new Captured(to, subject, textBody, Instant.now()));
			while (last.size() > MAX_ENTRIES) {
				last.remove(last.keySet().iterator().next());
			}
		}
		log.info("Mock e-mail to {} captured in memory ({} characters, body not logged)", EmailMask.mask(to),
				textBody.length());
	}

	Optional<Captured> last(String to) {
		synchronized (last) {
			return Optional.ofNullable(last.get(key(to)));
		}
	}

	/** Addresses match case-insensitively (the client may type them differently from how they are stored). */
	private static String key(String to) {
		return to.trim().toLowerCase(Locale.ROOT);
	}
}
