package com.qalsmartkeyboard.tools;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param staleAfter age of a snapshot beyond which the response says {@code stale: true} (env
 *                   {@code QAL_TOOLS_FX_STALE_AFTER}, default 48 hours)
 */
@ConfigurationProperties("qal.tools.fx")
record FxProperties(@DefaultValue("48h") Duration staleAfter) {

	FxProperties {
		if (staleAfter.isNegative() || staleAfter.isZero()) {
			throw new IllegalArgumentException("qal.tools.fx.stale-after must be positive");
		}
	}
}
