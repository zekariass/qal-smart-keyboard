package com.qalsmartkeyboard.identity.oauth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.account.UserAccount;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.account.UserAccountStore.IdentitySignIn;
import com.qalsmartkeyboard.identity.merge.GuestMergeService;
import com.qalsmartkeyboard.identity.merge.GuestMergeService.GuestCaller;
import com.qalsmartkeyboard.identity.session.IssuedSession;
import com.qalsmartkeyboard.identity.session.SessionContext;
import com.qalsmartkeyboard.identity.session.SessionIssuer;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Sign-in with an external ID token (SD 9.2), {@code POST /auth/oauth/{provider}}.
 *
 * <p>Order: provider supported (400) → per-IP limit → per-device limit (only when the request names its installation)
 * → ID-token verification, outside any transaction (JWKS cached; a cold fetch is a short HTTP call) → [transaction:
 * per-identity advisory lock → find or create the account by (provider, subject) → session]. Failures that must not
 * leave rows (age confirmation missing, suspended, deleted) return normally from the transaction and become Problems
 * afterwards.
 *
 * <p><b>Guest upgrade (B2.6, ADR 0010):</b> with the bearer token of a live guest, a new identity turns the guest itself
 * into the registered account and an existing one receives the guest's data ({@link GuestMergeService}); the response
 * carries {@code mergedGuestUserId}. A guest token that can no longer link is a 401 and nothing is written.
 *
 * <p><b>Account resolution:</b> by (provider, subject) in {@code user_auth_identities}, never by e-mail (see
 * {@link UserAccountStore#signInWithIdentity}). The ID token itself is never stored or logged; neither is the e-mail.
 */
class OAuthService {

	private static final Logger log = LoggerFactory.getLogger(OAuthService.class);
	static final String PROVIDER_FIELD = "provider";

	/** What the controller hands over (the request body, already validated for shape). */
	record Exchange(String idToken, String nonce, String displayName, boolean ageConfirmed) {

		/** Never prints the token, the nonce or the name. */
		@Override
		public String toString() {
			return "Exchange[...]";
		}
	}

	/**
	 * The outcome of a successful sign-in.
	 *
	 * @param mergedGuestUserId the guest linked by this sign-in, or {@code null}
	 */
	record SignedIn(UserAccount user, boolean created, IssuedSession session, UUID mergedGuestUserId) {
	}

	private final Map<String, JwksIdTokenVerifier> verifiers;
	private final UserAccountStore accounts;
	private final SessionIssuer sessions;
	private final RateLimiter limiter;
	private final RateLimitPolicy perIp;
	private final RateLimitPolicy perDevice;
	private final TransactionTemplate transactions;
	private final JdbcClient jdbc;
	private final Clock clock;
	private final MeterRegistry meters;
	private final GuestMergeService merges;

	OAuthService(Map<String, JwksIdTokenVerifier> verifiers, UserAccountStore accounts, SessionIssuer sessions,
			RateLimiter limiter, OAuthProperties.Limits limits, TransactionTemplate transactions, JdbcClient jdbc,
			Clock clock, MeterRegistry meters, GuestMergeService merges) {
		this.merges = merges;
		this.verifiers = Map.copyOf(verifiers);
		this.accounts = accounts;
		this.sessions = sessions;
		this.limiter = limiter;
		this.perIp = limits.exchangePerIp().policy("oauth.exchange.ip");
		this.perDevice = limits.exchangePerDevice().policy("oauth.exchange.device");
		this.transactions = transactions;
		this.jdbc = jdbc;
		this.clock = clock;
		this.meters = meters;
	}

	/**
	 * @param provider  {@code GOOGLE} or {@code APPLE} (the contract's enum); anything else is a 400
	 * @param deviceKey the request's {@code device.installationId}, or {@code null}
	 * @param guest     the caller's guest bearer token, or {@code null} (no bearer, or a registered one: not linked)
	 */
	SignedIn exchange(String provider, Exchange request, String clientIp, String deviceKey, SessionContext session,
			GuestCaller guest) {
		JwksIdTokenVerifier verifier = verifiers.get(provider);
		if (verifier == null) {
			count(provider, "unsupported");
			throw unsupported();
		}
		RateLimitDecision ip = limiter.tryConsume(perIp, clientIp);
		if (!ip.allowed()) {
			count(provider, "rate_limited");
			throw ip.toProblem();
		}
		if (deviceKey != null) {
			RateLimitDecision device = limiter.tryConsume(perDevice, deviceKey);
			if (!device.allowed()) {
				count(provider, "rate_limited");
				throw device.toProblem();
			}
		}
		VerifiedIdToken token;
		try {
			token = verifier.verify(request.idToken(), request.nonce());
		}
		catch (IdTokenVerificationException e) {
			// the reason is an enum name: no token, claim or library message
			IdTokenVerificationException.Failure failure = e.failure();
			count(provider, failure.unavailable() ? "provider_unavailable" : "rejected_" + failure.name().toLowerCase(java.util.Locale.ROOT));
			if (failure.unavailable()) {
				log.warn("{} sign-in: the provider's signing keys are unavailable", provider);
				throw new ApiException(ApiErrorCode.PROVIDER_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, null);
			}
			log.debug("{} ID token rejected: {}", provider, failure);
			throw new ApiException(ApiErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED,
					"The ID token was not accepted.");
		}
		String displayName = displayName(request.displayName(), token.name());
		Outcome outcome = transactions.execute(status -> signIn(provider, token, displayName, request.ageConfirmed(),
				session, guest));
		return switch (outcome) {
			case Outcome.Success s -> {
				count(provider, s.signedIn().created() ? "created" : "success");
				log.info("{} sign-in for user {} ({})", provider, s.signedIn().user().id(),
						s.signedIn().created() ? "new account" : "existing account");
				yield s.signedIn();
			}
			case Outcome.Failure f -> {
				count(provider, f.metric());
				throw f.problem();
			}
		};
	}

	private Outcome signIn(String provider, VerifiedIdToken token, String displayName, boolean ageConfirmed,
			SessionContext session, GuestCaller guest) {
		// one sign-in per identity at a time: a first-time double tap must not race on the unique (provider, subject)
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
				.param("key", "qal.oauth:" + provider + ":" + token.subject()).query().singleRow();
		if (guest != null && !merges.lockMergeableGuest(guest)) {
			return new Outcome.Failure("guest_stale", GuestMergeService.staleGuestToken());
		}
		Instant now = clock.instant();
		IdentitySignIn result = accounts.signInWithIdentity(provider, token.subject(), token.email(), displayName,
				ageConfirmed, now, guest == null ? null : guest.userId());
		return switch (result) {
			case IdentitySignIn.AgeConfirmationRequired a -> new Outcome.Failure("age_required",
					new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
							List.of(new ApiFieldError("ageConfirmed", "REQUIRED",
									"ageConfirmed is required to create a new account.")),
							Map.of()));
			case IdentitySignIn.Suspended s -> new Outcome.Failure("suspended",
					new ApiException(ApiErrorCode.ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN, null));
			case IdentitySignIn.Unavailable u -> new Outcome.Failure("unavailable",
					new ApiException(ApiErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, null));
			case IdentitySignIn.SignedIn s -> {
				boolean upgraded = guest != null && s.created();
				if (guest != null && !upgraded) {
					merges.mergeInto(guest.userId(), s.user().id(), provider, now);
				}
				IssuedSession issued = sessions.issue(s.user(), session);
				if (upgraded) {
					merges.completeUpgrade(s.user().id(), provider, issued.sessionId());
				}
				yield new Outcome.Success(new SignedIn(s.user(), s.created(), issued,
						guest == null ? null : guest.userId()));
			}
		};
	}

	/** The client's {@code displayName} wins over the token's {@code name}; trimmed and capped at the column's 120. */
	static String displayName(String requested, String fromToken) {
		String name = requested != null && !requested.isBlank() ? requested : fromToken;
		if (name == null || name.isBlank()) {
			return null;
		}
		name = name.strip();
		return name.length() <= 120 ? name : name.substring(0, 120);
	}

	/** A provider the server cannot verify (APPLE until its adapter exists, GOOGLE without client ids, or unknown). */
	static ApiException unsupported() {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
				List.of(new ApiFieldError(PROVIDER_FIELD, "PROVIDER_NOT_SUPPORTED",
						"Sign-in with this provider is not available.")),
				Map.of());
	}

	private void count(String provider, String outcome) {
		if (meters != null) {
			meters.counter("qal.identity.oauth", "provider", provider.length() <= 10 ? provider : "other", "outcome", outcome)
					.increment();
		}
	}

	private sealed interface Outcome {

		record Success(SignedIn signedIn) implements Outcome {
		}

		record Failure(String metric, ApiException problem) implements Outcome {
		}
	}
}
