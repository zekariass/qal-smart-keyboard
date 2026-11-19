package com.qalsmartkeyboard.identity.email;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.identity.email.EmailAuthService.Caller;
import com.qalsmartkeyboard.identity.email.EmailAuthService.PendingVerification;
import com.qalsmartkeyboard.identity.email.EmailAuthService.RegisterCommand;
import com.qalsmartkeyboard.identity.email.EmailAuthService.Registration;
import com.qalsmartkeyboard.identity.email.EmailAuthService.SignedIn;
import com.qalsmartkeyboard.identity.merge.GuestMergeService.GuestCaller;
import com.qalsmartkeyboard.identity.session.AuthSessionDto;
import com.qalsmartkeyboard.identity.session.DeviceRegistrationDto;
import com.qalsmartkeyboard.identity.session.SessionContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * E-mail + password endpoints (ADR 0033): {@code registerWithEmail}, {@code loginWithEmail},
 * {@code verifyEmailCode}, {@code resendEmailCode}, {@code forgotPassword}, {@code resetPassword}. Anonymous in the
 * contract; register, login and verify accept a guest bearer token and link the guest (ADR 0010). Password lengths are
 * checked by the service in Unicode code points (the contract's {@code minLength}/{@code maxLength}); the bean
 * constraints here only cap the request size. The client IP is {@link HttpServletRequest#getRemoteAddr()}.
 */
@RestController
class EmailAuthController {

	private final EmailAuthService service;

	EmailAuthController(EmailAuthService service) {
		this.service = service;
	}

	/** {@code EmailRegisterRequest}. */
	record EmailRegisterRequest(@NotNull @Size(max = 320) String email, @NotNull @Size(max = 512) String password,
			@Size(max = 120) String displayName, @NotNull Boolean ageConfirmed, @Valid DeviceRegistrationDto device) {

		@Override
		public String toString() {
			return "EmailRegisterRequest[...]";
		}
	}

	/** {@code EmailLoginRequest}. */
	record EmailLoginRequest(@NotNull @Size(max = 320) String email, @NotNull @Size(max = 512) String password,
			@Valid DeviceRegistrationDto device) {

		@Override
		public String toString() {
			return "EmailLoginRequest[...]";
		}
	}

	/** {@code EmailVerifyRequest}. */
	record EmailVerifyRequest(@NotNull UUID challengeId, @NotNull @Pattern(regexp = "^[0-9]{4,8}$") String code,
			@Valid DeviceRegistrationDto device) {

		@Override
		public String toString() {
			return "EmailVerifyRequest[challengeId=" + challengeId + ", code=<redacted>]";
		}
	}

	/** {@code EmailResendRequest}. */
	record EmailResendRequest(@NotNull UUID challengeId) {
	}

	/** {@code PasswordForgotRequest}. */
	record PasswordForgotRequest(@NotNull @Size(max = 320) String email) {

		@Override
		public String toString() {
			return "PasswordForgotRequest[...]";
		}
	}

	/** {@code PasswordResetRequest}: {@code token} is the code from the e-mail. */
	record PasswordResetRequest(@NotNull @Size(max = 320) String email, @NotNull @Size(max = 256) String token,
			@NotNull @Size(max = 512) String newPassword) {

		@Override
		public String toString() {
			return "PasswordResetRequest[...]";
		}
	}

	/** {@code EmailVerificationChallenge}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record EmailVerificationChallengeDto(UUID challengeId, Instant expiresAt, Instant resendAvailableAt,
			int codeLength) {

		static EmailVerificationChallengeDto of(PendingVerification pending) {
			return new EmailVerificationChallengeDto(pending.challengeId(), pending.expiresAt(),
					pending.resendAvailableAt(), pending.codeLength());
		}
	}

	@PostMapping(path = "/api/v1/auth/email/register", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<?> register(@Valid @RequestBody EmailRegisterRequest body,
			@AuthenticationPrincipal AuthenticatedUser caller, HttpServletRequest request) {
		Registration result = service.register(new RegisterCommand(body.email(), body.password(), body.displayName(),
				Boolean.TRUE.equals(body.ageConfirmed())), caller(body.device(), caller, request));
		return switch (result) {
			case Registration.Created c -> ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
					.body(session(c.signedIn()));
			case Registration.Pending p -> ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
					.body(EmailVerificationChallengeDto.of(p.pending()));
		};
	}

	@PostMapping(path = "/api/v1/auth/email/login", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<AuthSessionDto> login(@Valid @RequestBody EmailLoginRequest body,
			@AuthenticationPrincipal AuthenticatedUser caller, HttpServletRequest request) {
		SignedIn signedIn = service.login(body.email(), body.password(), caller(body.device(), caller, request));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session(signedIn));
	}

	@PostMapping(path = "/api/v1/auth/email/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<AuthSessionDto> verify(@Valid @RequestBody EmailVerifyRequest body,
			@AuthenticationPrincipal AuthenticatedUser caller, HttpServletRequest request) {
		SignedIn signedIn = service.verify(body.challengeId(), body.code(), caller(body.device(), caller, request));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session(signedIn));
	}

	@PostMapping(path = "/api/v1/auth/email/verify/resend", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<EmailVerificationChallengeDto> resend(@Valid @RequestBody EmailResendRequest body,
			HttpServletRequest request) {
		PendingVerification pending = service.resend(body.challengeId(), request.getRemoteAddr());
		return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
				.body(EmailVerificationChallengeDto.of(pending));
	}

	@PostMapping(path = "/api/v1/auth/password/forgot", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<Void> forgot(@Valid @RequestBody PasswordForgotRequest body, HttpServletRequest request) {
		service.forgot(body.email(), request.getRemoteAddr());
		return ResponseEntity.status(HttpStatus.ACCEPTED).build();
	}

	@PostMapping(path = "/api/v1/auth/password/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<Void> reset(@Valid @RequestBody PasswordResetRequest body, HttpServletRequest request) {
		service.reset(body.email(), body.token(), body.newPassword(), request.getRemoteAddr());
		return ResponseEntity.noContent().build();
	}

	private static Caller caller(DeviceRegistrationDto device, AuthenticatedUser caller, HttpServletRequest request) {
		String deviceKey = device == null ? null : device.installationId().toString();
		return new Caller(request.getRemoteAddr(), deviceKey,
				new SessionContext(null, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT),
						device == null ? null : device.toDeviceInfo(request.getHeader("X-App-Version"))),
				GuestCaller.from(caller));
	}

	private static AuthSessionDto session(SignedIn signedIn) {
		return AuthSessionDto.of(signedIn.session(), signedIn.user(), signedIn.created(), signedIn.mergedGuestUserId());
	}
}
