package com.qalsmartkeyboard.notifications.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code qal.notifications.sms.*}.
 *
 * @param provider which {@link SmsProvider} sends SMS: {@code log} (development/tests only). No default: startup fails
 *                 if unset. A real aggregator adapter is added when OI-11 is decided.
 * @param log      settings of the {@code log} provider
 */
@ConfigurationProperties("qal.notifications.sms")
record SmsProperties(String provider, @DefaultValue Log log) {

	/** @param includeMessage DEV ONLY: log the message body including the code */
	record Log(@DefaultValue("false") boolean includeMessage) {
	}
}
