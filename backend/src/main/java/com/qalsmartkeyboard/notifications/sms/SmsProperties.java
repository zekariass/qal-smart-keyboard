package com.qalsmartkeyboard.notifications.sms;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code qal.notifications.sms.*}.
 *
 * @param provider    which {@link SmsProvider} sends SMS: {@code sms-ethiopia} (production, ADR 0025), or {@code log} /
 *                    {@code capture} (development/tests only). No default: startup fails if unset.
 * @param log         settings of the {@code log} provider
 * @param smsEthiopia settings of the {@code sms-ethiopia} provider ({@code qal.notifications.sms.sms-ethiopia.*})
 */
@ConfigurationProperties("qal.notifications.sms")
record SmsProperties(String provider, @DefaultValue Log log, @DefaultValue SmsEthiopia smsEthiopia) {

	/** @param includeMessage DEV ONLY: log the message body including the code */
	record Log(@DefaultValue("false") boolean includeMessage) {
	}

	/**
	 * @param apiKey         campaign API key (env {@code QAL_SMS_ETHIOPIA_API_KEY}); required when selected; redacted in
	 *                       toString
	 * @param baseUrl        vendor origin (env {@code QAL_SMS_ETHIOPIA_BASE_URL}); must be https under production-like
	 *                       profiles
	 * @param connectTimeout connect timeout
	 * @param readTimeout    response timeout
	 */
	record SmsEthiopia(String apiKey, @DefaultValue("https://smsethiopia.com/") URI baseUrl,
			@DefaultValue("2s") Duration connectTimeout, @DefaultValue("5s") Duration readTimeout) {

		@Override
		public String toString() {
			return "SmsEthiopia[apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<redacted>")
					+ ", baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout
					+ "]";
		}
	}
}
