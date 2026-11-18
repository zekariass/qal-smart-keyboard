package com.qalsmartkeyboard.usage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qalsmartkeyboard.entitlements.token.EntitlementTokenService;
import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;
import com.qalsmartkeyboard.usage.UsageReadDtos.EntitlementsDto;
import com.qalsmartkeyboard.usage.UsageReadDtos.FeatureDto;
import com.qalsmartkeyboard.usage.UsageReadDtos.PlanDto;
import com.qalsmartkeyboard.usage.UsageReadDtos.TokenDto;
import com.qalsmartkeyboard.usage.UsageReadDtos.UsageItemDto;
import com.qalsmartkeyboard.usage.UsageReadDtos.UsageReportDto;

/**
 * B4.5: every response shape of {@code getEntitlements} and {@code getUsage} against the contract (service mocked) behind
 * the real security chain. The numbers behind the shapes are proven in {@link UsageReadIT}.
 */
@WebMvcTest(UsageReadController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class })
class UsageReadControllerTest {

	static final Instant DAY_END = Instant.parse("2026-11-19T21:00:00Z");
	static final TokenDto TOKEN = new TokenDto("eyJhbGciOiJFZERTQSJ9.e30.c2ln", "ent-2026-11",
			Instant.parse("2026-11-19T08:00:00Z"), Instant.parse("2026-11-22T08:00:00Z"));

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService tokens;
	@MockitoBean
	UsageReadService reads;
	@MockitoBean
	EntitlementTokenService issuer;

	final UUID userId = UUID.randomUUID();

	String bearer() {
		return "Bearer " + tokens.issue(userId, UUID.randomUUID(), null, "REGISTERED").value();
	}

	private void entitlements(EntitlementsDto dto) {
		when(reads.entitlements(eq(userId), any(), eq(issuer))).thenReturn(dto);
	}

	@Test
	void aFreeUserSeesDailyAllowanceUsedRemainingAndReset() throws Exception {
		entitlements(new EntitlementsDto(new PlanDto("FREE", "Free", 0, "DEFAULT", null, null, null),
				List.of(new FeatureDto("OFFLINE_TYPING", true, null, "NONE", "NONE", null, null, null),
						new FeatureDto("TRANSLATE", true, 10L, "REQUEST", "DAILY", 4L, 6L, DAY_END),
						new FeatureDto("ON_DEVICE_AI_MODELS", false, null, "NONE", "NONE", null, null, null)),
				0L, TOKEN));
		mvc.perform(get("/api/v1/me/entitlements").header("Authorization", bearer())).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.plan.planCode").value("FREE")).andExpect(jsonPath("$.plan.source").value("DEFAULT"))
				.andExpect(jsonPath("$.plan.effectiveUntil").doesNotExist())
				.andExpect(jsonPath("$.features[1].used").value(4)).andExpect(jsonPath("$.features[1].remaining").value(6))
				.andExpect(jsonPath("$.features[1].resetsAt").value("2026-11-19T21:00:00Z"))
				.andExpect(jsonPath("$.features[0].used").doesNotExist())
				.andExpect(jsonPath("$.creditBalance").value(0))
				.andExpect(jsonPath("$.entitlementToken.keyId").value("ent-2026-11"));
	}

	@Test
	void aGuestSeesTheGuestPlan() throws Exception {
		entitlements(new EntitlementsDto(new PlanDto("GUEST", "Guest", -1, "DEFAULT", null, null, null),
				List.of(new FeatureDto("TRANSLATE", true, 3L, "REQUEST", "DAILY", 0L, 3L, DAY_END)), 0L, TOKEN));
		mvc.perform(get("/api/v1/me/entitlements").header("Authorization", bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.plan.planCode").value("GUEST"))
				.andExpect(jsonPath("$.features[0].remaining").value(3));
	}

	@Test
	void aUserWithCreditsSeesTheRoundedDownBalance() throws Exception {
		entitlements(new EntitlementsDto(new PlanDto("FREE", "Free", 0, "DEFAULT", null, null, null),
				List.of(new FeatureDto("SPEECH_TO_TEXT", true, 600L, "SECOND", "MONTHLY", 100L, 500L,
						Instant.parse("2026-11-30T21:00:00Z"))),
				250L, TOKEN));
		mvc.perform(get("/api/v1/me/entitlements").header("Authorization", bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.creditBalance").value(250));
	}

	@Test
	void aUserOverQuotaSeesZeroRemaining() throws Exception {
		entitlements(new EntitlementsDto(new PlanDto("FREE", "Free", 0, "DEFAULT", null, null, null),
				List.of(new FeatureDto("TRANSLATE", true, 10L, "REQUEST", "DAILY", 10L, 0L, DAY_END)), 12L, TOKEN));
		mvc.perform(get("/api/v1/me/entitlements").header("Authorization", bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.features[0].used").value(10)).andExpect(jsonPath("$.features[0].remaining").value(0));
	}

	@Test
	void aPaidPlanWithAnAnchoredMonthlyWindowShowsItsSourceAndEnd() throws Exception {
		UUID subscription = UUID.randomUUID();
		entitlements(new EntitlementsDto(
				new PlanDto("PREMIUM", "Premium", 10, "SUBSCRIPTION", subscription, null,
						Instant.parse("2026-12-17T08:00:00Z")),
				List.of(new FeatureDto("TRANSLATE", true, 600L, "REQUEST", "MONTHLY", 42L, 558L,
						Instant.parse("2026-12-17T08:00:00Z")),
						new FeatureDto("TONE_ADJUST", true, null, "REQUEST", "MONTHLY", 7L, null,
								Instant.parse("2026-12-17T08:00:00Z"))),
				250L, TOKEN));
		mvc.perform(get("/api/v1/me/entitlements").header("Authorization", bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.plan.source").value("SUBSCRIPTION"))
				.andExpect(jsonPath("$.plan.subscriptionId").value(subscription.toString()))
				.andExpect(jsonPath("$.plan.effectiveUntil").value("2026-12-17T08:00:00Z"))
				.andExpect(jsonPath("$.features[0].remaining").value(558))
				.andExpect(jsonPath("$.features[1].includedUnits").doesNotExist())
				.andExpect(jsonPath("$.features[1].remaining").doesNotExist());
	}

	@Test
	void usageReportsTheCurrentWindowsWithCredits() throws Exception {
		when(reads.usage(userId, null, null)).thenReturn(new UsageReportDto(List.of(
				new UsageItemDto("TRANSLATE", Instant.parse("2026-11-18T21:00:00Z"), DAY_END, 10L, 10L,
						new BigDecimal("0.500000"), "REQUEST"),
				new UsageItemDto("SPEECH_TO_TEXT", Instant.parse("2026-10-31T21:00:00Z"),
						Instant.parse("2026-11-30T21:00:00Z"), 0L, null, BigDecimal.ZERO.setScale(6), "SECOND"))));
		mvc.perform(get("/api/v1/me/usage").header("Authorization", bearer())).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items[0].usedUnits").value(10))
				.andExpect(jsonPath("$.items[0].includedUnits").value(10))
				.andExpect(jsonPath("$.items[0].creditsConsumed").value(0.5))
				.andExpect(jsonPath("$.items[1].includedUnits").doesNotExist());
	}

	@Test
	void usageAcceptsAPeriodAndPassesItOn() throws Exception {
		Instant from = Instant.parse("2026-10-01T00:00:00Z");
		Instant to = Instant.parse("2026-10-31T21:00:00Z");
		when(reads.usage(userId, from, to)).thenReturn(new UsageReportDto(List.of()));
		mvc.perform(get("/api/v1/me/usage").param("from", "2026-10-01T00:00:00Z").param("to", "2026-11-01T00:00:00+03:00")
				.header("Authorization", bearer())).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		verify(reads).usage(userId, from, to);
	}

	@Test
	void aBadPeriodIs400() throws Exception {
		mvc.perform(get("/api/v1/me/usage").param("from", "yesterday").header("Authorization", bearer()))
				.andExpect(status().isBadRequest());
		verify(reads, never()).usage(any(), any(), any());
	}

	@Test
	void withoutATokenBothAre401() throws Exception {
		mvc.perform(get("/api/v1/me/entitlements")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(get("/api/v1/me/usage")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void aSuspendedOrDeletedAccountIs403() throws Exception {
		when(reads.entitlements(eq(userId), any(), eq(issuer)))
				.thenThrow(new ApiException(ApiErrorCode.ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN, "This account is suspended."));
		when(reads.usage(userId, null, null)).thenThrow(
				new ApiException(ApiErrorCode.DELETION_PENDING, HttpStatus.FORBIDDEN, "This account is being deleted."));
		mvc.perform(get("/api/v1/me/entitlements").header("Authorization", bearer())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
		mvc.perform(get("/api/v1/me/usage").header("Authorization", bearer())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("DELETION_PENDING"));
	}
}
