package com.qalsmartkeyboard.entitlements.token;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.mock.env.MockEnvironment;

import com.qalsmartkeyboard.entitlements.AccessState;
import com.qalsmartkeyboard.entitlements.EffectivePlan;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.entitlements.FeatureEntitlement;
import com.qalsmartkeyboard.entitlements.MeteringUnit;
import com.qalsmartkeyboard.entitlements.PlanSource;
import com.qalsmartkeyboard.entitlements.ResetPeriod;
import com.qalsmartkeyboard.entitlements.ResolvedEntitlements;

/** Test helpers: properties, PEM key pairs, resolver results and services at fixed instants. */
final class TokenFixtures {

	static final SecureRandom RANDOM = new SecureRandom();
	static final String ISSUER = "qal-smart-keyboard";
	static final String AUDIENCE = "qal-keyboard";

	private TokenFixtures() {
	}

	record Pem(String privateKey, String publicKey, KeyPair pair) {
	}

	static Pem pem() {
		KeyPair pair = EntitlementTokenKeys.generate(RANDOM);
		return new Pem(pem("PRIVATE KEY", pair.getPrivate().getEncoded()), pem("PUBLIC KEY", pair.getPublic().getEncoded()),
				pair);
	}

	static String pem(String type, byte[] der) {
		return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
				+ "\n-----END " + type + "-----\n";
	}

	static EntitlementTokenProperties properties(EntitlementTokenProperties.SigningKey signingKey,
			List<EntitlementTokenProperties.VerificationKey> previous, List<String> offlineFeatures) {
		return new EntitlementTokenProperties(ISSUER, AUDIENCE, Duration.ofHours(24), Duration.ofHours(72),
				offlineFeatures, signingKey, previous);
	}

	static EntitlementTokenProperties properties(String keyId, Pem pem,
			List<EntitlementTokenProperties.VerificationKey> previous) {
		return properties(new EntitlementTokenProperties.SigningKey(keyId, pem.privateKey(), pem.publicKey(), false),
				previous, List.of("ON_DEVICE_AI_MODELS"));
	}

	static EntitlementTokenKeys keys(EntitlementTokenProperties properties) {
		return EntitlementTokenKeys.load(properties, new MockEnvironment(), RANDOM);
	}

	static EntitlementTokenService service(EntitlementResolver resolver, EntitlementTokenProperties properties,
			Instant now) {
		return new EntitlementTokenService(resolver, keys(properties), properties, Clock.fixed(now, ZoneOffset.UTC));
	}

	static EntitlementResolver resolverReturning(ResolvedEntitlements resolved) {
		EntitlementResolver resolver = mock(EntitlementResolver.class);
		when(resolver.resolve(any(UUID.class))).thenReturn(resolved);
		return resolver;
	}

	/** A granted result: the plan, ON_DEVICE_AI_MODELS as given, plus a metered and an ungated feature. */
	static ResolvedEntitlements granted(UUID userId, String planCode, boolean onDeviceModels) {
		return granted(userId, planCode, onDeviceModels, Instant.parse("2026-12-31T00:00:00Z"), false);
	}

	/** As above with the winning source ending at {@code effectiveUntil} (null = open-ended). */
	static ResolvedEntitlements granted(UUID userId, String planCode, boolean onDeviceModels, Instant effectiveUntil,
			boolean inGrace) {
		Map<String, FeatureEntitlement> features = new LinkedHashMap<>();
		features.put("OFFLINE_TYPING", new FeatureEntitlement("OFFLINE_TYPING", false, true, MeteringUnit.NONE, null,
				ResetPeriod.NONE, BigDecimal.ZERO, false, FeatureEntitlement.Origin.UNGATED));
		features.put("TRANSLATE", new FeatureEntitlement("TRANSLATE", true, true, MeteringUnit.REQUEST, 600L,
				ResetPeriod.MONTHLY, BigDecimal.ONE, true, FeatureEntitlement.Origin.PLAN));
		features.put("HISTORY_SYNC", new FeatureEntitlement("HISTORY_SYNC", true, true, MeteringUnit.NONE, null,
				ResetPeriod.NONE, BigDecimal.ZERO, false, FeatureEntitlement.Origin.PLAN));
		features.put("ON_DEVICE_AI_MODELS", new FeatureEntitlement("ON_DEVICE_AI_MODELS", true, onDeviceModels,
				MeteringUnit.NONE, null, ResetPeriod.NONE, BigDecimal.ZERO, false,
				onDeviceModels ? FeatureEntitlement.Origin.PLAN : FeatureEntitlement.Origin.NO_PLAN_ROW));
		EffectivePlan plan = new EffectivePlan(UUID.randomUUID(), planCode, planCode, 10, PlanSource.SUBSCRIPTION,
				UUID.randomUUID(), null, effectiveUntil, inGrace, null);
		return new ResolvedEntitlements(userId, Instant.now(), AccessState.GRANTED, Optional.of(plan), "PROVISIONAL_V2",
				features);
	}

	static ResolvedEntitlements denied(UUID userId, AccessState access) {
		return new ResolvedEntitlements(userId, Instant.now(), access, Optional.empty(), "PROVISIONAL_V2", Map.of());
	}

	static EntitlementTokenVerifier verifier(EntitlementTokenKeys pinned) {
		return new EntitlementTokenVerifier(pinned.publicKeys(), ISSUER, AUDIENCE, Duration.ofMinutes(5));
	}
}
