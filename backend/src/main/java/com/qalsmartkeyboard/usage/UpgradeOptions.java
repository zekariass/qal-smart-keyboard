package com.qalsmartkeyboard.usage;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.usage.QuotaExceededException.UpgradeOption;

/**
 * {@code upgradeOptions} of a {@code QUOTA_EXCEEDED} problem: ACTIVE {@code billing_products} the user could buy to get
 * past the limit. PLAN: a sellable, ACTIVE plan of higher rank than the current one whose entitlement rows (current
 * quota policy version) give access to the feature. CREDIT_PACK: any ACTIVE credit pack, when credits can pay for the
 * feature. Guests get none (they cannot buy, SD 9.1; they sign in first). Either kind is listed only when it is
 * purchasable somewhere (T8.10): an active, currently valid price in a market whose offerings
 * ({@code fn_billing_offering_enabled}, V31) include its family, in the user's own market when
 * {@code users.market_country_code} is known. This is a hint for the client only (the market of a request is resolved
 * at checkout from request signals): {@code /billing/options} and order creation apply the exact market, channel
 * policy and offerings (SD 16.3). All seeded products are DRAFT, so the list is empty until billing activates products.
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
				JOIN users u ON u.id = :user AND u.account_type = 'REGISTERED'
				WHERE bp.product_status = 'ACTIVE'
				  AND EXISTS (SELECT 1 FROM billing_prices pr
				              WHERE pr.billing_product_id = bp.id AND pr.is_active AND pr.valid_from <= NOW()
				                AND (pr.valid_until IS NULL OR pr.valid_until > NOW())
				                AND (u.market_country_code IS NULL OR pr.market_country_code = u.market_country_code)
				                AND fn_billing_offering_enabled(pr.market_country_code, bp.product_type))
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
