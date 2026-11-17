package com.qalsmartkeyboard.identity.otp;

import java.util.regex.Pattern;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.otp.OtpDtos.OtpChallengeDto;
import com.qalsmartkeyboard.identity.otp.OtpDtos.OtpStartRequest;
import com.qalsmartkeyboard.identity.otp.OtpDtos.OtpVerifyRequest;
import com.qalsmartkeyboard.identity.otp.OtpService.StartedChallenge;
import com.qalsmartkeyboard.identity.otp.OtpService.VerifiedSignIn;
import com.qalsmartkeyboard.identity.session.AuthSessionDto;
import com.qalsmartkeyboard.identity.session.SessionContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * {@code POST /api/v1/auth/otp/start} ({@code startPhoneOtp}) and {@code POST /api/v1/auth/otp/verify}
 * ({@code verifyPhoneOtp}). Anonymous in the contract.
 *
 * <p>The client IP is {@link HttpServletRequest#getRemoteAddr()}: behind a load balancer the deployment must make the
 * container resolve {@code X-Forwarded-For} from trusted proxies only ({@code server.forward-headers-strategy}), never
 * this code. A successful start returns the caller's per-IP {@code RateLimit-*} headers (never the per-number budget,
 * which would hint at activity on someone else's number).
 */
@RestController
class OtpController {

	private static final Pattern LOCALE_TAG = Pattern.compile("^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$");

	private final OtpService service;

	OtpController(OtpService service) {
		this.service = service;
	}

	@PostMapping(path = "/api/v1/auth/otp/start", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<OtpChallengeDto> start(@Valid @RequestBody OtpStartRequest body,
			@RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
			HttpServletRequest request) {
		String deviceKey = body.installationId() == null ? null : body.installationId().toString();
		StartedChallenge started = service.start(body.phoneNumber(), request.getRemoteAddr(), deviceKey,
				uiLocale(acceptLanguage));
		HttpHeaders headers = new HttpHeaders();
		started.callerLimit().headers().forEach(headers::set);
		return ResponseEntity.status(HttpStatus.ACCEPTED).headers(headers).body(new OtpChallengeDto(
				started.challengeId(), started.expiresAt(), started.resendAvailableAt(), started.codeLength()));
	}

	@PostMapping(path = "/api/v1/auth/otp/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<AuthSessionDto> verify(@Valid @RequestBody OtpVerifyRequest body, HttpServletRequest request) {
		String deviceKey = body.device() == null ? null : body.device().installationId().toString();
		VerifiedSignIn signIn = service.verify(body.challengeId(), body.code(), Boolean.TRUE.equals(body.ageConfirmed()),
				request.getRemoteAddr(), deviceKey,
				new SessionContext(null, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT)));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(AuthSessionDto.of(signIn.session(), signIn.user(), signIn.created()));
	}

	/** First language tag of {@code Accept-Language} if well formed (max 35 characters as in {@code users.ui_locale}). */
	static String uiLocale(String acceptLanguage) {
		if (acceptLanguage == null) {
			return null;
		}
		String first = acceptLanguage.split(",", 2)[0].split(";", 2)[0].trim();
		return first.length() <= 35 && LOCALE_TAG.matcher(first).matches() ? first : null;
	}
}
