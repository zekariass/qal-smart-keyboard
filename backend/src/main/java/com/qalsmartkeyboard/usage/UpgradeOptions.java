package com.qalsmartkeyboard.usage;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.usage.QuotaExceededException.UpgradeOption;

/**
 * {@code upgradeOptions} of a {@code QUOTA_EXCEEDED} problem: ACTIVE {@code billing_products} the user could buy to get
 * past the limit. PLAN: a sellable, ACTIVE plan of higher rank than the current one whose entitlement rows (current
 * quota policy version) give access to the feature. CREDIT_PACK: any ACTIVE credit pack, when credits can pay for the
 * feature. Guests get none (they cannot buy, SD 9.1; they sign in first). This is a hint for the client only: price
 * books, markets and channel policy (SD 16.3) are checked at checkout. All seeded products are DRAFT, so the list is
 * empty until billing activates products (M4).
 */
final class UpgradeOptions {

	private final JdbcClient jdbc;

	UpgradeOptions(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @param quotaPolicyVersion the version the user's entitlements were resolved with */
	List<UpgradeOption> forFeature(UUID userId, String featureCode, int currentPlanRank, boolean creditsUsable,
			String quotaPolicyVersion) {
		return jdbc.sql("""
				SELECT CASE bp.product_type WHEN 'SUBSCRIPTION' THEN 'PLAN' ELSE 'CREDIT_PACK' END AS option_type,
				       bp.product_code
				FROM billing_products bp
				LEFT JOIN subscription_plans sp ON sp.id = bp.plan_id
				WHERE bp.product_status = 'ACTIVE'
				  AND EXISTS (SELECT 1 FROM users u WHERE u.id = :user AND u.account_type = 'REGISTERED')
				  AND ((bp.product_type = 'SUBSCRIPTION' AND sp.plan_status = 'ACTIVE' AND sp.is_sellable
				        AND sp.plan_rank > :rank
				        AND EXISTS (SELECT 1 FROM plan_entitlements pe
				                    WHERE pe.plan_id = sp.id AND pe.feature_code = :feature AND pe.access_enabled
				                      AND pe.configuration ->> 'quotaPolicyVersion' = :version))
				       OR (bp.product_type = 'AI_CREDIT_PACK' AND :credits))
				ORDER BY CASE bp.product_type WHEN 'SUBSCRIPTION' THEN 0 ELSE 1 END, sp.plan_rank, bp.product_code
				""").param("user", userId).param("rank", currentPlanRank).param("feature", featureCode)
				.param("version", quotaPolicyVersion).param("credits", creditsUsable)
				.query((rs, n) -> new UpgradeOption(rs.getString("option_type"), rs.getString("product_code")))
				.list();
	}
}
