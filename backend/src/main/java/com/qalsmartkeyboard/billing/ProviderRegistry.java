package com.qalsmartkeyboard.billing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.billing.spi.PaymentProvider;
import com.qalsmartkeyboard.billing.spi.StoreBillingProvider;

/**
 * Adapters by {@code provider_code} (SD 16.3 rule 2: a database row alone never creates an integration). Two adapters
 * with the same code stop the application.
 */
final class ProviderRegistry {

	private final Map<String, PaymentProvider> gateways = new HashMap<>();
	private final Map<String, StoreBillingProvider> stores = new HashMap<>();

	ProviderRegistry(List<PaymentProvider> gateways, List<StoreBillingProvider> stores) {
		for (PaymentProvider p : gateways) {
			if (this.gateways.put(p.providerCode(), p) != null) {
				throw new IllegalStateException("Two payment provider adapters for " + p.providerCode());
			}
		}
		for (StoreBillingProvider s : stores) {
			if (this.stores.put(s.providerCode(), s) != null || this.gateways.containsKey(s.providerCode())) {
				throw new IllegalStateException("Two payment provider adapters for " + s.providerCode());
			}
		}
	}

	/** The gateway adapter for {@code code} configured for {@code environment}. */
	Optional<PaymentProvider> gateway(String code, PaymentEnvironment environment) {
		PaymentProvider p = gateways.get(code);
		return p != null && p.supports(environment) ? Optional.of(p) : Optional.empty();
	}

	/** Whether any adapter (gateway or store) for {@code code} runs for {@code environment}. */
	boolean registered(String code, PaymentEnvironment environment) {
		PaymentProvider p = gateways.get(code);
		if (p != null) {
			return p.supports(environment);
		}
		StoreBillingProvider s = stores.get(code);
		return s != null && s.supports(environment);
	}
}
