package com.qalsmartkeyboard.aigateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.qalsmartkeyboard.aigateway.spi.AiProvider;
import com.qalsmartkeyboard.aigateway.spi.ProviderDescriptor;
import com.qalsmartkeyboard.aigateway.spi.ResolvedProvider;
import com.qalsmartkeyboard.aigateway.spi.RouteQuery;

/**
 * The only way the pipeline obtains a provider (B5.1, ADR 0014). A provider is returned for a query only if all of:
 * <ol>
 * <li>its row is ACTIVE, does not train on customer data and (unless self-hosted) has a signed DPA;</li>
 * <li>an adapter bean with the same code exists (a row alone never creates an integration);</li>
 * <li>its data policy satisfies the request (zero retention, regions);</li>
 * <li>an enabled capability on an ACTIVE model matches feature, language pair, minimum quality tier and input size.</li>
 * </ol>
 * Result order is by provider code and carries no routing meaning: priority, weights, fallback, circuit breaker, kill
 * switch and cost caps are the router's (B5.2, {@code AiRouter}). The descriptor snapshot is cached for {@code qal.ai.registry-cache-ttl} and a stale
 * snapshot is served if a reload fails (the same policy as the configuration module).
 */
public class ProviderRegistry {

	private final ProviderDescriptorSource source;
	private final Map<String, AiProvider> adapters = new HashMap<>();
	private final Duration ttl;
	private final Clock clock;

	private volatile Snapshot snapshot;

	private record Snapshot(List<ProviderDescriptor> descriptors, Instant loadedAt) {
	}

	public ProviderRegistry(ProviderDescriptorSource source, Collection<? extends AiProvider> adapters, Duration ttl,
			Clock clock) {
		this.source = source;
		for (AiProvider adapter : adapters) {
			if (this.adapters.put(adapter.code(), adapter) != null) {
				throw new IllegalStateException("Two AI provider adapters share the code " + adapter.code());
			}
		}
		this.ttl = ttl;
		this.clock = clock;
	}

	/** Providers allowed for the query, ordered by code. */
	public List<ResolvedProvider> resolve(RouteQuery query) {
		return resolve(query, ProviderDescriptor::routable);
	}

	/**
	 * DEGRADED providers that would otherwise qualify (same adapter, contract, data-policy and capability checks),
	 * ordered by code. The router appends them after every ACTIVE candidate as a last resort (ADR 0007 D29).
	 */
	public List<ResolvedProvider> resolveLastResort(RouteQuery query) {
		return resolve(query, ProviderDescriptor::lastResort);
	}

	private List<ResolvedProvider> resolve(RouteQuery query, java.util.function.Predicate<ProviderDescriptor> status) {
		List<ResolvedProvider> out = new ArrayList<>();
		for (ProviderDescriptor d : descriptors()) {
			AiProvider adapter = adapters.get(d.code());
			if (adapter == null || !status.test(d) || !d.dataPolicy().satisfies(query.dataPolicy())) {
				continue;
			}
			d.capabilityFor(query).ifPresent(c -> out.add(new ResolvedProvider(d, adapter, c)));
		}
		out.sort(Comparator.comparing(ResolvedProvider::code));
		return List.copyOf(out);
	}

	/** One provider by code under the same rules (B5.2 uses it to walk a policy ordered list). */
	public Optional<ResolvedProvider> resolve(String providerCode, RouteQuery query) {
		return resolve(query).stream().filter(r -> r.code().equals(providerCode)).findFirst();
	}

	/** ACTIVE, routable providers that have an adapter, for operator views and capability listings. */
	public List<ProviderDescriptor> activeDescriptors() {
		return descriptors().stream().filter(d -> d.routable() && adapters.containsKey(d.code())).toList();
	}

	private List<ProviderDescriptor> descriptors() {
		Snapshot s = snapshot;
		Instant now = clock.instant();
		if (s != null && Duration.between(s.loadedAt(), now).compareTo(ttl) < 0) {
			return s.descriptors();
		}
		try {
			s = new Snapshot(List.copyOf(source.loadAll()), now);
			snapshot = s;
		} catch (RuntimeException e) {
			if (s == null) {
				throw e;
			}
			// serve the stale snapshot: a flapping database must not take AI down while providers are unchanged
		}
		return s.descriptors();
	}
}
