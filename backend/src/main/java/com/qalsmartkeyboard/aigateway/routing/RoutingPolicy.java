package com.qalsmartkeyboard.aigateway.routing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.DoubleSupplier;

import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.RouteQuery;

/**
 * One enabled {@code ai_routing_policies} row, with language ids resolved to codes and the model resolved to its
 * provider. A null language means "any".
 */
public record RoutingPolicy(AiFeature feature, String sourceLanguage, String destinationLanguage,
		String providerCode, int priority, int trafficWeight, boolean requiresZeroRetention, Instant validFrom,
		Instant validUntil) {

	/**
	 * The providers a policy names for a query, in the order to try them; {@code zeroRetentionOnly} are those whose
	 * policy row demands zero retention.
	 */
	public record Plan(List<String> providers, Set<String> zeroRetentionOnly) {
	}

	boolean validAt(Instant now) {
		return !now.isBefore(validFrom) && (validUntil == null || now.isBefore(validUntil));
	}

	/** 0 = any/any, 1 = one language fixed, 2 = both fixed. Only matching rows are compared. */
	int specificity() {
		return (sourceLanguage == null ? 0 : 1) + (destinationLanguage == null ? 0 : 1);
	}

	boolean matches(RouteQuery q, Instant now) {
		return feature == q.feature() && validAt(now) && trafficWeight > 0
				&& (sourceLanguage == null || sourceLanguage.equals(q.sourceLanguage()))
				&& (destinationLanguage == null || destinationLanguage.equals(q.destinationLanguage()));
	}

	/**
	 * The most specific matching tier decides: rows of a narrower language scope replace the broader ones (a policy for
	 * en to am does not merge with the any-language row). Inside the tier providers are ordered by priority, lowest
	 * first; equal priorities are ordered by a weighted random draw (traffic_weight is the weight), so the heavier
	 * provider is tried first more often. A provider appears once, at its best position. Empty when no row matches:
	 * the caller then uses registry order.
	 */
	public static Optional<Plan> plan(List<RoutingPolicy> all, RouteQuery query, Instant now, DoubleSupplier random) {
		List<RoutingPolicy> matching = all.stream().filter(p -> p.matches(query, now)).toList();
		if (matching.isEmpty()) {
			return Optional.empty();
		}
		int best = matching.stream().mapToInt(RoutingPolicy::specificity).max().getAsInt();
		List<RoutingPolicy> tier = matching.stream().filter(p -> p.specificity() == best)
				.sorted(Comparator.comparingInt(RoutingPolicy::priority)).toList();
		List<String> order = new ArrayList<>();
		Set<String> zdrOnly = new LinkedHashSet<>();
		int i = 0;
		while (i < tier.size()) {
			int j = i;
			while (j < tier.size() && tier.get(j).priority() == tier.get(i).priority()) {
				j++;
			}
			List<RoutingPolicy> group = new ArrayList<>(tier.subList(i, j));
			// weighted random order: key = u^(1/weight), largest first
			List<double[]> keyed = new ArrayList<>();
			for (int k = 0; k < group.size(); k++) {
				keyed.add(new double[] { Math.pow(random.getAsDouble(), 1.0 / group.get(k).trafficWeight()), k });
			}
			keyed.sort((a, b) -> Double.compare(b[0], a[0]));
			for (double[] key : keyed) {
				RoutingPolicy p = group.get((int) key[1]);
				if (!order.contains(p.providerCode())) {
					order.add(p.providerCode());
				}
				if (p.requiresZeroRetention()) {
					zdrOnly.add(p.providerCode());
				}
			}
			i = j;
		}
		return Optional.of(new Plan(List.copyOf(order), Set.copyOf(zdrOnly)));
	}
}
