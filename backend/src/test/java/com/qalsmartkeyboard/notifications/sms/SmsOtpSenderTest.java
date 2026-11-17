package com.qalsmartkeyboard.notifications.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.qalsmartkeyboard.notifications.OtpDeliveryException;
import com.qalsmartkeyboard.notifications.OtpMessage;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SmsOtpSenderTest {

	static final OtpMessage MESSAGE = new OtpMessage("+251911234567", "482913", Duration.ofMinutes(5), "am");

	@Test
	void logProviderNeverLogsTheCodeOrTheFullNumberByDefault() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		try (CapturedLogs logs = new CapturedLogs()) {
			new SmsOtpSender(new LoggingSmsProvider(false), meters).send(MESSAGE);
			assertThat(logs.texts()).isNotEmpty().noneMatch(t -> t.contains("482913") || t.contains("911234567"))
					.anyMatch(t -> t.contains("+251*******67"));
		}
		assertThat(meters.counter("qal.notifications.sms.sent", "provider", "log", "outcome", "accepted").count())
				.isEqualTo(1.0);
	}

	@Test
	void devOnlySwitchLogsTheMessageBody() {
		try (CapturedLogs logs = new CapturedLogs()) {
			new SmsOtpSender(new LoggingSmsProvider(true), null).send(MESSAGE);
			assertThat(logs.texts()).anyMatch(t -> t.contains("482913") && t.startsWith("DEV ONLY"));
		}
	}

	@Test
	void providerFailureIsWrappedWithoutTheProvidersMessage() {
		SmsProvider failing = new SmsProvider() {
			@Override
			public String id() {
				return "failing";
			}

			@Override
			public void send(String to, String body) {
				throw new IllegalStateException("provider said: bad number " + to + " for " + body);
			}
		};
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		try (CapturedLogs logs = new CapturedLogs()) {
			assertThatThrownBy(() -> new SmsOtpSender(failing, meters).send(MESSAGE))
					.isInstanceOf(OtpDeliveryException.class).hasNoCause()
					.hasMessageNotContaining("482913").hasMessageNotContaining("911234567");
			assertThat(logs.texts()).noneMatch(t -> t.contains("482913") || t.contains("911234567"));
		}
		assertThat(meters.counter("qal.notifications.sms.sent", "provider", "failing", "outcome", "failed").count())
				.isEqualTo(1.0);
	}

	@Test
	void messageMentionsCodeAndValidity() {
		assertThat(SmsOtpSender.render(MESSAGE)).contains("482913").contains("5 minutes");
		assertThat(MESSAGE.toString()).doesNotContain("482913").doesNotContain("911234567");
	}

	@Test
	void providerSelectionFailsClosed() {
		SmsConfiguration config = new SmsConfiguration();
		assertThatThrownBy(() -> config.smsProvider(new SmsProperties(null, new SmsProperties.Log(false)),
				new MockEnvironment())).isInstanceOf(IllegalStateException.class).hasMessageContaining("not set");
		assertThatThrownBy(() -> config.smsProvider(new SmsProperties("acme", new SmsProperties.Log(false)),
				new MockEnvironment())).isInstanceOf(IllegalStateException.class).hasMessageContaining("Unknown");
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("production");
		assertThatThrownBy(() -> config.smsProvider(new SmsProperties("log", new SmsProperties.Log(false)), prod))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("refused");
		assertThat(config.smsProvider(new SmsProperties("log", new SmsProperties.Log(false)), new MockEnvironment())
				.id()).isEqualTo("log");
	}
}
