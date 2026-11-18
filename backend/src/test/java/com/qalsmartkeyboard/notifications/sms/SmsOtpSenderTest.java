package com.qalsmartkeyboard.notifications.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import com.qalsmartkeyboard.notifications.OtpDeliveryException;
import com.qalsmartkeyboard.notifications.OtpMessage;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SmsOtpSenderTest {

	static final ObjectProvider<MeterRegistry> NO_METERS = new DefaultListableBeanFactory()
			.getBeanProvider(MeterRegistry.class);
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
	void messageFitsOneGsm7Segment() {
		// SMS Ethiopia: 160 GSM-7 characters per SMS (70 for Amharic/UCS-2); every segment is charged
		for (long minutes : new long[] { 1, 5, 10, 59 }) {
			String text = SmsOtpSender.render(new OtpMessage("+251911234567", "482913", Duration.ofMinutes(minutes), "am"));
			assertThat(text).hasSizeLessThanOrEqualTo(160).matches("[A-Za-z0-9 .,:-]+");
		}
	}

	@Test
	void typedProviderFailureIsLoggedWithItsReason() {
		SmsProvider failing = new SmsProvider() {
			@Override
			public String id() {
				return "typed";
			}

			@Override
			public void send(String to, String body) {
				throw new SmsDeliveryException("typed", SmsDeliveryException.Reason.INSUFFICIENT_BALANCE);
			}
		};
		try (CapturedLogs logs = new CapturedLogs()) {
			assertThatThrownBy(() -> new SmsOtpSender(failing, null).send(MESSAGE))
					.isInstanceOf(OtpDeliveryException.class).hasNoCause();
			assertThat(logs.texts()).anyMatch(t -> t.contains("insufficient_balance"));
		}
	}

	@Test
	void providerSelectionFailsClosed() {
		SmsConfiguration config = new SmsConfiguration();
		assertThatThrownBy(() -> config.smsProvider(new SmsProperties(null, new SmsProperties.Log(false), null),
				new MockEnvironment(), NO_METERS)).isInstanceOf(IllegalStateException.class).hasMessageContaining("not set");
		assertThatThrownBy(() -> config.smsProvider(new SmsProperties("acme", new SmsProperties.Log(false), null),
				new MockEnvironment(), NO_METERS)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Unknown");
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("production");
		assertThatThrownBy(() -> config.smsProvider(new SmsProperties("log", new SmsProperties.Log(false), null), prod,
				NO_METERS))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("refused");
		assertThat(config.smsProvider(new SmsProperties("log", new SmsProperties.Log(false), null), new MockEnvironment(),
				NO_METERS).id()).isEqualTo("log");
	}
}
