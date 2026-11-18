package com.qalsmartkeyboard.usage;

/**
 * What paid for a settled request: contract {@code UsageSummary.usageSource} and {@code ai_requests.usage_source}. A
 * request paid partly by the allowance and partly by credits is {@link #AI_CREDITS} (V1
 * {@code ck_ai_request_credits_source}: credits charged implies AI_CREDITS); the split is on the {@link Settlement}.
 */
public enum UsageSource {
	PLAN_ALLOWANCE, AI_CREDITS
}
