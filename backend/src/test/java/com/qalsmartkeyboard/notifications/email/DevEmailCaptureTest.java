package com.qalsmartkeyboard.notifications.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.qalsmartkeyboard.notifications.EmailCodeMessage;
import com.qalsmartkeyboard.notifications.EmailCodeSender;
import com.qalsmartkeyboard.notifications.EmailDeliveryException;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/** ADR 0033: e-mail provider selection, the code templates, and the dev capture endpoint kept out of production. */
class DevEmailCaptureTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(EmailConfiguration.class, DevEmailCaptureController.class)
			.withBean(HttpSecurity.class, () -> org.mockito.Mockito.mock(HttpSecurity.class,
					org.mockito.Mockito.RETURNS_SELF));

	@Test
	void defaultProviderIsNoneAndSendingFailsClosed() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			EmailCodeSender sender = ctx.getBean(EmailCodeSender.class);
			assertThat(sender.available()).isFalse();
			assertThatThrownBy(() -> sender.send(new EmailCodeMessage("a@example.com", "123456", Duration.ofMinutes(15),
					EmailCodeMessage.Kind.SIGN_UP))).isInstanceOf(EmailDeliveryException.class)
					.message().doesNotContain("123456").doesNotContain("example");
			assertThat(ctx).doesNotHaveBean(DevEmailCaptureController.class);
		});
	}

	@Test
	void unknownProviderStopsTheApplication() {
		runner.withPropertyValues("qal.notifications.email.provider=ses").run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void endpointNeedsTheCaptureProvider() {
		runner.withPropertyValues("qal.notifications.email.provider=log", EmailConfiguration.ENDPOINT_PROPERTY + "=true")
				.run(ctx -> assertThat(ctx).hasFailed());
		runner.withPropertyValues("qal.notifications.email.provider=capture",
				EmailConfiguration.ENDPOINT_PROPERTY + "=true").run(ctx -> {
					assertThat(ctx).hasNotFailed();
					assertThat(ctx).hasSingleBean(DevEmailCaptureController.class);
				});
	}

	@Test
	void mocksAndEndpointAreRefusedUnderProductionLikeProfiles() {
		for (String profile : new String[] { "prod", "production", "staging" }) {
			for (String provider : new String[] { "log", "capture" }) {
				runner.withPropertyValues("spring.profiles.active=" + profile, "qal.notifications.email.provider=" + provider)
						.run(ctx -> assertThat(ctx).as(profile + " " + provider).hasFailed());
			}
			runner.withPropertyValues("spring.profiles.active=" + profile, "qal.notifications.email.provider=none")
					.run(ctx -> assertThat(ctx).as(profile + " none").hasNotFailed());
		}
	}

	@Test
	void returnsTheLastMessageAndCodeForAnAddressToLoopbackOnly() throws Exception {
		CapturingEmailSender provider = new CapturingEmailSender();
		TemplatedEmailCodeSender sender = new TemplatedEmailCodeSender(provider, null);
		sender.send(new EmailCodeMessage("Abebe@example.com", "482913", Duration.ofMinutes(15),
				EmailCodeMessage.Kind.PASSWORD_RESET));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new DevEmailCaptureController(sender, new MockEnvironment()))
				.build();
		mvc.perform(get("/dev/email/last").param("to", "abebe@EXAMPLE.com")).andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("482913"))
				.andExpect(jsonPath("$.subject").value("Your Qal password reset code: 482913"));
		mvc.perform(get("/dev/email/last").param("to", "other@example.com")).andExpect(status().isNotFound());
		mvc.perform(get("/dev/email/last").param("to", "abebe@example.com").with(r -> {
			r.setRemoteAddr("203.0.113.9");
			return r;
		})).andExpect(status().isNotFound());
		assertThat(provider.last("abebe@example.com").orElseThrow().toString()).doesNotContain("482913")
				.doesNotContain("Abebe");
	}

	@Test
	void templatesAreShortPlainTextWithoutLinks() {
		for (EmailCodeMessage.Kind kind : EmailCodeMessage.Kind.values()) {
			EmailCodeMessage m = new EmailCodeMessage("a@example.com", "123456", Duration.ofMinutes(15), kind);
			assertThat(TemplatedEmailCodeSender.subject(m)).contains("123456").hasSizeLessThan(60);
			assertThat(TemplatedEmailCodeSender.body(m)).contains("123456").contains("15 minutes")
					.doesNotContain("http").hasSizeLessThan(400);
		}
		assertThat(new EmailCodeMessage("a@example.com", "123456", Duration.ofMinutes(1), EmailCodeMessage.Kind.SIGN_UP)
				.toString()).doesNotContain("123456").doesNotContain("example");
	}

	@Test
	void logProviderNeverLogsTheBodyUnlessTheDevSwitchIsOn() {
		try (CapturedLogs logs = new CapturedLogs()) {
			new TemplatedEmailCodeSender(new LoggingEmailSender(false), null).send(new EmailCodeMessage(
					"abebe@example.com", "482913", Duration.ofMinutes(15), EmailCodeMessage.Kind.SIGN_UP));
			assertThat(logs.texts()).noneMatch(t -> t.contains("482913")).noneMatch(t -> t.contains("abebe@"))
					.anyMatch(t -> t.contains("a***@example.com"));
		}
	}
}
