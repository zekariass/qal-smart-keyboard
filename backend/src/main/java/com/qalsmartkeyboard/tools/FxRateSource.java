package com.qalsmartkeyboard.tools;

import java.util.Optional;

/**
 * Port for the exchange-rate provider. The shipped adapter is {@link StaticFxRateSource} (a seeded table, no external
 * call). A real provider (open question: source and licence, SD 26.2) implements this interface and replaces the bean
 * without any change to the API.
 */
interface FxRateSource {

	/** The newest snapshot, or empty when the source has none. */
	Optional<FxRateSnapshot> latest();
}
