package com.qalsmartkeyboard.identity.email;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.account.UserAccount;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.email.EmailAddresses.Address;
import com.qalsmartkeyboard.identity.email.EmailStore.Account;
import com.qalsmartkeyboard.identity.email.EmailStore.Challenge;
import com.qalsmartkeyboard.identity.merge.GuestMergeService;
import com.qalsmartkeyboard.identity.merge.GuestMergeService.GuestCaller;
import com.qalsmartkeyboard.identity.session.IssuedSession;
import com.qalsmartkeyboard.identity.session.SessionContext;
import com.qalsmartkeyboard.identity.session.SessionIssuer;
import com.qalsmartkeyboard.identity.session.SessionService;
import com.qalsmartkeyboard.notifications.EmailCodeMessage;
import com.qalsmartkeyboard.notifications.EmailCodeSender;
import com.qalsmartkeyboard.notifications.EmailDeliveryException;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * E-mail + password sign-up and sign-in, e-mail verification and password reset (ADR 0033).
 *
 * <p><b>Register.</b> Address and password rules (400) → per-IP/device limits → Argon2id hash (outside any transaction)
 * → [transaction, per-address advisory lock]. With verification <b>off</b>: a live account with the address is 409
 * {@code EMAIL_ALREADY_REGISTERED}; otherwise the account is created (or the caller's guest upgraded in place), with
 * {@code email_verified_at} NULL, and signed in (201). With verification <b>on</b> nothing is created: a
 * {@code REGISTRATION} challenge holds the password hash and the sealed address, the code is e-mailed and the response
 * is 202 with the challenge. A verified account with the address is 409; an <em>unverified</em> one is not (see
 * squatting below).
 *
 * <p><b>Verify</b> ({@code challengeId} + code): the challenge is the capability, so a code from a sign-up started by
 * someone else (sent to the same mailbox) cannot complete this client's sign-up. A {@code REGISTRATION} code creates
 * the verified account (or upgrades the guest) - or, when an <em>unverified</em> account holds the address, the
 * mailbox owner takes it over: its password is replaced by the one chosen with this sign-up, every session of it ends
 * and the address becomes verified. An {@code EMAIL_VERIFICATION} code (sent when an unverified account signs in while
 * verification is required) verifies that account. Both sign in.
 *
 * <p><b>Login.</b> Limits → the account is read without a lock → Argon2id check (a dummy hash when there is no
 * account or no password, so the time does not tell) → [transaction: lock, re-read; a wrong password counts and locks
 * the credential after {@code lockout.max-failures}; a right one clears the count → 403 {@code ACCOUNT_SUSPENDED},
 * or with verification required and an unverified address 403 {@code EMAIL_NOT_VERIFIED} (with a challenge, code
 * e-mailed) → guest merge → session]. Re-hash with stronger parameters after a successful sign-in.
 *
 * <p><b>Forgot / reset.</b> Forgot is always 202 (unless rate-limited or e-mail is not configured, both independent
 * of the address): the code is generated only for a live account with a password, and the e-mail is sent off the
 * request thread so the response time does not tell either. Reset ({@code email}, code, new password) answers 400
 * {@code RESET_CODE_INVALID} for every failure (unknown address, no challenge, wrong, expired, used up), so it does not
 * tell either; success replaces the password, clears the lock, verifies the address and ends every session.
 */
class EmailAuthService {

	private static final Logger log = LoggerFactory.getLogger(EmailAuthService.class);
	private static final Set<String> CLOSED = Set.of("DELETED", "MERGED");
	private static final Set<String> CAN_RECEIVE_CODES = Set.of("ACTIVE", "PENDING", "DELETION_PENDING");

	/** A signed-in result. @param mergedGuestUserId the guest linked by this sign-in, or {@code null} */
	record SignedIn(UserAccount user, boolean created, IssuedSession session, UUID mergedGuestUserId) {
	}

	/** A code was (or will be) e-mailed for this challenge. */
	record PendingVerification(UUID challengeId, Instant expiresAt, Instant resendAvailableAt, int codeLength) {
	}

	/** Register outcome: signed in (verification off) or pending (verification on). */
	sealed interface Registration {
		record Created(SignedIn signedIn) implements Registration {
		}

		record Pending(PendingVerification pending) implements Registration {
		}
	}

	/** Input of register. Never prints the address, password or name. */
	record RegisterCommand(String email, String password, String displayName, boolean ageConfirmed) {
		@Override
		public String toString() {
			return "RegisterCommand[...]";
		}
	}

	/** The request metadata of a sign-in. */
	record Caller(String clientIp, String deviceKey, SessionContext session, GuestCaller guest) {
	}

	private final EmailStore store;
	private final EmailSecrets secrets;
	private final PasswordHasher hasher;
	private final PasswordPolicy policy;
	private final UserAccountStore accounts;
	private final SessionIssuer sessions;
	private final SessionService sessionService;
	private final GuestMergeService merges;
	private final EmailCodeSender sender;
	private final RateLimiter limiter;
	private final EmailAuthProperties properties;
	private final TransactionTemplate transactions;
	private final Clock clock;
	private final SecureRandom random;
	private final MeterRegistry meters;
	private final Executor asyncSender;

	private final RateLimitPolicy registerPerIp;
	private final RateLimitPolicy registerPerDevice;
	private final RateLimitPolicy loginPerIp;
	private final RateLimitPolicy loginPerDevice;
	private final RateLimitPolicy verifyPerIp;
	private final RateLimitPolicy resendPerIp;
	private final RateLimitPolicy forgotPerIp;
	private final RateLimitPolicy resetPerIp;
	private final List<RateLimitPolicy> sendPerAddress;

	EmailAuthService(EmailStore store, EmailSecrets secrets, PasswordHasher hasher, PasswordPolicy policy,
			UserAccountStore accounts, SessionIssuer sessions, SessionService sessionService, GuestMergeService merges,
			EmailCodeSender sender, RateLimiter limiter, EmailAuthProperties properties, TransactionTemplate transactions,
			Clock clock, SecureRandom random, MeterRegistry meters, Executor asyncSender) {
		this.store = store;
		this.secrets = secrets;
		this.hasher = hasher;
		this.policy = policy;
		this.accounts = accounts;
		this.sessions = sessions;
		this.sessionService = sessionService;
		this.merges = merges;
		this.sender = sender;
		this.limiter = limiter;
		this.properties = properties;
		this.transactions = transactions;
		this.clock = clock;
		this.random = random;
		this.meters = meters;
		this.asyncSender = asyncSender;
		EmailAuthProperties.Limits limits = properties.limits();
		this.registerPerIp = limits.registerPerIp().policy("email.register.ip");
		this.registerPerDevice = limits.loginPerDevice().policy("email.register.device");
		this.loginPerIp = limits.loginPerIp().policy("email.login.ip");
		this.loginPerDevice = limits.loginPerDevice().policy("email.login.device");
		this.verifyPerIp = limits.verifyPerIp().policy("email.verify.ip");
		this.resendPerIp = limits.resendPerIp().policy("email.resend.ip");
		this.forgotPerIp = limits.forgotPerIp().policy("email.forgot.ip");
		this.resetPerIp = limits.resetPerIp().policy("email.reset.ip");
		this.sendPerAddress = List.of(limits.sendPerAddress().policy("email.send.address"),
				limits.sendPerAddressDaily().policy("email.send.address-daily"));
	}

	boolean verificationRequired() {
		return properties.verificationRequired();
	}

	// ================================================================== register

	Registration register(RegisterCommand command, Caller caller) {
		requireEnabled();
		consume(registerPerIp, caller.clientIp(), "register");
		if (caller.deviceKey() != null) {
			consume(registerPerDevice, caller.deviceKey(), "register");
		}
		Address address = parseAddress(command.email(), "email");
		if (!command.ageConfirmed()) {
			throw fieldProblem("ageConfirmed", "REQUIRED", "ageConfirmed is required to create a new account.");
		}
		String displayName = displayName(command.displayName());
		String password = checkedPassword(command.password(), address, "password");
		if (properties.verificationRequired() && !sender.available()) {
			// startup refuses this combination; kept as a fail-closed guard
			throw emailNotConfigured();
		}
		String passwordHash = hasher.hash(password);
		String emailHash = secrets.addressHash(address.normalized());
		Instant now = clock.instant();
		if (!properties.verificationRequired()) {
			SignInOutcome outcome;
			try {
				outcome = transactions.execute(status -> registerUnverified(address, emailHash, displayName,
						passwordHash, caller, now));
			}
			catch (DuplicateKeyException e) {
				count("register", "conflict");
				throw alreadyRegistered();
			}
			SignedIn signedIn = finish("register", outcome);
			log.info("E-mail sign-up created user {} (address not verified: verification is off)",
					signedIn.user().id());
			return new Registration.Created(signedIn);
		}
		StartOutcome started = transactions.execute(status -> startRegistration(address, emailHash, displayName,
				passwordHash, caller.clientIp(), now));
		return switch (started) {
			case StartOutcome.Refused r -> {
				count("register", r.metric());
				throw r.problem();
			}
			case StartOutcome.Created c -> {
				deliver(c.code(), address.address(), EmailCodeMessage.Kind.SIGN_UP, "register");
				yield new Registration.Pending(c.pending());
			}
		};
	}

	private SignInOutcome registerUnverified(Address address, String emailHash, String displayName, String passwordHash,
			Caller caller, Instant now) {
		store.lockAddress(emailHash);
		store.releaseClosedIdentity(address.normalized());
		if (store.findAccount(address.normalized()).isPresent()) {
			return new SignInOutcome.Failure("conflict", alreadyRegistered());
		}
		GuestCaller guest = caller.guest();
		if (guest != null && !merges.lockMergeableGuest(guest)) {
			return new SignInOutcome.Failure("guest_stale", GuestMergeService.staleGuestToken());
		}
		UserAccount created = accounts.createEmailAccount(address.address(), address.normalized(), displayName, null,
				now, guest == null ? null : guest.userId());
		store.setPassword(created.id(), passwordHash, now);
		IssuedSession issued = sessions.issue(created, caller.session());
		if (guest != null) {
			merges.completeUpgrade(created.id(), "EMAIL", issued.sessionId());
		}
		return new SignInOutcome.Success(new SignedIn(created, true, issued, guest == null ? null : guest.userId()));
	}

	private StartOutcome startRegistration(Address address, String emailHash, String displayName, String passwordHash,
			String clientIp, Instant now) {
		store.lockAddress(emailHash);
		Optional<Account> existing = store.findAccount(address.normalized());
		if (existing.isPresent() && (existing.get().verified() || !takeoverAllowed(existing.get()))) {
			return new StartOutcome.Refused("conflict", alreadyRegistered());
		}
		Optional<Challenge> latest = store.latestForAddress(emailHash, EmailStore.REGISTRATION);
		if (latest.isPresent()) {
			Instant resendAt = latest.get().lastSentAt().plus(properties.resendCooldown());
			if (now.isBefore(resendAt)) {
				return new StartOutcome.Refused("cooldown",
						RateLimitDecision.retryAfterProblem(Duration.between(now, resendAt)));
			}
		}
		Optional<ApiException> limited = sendBudget(emailHash);
		if (limited.isPresent()) {
			return new StartOutcome.Refused("rate_limited", limited.get());
		}
		UUID id = UUID.randomUUID();
		String code = newCode();
		Instant expiresAt = now.plus(properties.codeTtl());
		store.insertChallenge(id, null, EmailStore.REGISTRATION, emailHash,
				secrets.codeHash(id, EmailStore.REGISTRATION, code),
				secrets.seal(id, new EmailSecrets.Payload(address.address(), displayName)), passwordHash,
				properties.maxAttempts(), clientIp, now, expiresAt);
		return new StartOutcome.Created(code, new PendingVerification(id, expiresAt,
				now.plus(properties.resendCooldown()), EmailAuthProperties.CODE_LENGTH));
	}

	/** An unverified account may be taken over by the proven mailbox owner unless it is suspended or being deleted. */
	private static boolean takeoverAllowed(Account account) {
		return "ACTIVE".equals(account.status()) || "PENDING".equals(account.status());
	}

	// ================================================================== verify and resend

	SignedIn verify(UUID challengeId, String code, Caller caller) {
		requireEnabled();
		consume(verifyPerIp, caller.clientIp(), "verify");
		if (caller.deviceKey() != null) {
			consume(loginPerDevice, caller.deviceKey(), "verify");
		}
		SignInOutcome outcome = transactions.execute(status -> verifyInTransaction(challengeId, code, caller));
		SignedIn signedIn = finish("verify", outcome);
		log.info("E-mail verified for user {} ({})", signedIn.user().id(),
				signedIn.created() ? "new account" : "existing account");
		return signedIn;
	}

	private SignInOutcome verifyInTransaction(UUID challengeId, String code, Caller caller) {
		Optional<String> emailHash = store.findEmailHash(challengeId);
		if (emailHash.isEmpty()) {
			return new SignInOutcome.Failure("unknown_challenge", codeInvalid(null));
		}
		store.lockAddress(emailHash.get());
		Challenge challenge = store.findForUpdate(challengeId).orElse(null);
		if (challenge == null || EmailStore.PASSWORD_RESET.equals(challenge.purpose())) {
			return new SignInOutcome.Failure("unknown_challenge", codeInvalid(null));
		}
		Instant now = clock.instant();
		Optional<SignInOutcome.Failure> rejected = checkCode(challenge, code, now);
		if (rejected.isPresent()) {
			return rejected.get();
		}
		GuestCaller guest = caller.guest();
		if (guest != null && !merges.lockMergeableGuest(guest)) {
			// nothing consumed: the client drops the stale guest token and retries with the same code
			return new SignInOutcome.Failure("guest_stale", GuestMergeService.staleGuestToken());
		}
		store.consume(challenge.id(), now);
		if (EmailStore.EMAIL_VERIFICATION.equals(challenge.purpose())) {
			return verifyExistingAccount(challenge, caller, now);
		}
		EmailSecrets.Payload payload = secrets.open(challenge.id(), challenge.payload());
		Address address = EmailAddresses.parse(payload.address());
		Optional<Account> existing = store.findAccount(address.normalized());
		if (existing.isEmpty()) {
			store.releaseClosedIdentity(address.normalized());
			UserAccount created = accounts.createEmailAccount(address.address(), address.normalized(),
					payload.displayName(), now, now, guest == null ? null : guest.userId());
			store.setPassword(created.id(), challenge.pendingPasswordHash(), now);
			store.consumeOpenRegistrations(challenge.emailHash(), now);
			IssuedSession issued = sessions.issue(created, caller.session());
			if (guest != null) {
				merges.completeUpgrade(created.id(), "EMAIL", issued.sessionId());
			}
			return new SignInOutcome.Success(new SignedIn(created, true, issued, guest == null ? null : guest.userId()));
		}
		Account account = existing.get();
		if (account.verified() || !takeoverAllowed(account)) {
			return "SUSPENDED".equals(account.status())
					? new SignInOutcome.Failure("suspended", suspended())
					: new SignInOutcome.Failure("conflict", alreadyRegistered());
		}
		// Squatting fix (ADR 0033): whoever proves the mailbox owns the unverified account. The password chosen with this
		// sign-up replaces the old one and every session of the account ends.
		if (lockAndReload(account.userId()) == null) {
			return new SignInOutcome.Failure("unavailable", codeInvalid(null));
		}
		store.setPassword(account.userId(), challenge.pendingPasswordHash(), now);
		int revoked = sessionService.revokeAllForPasswordChange(account.userId());
		store.consumeAllOfUser(account.userId(), now);
		store.consumeOpenRegistrations(challenge.emailHash(), now);
		log.info("Unverified account {} taken over by the proven mailbox owner; {} session row(s) ended",
				account.userId(), revoked);
		count("verify", "takeover");
		return signInExisting(account.userId(), true, caller, now);
	}

	private SignInOutcome verifyExistingAccount(Challenge challenge, Caller caller, Instant now) {
		Account account = lockAndReload(challenge.userId());
		if (account == null) {
			return new SignInOutcome.Failure("unavailable",
					new ApiException(ApiErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, null));
		}
		if (account.email() == null
				|| !secrets.addressHash(account.email().toLowerCase(java.util.Locale.ROOT)).equals(challenge.emailHash())) {
			return new SignInOutcome.Failure("unavailable", codeInvalid(null));
		}
		if ("SUSPENDED".equals(account.status())) {
			return new SignInOutcome.Failure("suspended", suspended());
		}
		store.consumeAllOfUser(account.userId(), now);
		return signInExisting(account.userId(), true, caller, now);
	}

	/** Checks a challenge's code; a wrong one counts. The failure is returned so the counter commits. */
	private Optional<SignInOutcome.Failure> checkCode(Challenge challenge, String code, Instant now) {
		if (challenge.consumedAt() != null) {
			return Optional.of(new SignInOutcome.Failure("replayed", codeInvalid(null)));
		}
		if (!now.isBefore(challenge.expiresAt())) {
			return Optional.of(new SignInOutcome.Failure("expired", new ApiException(ApiErrorCode.OTP_EXPIRED,
					HttpStatus.UNAUTHORIZED, "The code has expired. Request a new code.")));
		}
		if (challenge.attempts() >= challenge.maxAttempts()) {
			return Optional.of(new SignInOutcome.Failure("too_many_attempts", tooManyAttempts()));
		}
		if (!secrets.codeMatches(challenge.id(), challenge.purpose(), code, challenge.codeHash())) {
			int attempts = store.recordFailedAttempt(challenge.id());
			return Optional.of(attempts >= challenge.maxAttempts()
					? new SignInOutcome.Failure("too_many_attempts", tooManyAttempts())
					: new SignInOutcome.Failure("invalid", codeInvalid(challenge.maxAttempts() - attempts)));
		}
		return Optional.empty();
	}

	PendingVerification resend(UUID challengeId, String clientIp) {
		requireEnabled();
		if (!sender.available()) {
			throw emailNotConfigured();
		}
		consume(resendPerIp, clientIp, "resend");
		StartOutcome outcome = transactions.execute(status -> resendInTransaction(challengeId));
		return switch (outcome) {
			case StartOutcome.Refused r -> {
				count("resend", r.metric());
				throw r.problem();
			}
			case StartOutcome.Created c -> {
				EmailCodeMessage.Kind kind = c.registration() ? EmailCodeMessage.Kind.SIGN_UP
						: EmailCodeMessage.Kind.VERIFY_EMAIL;
				deliver(c.code(), c.to(), kind, "resend");
				yield c.pending();
			}
		};
	}

	private StartOutcome resendInTransaction(UUID challengeId) {
		Optional<String> emailHash = store.findEmailHash(challengeId);
		if (emailHash.isEmpty()) {
			return new StartOutcome.Refused("unknown_challenge", codeInvalid(null));
		}
		store.lockAddress(emailHash.get());
		Challenge challenge = store.findForUpdate(challengeId).orElse(null);
		Instant now = clock.instant();
		if (challenge == null || EmailStore.PASSWORD_RESET.equals(challenge.purpose()) || challenge.consumedAt() != null
				|| !now.isBefore(challenge.createdAt().plus(properties.challengeMaxAge()))) {
			return new StartOutcome.Refused("unknown_challenge", codeInvalid(null));
		}
		Instant resendAt = challenge.lastSentAt().plus(properties.resendCooldown());
		if (now.isBefore(resendAt)) {
			return new StartOutcome.Refused("cooldown",
					RateLimitDecision.retryAfterProblem(Duration.between(now, resendAt)));
		}
		if (challenge.sends() >= properties.maxSends()) {
			return new StartOutcome.Refused("max_sends", new ApiException(ApiErrorCode.RATE_LIMITED,
					HttpStatus.TOO_MANY_REQUESTS, "No more codes for this request. Start again.", List.of(), Map.of(),
					Map.of(RateLimitDecision.RETRY_AFTER, Long.toString(properties.resendCooldown().toSeconds() + 1))));
		}
		Optional<ApiException> limited = sendBudget(challenge.emailHash());
		if (limited.isPresent()) {
			return new StartOutcome.Refused("rate_limited", limited.get());
		}
		String to;
		boolean registration = EmailStore.REGISTRATION.equals(challenge.purpose());
		if (registration) {
			to = secrets.open(challenge.id(), challenge.payload()).address();
		}
		else {
			Account account = store.findAccountById(challenge.userId()).orElse(null);
			if (account == null || account.email() == null || !CAN_RECEIVE_CODES.contains(account.status())) {
				return new StartOutcome.Refused("unavailable", codeInvalid(null));
			}
			to = account.email();
		}
		String code = newCode();
		Instant expiresAt = now.plus(properties.codeTtl());
		store.resend(challenge.id(), secrets.codeHash(challenge.id(), challenge.purpose(), code), now, expiresAt);
		return new StartOutcome.Created(code, new PendingVerification(challenge.id(), expiresAt,
				now.plus(properties.resendCooldown()), EmailAuthProperties.CODE_LENGTH), to, registration);
	}

	// ================================================================== login

	SignedIn login(String email, String password, Caller caller) {
		requireEnabled();
		consume(loginPerIp, caller.clientIp(), "login");
		if (caller.deviceKey() != null) {
			consume(loginPerDevice, caller.deviceKey(), "login");
		}
		Address address = parseAddress(email, "email");
		String normalizedPassword = password == null ? "" : PasswordPolicy.normalize(password);
		boolean plausible = normalizedPassword.codePointCount(0, normalizedPassword.length()) <= PasswordPolicy.MAX_LENGTH
				&& !normalizedPassword.isEmpty();
		Account read = store.findAccount(address.normalized()).orElse(null);
		Instant now = clock.instant();
		if (read != null && read.passwordHash() != null && read.lockedUntil() != null && now.isBefore(read.lockedUntil())) {
			count("login", "locked");
			throw locked(Duration.between(now, read.lockedUntil()));
		}
		String storedHash = read == null ? null : read.passwordHash();
		// always one Argon2id verification, against a dummy hash when there is nothing to compare with
		boolean matches = hasher.matches(plausible ? normalizedPassword : "x", storedHash) && plausible;
		if (storedHash == null) {
			count("login", "invalid_credentials");
			throw invalidCredentials();
		}
		String emailHash = secrets.addressHash(address.normalized());
		LoginOutcome outcome = transactions.execute(status -> loginInTransaction(address, emailHash, storedHash,
				matches, caller));
		return switch (outcome) {
			case LoginOutcome.Failure f -> {
				count("login", f.metric());
				throw f.problem();
			}
			case LoginOutcome.NotVerified n -> {
				if (n.code() != null) {
					deliver(n.code(), n.to(), EmailCodeMessage.Kind.VERIFY_EMAIL, "login");
				}
				count("login", "not_verified");
				throw notVerified(n.pending());
			}
			case LoginOutcome.Success s -> {
				count("login", "success");
				log.info("E-mail sign-in for user {}", s.signedIn().user().id());
				rehashIfNeeded(s.signedIn().user().id(), storedHash, normalizedPassword);
				yield s.signedIn();
			}
		};
	}

	private LoginOutcome loginInTransaction(Address address, String emailHash, String readHash, boolean matches,
			Caller caller) {
		store.lockAddress(emailHash);
		Account account = store.findAccount(address.normalized()).orElse(null);
		Instant now = clock.instant();
		if (account == null || account.passwordHash() == null || !account.passwordHash().equals(readHash)) {
			// deleted, or the password changed between the check and the lock: not counted
			return new LoginOutcome.Failure("invalid_credentials", invalidCredentials());
		}
		if (account.lockedUntil() != null && now.isBefore(account.lockedUntil())) {
			return new LoginOutcome.Failure("locked", locked(Duration.between(now, account.lockedUntil())));
		}
		if (!matches) {
			Optional<Instant> lockedNow = store.recordFailure(account.userId(), properties.lockout().maxFailures(),
					properties.lockout().duration(), now);
			if (lockedNow.isPresent()) {
				log.warn("E-mail credential of user {} locked after {} wrong passwords", account.userId(),
						properties.lockout().maxFailures());
				count("login", "lockout");
				return new LoginOutcome.Failure("locked", locked(Duration.between(now, lockedNow.get())));
			}
			return new LoginOutcome.Failure("invalid_credentials", invalidCredentials());
		}
		store.clearFailures(account.userId(), now);
		// lock order as in every sign-in: the guest row, then the account row (GuestMergeService)
		GuestCaller guest = caller.guest();
		if (guest != null && !merges.lockMergeableGuest(guest)) {
			return new LoginOutcome.Failure("guest_stale", GuestMergeService.staleGuestToken());
		}
		account = lockAndReload(account.userId());
		if (account == null) {
			return new LoginOutcome.Failure("invalid_credentials", invalidCredentials());
		}
		switch (account.status()) {
			case "SUSPENDED":
				return new LoginOutcome.Failure("suspended", suspended());
			case "DELETED", "MERGED":
				return new LoginOutcome.Failure("invalid_credentials", invalidCredentials());
			default:
				break;
		}
		if (properties.verificationRequired() && !account.verified()) {
			return verificationNeeded(account, emailHash, caller.clientIp(), now);
		}
		SignInOutcome signIn = signInExisting(account.userId(), false, caller, now);
		return switch (signIn) {
			case SignInOutcome.Success s -> new LoginOutcome.Success(s.signedIn());
			case SignInOutcome.Failure f -> new LoginOutcome.Failure(f.metric(), f.problem());
		};
	}

	/** Reuses the account's open verification challenge (new code after the cooldown) or starts one. */
	private LoginOutcome verificationNeeded(Account account, String emailHash, String clientIp, Instant now) {
		if (!sender.available()) {
			return new LoginOutcome.Failure("email_not_configured", emailNotConfigured());
		}
		Challenge open = store.latestOpenForUser(account.userId(), EmailStore.EMAIL_VERIFICATION)
				.filter(c -> now.isBefore(c.createdAt().plus(properties.challengeMaxAge())))
				.filter(c -> c.sends() < properties.maxSends() || now.isBefore(c.expiresAt())).orElse(null);
		if (open != null && now.isBefore(open.lastSentAt().plus(properties.resendCooldown()))) {
			// a code was sent moments ago: point the client at it, send nothing
			return new LoginOutcome.NotVerified(pending(open.id(), open.expiresAt(), open.lastSentAt()), null, null);
		}
		if (sendBudget(emailHash).isPresent()) {
			if (open != null) {
				return new LoginOutcome.NotVerified(pending(open.id(), open.expiresAt(), open.lastSentAt()), null,
						null);
			}
			return new LoginOutcome.Failure("rate_limited", RateLimitDecision.retryAfterProblem(Duration.ofMinutes(5)));
		}
		String code = newCode();
		Instant expiresAt = now.plus(properties.codeTtl());
		UUID id;
		if (open != null && open.sends() < properties.maxSends()) {
			id = open.id();
			store.resend(id, secrets.codeHash(id, EmailStore.EMAIL_VERIFICATION, code), now, expiresAt);
		}
		else {
			store.consumeAllOfUser(account.userId(), now);
			id = UUID.randomUUID();
			store.insertChallenge(id, account.userId(), EmailStore.EMAIL_VERIFICATION, emailHash,
					secrets.codeHash(id, EmailStore.EMAIL_VERIFICATION, code), null, null, properties.maxAttempts(),
					clientIp, now, expiresAt);
		}
		return new LoginOutcome.NotVerified(pending(id, expiresAt, now), code, account.email());
	}

	/**
	 * Takes the account row lock (the one account deletion and guest merge take) and reads the account again, so a
	 * deletion that closed it meanwhile is seen.
	 *
	 * @return {@code null} if the account is gone or closed
	 */
	private Account lockAndReload(UUID userId) {
		accounts.lockForMerge(userId);
		Account account = store.findAccountById(userId).orElse(null);
		return account == null || CLOSED.contains(account.status()) ? null : account;
	}

	private PendingVerification pending(UUID id, Instant expiresAt, Instant lastSentAt) {
		return new PendingVerification(id, expiresAt, lastSentAt.plus(properties.resendCooldown()),
				EmailAuthProperties.CODE_LENGTH);
	}

	/**
	 * Signs an existing account in: records the login, merges the caller's guest, issues the session. The caller holds
	 * the guest lock ({@link GuestMergeService#lockMergeableGuest}) and then the account row lock.
	 */
	private SignInOutcome signInExisting(UUID userId, boolean verifiedNow, Caller caller, Instant now) {
		UserAccount user = accounts.recordEmailSignIn(userId, verifiedNow, now);
		GuestCaller guest = caller.guest();
		boolean merged = guest != null && merges.mergeInto(guest.userId(), userId, "EMAIL", now);
		IssuedSession issued = sessions.issue(user, caller.session());
		return new SignInOutcome.Success(new SignedIn(user, false, issued, merged ? guest.userId() : null));
	}

	private void rehashIfNeeded(UUID userId, String storedHash, String normalizedPassword) {
		if (!hasher.needsRehash(storedHash)) {
			return;
		}
		try {
			String upgraded = hasher.hash(normalizedPassword);
			Boolean done = transactions.execute(status -> store.upgradeHash(userId, storedHash, upgraded,
					clock.instant()));
			if (Boolean.TRUE.equals(done)) {
				count("rehash", "upgraded");
			}
		}
		catch (RuntimeException e) {
			// the sign-in already succeeded; the next one tries again
			log.warn("Password re-hash for user {} failed ({})", userId, e.getClass().getSimpleName());
		}
	}

	// ================================================================== forgot and reset

	void forgot(String email, String clientIp) {
		requireEnabled();
		if (!sender.available()) {
			count("forgot", "email_not_configured");
			throw emailNotConfigured();
		}
		consume(forgotPerIp, clientIp, "forgot");
		Address address = parseAddress(email, "email");
		String emailHash = secrets.addressHash(address.normalized());
		// the per-address budget is taken whether or not an account exists, so a 429 says nothing about one
		Optional<ApiException> limited = sendBudget(emailHash);
		if (limited.isPresent()) {
			count("forgot", "rate_limited");
			throw limited.get();
		}
		Optional<String[]> toSend = transactions.execute(status -> {
			store.lockAddress(emailHash);
			Instant now = clock.instant();
			Account account = store.findAccount(address.normalized()).orElse(null);
			if (account == null || account.passwordHash() == null || !"REGISTERED".equals(account.accountType())
					|| !CAN_RECEIVE_CODES.contains(account.status())) {
				return Optional.<String[]>empty();
			}
			Optional<Challenge> open = store.latestOpenForUser(account.userId(), EmailStore.PASSWORD_RESET);
			if (open.isPresent() && now.isBefore(open.get().lastSentAt().plus(properties.resendCooldown()))) {
				return Optional.<String[]>empty();
			}
			// a new code supersedes every earlier reset code of the account
			open.ifPresent(c -> store.consume(c.id(), now));
			UUID id = UUID.randomUUID();
			String code = newCode();
			store.insertChallenge(id, account.userId(), EmailStore.PASSWORD_RESET, emailHash,
					secrets.codeHash(id, EmailStore.PASSWORD_RESET, code), null, null, properties.maxAttempts(),
					clientIp, now, now.plus(properties.codeTtl()));
			return Optional.of(new String[] { code, account.email() });
		});
		if (toSend.isPresent()) {
			String code = toSend.get()[0];
			String to = toSend.get()[1];
			// off the request thread: the response time must not depend on whether the account exists
			asyncSender.execute(() -> deliver(code, to, EmailCodeMessage.Kind.PASSWORD_RESET, "forgot"));
		}
		count("forgot", "accepted");
	}

	void reset(String email, String code, String newPassword, String clientIp) {
		requireEnabled();
		if (!sender.available()) {
			count("reset", "email_not_configured");
			throw emailNotConfigured();
		}
		consume(resetPerIp, clientIp, "reset");
		Address address = parseAddress(email, "email");
		String password = checkedPassword(newPassword, address, "newPassword");
		String passwordHash = hasher.hash(password);
		boolean wellFormed = code != null && code.matches("^[0-9]{" + EmailAuthProperties.CODE_LENGTH + "}$");
		String emailHash = secrets.addressHash(address.normalized());
		String outcome = transactions.execute(status -> {
			store.lockAddress(emailHash);
			Instant now = clock.instant();
			Challenge challenge = store.latestForAddress(emailHash, EmailStore.PASSWORD_RESET).orElse(null);
			if (challenge == null || challenge.consumedAt() != null || !now.isBefore(challenge.expiresAt())
					|| challenge.attempts() >= challenge.maxAttempts()) {
				return "invalid";
			}
			if (!wellFormed || !secrets.codeMatches(challenge.id(), EmailStore.PASSWORD_RESET, code,
					challenge.codeHash())) {
				store.recordFailedAttempt(challenge.id());
				return "invalid";
			}
			store.consume(challenge.id(), now);
			Account account = lockAndReload(challenge.userId());
			if (account == null || account.email() == null
					|| !secrets.addressHash(account.email().toLowerCase(java.util.Locale.ROOT)).equals(emailHash)) {
				return "invalid";
			}
			store.setPassword(account.userId(), passwordHash, now);
			accounts.markEmailVerified(account.userId(), now);
			store.consumeAllOfUser(account.userId(), now);
			int revoked = sessionService.revokeAllForPasswordChange(account.userId());
			log.info("Password reset for user {}; {} session row(s) ended", account.userId(), revoked);
			return "success";
		});
		count("reset", outcome);
		if (!"success".equals(outcome)) {
			throw new ApiException(ApiErrorCode.RESET_CODE_INVALID, HttpStatus.BAD_REQUEST,
					"The code is not valid or has expired. Request a new code.");
		}
	}

	// ================================================================== helpers

	private SignedIn finish(String flow, SignInOutcome outcome) {
		return switch (outcome) {
			case SignInOutcome.Success s -> {
				count(flow, s.signedIn().created() ? "created" : "success");
				yield s.signedIn();
			}
			case SignInOutcome.Failure f -> {
				count(flow, f.metric());
				throw f.problem();
			}
		};
	}

	private void deliver(String code, String to, EmailCodeMessage.Kind kind, String flow) {
		try {
			sender.send(new EmailCodeMessage(to, code, properties.codeTtl(), kind));
			count("sent", flow);
		}
		catch (EmailDeliveryException e) {
			count("delivery_failed", flow);
			log.warn("E-mail code delivery failed ({}); the client may ask for a new code after the cooldown", kind);
		}
		catch (RuntimeException e) {
			count("delivery_failed", flow);
			log.warn("E-mail code delivery failed ({}, {})", kind, e.getClass().getSimpleName());
		}
	}

	/** The per-address e-mail budget (hourly and daily); consumed in order, the first refusal wins. */
	private Optional<ApiException> sendBudget(String emailHash) {
		for (RateLimitPolicy policy : sendPerAddress) {
			RateLimitDecision decision = limiter.tryConsume(policy, emailHash);
			if (!decision.allowed()) {
				return Optional.of(decision.toProblem());
			}
		}
		return Optional.empty();
	}

	private void consume(RateLimitPolicy policy, String key, String flow) {
		RateLimitDecision decision = limiter.tryConsume(policy, key);
		if (!decision.allowed()) {
			count(flow, "rate_limited");
			throw decision.toProblem();
		}
	}

	private void requireEnabled() {
		if (!properties.enabled()) {
			throw new ApiException(ApiErrorCode.EMAIL_NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE,
					"E-mail sign-in is not available. Use phone or Google sign-in.");
		}
	}

	private String checkedPassword(String password, Address address, String field) {
		if (password == null) {
			throw fieldProblem(field, PasswordPolicy.TOO_SHORT, "A password is required.");
		}
		Optional<PasswordPolicy.Violation> violation = policy.check(password, address);
		if (violation.isPresent()) {
			throw fieldProblem(field, violation.get().code(), violation.get().message());
		}
		return PasswordPolicy.normalize(password);
	}

	private static Address parseAddress(String email, String field) {
		try {
			return EmailAddresses.parse(email);
		}
		catch (IllegalArgumentException e) {
			throw fieldProblem(field, "INVALID_EMAIL", e.getMessage());
		}
	}

	/** Trimmed, control characters removed, capped at the column's 120; blank = none. */
	static String displayName(String requested) {
		if (requested == null) {
			return null;
		}
		String name = requested.replaceAll("\\p{Cntrl}", "").strip();
		if (name.isEmpty()) {
			return null;
		}
		return name.length() <= 120 ? name : name.substring(0, 120);
	}

	/** Uniform over 000000-999999. */
	private String newCode() {
		return String.format("%06d", random.nextInt(1_000_000));
	}

	private void count(String flow, String outcome) {
		if (meters != null) {
			meters.counter("qal.identity.email", "flow", flow, "outcome", outcome).increment();
		}
	}

	static ApiException fieldProblem(String field, String code, String message) {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
				List.of(new ApiFieldError(field, code, message)), Map.of());
	}

	static ApiException alreadyRegistered() {
		return new ApiException(ApiErrorCode.EMAIL_ALREADY_REGISTERED, HttpStatus.CONFLICT,
				"An account with this e-mail address exists. Sign in, or reset the password.");
	}

	static ApiException invalidCredentials() {
		return new ApiException(ApiErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED,
				"The e-mail address or the password is not correct.");
	}

	static ApiException locked(Duration retryAfter) {
		long seconds = Math.max(1, retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0));
		return new ApiException(ApiErrorCode.ACCOUNT_LOCKED, HttpStatus.TOO_MANY_REQUESTS,
				"Too many wrong passwords. Try again later or reset the password.", List.of(), Map.of(),
				Map.of(RateLimitDecision.RETRY_AFTER, Long.toString(seconds)));
	}

	static ApiException suspended() {
		return new ApiException(ApiErrorCode.ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN, null);
	}

	static ApiException emailNotConfigured() {
		return new ApiException(ApiErrorCode.EMAIL_NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE,
				"E-mail cannot be sent right now. Try again later or use another sign-in method.");
	}

	static ApiException notVerified(PendingVerification pending) {
		Map<String, Object> extensions = new LinkedHashMap<>();
		extensions.put("challengeId", pending.challengeId().toString());
		extensions.put("expiresAt", pending.expiresAt().toString());
		extensions.put("resendAvailableAt", pending.resendAvailableAt().toString());
		extensions.put("codeLength", pending.codeLength());
		return new ApiException(ApiErrorCode.EMAIL_NOT_VERIFIED, HttpStatus.FORBIDDEN,
				"Verify your e-mail address with the code we sent.", List.of(), extensions);
	}

	private static ApiException codeInvalid(Integer attemptsRemaining) {
		String detail = "The code is not valid. Request a new code if needed.";
		return attemptsRemaining == null
				? new ApiException(ApiErrorCode.OTP_INVALID, HttpStatus.UNAUTHORIZED, detail)
				: new ApiException(ApiErrorCode.OTP_INVALID, HttpStatus.UNAUTHORIZED, detail, List.of(),
						Map.of("attemptsRemaining", attemptsRemaining));
	}

	private static ApiException tooManyAttempts() {
		return new ApiException(ApiErrorCode.OTP_TOO_MANY_ATTEMPTS, HttpStatus.TOO_MANY_REQUESTS,
				"Too many wrong codes. Request a new code.");
	}

	// ------------------------------------------------------------------ transaction outcomes

	private sealed interface SignInOutcome {
		record Success(SignedIn signedIn) implements SignInOutcome {
		}

		record Failure(String metric, ApiException problem) implements SignInOutcome {
		}
	}

	private sealed interface LoginOutcome {
		record Success(SignedIn signedIn) implements LoginOutcome {
		}

		record Failure(String metric, ApiException problem) implements LoginOutcome {
		}

		/** @param code the new code to send after commit, or {@code null} when none is sent */
		record NotVerified(PendingVerification pending, String code, String to) implements LoginOutcome {
			@Override
			public String toString() {
				return "NotVerified[" + pending.challengeId() + "]";
			}
		}
	}

	private sealed interface StartOutcome {
		record Created(String code, PendingVerification pending, String to, boolean registration)
				implements StartOutcome {

			Created(String code, PendingVerification pending) {
				this(code, pending, null, true);
			}

			@Override
			public String toString() {
				return "Created[" + pending.challengeId() + "]";
			}
		}

		record Refused(String metric, ApiException problem) implements StartOutcome {
		}
	}
}
