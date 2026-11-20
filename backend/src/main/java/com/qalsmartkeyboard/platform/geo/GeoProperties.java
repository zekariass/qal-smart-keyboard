package com.qalsmartkeyboard.platform.geo;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code qal.geo.*} (ADR 0045).
 *
 * @param trustedProxies   CIDRs of the edge (e.g. Cloudflare's published ranges, or the load balancer in front of the
 *                         app) whose direct connections may carry {@code CF-IPCountry}; empty = no network check
 * @param edgeSecret       shared secret the edge adds in {@code edgeSecretHeader} (e.g. a Cloudflare Transform Rule);
 *                         blank = no secret check. {@code CF-IPCountry} is trusted only when at least one of the two
 *                         checks is configured and every configured check passes (default: never trusted)
 * @param edgeSecretHeader header carrying {@code edgeSecret}
 * @param devMockCountry   {@code DEV_MOCK_COUNTRY}: replaces the IP country in development and tests; the
 *                         prod/production/staging profiles refuse to start when it is set
 */
@ConfigurationProperties("qal.geo")
public record GeoProperties(@DefaultValue List<String> trustedProxies, String edgeSecret,
		@DefaultValue("X-Qal-Edge-Secret") String edgeSecretHeader, String devMockCountry) {

	public GeoProperties {
		trustedProxies = trustedProxies == null ? List.of()
				: trustedProxies.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
		edgeSecret = edgeSecret == null || edgeSecret.isBlank() ? null : edgeSecret.strip();
		edgeSecretHeader = edgeSecretHeader == null || edgeSecretHeader.isBlank() ? "X-Qal-Edge-Secret"
				: edgeSecretHeader.strip();
		devMockCountry = devMockCountry == null || devMockCountry.isBlank() ? null : devMockCountry.strip();
	}

	/** Never prints the edge secret. */
	@Override
	public String toString() {
		return "GeoProperties[trustedProxies=" + trustedProxies + ", edgeSecret=" + (edgeSecret == null ? "<unset>"
				: "<redacted>") + ", edgeSecretHeader=" + edgeSecretHeader + ", devMockCountry=" + devMockCountry + "]";
	}
}
