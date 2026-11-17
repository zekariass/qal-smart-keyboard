package com.qalsmartkeyboard.identity.oauth;

import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.BadJWSException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.ExpiredJWTException;
import com.qalsmartkeyboard.identity.oauth.IdTokenVerificationException.Failure;

/**
 * Verifies an OpenID Connect ID token against the issuer's JWKS (SD 9.2): the same checks for Google and, later, Apple.
 *
 * <ul>
 * <li><b>Signature:</b> RS256 only. {@code alg: none}, HS256 (including the "public key as HMAC secret" trick) and any
 * other algorithm never reach a key; keys are selected by {@code kid} and must be RSA signing keys.</li>
 * <li><b>Claims:</b> {@code iss} in the configured set, {@code aud} contains one of the configured client ids,
 * {@code exp} in the future and {@code iat} not in the future (both with a small clock skew), {@code sub} present, and
 * the {@code nonce} claim equal to the request's nonce (constant-time).</li>
 * <li><b>JWKS:</b> fetched from the configured URI with Nimbus' {@link JWKSourceBuilder}: cached for
 * {@code cacheTtl}, refreshed on an unknown {@code kid} (key rotation) at most once per {@code minRefreshInterval},
 * and tolerant of a provider outage for {@code outageTolerance} (cached keys keep verifying). Responses are size and
 * time limited and redirects are not followed.</li>
 * <li>{@code email} is exposed only when {@code email_verified} is true.</li>
 * </ul>
 */
final class JwksIdTokenVerifier {

	private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
	private final Set<String> issuers;
	private final Set<String> audiences;
	private final Duration clockSkew;
	private final Clock clock;

	/** Fetches the JWKS from {@code jwksUri} (https, or http to a loopback host for tests and local stubs). */
	JwksIdTokenVerifier(URI jwksUri, Set<String> issuers, Set<String> audiences, Duration clockSkew, Duration cacheTtl,
			Duration minRefreshInterval, Duration outageTolerance, Duration httpTimeout, Clock clock) {
		this(jwksSource(jwksUri, cacheTtl, minRefreshInterval, outageTolerance, httpTimeout), issuers, audiences,
				clockSkew, clock);
	}

	/** Uses an existing key source (tests inject one). */
	JwksIdTokenVerifier(JWKSource<SecurityContext> keys, Set<String> issuers, Set<String> audiences, Duration clockSkew,
			Clock clock) {
		if (issuers.isEmpty() || audiences.isEmpty()) {
			throw new IllegalArgumentException("an ID token verifier needs at least one issuer and one audience");
		}
		this.issuers = Set.copyOf(issuers);
		this.audiences = Set.copyOf(audiences);
		this.clockSkew = clockSkew;
		this.clock = clock;
		processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
		DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(null, null,
				Set.of("iss", "sub", "aud", "exp", "iat"), null);
		claims.setMaxClockSkew((int) clockSkew.toSeconds());
		processor.setJWTClaimsSetVerifier(claims);
	}

	static JWKSource<SecurityContext> jwksSource(URI jwksUri, Duration cacheTtl, Duration minRefreshInterval,
			Duration outageTolerance, Duration httpTimeout) {
		requireSecureJwksUri(jwksUri);
		try {
			int timeout = (int) httpTimeout.toMillis();
			DefaultResourceRetriever retriever = new DefaultResourceRetriever(timeout, timeout, 256 * 1024, false);
			return JWKSourceBuilder.<SecurityContext>create(jwksUri.toURL(), retriever)
					.cache(cacheTtl.toMillis(), Math.max(timeout, 1000)).rateLimited(minRefreshInterval.toMillis())
					.retrying(true).outageTolerant(outageTolerance.toMillis()).build();
		}
		catch (MalformedURLException e) {
			throw new IllegalArgumentException("the JWKS URI is not a valid URL");
		}
	}

	/** The key set decides who may sign in, so it is fetched over TLS (a loopback stub may use plain http). */
	static void requireSecureJwksUri(URI uri) {
		String scheme = uri.getScheme();
		String host = uri.getHost();
		boolean loopback = host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
		if (!("https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && loopback))) {
			throw new IllegalArgumentException("the JWKS URI must be https (http only to a loopback host)");
		}
	}

	/**
	 * @param expectedNonce the nonce the client sent with the request; the token must carry exactly this
	 * @throws IdTokenVerificationException the token is not acceptable, or the JWKS is unavailable
	 */
	VerifiedIdToken verify(String idToken, String expectedNonce) throws IdTokenVerificationException {
		JWTClaimsSet claims;
		try {
			claims = processor.process(idToken, null);
		}
		catch (ParseException e) {
			throw new IdTokenVerificationException(Failure.MALFORMED);
		}
		catch (ExpiredJWTException e) {
			throw new IdTokenVerificationException(Failure.EXPIRED);
		}
		catch (BadJWSException e) {
			throw new IdTokenVerificationException(Failure.BAD_SIGNATURE);
		}
		catch (BadJWTException e) {
			throw new IdTokenVerificationException(Failure.MISSING_CLAIM);
		}
		catch (BadJOSEException e) {
			// plain JWT, another algorithm, or no key of the right type for the kid
			throw new IdTokenVerificationException(Failure.UNSUPPORTED_ALGORITHM_OR_KEY);
		}
		catch (JOSEException e) {
			// a key source failure: a rate-limited refresh for an unknown kid means "no such key", else an outage
			throw new IdTokenVerificationException(
					e instanceof RateLimitReachedException ? Failure.UNKNOWN_KEY : Failure.JWKS_UNAVAILABLE);
		}
		catch (RuntimeException e) {
			throw new IdTokenVerificationException(Failure.MALFORMED);
		}
		return check(claims, expectedNonce);
	}

	private VerifiedIdToken check(JWTClaimsSet claims, String expectedNonce) throws IdTokenVerificationException {
		if (claims.getIssuer() == null || !issuers.contains(claims.getIssuer())) {
			throw new IdTokenVerificationException(Failure.BAD_ISSUER);
		}
		List<String> audience = claims.getAudience();
		if (audience == null || audience.stream().noneMatch(audiences::contains)) {
			throw new IdTokenVerificationException(Failure.BAD_AUDIENCE);
		}
		Date issuedAt = claims.getIssueTime();
		if (issuedAt.toInstant().isAfter(Instant.now(clock).plus(clockSkew))) {
			throw new IdTokenVerificationException(Failure.NOT_YET_VALID);
		}
		String subject = claims.getSubject();
		if (subject == null || subject.isBlank() || subject.length() > 255) {
			throw new IdTokenVerificationException(Failure.MISSING_CLAIM);
		}
		Object nonce = claims.getClaim("nonce");
		if (!(nonce instanceof String tokenNonce) || expectedNonce == null || !MessageDigest.isEqual(
				tokenNonce.getBytes(StandardCharsets.UTF_8), expectedNonce.getBytes(StandardCharsets.UTF_8))) {
			throw new IdTokenVerificationException(Failure.BAD_NONCE);
		}
		String email = null;
		Object rawEmail = claims.getClaim("email");
		if (rawEmail instanceof String candidate && !candidate.isBlank() && candidate.length() <= 320
				&& emailVerified(claims.getClaim("email_verified"))) {
			email = candidate;
		}
		Object rawName = claims.getClaim("name");
		return new VerifiedIdToken(subject, email, rawName instanceof String n && !n.isBlank() ? n : null);
	}

	/** Google sends a boolean; some issuers send the string {@code "true"}. Anything else is "not verified". */
	private static boolean emailVerified(Object value) {
		return Boolean.TRUE.equals(value) || (value instanceof String s && s.equalsIgnoreCase("true"));
	}
}
