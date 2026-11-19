package com.qalsmartkeyboard.notifications.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code qal.notifications.email.*} (ADR 0033).
 *
 * @param provider which {@link EmailSender} delivers e-mail: {@code none} (default: no e-mail can be sent; flows that
 *                 need one answer 503 {@code EMAIL_NOT_CONFIGURED}), or {@code log} / {@code capture} (development and
 *                 tests only, refused under prod/production/staging). No real vendor adapter exists yet.
 * @param log      settings of the {@code log} provider
 */
@ConfigurationProperties("qal.notifications.email")
record EmailProperties(@DefaultValue("none") String provider, @DefaultValue Log log) {

	/** @param includeMessage DEV ONLY: log the message body including the code */
	record Log(@DefaultValue("false") boolean includeMessage) {
	}
}
