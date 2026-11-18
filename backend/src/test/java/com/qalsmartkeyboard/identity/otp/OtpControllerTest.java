package com.qalsmartkeyboard.identity.otp;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qalsmartkeyboard.identity.account.UserAccount;
import com.qalsmartkeyboard.identity.merge.GuestMergeService.GuestCaller;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.otp.OtpService.StartedChallenge;
import com.qalsmartkeyboard.identity.otp.OtpService.VerifiedSignIn;
import com.qalsmartkeyboard.identity.session.IssuedSession;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B2.1: {@code /auth/otp/start} and {@code /auth/otp/verify} responses against the contract (service mocked), behind the
 * real security chain (B2.4): both operations are reachable anonymously.
 */
@WebMvcTest(OtpController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class })
class OtpControllerTest {

	static final String START = "/api/v1/auth/otp/start";
	static final String VERIFY = "/api/v1/auth/otp/verify";
	static final RateLimitPolicy IP_POLICY = new RateLimitPolicy("otp.start.ip", 30, Duration.ofHours(1));

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService tokens;

	@MockitoBean
	OtpService service;

	@Test
	void startReturns202ChallengeWithCallerRateLimitHeaders() throws Exception {
		UUID id = UUID.randomUUID();
		when(service.start(eq("+251911234567"), any(), isNull(), eq("am-ET"))).thenReturn(new StartedChallenge(id,
				Instant.parse("2026-11-17T10:05:00Z"), Instant.parse("2026-11-17T10:01:00Z"), 6,
				new RateLimitDecision(IP_POLICY, true, 29, Duration.ZERO, Duration.ofMinutes(60))));
		mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON).header("Accept-Language", "am-ET,en;q=0.5")
				.content("{\"phoneNumber\":\"+251911234567\",\"channel\":\"SMS\"}")).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.challengeId").value(id.toString()))
				.andExpect(jsonPath("$.expiresAt").value("2026-11-17T10:05:00Z"))
				.andExpect(jsonPath("$.resendAvailableAt").value("2026-11-17T10:01:00Z"))
				.andExpect(jsonPath("$.codeLength").value(6)).andExpect(header().string("RateLimit-Limit", "30"))
				.andExpect(header().string("RateLimit-Remaining", "29")).andExpect(header().exists("X-Correlation-Id"))
				.andExpect(content().string(not(containsString("911234567"))));
		verify(service).start(eq("+251911234567"), eq("127.0.0.1"), isNull(), eq("am-ET"));
	}

	@Test
	void startRejectsNonE164With400WithoutEchoingTheNumber() throws Exception {
		mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"0911 234 567\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("phoneNumber"))
				.andExpect(content().string(not(containsString("234 567"))));
		mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON)
				.content("{\"phoneNumber\":\"+251911234567\",\"channel\":\"CARRIER_PIGEON\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("channel"));
	}

	@Test
	void startRateLimitedIs429ProblemWithRetryAfterAndRateLimitHeaders() throws Exception {
		when(service.start(any(), any(), any(), any())).thenThrow(
				new RateLimitDecision(IP_POLICY, false, 0, Duration.ofSeconds(90), Duration.ofSeconds(90)).toProblem());
		mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+251911234567\"}"))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(jsonPath("$.correlationId").isNotEmpty()).andExpect(header().string("Retry-After", "90"))
				.andExpect(header().string("RateLimit-Limit", "30")).andExpect(header().string("RateLimit-Remaining", "0"))
				.andExpect(header().string("RateLimit-Reset", "90"));
	}

	@Test
	void startEthiopianLandlineIs400() throws Exception {
		when(service.start(eq("+251111234567"), any(), any(), any())).thenThrow(new ApiException(
				ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
				List.of(new ApiFieldError("phoneNumber", "INVALID_PHONE_NUMBER", "Must be a mobile number.")), Map.of()));
		mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+251111234567\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("INVALID_PHONE_NUMBER"));
	}

	/** ADR 0007 D9 (1): the optional {@code installationId} keys the per-device start limit. */
	@Test
	void startPassesTheInstallationIdAsTheDeviceKey() throws Exception {
		UUID installation = UUID.randomUUID();
		when(service.start(any(), any(), any(), any())).thenReturn(new StartedChallenge(UUID.randomUUID(),
				Instant.parse("2026-11-17T10:05:00Z"), Instant.parse("2026-11-17T10:01:00Z"), 6,
				new RateLimitDecision(IP_POLICY, true, 29, Duration.ZERO, Duration.ofMinutes(60))));
		mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON)
				.content("{\"phoneNumber\":\"+251911234567\",\"installationId\":\"" + installation + "\"}"))
				.andExpect(status().isAccepted());
		verify(service).start(eq("+251911234567"), eq("127.0.0.1"), eq(installation.toString()), isNull());

		mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON)
				.content("{\"phoneNumber\":\"+251911234567\",\"installationId\":\"not-a-uuid\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(content().string(not(containsString("not-a-uuid"))));
	}

	/** ADR 0007 D9 (3): an invalid bearer on an optional-bearer operation stays a 401 (now declared). */
	@Test
	void startWithAnInvalidBearerTokenIs401() throws Exception {
		mvc.perform(post(START).header("Authorization", "Bearer garbage").contentType(MediaType.APPLICATION_JSON)
				.content("{\"phoneNumber\":\"+251911234567\"}")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	/** ADR 0007 D9 (2): verify declares 403 for a suspended account. */
	@Test
	void verifySuspendedAccountIs403() throws Exception {
		when(service.verify(any(), any(), anyBoolean(), any(), isNull(), any(), isNull()))
				.thenThrow(new ApiException(ApiErrorCode.ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN, null));
		mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON)
				.content("{\"challengeId\":\"" + UUID.randomUUID() + "\",\"code\":\"123456\"}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
	}

	@Test
	void verifyReturnsAuthSessionWithNoStore() throws Exception {
		UUID challenge = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID installation = UUID.randomUUID();
		UserAccount user = new UserAccount(userId, "REGISTERED", "ACTIVE", "+251911234567",
				Instant.parse("2026-11-17T10:00:00Z"), null, null, "Africa/Addis_Ababa",
				Instant.parse("2026-11-17T10:00:00Z"));
		IssuedSession session = new IssuedSession("access.jwt.value", Instant.parse("2026-11-17T10:15:00Z"),
				"refresh-token-value", Instant.parse("2027-01-16T10:00:00Z"), UUID.randomUUID(), null);
		when(service.verify(eq(challenge), eq("123456"), eq(true), eq("127.0.0.1"), eq(installation.toString()), any(),
				isNull()))
				.thenReturn(new VerifiedSignIn(user, true, session, null));
		mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content("""
				{"challengeId":"%s","code":"123456","ageConfirmed":true,
				 "device":{"installationId":"%s","platform":"ANDROID","appVersion":"2.0.0"}}
				""".formatted(challenge, installation))).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.accessToken").value("access.jwt.value"))
				.andExpect(jsonPath("$.refreshToken").value("refresh-token-value"))
				.andExpect(jsonPath("$.isNewUser").value(true)).andExpect(jsonPath("$.user.id").value(userId.toString()))
				.andExpect(jsonPath("$.user.accountType").value("REGISTERED"))
				.andExpect(jsonPath("$.user.phoneNumberMasked").value("+251 9•• ••• 567"))
				.andExpect(jsonPath("$.user.phoneVerified").value(true))
				.andExpect(jsonPath("$.deviceId").doesNotExist())
				.andExpect(content().string(not(containsString("911234567"))));
	}

	/** B2.6: a sign-in that linked a guest returns {@code mergedGuestUserId} (contract {@code AuthSession}). */
	@Test
	void verifyReturnsTheMergedGuestUserId() throws Exception {
		UUID guest = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UserAccount user = new UserAccount(userId, "REGISTERED", "ACTIVE", "+251911234567",
				Instant.parse("2026-11-17T10:00:00Z"), null, null, "Africa/Addis_Ababa",
				Instant.parse("2026-11-17T10:00:00Z"));
		IssuedSession session = new IssuedSession("access.jwt.value", Instant.parse("2026-11-17T10:15:00Z"),
				"refresh-token-value", Instant.parse("2027-01-16T10:00:00Z"), UUID.randomUUID(), null);
		when(service.verify(any(), any(), anyBoolean(), any(), isNull(), any(), any()))
				.thenReturn(new VerifiedSignIn(user, false, session, guest));
		String registered = tokens.issue(UUID.randomUUID(), UUID.randomUUID(), null, "REGISTERED").value();
		mvc.perform(post(VERIFY).header("Authorization", "Bearer " + registered).contentType(MediaType.APPLICATION_JSON)
				.content("{\"challengeId\":\"" + UUID.randomUUID() + "\",\"code\":\"123456\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.mergedGuestUserId").value(guest.toString()))
				.andExpect(jsonPath("$.isNewUser").value(false));
		// a registered account's token in the guest position links nothing: the service gets no guest
		verify(service).verify(any(), any(), anyBoolean(), any(), isNull(), any(), isNull());
	}

	/**
	 * B2.6: a guest token is honoured only while its account is an active guest, which needs the account store; without
	 * it (this MVC slice) every guest token is refused, never accepted unchecked (fail closed).
	 */
	@Test
	void aGuestTokenIsRefusedWhenItsAccountCannotBeChecked() throws Exception {
		String guest = tokens.issue(UUID.randomUUID(), UUID.randomUUID(), null, "GUEST").value();
		mvc.perform(post(VERIFY).header("Authorization", "Bearer " + guest).contentType(MediaType.APPLICATION_JSON)
				.content("{\"challengeId\":\"" + UUID.randomUUID() + "\",\"code\":\"123456\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
		verify(service, org.mockito.Mockito.never()).verify(any(), any(), anyBoolean(), any(), any(), any(),
				any(GuestCaller.class));
	}

	@Test
	void verifyFailuresAreContractProblems() throws Exception {
		UUID challenge = UUID.randomUUID();
		String body = "{\"challengeId\":\"" + challenge + "\",\"code\":\"000000\"}";
		when(service.verify(any(), any(), anyBoolean(), any(), isNull(), any(), isNull()))
				.thenThrow(new ApiException(ApiErrorCode.OTP_INVALID, HttpStatus.UNAUTHORIZED, "x"))
				.thenThrow(new ApiException(ApiErrorCode.OTP_EXPIRED, HttpStatus.UNAUTHORIZED, "x"))
				.thenThrow(new ApiException(ApiErrorCode.OTP_TOO_MANY_ATTEMPTS, HttpStatus.TOO_MANY_REQUESTS, "x"))
				.thenThrow(new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
						List.of(new ApiFieldError("ageConfirmed", "REQUIRED", "ageConfirmed is required.")), Map.of()));
		mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
		mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
		mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("OTP_TOO_MANY_ATTEMPTS"));
		mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("ageConfirmed"));
	}

	@Test
	void verifyRejectsMalformedCodeWithoutEchoingIt() throws Exception {
		mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON)
				.content("{\"challengeId\":\"" + UUID.randomUUID() + "\",\"code\":\"12ab56\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("code"))
				.andExpect(content().string(not(containsString("12ab56"))));
		mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("challengeId"));
	}

	@Test
	void acceptLanguageIsReducedToOneWellFormedTag() {
		org.assertj.core.api.Assertions.assertThat(OtpController.uiLocale("am-ET,en;q=0.5")).isEqualTo("am-ET");
		org.assertj.core.api.Assertions.assertThat(OtpController.uiLocale("ti")).isEqualTo("ti");
		org.assertj.core.api.Assertions.assertThat(OtpController.uiLocale("<script>")).isNull();
		org.assertj.core.api.Assertions.assertThat(OtpController.uiLocale(null)).isNull();
	}
}
