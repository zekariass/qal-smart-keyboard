package com.qalsmartkeyboard.aigateway.spi;

/** {@code ai_language_capabilities.quality_tier}, ordered weakest to strongest (SD 11.2). */
public enum QualityTier {
	UNVERIFIED, EXPERIMENTAL, BETA, PRODUCTION;

	/** Only BETA and PRODUCTION capabilities are exposed to users. */
	public boolean userExposed() {
		return compareTo(BETA) >= 0;
	}

	public boolean atLeast(QualityTier minimum) {
		return compareTo(minimum) >= 0;
	}
}
