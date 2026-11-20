/**
 * Payment provider SPI (ADR 0045, after the ADR 0014 pattern): one {@link com.qalsmartkeyboard.billing.spi.PaymentProvider}
 * bean per gateway, keyed by {@code payment_providers.provider_code}. Adapters start hosted checkouts, verify payments
 * server to server, authenticate and parse webhooks and map provider statuses to
 * {@link com.qalsmartkeyboard.billing.spi.ProviderPaymentStatus}. Orders, entitlements and subscription terms never
 * depend on a specific provider. Adapters hold their own secrets (from the environment); nothing in these types
 * carries a secret, and their {@code toString} never prints payer data.
 */
@NamedInterface("spi")
package com.qalsmartkeyboard.billing.spi;

import org.springframework.modulith.NamedInterface;
