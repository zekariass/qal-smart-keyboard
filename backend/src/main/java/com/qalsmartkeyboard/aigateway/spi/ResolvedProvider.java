package com.qalsmartkeyboard.aigateway.spi;

/** A provider the registry allows for a query: its adapter, descriptor and the capability that matched. */
public record ResolvedProvider(ProviderDescriptor descriptor, AiProvider adapter, ProviderCapability capability) {

	public String code() {
		return descriptor.code();
	}
}
