package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;

/**
 * 429 {@code QUOTA_EXCEEDED}, the contract's {@code QuotaExceededProblem} (SD 14.4): {@code featureCode},
 * {@code resetAt} (absent for an allowance that never resets), {@code creditBalance} (whole credits, rounded down, SD
 * 15.4) and {@code upgradeOptions} ({@code {type: PLAN | CREDIT_PACK, productCode}}). The detail names the feature
 * code and the reset time only (no user content).
 */
public class QuotaExceededException extends ApiException {

	/** One entry of {@code upgradeOptions}: type {@code PLAN} or {@code CREDIT_PACK}. */
	public record UpgradeOption(String type, String productCode) {
	}

	private final String featureCode;
	private final Instant resetAt;
	private final BigDecimal creditBalance;
	private final List<UpgradeOption> upgradeOptions;

	public QuotaExceededException(String featureCode, Instant resetAt, BigDecimal creditBalance,
			List<UpgradeOption> upgradeOptions) {
		super(ApiErrorCode.QUOTA_EXCEEDED, ApiErrorCode.QUOTA_EXCEEDED.defaultStatus(),
				"The allowance for " + featureCode + " is used up" + (resetAt == null ? "." : " until " + resetAt + "."),
				List.of(), members(featureCode, resetAt, creditBalance, upgradeOptions));
		this.featureCode = featureCode;
		this.resetAt = resetAt;
		this.creditBalance = wholeCredits(creditBalance);
		this.upgradeOptions = List.copyOf(upgradeOptions);
	}

	private static BigDecimal wholeCredits(BigDecimal balance) {
		return balance.setScale(0, RoundingMode.FLOOR);
	}

	private static Map<String, Object> members(String featureCode, Instant resetAt, BigDecimal creditBalance,
			List<UpgradeOption> upgradeOptions) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("featureCode", featureCode);
		if (resetAt != null) {
			m.put("resetAt", resetAt.toString());
		}
		m.put("creditBalance", wholeCredits(creditBalance));
		m.put("upgradeOptions", upgradeOptions.stream()
				.map(o -> Map.of("type", o.type(), "productCode", o.productCode())).toList());
		return m;
	}

	public String featureCode() {
		return featureCode;
	}

	/** Null when the allowance never resets (reset period NONE). */
	public Instant resetAt() {
		return resetAt;
	}

	/** Whole credits, rounded down. */
	public BigDecimal creditBalance() {
		return creditBalance;
	}

	public List<UpgradeOption> upgradeOptions() {
		return upgradeOptions;
	}
}
