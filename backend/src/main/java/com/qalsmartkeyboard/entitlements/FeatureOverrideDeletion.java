package com.qalsmartkeyboard.entitlements;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.UserDeletionParticipant;

/**
 * Account deletion (B6.2, ADR 0015) for {@code user_feature_overrides}: the per-account feature switches are deleted
 * (admin decisions about an account that no longer exists) and the resolver's cache entry is dropped.
 * {@code user_subscriptions} are financial records ({@code ON DELETE RESTRICT}) and stay with the anonymised account;
 * a deleted account resolves to no plan at all.
 */
@Component
class FeatureOverrideDeletion implements UserDeletionParticipant {

	private static final Logger log = LoggerFactory.getLogger(FeatureOverrideDeletion.class);

	private final JdbcClient jdbc;
	private final EntitlementResolver resolver;

	FeatureOverrideDeletion(JdbcClient jdbc, EntitlementResolver resolver) {
		this.jdbc = jdbc;
		this.resolver = resolver;
	}

	@Override
	public int deletionOrder() {
		return ENTITLEMENTS;
	}

	@Override
	public int purgeUser(UUID userId, Instant now) {
		int rows = jdbc.sql("DELETE FROM user_feature_overrides WHERE user_id = :user").param("user", userId).update();
		resolver.invalidate(userId);
		log.info("Account deletion {}: {} feature overrides deleted", userId, rows);
		return rows;
	}
}
