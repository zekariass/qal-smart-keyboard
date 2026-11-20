package com.qalsmartkeyboard.billing.spi;

/**
 * A payment method the provider reports as enabled for a merchant account (method discovery, SD 16.4; e.g. Chapa's
 * method list). Discovery only proposes rows: an operator still enables them.
 */
public record DiscoveredMethod(String providerMethodCode, String displayName, String methodType, String currencyCode) {
}
