package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qalsmartkeyboard.aigateway.pipeline.AiUserContext;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;

/**
 * ADR 0007 D29: a cancelled request (the caller went away) has no client-visible outcome, so no body and no Problem.
 * Not contract validated: the 499 is not a contract status, nobody is there to read it.
 */
@WebMvcTest(AiController.class)
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class, AiControllerCancelTest.Properties.class })
class AiControllerCancelTest {

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AiGatewayProperties.class)
	static class Properties {
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService tokens;
	@MockitoBean
	AiPipeline pipeline;
	@MockitoBean
	AiFeedback feedback;
	@MockitoBean
	RateLimiter limiter;
	@MockitoBean
	ProviderRegistry registry;
	@MockitoBean
	EntitlementResolver entitlements;
	@MockitoBean
	AiUserContext users;

	@Test
	void aCancelledRequestEndsWithoutABodyAndWithACleanThread() throws Exception {
		when(limiter.tryConsume(any(), any())).thenAnswer(
				i -> new RateLimitDecision(i.getArgument(0), true, 29, Duration.ZERO, Duration.ofSeconds(60)));
		when(pipeline.execute(any())).thenAnswer(i -> {
			Thread.currentThread().interrupt(); // what the pipeline does before it throws
			throw new AiRequestCancelledException();
		});
		String bearer = "Bearer " + tokens.issue(UUID.randomUUID(), UUID.randomUUID(), null, "REGISTERED").value();
		for (String path : new String[] { "translate", "transform", "detect-language" }) {
			String response = mvc.perform(post("/api/v1/ai/" + path).header("Authorization", bearer)
					.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
					.content("{\"task\":\"REWRITE\",\"text\":\"abc\",\"destinationLanguage\":\"am\"}"))
					.andExpect(status().is(AiController.CLIENT_CLOSED_REQUEST)).andReturn().getResponse()
					.getContentAsString();
			assertThat(response).isEmpty();
			assertThat(Thread.currentThread().isInterrupted()).isFalse();
		}
	}
}
