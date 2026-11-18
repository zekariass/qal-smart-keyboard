package com.qalsmartkeyboard.notifications.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.qalsmartkeyboard.notifications.OtpSender;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/** ADR 0025: {@code provider=sms-ethiopia} is selectable under production profiles and fails closed without a key. */
class SmsEthiopiaConfigurationTest {

	static final String KEY = "sms-eth-config-key-do-not-log-4Rt2";

	final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(SmsConfiguration.class)
			.withPropertyValues("qal.notifications.sms.provider=sms-ethiopia");

	@Test
	void startupFailsClearlyWhenTheKeyIsBlank() {
		for (String key : new String[] { "", "   " }) {
			runner.withPropertyValues("qal.notifications.sms.sms-ethiopia.api-key=" + key).run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
						.hasMessageContaining("QAL_SMS_ETHIOPIA_API_KEY");
			});
		}
		runner.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void startsUnderProductionProfilesWithTheKeySet() {
		for (String profile : new String[] { "prod", "production", "staging" }) {
			try (CapturedLogs logs = new CapturedLogs()) {
				runner.withPropertyValues("qal.notifications.sms.sms-ethiopia.api-key=" + KEY)
						.withInitializer(c -> c.getEnvironment().setActiveProfiles(profile)).run(context -> {
							assertThat(context).as(profile).hasNotFailed().hasSingleBean(OtpSender.class);
							SmsProvider provider = context.getBean(SmsProvider.class);
							assertThat(provider).isInstanceOf(SmsEthiopiaProvider.class);
							assertThat(provider.id()).isEqualTo("sms-ethiopia");
							assertThat(provider.toString()).doesNotContain(KEY)
									.contains("baseUrl=https://smsethiopia.com/").contains("connectTimeout=PT2S")
									.contains("readTimeout=PT5S");
							assertThat(context.getBean(SmsProperties.class).toString()).doesNotContain(KEY);
						});
				assertThat(logs.texts()).noneMatch(t -> t.contains(KEY));
			}
		}
	}

	@Test
	void baseUrlAndTimeoutsAreConfigurable() {
		runner.withPropertyValues("qal.notifications.sms.sms-ethiopia.api-key=" + KEY,
				"qal.notifications.sms.sms-ethiopia.base-url=http://localhost:8089/",
				"qal.notifications.sms.sms-ethiopia.connect-timeout=1s",
				"qal.notifications.sms.sms-ethiopia.read-timeout=3s").run(context -> {
					assertThat(context).hasNotFailed();
					SmsProperties.SmsEthiopia s = context.getBean(SmsProperties.class).smsEthiopia();
					assertThat(s.readTimeout()).isEqualTo(Duration.ofSeconds(3));
					assertThat(context.getBean(SmsProvider.class).toString()).contains("http://localhost:8089/")
							.contains("connectTimeout=PT1S");
				});
	}

	@Test
	void plainHttpIsRefusedUnderProductionProfiles() {
		runner.withPropertyValues("qal.notifications.sms.sms-ethiopia.api-key=" + KEY,
				"qal.notifications.sms.sms-ethiopia.base-url=http://smsethiopia.com/")
				.withInitializer(c -> c.getEnvironment().setActiveProfiles("prod")).run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("https");
				});
	}

	@Test
	void nonHttpBaseUrlIsRefused() {
		runner.withPropertyValues("qal.notifications.sms.sms-ethiopia.api-key=" + KEY,
				"qal.notifications.sms.sms-ethiopia.base-url=ftp://smsethiopia.com/").run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("base-url");
				});
	}
}
