package com.qalsmartkeyboard.usage;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.entitlements.EffectivePlan;
import com.qalsmartkeyboard.entitlements.FeatureEntitlement;
import com.qalsmartkeyboard.entitlements.PlanSource;

/**
 * The current window of a user's feature (SD 15.3 step 2): the user's time zone ({@code users.time_zone}, V1 default
 * {@code Africa/Addis_Ababa}; an unknown zone id falls back to {@code qal.usage.default-time-zone}), the feature's
 * reset period and, for MONTHLY/YEARLY on a paid or granted plan, the anchor {@link EffectivePlan#periodStart()}. The
 * DEFAULT plan (FREE, GUEST) has no anchor: calendar months.
 */
final class UserWindows {

	private static final Logger log = LoggerFactory.getLogger(UserWindows.class);

	private final JdbcClient jdbc;
	private final ZoneId defaultZone;

	UserWindows(JdbcClient jdbc, ZoneId defaultZone) {
		this.jdbc = jdbc;
		this.defaultZone = defaultZone;
	}

	UsageWindow current(UUID userId, FeatureEntitlement feature, Optional<EffectivePlan> plan, Instant at) {
		Instant anchor = plan.filter(p -> p.source() != PlanSource.DEFAULT).map(EffectivePlan::periodStart)
				.orElse(null);
		return UsageWindows.of(feature.resetPeriod(), zoneOf(userId), anchor, at);
	}

	ZoneId zoneOf(UUID userId) {
		String id = jdbc.sql("SELECT time_zone FROM users WHERE id = :id").param("id", userId).query(String.class)
				.optional().orElse(null);
		if (id == null) {
			return defaultZone;
		}
		try {
			return ZoneId.of(id);
		} catch (DateTimeException e) {
			log.warn("User {} has an unknown time zone id; metering windows use {}", userId, defaultZone);
			return defaultZone;
		}
	}
}
