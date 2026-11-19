package com.qalsmartkeyboard.notifications.email;

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

import com.qalsmartkeyboard.notifications.EmailCodeSender;

import jakarta.servlet.http.HttpServletRequest;

/**
 * DEVELOPMENT ONLY (ADR 0033, the rules of ADR 0007 D28): {@code GET /dev/email/last?to=user%40example.com} returns
 * the last e-mail captured for an address, with the code, so a local client test can sign up without a mailbox. Not
 * part of the API contract and not under {@code /api/v1}. The bean exists only when
 * {@code qal.notifications.email.capture.endpoint-enabled=true}, and refuses to start unless the {@code capture}
 * provider is active and no {@code prod}/{@code production}/{@code staging} profile is. It answers only to loopback
 * callers (404 otherwise).
 */
@RestController
@ConditionalOnProperty(name = EmailConfiguration.ENDPOINT_PROPERTY, havingValue = "true")
class DevEmailCaptureController {

	private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

	private final CapturingEmailSender provider;

	/** Fails closed: needs the capture provider and a profile that is not prod/production/staging. */
	DevEmailCaptureController(EmailCodeSender sender, Environment environment) {
		if (environment.acceptsProfiles(Profiles.of(EmailConfiguration.PRODUCTION_LIKE_PROFILES))) {
			throw new IllegalStateException(
					EmailConfiguration.ENDPOINT_PROPERTY + " is refused under the prod/production/staging profiles");
		}
		if (!(sender instanceof TemplatedEmailCodeSender templated)
				|| !(templated.provider() instanceof CapturingEmailSender capturing)) {
			throw new IllegalStateException(
					EmailConfiguration.ENDPOINT_PROPERTY + " needs qal.notifications.email.provider=capture");
		}
		this.provider = capturing;
	}

	record LastEmail(String to, String subject, String body, String code, Instant receivedAt) {

		@Override
		public String toString() {
			return "LastEmail[<redacted>]";
		}
	}

	@GetMapping("/dev/email/last")
	LastEmail last(@RequestParam("to") String to, HttpServletRequest request) {
		if (!isLoopback(request.getRemoteAddr())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		CapturingEmailSender.Captured captured = provider.last(to)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		Matcher m = CODE.matcher(captured.body());
		return new LastEmail(captured.to(), captured.subject(), captured.body(), m.find() ? m.group(1) : null,
				captured.receivedAt());
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
