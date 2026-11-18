package com.qalsmartkeyboard.entitlements.token;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nimbusds.jose.util.JSONObjectUtils;

/**
 * Reference implementation of the <b>client</b> rules for entitlement tokens (ADR 0013), written independently of the
 * issuer (JDK Ed25519 and a hand-written compact-JWS parser; Nimbus only parses JSON) so the tests prove the format,
 * not one library talking to itself. The Android, iOS and {@code keyboard-core} verifiers must implement the same
 * rules, in this order:
 *
 * <ol>
 * <li><b>Shape:</b> exactly three base64url parts without padding, at most {@value #MAX_LENGTH} characters.</li>
 * <li><b>Header:</b> {@code alg} exactly {@code EdDSA} (so {@code none}, {@code HS*}, {@code ES*}, {@code RS*} are
 * rejected before any key is looked at), {@code typ} exactly {@value EntitlementTokenService#TYPE_VALUE}, no
 * {@code crit}, no embedded or remote keys ({@code jwk}, {@code jku}, {@code x5u}, {@code x5c}), and a {@code kid}
 * that is in the <b>pinned</b> key set (shipped with the app; never a key fetched at run time).</li>
 * <li><b>Signature:</b> Ed25519 over {@code ASCII(header "." payload)} with that pinned key; 64 bytes.</li>
 * <li><b>Claims:</b> {@code iss} and {@code aud} equal the pinned values; {@code ver} = 1; {@code sub} a UUID (and the
 * signed-in user when the client knows it); {@code deviceId}, when present and the client knows its own, equal to it;
 * {@code iat}, {@code exp}, {@code offlineGraceUntil} integers with {@code iat < exp <= iat + 24 h} and
 * {@code exp <= offlineGraceUntil <= exp + 72 h}; {@code features} an object of booleans; {@code planCode},
 * {@code jti} present; {@code iat} not more than the clock skew in the future.</li>
 * <li><b>Time</b> (the caller passes "now": the device clock, but never earlier than the newest {@code iat} or server
 * time it has seen, so turning the clock back does not extend a token): {@code now < exp} is {@link Validity#VALID};
 * {@code exp <= now < offlineGraceUntil} is {@link Validity#OFFLINE_GRACE}, which opens gates <b>only while
 * offline</b> (online, the client fetches a fresh token instead); {@code now >= offlineGraceUntil} is rejected.</li>
 * </ol>
 * A rejected token opens no gate. A client that gets 401/403 from the token endpoint drops its cached token.
 */
final class EntitlementTokenVerifier {

	static final int MAX_LENGTH = 8192;
	static final Duration MAX_TTL = Duration.ofHours(24);
	static final Duration MAX_GRACE = Duration.ofHours(72);
	private static final Set<String> FORBIDDEN_HEADERS = Set.of("crit", "jwk", "jku", "x5u", "x5c");

	enum Reason {
		MALFORMED, ALGORITHM, TYPE, UNKNOWN_KEY, SIGNATURE, ISSUER, AUDIENCE, SUBJECT, DEVICE, CLAIMS, VERSION,
		NOT_YET_VALID, EXPIRED
	}

	enum Validity {
		/** Before {@code exp}: gates apply online and offline. */
		VALID,
		/** Past {@code exp}, before {@code offlineGraceUntil}: gates apply only while offline. */
		OFFLINE_GRACE
	}

	static final class Rejected extends RuntimeException {
		final Reason reason;

		Rejected(Reason reason) {
			super(reason.name(), null, false, false);
			this.reason = reason;
		}
	}

	record Verified(Validity validity, String keyId, UUID subject, UUID deviceId, String planCode,
			Map<String, Boolean> features, Instant issuedAt, Instant expiresAt, Instant offlineGraceUntil,
			String policyVersion, String tokenId) {

		/** The client's gate decision for one feature. */
		boolean allows(String featureCode, boolean online) {
			if (validity == Validity.OFFLINE_GRACE && online) {
				return false;
			}
			return Boolean.TRUE.equals(features.get(featureCode));
		}
	}

	private final Map<String, PublicKey> pinnedKeys;
	private final String issuer;
	private final String audience;
	private final Duration clockSkew;

	EntitlementTokenVerifier(Map<String, PublicKey> pinnedKeys, String issuer, String audience, Duration clockSkew) {
		this.pinnedKeys = Map.copyOf(pinnedKeys);
		this.issuer = Objects.requireNonNull(issuer);
		this.audience = Objects.requireNonNull(audience);
		this.clockSkew = Objects.requireNonNull(clockSkew);
	}

	Verified verify(String token, Instant now) {
		return verify(token, now, null, null);
	}

	Verified verify(String token, Instant now, UUID expectedSubject, UUID ownDeviceId) {
		if (token == null || token.length() > MAX_LENGTH || !token.matches("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")) {
			throw new Rejected(Reason.MALFORMED);
		}
		String[] parts = token.split("\\.");
		Map<String, Object> header = json(parts[0]);

		if (!"EdDSA".equals(header.get("alg"))) {
			throw new Rejected(Reason.ALGORITHM);
		}
		if (!EntitlementTokenService.TYPE_VALUE.equals(header.get("typ"))) {
			throw new Rejected(Reason.TYPE);
		}
		for (String forbidden : FORBIDDEN_HEADERS) {
			if (header.containsKey(forbidden)) {
				throw new Rejected(Reason.MALFORMED);
			}
		}
		Object kid = header.get("kid");
		PublicKey key = kid instanceof String id ? pinnedKeys.get(id) : null;
		if (key == null) {
			throw new Rejected(Reason.UNKNOWN_KEY);
		}
		byte[] signature = decode(parts[2]);
		if (signature.length != 64 || !ed25519(key, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII),
				signature)) {
			throw new Rejected(Reason.SIGNATURE);
		}

		Map<String, Object> claims = json(parts[1]);
		if (!issuer.equals(claims.get("iss"))) {
			throw new Rejected(Reason.ISSUER);
		}
		Object aud = claims.get("aud");
		if (!(audience.equals(aud) || (aud instanceof List<?> list && list.contains(audience)))) {
			throw new Rejected(Reason.AUDIENCE);
		}
		if (!(claims.get("ver") instanceof Number ver) || ver.longValue() != EntitlementTokenService.FORMAT_VERSION
				|| ver.doubleValue() != EntitlementTokenService.FORMAT_VERSION) {
			throw new Rejected(Reason.VERSION);
		}
		UUID subject = uuid(claims.get("sub"), Reason.SUBJECT);
		if (expectedSubject != null && !expectedSubject.equals(subject)) {
			throw new Rejected(Reason.SUBJECT);
		}
		UUID deviceId = claims.containsKey("deviceId") ? uuid(claims.get("deviceId"), Reason.DEVICE) : null;
		if (ownDeviceId != null && deviceId != null && !ownDeviceId.equals(deviceId)) {
			throw new Rejected(Reason.DEVICE);
		}
		Instant iat = seconds(claims.get("iat"));
		Instant exp = seconds(claims.get("exp"));
		Instant grace = seconds(claims.get("offlineGraceUntil"));
		if (!iat.isBefore(exp) || exp.isAfter(iat.plus(MAX_TTL)) || grace.isBefore(exp)
				|| grace.isAfter(exp.plus(MAX_GRACE))) {
			throw new Rejected(Reason.CLAIMS);
		}
		if (!(claims.get("planCode") instanceof String planCode) || planCode.isBlank()
				|| !(claims.get("jti") instanceof String jti) || jti.isBlank()) {
			throw new Rejected(Reason.CLAIMS);
		}
		Map<String, Boolean> features = new LinkedHashMap<>();
		if (!(claims.get("features") instanceof Map<?, ?> map)) {
			throw new Rejected(Reason.CLAIMS);
		}
		for (Map.Entry<?, ?> e : map.entrySet()) {
			if (!(e.getKey() instanceof String code) || !(e.getValue() instanceof Boolean value)) {
				throw new Rejected(Reason.CLAIMS);
			}
			features.put(code, value);
		}
		if (iat.isAfter(now.plus(clockSkew))) {
			throw new Rejected(Reason.NOT_YET_VALID);
		}
		Validity validity;
		if (now.isBefore(exp)) {
			validity = Validity.VALID;
		}
		else if (now.isBefore(grace)) {
			validity = Validity.OFFLINE_GRACE;
		}
		else {
			throw new Rejected(Reason.EXPIRED);
		}
		return new Verified(validity, (String) kid, subject, deviceId, planCode, Collections.unmodifiableMap(features),
				iat, exp, grace, claims.get("policyVersion") instanceof String p ? p : null, jti);
	}

	static boolean ed25519(PublicKey key, byte[] message, byte[] signature) {
		try {
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(key);
			verifier.update(message);
			return verifier.verify(signature);
		}
		catch (GeneralSecurityException e) {
			return false;
		}
	}

	private static Map<String, Object> json(String part) {
		try {
			return JSONObjectUtils.parse(new String(decode(part), StandardCharsets.UTF_8));
		}
		catch (ParseException e) {
			throw new Rejected(Reason.MALFORMED);
		}
	}

	private static byte[] decode(String part) {
		try {
			return Base64.getUrlDecoder().decode(part);
		}
		catch (IllegalArgumentException e) {
			throw new Rejected(Reason.MALFORMED);
		}
	}

	private static UUID uuid(Object value, Reason reason) {
		if (value instanceof String s && s.length() == 36) {
			try {
				return UUID.fromString(s);
			}
			catch (IllegalArgumentException e) {
				// fall through
			}
		}
		throw new Rejected(reason);
	}

	private static Instant seconds(Object value) {
		if (value instanceof Number n && n.doubleValue() == n.longValue()) {
			return Instant.ofEpochSecond(n.longValue());
		}
		throw new Rejected(Reason.CLAIMS);
	}

	static Optional<Reason> reason(Runnable call) {
		try {
			call.run();
			return Optional.empty();
		}
		catch (Rejected e) {
			return Optional.of(e.reason);
		}
	}
}
