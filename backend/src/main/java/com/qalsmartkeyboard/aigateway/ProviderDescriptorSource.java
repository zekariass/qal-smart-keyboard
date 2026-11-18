package com.qalsmartkeyboard.aigateway;

import java.util.List;

import com.qalsmartkeyboard.aigateway.spi.ProviderDescriptor;

/** Where provider descriptors come from (the database in production, a list in unit tests). */
@FunctionalInterface
public interface ProviderDescriptorSource {

	/** Every provider row with its ACTIVE models and enabled capabilities, whatever the provider status. */
	List<ProviderDescriptor> loadAll();
}
