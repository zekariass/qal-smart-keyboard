package com.qalsmartkeyboard.identity.otp;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.account.UserAccount;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.account.UserAccountStore.PhoneSignIn;
import com.qalsmartkeyboard.identity.merge.GuestMergeService;
import com.qalsmartkeyboard.identity.merge.GuestMergeService.GuestCaller;
import com.qalsmartkeyboard.identity.otp.OtpChallengeStore.Challenge;
import com.qalsmartkeyboard.identity.session.IssuedSession;
import com.qalsmartkeyboard.identity.session.SessionContext;
import com.qalsmartkeyboard.identity.session.SessionIssuer;
import com.qalsmartkeyboard.notifications.OtpDeliveryException;
import com.qalsmartkeyboard.notifications.OtpMessage;
import com.qalsmartkeyboard.notifications.OtpSender;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

/**
 * Phone OTP sign-in (SD 9.2): start sends a 6-digit code, verify exchanges it for a session.
 *
 * <p><b>Start</b>: number rules (400) → per-IP limit → per-device limit (only when the request names its
 * {@code installationId}) → [transaction: per-number advisory lock → resend cooldown →
 * per-number limits → new challenge, which supersedes earlier ones] → SMS after commit (SD 8.3: no external call inside
 * a transaction). A delivery failure is counted and logged, and the response stays 202 ("code sent or silently
 * suppressed"): the response never depends on whether the number has an account.
 *
 * <p><b>Verify</b>: per-IP and per-device limits → [transaction: per-number lock → challenge row lock → reject if
 * consumed (replay), superseded, expired or locked → constant-time code check; a wrong code increments
 * {@code attempts} and the limit locks the challenge → (guest bearer: lock the guest, refuse a stale one) → consume →
 * find or create the account (or upgrade the guest in place) → merge the guest into an existing account → issue the
 * session]. Failures return normally from the transaction so the attempt counter commits, then become Problems.
 *
 * <p><b>Guest upgrade (B2.6, ADR 0010):</b> a request with the bearer token of a live guest links that guest: a number
 * without an account turns the guest itself into the registered account, a number with an account receives the guest's
 * data ({@link GuestMergeService}). Either way the response carries {@code mergedGuestUserId}. A guest token that can no
 * longer link (merged, upgraded or ended meanwhile) is a 401 and the code is not consumed.
 */
class OtpService {

	private static final Logger log = LoggerFactory.getLogger(OtpService.class);
	private static final String INVALID_DETAIL = "The code is not valid. Request a new code if needed.";

	record StartedChallenge(UUID challengeId, Instant expiresAt, Instant resendAvailableAt, int codeLength,
			RateLimitDecision callerLimit) {
	}

	/** @param mergedGuestUserId the guest linked by this sign-in, or {@code null} */
	record VerifiedSignIn(UserAccount user, boolean created, IssuedSession session, UUID mergedGuestUserId) {
	}

	private sealed interface StartOutcome {
		record Created(UUID id, String code, Instant expiresAt, Instant resendAvailableAt) implements StartOutcome {
			@Override
			public String toString() {
				return "Created[id=" + id + "]";
			}
		}

		record Cooldown(Duration retryAfter) implements StartOutcome {
		}

		record Limited(RateLimitDecision decision) implements StartOutcome {
		}
	}

	enum VerifyFailure {
		UNKNOWN_CHALLENGE("unknown_challenge"), REPLAYED("replayed"), SUPERSEDED("superseded"), EXPIRED("expired"),
		TOO_MANY_ATTEMPTS("too_many_attempts"), INVALID("invalid"), AGE_REQUIRED("age_required"),
		SUSPENDED("suspended"), UNAVAILABLE("unavailable"), GUEST_STALE("guest_stale");

		final String metric;

		VerifyFailure(String metric) {
			this.metric = metric;
		}
	}

	private sealed interface VerifyOutcome {
		record Success(VerifiedSignIn signIn) implements VerifyOutcome {
		}

		/** @param attemptsRemaining wrong codes left before the challenge locks (INVALID only), else null */
		record Failure(VerifyFailure failure, boolean lockedNow, Integer attemptsRemaining) implements VerifyOutcome {

			Failure(VerifyFailure failure, boolean lockedNow) {
				this(failure, lockedNow, null);
			}
		}
	}

	private final OtpChallengeStore challenges;
	private final OtpSecrets secrets;
	private final UserAccountStore accounts;
	private final SessionIssuer sessions;
	private final OtpSender sender;
	private final RateLimiter limiter;
	private final OtpProperties properties;
	private final TransactionTemplate transactions;
	private final Clock clock;
	private final SecureRandom random;
	private final OtpMetrics metrics;
	private final GuestMergeService merges;

	private final RateLimitPolicy startPerIp;
	private final RateLimitPolicy startPerDevice;
	private final List<RateLimitPolicy> startPerPhone;
	private final RateLimitPolicy verifyPerIp;
	private final RateLimitPolicy verifyPerDevice;

	OtpService(OtpChallengeStore challenges, OtpSecrets secrets, UserAccountStore accounts, SessionIssuer sessions,
			OtpSender sender, RateLimiter limiter, OtpProperties properties, TransactionTemplate transactions,
			Clock clock, SecureRandom random, OtpMetrics metrics, GuestMergeService merges) {
		this.challenges = challenges;
		this.secrets = secrets;
		this.accounts = accounts;
		this.sessions = sessions;
		this.sender = sender;
		this.limiter = limiter;
		this.properties = properties;
		this.transactions = transactions;
		this.clock = clock;
		this.random = random;
		this.metrics = metrics;
		this.merges = merges;
		OtpProperties.Limits limits = properties.limits();
		this.startPerIp = limits.startPerIp().policy("otp.start.ip");
		this.startPerDevice = limits.startPerDevice().policy("otp.start.device");
		this.startPerPhone = List.of(limits.startPerPhone().policy("otp.start.phone"),
				limits.startPerPhoneDaily().policy("otp.start.phone-daily"));
		this.verifyPerIp = limits.verifyPerIp().policy("otp.verify.ip");
		this.verifyPerDevice = limits.verifyPerDevice().policy("otp.verify.device");
	}

	// ------------------------------------------------------------------ start

	/**
	 * @param deviceKey the request's {@code installationId}, or {@code null}
	 */
	StartedChallenge start(String phoneE164, String clientIp, String deviceKey, String uiLocale) {
		Optional<String> rejection = PhoneNumbers.rejectionReason(phoneE164);
		if (rejection.isPresent()) {
			metrics.started("invalid_number", phoneE164 != null && phoneE164.startsWith("+251") ? "ET" : "INTL");
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
					List.of(new ApiFieldError("phoneNumber", "INVALID_PHONE_NUMBER", rejection.get())), Map.of());
		}
		String destination = PhoneNumbers.destination(phoneE164);
		// The caller's own budgets (IP, then device) first, so a blocked client cannot drain a victim number's budget.
		RateLimitDecision ipDecision = limiter.tryConsume(startPerIp, clientIp);
		if (!ipDecision.allowed()) {
			metrics.started("rate_limited", destination);
			throw ipDecision.toProblem();
		}
		if (deviceKey != null) {
			RateLimitDecision deviceDecision = limiter.tryConsume(startPerDevice, deviceKey);
			if (!deviceDecision.allowed()) {
				metrics.started("rate_limited", destination);
				throw deviceDecision.toProblem();
			}
		}
		String phoneHash = secrets.phoneHash(phoneE164);
		StartOutcome outcome = transactions.execute(status -> {
			challenges.lockPhone(phoneHash);
			Instant now = clock.instant();
			Optional<Instant> latest = challenges.latestCreatedAt(phoneHash);
			if (latest.isPresent()) {
				Instant resendAt = latest.get().plus(properties.resendCooldown());
				if (now.isBefore(resendAt)) {
					return new StartOutcome.Cooldown(Duration.between(now, resendAt));
				}
			}
			for (RateLimitPolicy policy : startPerPhone) {
				RateLimitDecision decision = limiter.tryConsume(policy, phoneHash);
				if (!decision.allowed()) {
					return new StartOutcome.Limited(decision);
				}
			}
			UUID id = UUID.randomUUID();
			String code = newCode();
			Instant expiresAt = now.plus(properties.codeTtl());
			challenges.insert(id, phoneHash, secrets.codeHash(id, code), secrets.sealPhone(id, phoneE164),
					properties.maxAttempts(), clientIp, now, expiresAt);
			return new StartOutcome.Created(id, code, expiresAt, now.plus(properties.resendCooldown()));
		});
		return switch (outcome) {
			case StartOutcome.Cooldown c -> {
				metrics.started("cooldown", destination);
				throw RateLimitDecision.retryAfterProblem(c.retryAfter());
			}
			case StartOutcome.Limited l -> {
				metrics.started("rate_limited", destination);
				throw l.decision().toProblem();
			}
			case StartOutcome.Created c -> {
				deliver(c, phoneE164, uiLocale, destination);
				yield new StartedChallenge(c.id(), c.expiresAt(), c.resendAvailableAt(), OtpProperties.CODE_LENGTH,
						ipDecision);
			}
		};
	}

	private void deliver(StartOutcome.Created created, String phoneE164, String uiLocale, String destination) {
		try {
			sender.send(new OtpMessage(phoneE164, created.code(), properties.codeTtl(), uiLocale));
			metrics.started("sent", destination);
		}
		catch (OtpDeliveryException e) {
			metrics.started("delivery_failed", destination);
			log.warn("OTP delivery failed for challenge {}; the client may request a new code after the cooldown",
					created.id());
		}
	}

	/** Uniform over 000000-999999. */
	private String newCode() {
		int bound = (int) Math.pow(10, OtpProperties.CODE_LENGTH);
		return String.format("%0" + OtpProperties.CODE_LENGTH + "d", random.nextInt(bound));
	}

	// ------------------------------------------------------------------ verify

	/**
	 * @param deviceKey the request's {@code device.installationId}, or {@code null}
	 * @param guest     the caller's guest bearer token, or {@code null} (no bearer, or a registered one: not linked)
	 */
	VerifiedSignIn verify(UUID challengeId, String code, boolean ageConfirmed, String clientIp, String deviceKey,
			SessionContext session, GuestCaller guest) {
		RateLimitDecision ip = limiter.tryConsume(verifyPerIp, clientIp);
		if (!ip.allowed()) {
			metrics.verified("rate_limited");
			throw ip.toProblem();
		}
		if (deviceKey != null) {
			RateLimitDecision device = limiter.tryConsume(verifyPerDevice, deviceKey);
			if (!device.allowed()) {
				metrics.verified("rate_limited");
				throw device.toProblem();
			}
		}
		VerifyOutcome outcome = transactions.execute(status -> verifyInTransaction(challengeId, code, ageConfirmed,
				session, guest));
		return switch (outcome) {
			case VerifyOutcome.Success s -> {
				metrics.verified("success");
				yield s.signIn();
			}
			case VerifyOutcome.Failure f -> {
				metrics.verified(f.failure().metric);
				if (f.lockedNow()) {
					metrics.lockout();
				}
				throw problem(f.failure(), f.attemptsRemaining());
			}
		};
	}

	private VerifyOutcome verifyInTransaction(UUID challengeId, String code, boolean ageConfirmed,
			SessionContext session, GuestCaller guest) {
		Optional<String> phoneHash = challenges.findPhoneHash(challengeId);
		if (phoneHash.isEmpty()) {
			return new VerifyOutcome.Failure(VerifyFailure.UNKNOWN_CHALLENGE, false);
		}
		challenges.lockPhone(phoneHash.get());
		Challenge challenge = challenges.findForUpdate(challengeId).orElse(null);
		if (challenge == null) {
			return new VerifyOutcome.Failure(VerifyFailure.UNKNOWN_CHALLENGE, false);
		}
		Instant now = clock.instant();
		if (challenge.consumedAt() != null) {
			return new VerifyOutcome.Failure(VerifyFailure.REPLAYED, false);
		}
		if (challenges.isSuperseded(challenge)) {
			return new VerifyOutcome.Failure(VerifyFailure.SUPERSEDED, false);
		}
		if (!now.isBefore(challenge.expiresAt())) {
			return new VerifyOutcome.Failure(VerifyFailure.EXPIRED, false);
		}
		if (challenge.attempts() >= challenge.maxAttempts()) {
			return new VerifyOutcome.Failure(VerifyFailure.TOO_MANY_ATTEMPTS, false);
		}
		if (!secrets.codeMatches(challenge.id(), code, challenge.codeHash())) {
			int attempts = challenges.recordFailedAttempt(challenge.id());
			return attempts >= challenge.maxAttempts() ? new VerifyOutcome.Failure(VerifyFailure.TOO_MANY_ATTEMPTS, true)
					: new VerifyOutcome.Failure(VerifyFailure.INVALID, false, challenge.maxAttempts() - attempts);
		}
		if (guest != null && !merges.lockMergeableGuest(guest)) {
			// nothing consumed: the client drops the stale guest token and retries with the same code
			return new VerifyOutcome.Failure(VerifyFailure.GUEST_STALE, false);
		}
		String phone = secrets.openPhone(challenge.id(), challenge.phoneCiphertext());
		PhoneSignIn signIn = accounts.signInWithPhone(phone, ageConfirmed, now, guest == null ? null : guest.userId());
		if (signIn instanceof PhoneSignIn.AgeConfirmationRequired) {
			// nothing consumed or counted: the client asks for the age confirmation and retries with the same code
			return new VerifyOutcome.Failure(VerifyFailure.AGE_REQUIRED, false);
		}
		challenges.consume(challenge.id(), now);
		return switch (signIn) {
			case PhoneSignIn.Suspended s -> new VerifyOutcome.Failure(VerifyFailure.SUSPENDED, false);
			case PhoneSignIn.Unavailable u -> new VerifyOutcome.Failure(VerifyFailure.UNAVAILABLE, false);
			case PhoneSignIn.SignedIn s -> {
				boolean upgraded = guest != null && s.created();
				boolean merged = guest != null && !upgraded
						&& merges.mergeInto(guest.userId(), s.user().id(), "PHONE", now);
				// after a merge the guest's device belongs to the account, so the registration finds it
				IssuedSession issued = sessions.issue(s.user(), session);
				if (upgraded) {
					merges.completeUpgrade(s.user().id(), "PHONE", issued.sessionId());
				}
				yield new VerifyOutcome.Success(new VerifiedSignIn(s.user(), s.created(), issued,
						upgraded || merged ? guest.userId() : null));
			}
			case PhoneSignIn.AgeConfirmationRequired a -> throw new IllegalStateException("handled above");
		};
	}

	/** Replayed, superseded and unknown challenges are indistinguishable to the client. */
	private static ApiException problem(VerifyFailure failure, Integer attemptsRemaining) {
		return switch (failure) {
			case INVALID -> new ApiException(ApiErrorCode.OTP_INVALID, HttpStatus.UNAUTHORIZED, INVALID_DETAIL,
					List.of(), Map.of("attemptsRemaining", attemptsRemaining));
			case UNKNOWN_CHALLENGE, REPLAYED, SUPERSEDED -> new ApiException(ApiErrorCode.OTP_INVALID,
					HttpStatus.UNAUTHORIZED, INVALID_DETAIL);
			case EXPIRED -> new ApiException(ApiErrorCode.OTP_EXPIRED, HttpStatus.UNAUTHORIZED,
					"The code has expired. Request a new code.");
			case TOO_MANY_ATTEMPTS -> new ApiException(ApiErrorCode.OTP_TOO_MANY_ATTEMPTS, HttpStatus.TOO_MANY_REQUESTS,
					"Too many wrong codes. Request a new code.");
			case AGE_REQUIRED -> new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
					List.of(new ApiFieldError("ageConfirmed", "REQUIRED",
							"ageConfirmed is required to create a new account.")),
					Map.of());
			case SUSPENDED -> new ApiException(ApiErrorCode.ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN, null);
			case UNAVAILABLE -> new ApiException(ApiErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, null);
			case GUEST_STALE -> GuestMergeService.staleGuestToken();
		};
	}
}
