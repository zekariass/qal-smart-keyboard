package com.qalsmartkeyboard.notifications.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.qalsmartkeyboard.notifications.OtpSender;

/** ADR 0007 D28: the dev SMS capture provider and endpoint, and how they stay out of production. */
class DevSmsCaptureTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(SmsConfiguration.class, DevSmsCaptureController.class)
			.withBean(HttpSecurity.class, () -> org.mockito.Mockito.mock(HttpSecurity.class,
					org.mockito.Mockito.RETURNS_SELF))
			.withPropertyValues("qal.notifications.sms.provider=capture");

	@Test
	void endpointIsAbsentByDefault() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).hasSingleBean(OtpSender.class);
			assertThat(ctx).doesNotHaveBean(DevSmsCaptureController.class);
			assertThat(ctx).doesNotHaveBean("devSmsCaptureSecurity");
		});
	}

	@Test
	void endpointNeedsTheCaptureProvider() {
		new ApplicationContextRunner().withUserConfiguration(SmsConfiguration.class, DevSmsCaptureController.class)
				.withPropertyValues("qal.notifications.sms.provider=log", SmsConfiguration.ENDPOINT_PROPERTY + "=true")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void endpointAndProviderAreRefusedUnderProductionLikeProfiles() {
		for (String profile : new String[] { "prod", "production", "staging" }) {
			runner.withPropertyValues("spring.profiles.active=" + profile)
					.run(ctx -> assertThat(ctx).as(profile + " provider").hasFailed());
			runner.withPropertyValues("spring.profiles.active=" + profile, SmsConfiguration.ENDPOINT_PROPERTY + "=true")
					.run(ctx -> assertThat(ctx).as(profile + " endpoint").hasFailed());
		}
	}

	@Test
	void returnsTheLastMessageAndCodeForANumber() throws Exception {
		CapturingSmsProvider provider = new CapturingSmsProvider();
		new SmsOtpSender(provider, null).send(new com.qalsmartkeyboard.notifications.OtpMessage("+251911234567",
				"482913", java.time.Duration.ofMinutes(5), "am"));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new DevSmsCaptureController(provider, new org.springframework.mock.env.MockEnvironment())).build();
		mvc.perform(get("/dev/sms/last").param("phone", "+251911234567")).andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("482913"));
		// '+' arrives as a space when the client forgot to encode it
		mvc.perform(get("/dev/sms/last").param("phone", " 251911234567")).andExpect(status().isOk());
		mvc.perform(get("/dev/sms/last").param("phone", "+251900000000")).andExpect(status().isNotFound());
		mvc.perform(get("/dev/sms/last").param("phone", "+251911234567").with(r -> {
			r.setRemoteAddr("203.0.113.9");
			return r;
		})).andExpect(status().isNotFound());
	}

	@Test
	void capturedValuesNeverShowInToString() {
		CapturingSmsProvider provider = new CapturingSmsProvider();
		provider.send("+251911234567", "code 482913");
		assertThat(provider.last("+251911234567").orElseThrow().toString()).doesNotContain("482913")
				.doesNotContain("911234567");
	}

	@Test
	void capacityIsBounded() {
		CapturingSmsProvider provider = new CapturingSmsProvider();
		for (int i = 0; i < CapturingSmsProvider.MAX_ENTRIES + 5; i++) {
			provider.send("+2519" + String.format("%08d", i), "x");
		}
		assertThat(provider.last("+251900000000")).isEmpty();
		assertThat(provider.last("+2519" + String.format("%08d", CapturingSmsProvider.MAX_ENTRIES + 4))).isPresent();
	}
}
