package com.qalsmartkeyboard.billing.spi;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One received webhook, as bytes. Header names are lower case. Never logged.
 *
 * @param query the query parameters of the notification URL (first value each)
 */
public record WebhookRequest(MerchantAccount account, byte[] body, Map<String, List<String>> headers,
		Map<String, String> query) {

	public WebhookRequest {
		Objects.requireNonNull(account, "account");
		body = body == null ? new byte[0] : body;
		headers = headers == null ? Map.of() : headers;
		query = query == null ? Map.of() : query;
	}

	public String header(String name) {
		List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	@Override
	public String toString() {
		return "WebhookRequest[account=" + account.accountCode() + ", bytes=" + body.length + "]";
	}
}
