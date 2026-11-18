package com.qalsmartkeyboard.aigateway.routing;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.RouteQuery;

/**
 * Cached view of the enabled {@code ai_routing_policies} rows (B5.2). The snapshot is reused for the cache TTL and a
 * stale one is served if a reload fails, like the provider registry. Rows are only an ordering and an allow-list over
 * what the registry already permits: a policy can never route to a provider the registry refuses.
 */
public class RoutingPolicies {

	private static final String SQL = """
			SELECT r.feature_code, sl.language_code AS source_language, dl.language_code AS destination_language,
			       p.provider_code, r.priority, r.traffic_weight, r.requires_zero_retention, r.valid_from, r.valid_until
			  FROM ai_routing_policies r
			  JOIN ai_models m ON m.id = r.model_id
			  JOIN ai_providers p ON p.id = m.provider_id
			  LEFT JOIN supported_languages sl ON sl.id = r.source_language_id
			  LEFT JOIN supported_languages dl ON dl.id = r.destination_language_id
			 WHERE r.is_enabled AND r.execution_target = 'SERVER'
			 ORDER BY r.feature_code, r.priority, p.provider_code
			""";

	private final Supplier<List<RoutingPolicy>> source;
	private final Duration ttl;
	private final Clock clock;
	private final DoubleSupplier random;

	private volatile List<RoutingPolicy> snapshot;
	private volatile Instant loadedAt = Instant.MIN;

	public RoutingPolicies(Supplier<List<RoutingPolicy>> source, Duration ttl, Clock clock, DoubleSupplier random) {
		this.source = source;
		this.ttl = ttl;
		this.clock = clock;
		this.random = random;
	}

	public static Supplier<List<RoutingPolicy>> jdbc(JdbcClient jdbc) {
		return () -> jdbc.sql(SQL).query((rs, i) -> {
			Timestamp until = rs.getTimestamp("valid_until");
			return new RoutingPolicy(AiFeature.fromCode(rs.getString("feature_code")), rs.getString("source_language"),
					rs.getString("destination_language"), rs.getString("provider_code"), rs.getInt("priority"),
					rs.getInt("traffic_weight"), rs.getBoolean("requires_zero_retention"),
					rs.getTimestamp("valid_from").toInstant(), until == null ? null : until.toInstant());
		}).list().stream().filter(p -> p.feature() != null).toList();
	}

	/** The ordered provider plan for a query, empty when no policy row matches (registry order applies). */
	public Optional<RoutingPolicy.Plan> planFor(RouteQuery query) {
		return RoutingPolicy.plan(rows(), query, clock.instant(), random);
	}

	private List<RoutingPolicy> rows() {
		Instant now = clock.instant();
		List<RoutingPolicy> s = snapshot;
		if (s != null && Duration.between(loadedAt, now).compareTo(ttl) < 0) {
			return s;
		}
		try {
			s = List.copyOf(source.get());
			snapshot = s;
			loadedAt = now;
		} catch (RuntimeException e) {
			if (s == null) {
				throw e;
			}
			loadedAt = now; // serve the stale snapshot for another TTL instead of hammering a failing database
		}
		return s;
	}
}
