package com.qalsmartkeyboard.entitlements.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.qalsmartkeyboard.entitlements.AccessState;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.entitlements.token.EntitlementTokenVerifier.Validity;
import com.qalsmartkeyboard.entitlements.token.EntitlementTokenVerifier.Verified;
import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B4.4: {@code getEntitlementToken} and {@code getEntitlementTokenKeys} against the contract, behind the real security
 * chain, with the real issuer (ephemeral test key) and a mocked resolver. A client-side verifier pinned to the served
 * key set verifies the served token.
 */
@WebMvcTest(EntitlementTokenController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class, EntitlementTokenConfiguration.class })
class EntitlementTokenControllerTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService accessTokens;
	@MockitoBean
	EntitlementResolver resolver;
	@MockitoBean
	RateLimiter limiter;

	@BeforeEach
	void allowByDefault() {
		when(limiter.tryConsume(any(), any())).thenReturn(new RateLimitDecision(EntitlementTokenController.PER_USER,
				true, 59, Duration.ZERO, Duration.ofMinutes(60)));
	}

	final UUID userId = UUID.randomUUID();
	final UUID deviceId = UUID.randomUUID();

	String bearer() {
		return "Bearer " + accessTokens.issue(userId, UUID.randomUUID(), deviceId, "REGISTERED").value();
	}

	EntitlementTokenVerifier pinnedToServedKeys() throws Exception {
		String body = mvc.perform(get("/.well-known/jwks.json")).andReturn().getResponse().getContentAsString();
		Map<String, java.security.PublicKey> pinned = new java.util.LinkedHashMap<>();
		for (var jwk : JWKSet.parse(body).getKeys()) {
			OctetKeyPair okp = (OctetKeyPair) jwk;
			pinned.put(okp.getKeyID(), EntitlementTokenKeys.publicFromRaw(okp.getDecodedX()));
		}
		return new EntitlementTokenVerifier(pinned, "qal-smart-keyboard", "qal-keyboard", Duration.ofMinutes(5));
	}

	@Test
	void issuesATokenThatVerifiesAgainstThePublishedKeys() throws Exception {
		when(resolver.resolve(userId)).thenReturn(TokenFixtures.granted(userId, "PREMIUM", true));
		String body = mvc.perform(get("/api/v1/me/entitlements/token").header("Authorization", bearer()))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.keyId").isString()).andExpect(jsonPath("$.jws").isString())
				.andReturn().getResponse().getContentAsString();
		String jws = com.nimbusds.jose.util.JSONObjectUtils.parse(body).get("jws").toString();
		Instant expiresAt = Instant.parse(com.nimbusds.jose.util.JSONObjectUtils.parse(body).get("expiresAt").toString());
		Instant grace = Instant
				.parse(com.nimbusds.jose.util.JSONObjectUtils.parse(body).get("offlineGraceUntil").toString());
		assertThat(Duration.between(expiresAt, grace)).isEqualTo(Duration.ofHours(72));

		Verified v = pinnedToServedKeys().verify(jws, Instant.now(), userId, deviceId);
		assertThat(v.validity()).isEqualTo(Validity.VALID);
		assertThat(v.deviceId()).isEqualTo(deviceId);
		assertThat(v.features()).containsExactly(Map.entry("ON_DEVICE_AI_MODELS", true));
		assertThat(v.expiresAt()).isEqualTo(expiresAt);
	}

	@Test
	void theKeySetIsAnonymousPublicAndCacheable() throws Exception {
		mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "max-age=3600, public"))
				.andExpect(jsonPath("$.keys.length()").value(1)).andExpect(jsonPath("$.keys[0].kty").value("OKP"))
				.andExpect(jsonPath("$.keys[0].crv").value("Ed25519")).andExpect(jsonPath("$.keys[0].alg").value("EdDSA"))
				.andExpect(jsonPath("$.keys[0].use").value("sig")).andExpect(jsonPath("$.keys[0].d").doesNotExist());
		// the Authorization header is ignored there: even a garbage bearer gets the keys
		mvc.perform(get("/.well-known/jwks.json").header("Authorization", "Bearer not-a-token"))
				.andExpect(status().isOk());
	}

	@Test
	void withoutABearerTheTokenIs401() throws Exception {
		mvc.perform(get("/api/v1/me/entitlements/token")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		verify(resolver, never()).resolve(any(UUID.class));
	}

	@Test
	void suspendedAndDeletedAccountsGetNoToken() throws Exception {
		when(resolver.resolve(userId)).thenReturn(TokenFixtures.denied(userId, AccessState.SUSPENDED));
		mvc.perform(get("/api/v1/me/entitlements/token").header("Authorization", bearer()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"))
				.andExpect(jsonPath("$.jws").doesNotExist());
		when(resolver.resolve(userId)).thenReturn(TokenFixtures.denied(userId, AccessState.DELETED));
		mvc.perform(get("/api/v1/me/entitlements/token").header("Authorization", bearer()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DELETION_PENDING"));
		when(resolver.resolve(userId)).thenReturn(TokenFixtures.denied(userId, AccessState.UNKNOWN));
		mvc.perform(get("/api/v1/me/entitlements/token").header("Authorization", bearer()))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void sixtyPerHourPerUserThen429WithRateLimitHeaders() throws Exception {
		assertThat(EntitlementTokenController.PER_USER.capacity()).isEqualTo(60);
		assertThat(EntitlementTokenController.PER_USER.period()).isEqualTo(Duration.ofHours(1));
		when(resolver.resolve(userId)).thenReturn(TokenFixtures.granted(userId, "PREMIUM", true));
		mvc.perform(get("/api/v1/me/entitlements/token").header("Authorization", bearer())).andExpect(status().isOk());
		verify(limiter).tryConsume(EntitlementTokenController.PER_USER, userId.toString());

		when(limiter.tryConsume(any(), any())).thenReturn(new RateLimitDecision(EntitlementTokenController.PER_USER,
				false, 0, Duration.ofSeconds(90), Duration.ofSeconds(90)));
		mvc.perform(get("/api/v1/me/entitlements/token").header("Authorization", bearer()))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(header().string("Retry-After", "90")).andExpect(header().string("RateLimit-Limit", "60"))
				.andExpect(header().string("RateLimit-Remaining", "0")).andExpect(jsonPath("$.jws").doesNotExist());
	}
}
