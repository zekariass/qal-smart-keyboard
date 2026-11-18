package com.qalsmartkeyboard.aigateway.spi;

/**
 * What the provider confirms it processed, in every unit metering and cost tracking use (ADR 0011: settle with actual
 * units; {@code ai_requests.input_character_count} etc.). Token and audio figures are null when the vendor does not
 * report them; characters are always measured by us.
 */
public record ProviderUsage(int inputCharacters, int outputCharacters, Long inputTokens, Long outputTokens,
		Long audioMillis) {

	public ProviderUsage {
		if (inputCharacters < 0 || outputCharacters < 0) {
			throw new IllegalArgumentException("negative character count");
		}
	}

	public static ProviderUsage ofCharacters(int in, int out) {
		return new ProviderUsage(in, out, null, null, null);
	}

	/** Units to settle for a feature: REQUEST = 1, CHARACTER = input characters, SECOND = whole seconds rounded up. */
	public long units(MeteredUnit unit) {
		return switch (unit) {
			case REQUEST -> 1L;
			case CHARACTER -> inputCharacters;
			case SECOND -> audioMillis == null ? 0L : Math.ceilDiv(audioMillis, 1000L);
		};
	}
}
