package com.qalsmartkeyboard.billing.spi;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One received store notification ({@code POST /billing/store-notifications/{providerCode}}), as bytes. Header names
 * are lower case. Never logged.
 */
public record StoreNotificationRequest(byte[] body, Map<String, List<String>> headers) {

	public StoreNotificationRequest {
		body = body == null ? new byte[0] : body;
		headers = headers == null ? Map.of() : headers;
	}

	public String header(String name) {
		List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	@Override
	public String toString() {
		return "StoreNotificationRequest[bytes=" + body.length + "]";
	}
}
