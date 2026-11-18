package com.qalsmartkeyboard.notifications.sms;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * DEVELOPMENT ONLY (ADR 0007 D28): {@code GET /dev/sms/last?phone=%2B251...} returns the last SMS captured for a number
 * so the Android local-stack test can sign in without reading logs. Not part of the API contract and not under
 * {@code /api/v1}. The bean exists only when {@code qal.notifications.sms.capture.endpoint-enabled=true}, which
 * the controller itself refuses to start unless the {@code capture} provider is active and no
 * {@code prod}/{@code production}/{@code staging} profile is. It also answers only to loopback callers (404 otherwise).
 */
@RestController
@ConditionalOnProperty(name = SmsConfiguration.ENDPOINT_PROPERTY, havingValue = "true")
class DevSmsCaptureController {

	private static final Pattern CODE = Pattern.compile("\\b(\\d{4,8})\\b");

	private final CapturingSmsProvider provider;

	/** Fails closed: needs the capture provider and a profile that is not prod/production/staging. */
	DevSmsCaptureController(SmsProvider provider, Environment environment) {
		if (environment.acceptsProfiles(Profiles.of(SmsConfiguration.PRODUCTION_LIKE_PROFILES))) {
			throw new IllegalStateException(
					SmsConfiguration.ENDPOINT_PROPERTY + " is refused under the prod/production/staging profiles");
		}
		if (!(provider instanceof CapturingSmsProvider capturing)) {
			throw new IllegalStateException(
					SmsConfiguration.ENDPOINT_PROPERTY + " needs qal.notifications.sms.provider=capture");
		}
		this.provider = capturing;
	}

	record LastSms(String phone, String body, String code, Instant receivedAt) {

		@Override
		public String toString() {
			return "LastSms[<redacted>]";
		}
	}

	@GetMapping("/dev/sms/last")
	LastSms last(@RequestParam("phone") String phone, HttpServletRequest request) {
		if (!isLoopback(request.getRemoteAddr())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		// A '+' in a query string decodes to a space; E.164 numbers always start with '+'.
		String e164 = phone.trim();
		if (!e164.startsWith("+")) {
			e164 = "+" + e164;
		}
		CapturingSmsProvider.Captured captured = provider.last(e164)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		Matcher m = CODE.matcher(captured.body());
		return new LastSms(captured.phone(), captured.body(), m.find() ? m.group(1) : null, captured.receivedAt());
	}

	static boolean isLoopback(String remoteAddr) {
		try {
			return remoteAddr != null && InetAddress.getByName(remoteAddr).isLoopbackAddress();
		}
		catch (UnknownHostException e) {
			return false;
		}
	}
}
