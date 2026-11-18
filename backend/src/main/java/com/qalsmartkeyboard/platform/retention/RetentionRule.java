package com.qalsmartkeyboard.platform.retention;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One data class of the retention policy (SD 19.2): what it is, who owns it, how long it is kept and what happens then.
 *
 * @param code        stable lower-kebab-case id; also the metric tag and the key of {@code qal.retention.periods.*}
 * @param ownerModule the module that owns the data (or the one holding it until its owner exists)
 * @param action      see {@link RetentionAction}
 * @param period      the default period: after what the data is purged. {@code DELETE}, {@code ANONYMISE} and
 *                    {@code DELETE_BY_OWNER} need one (zero = as soon as the rule's own condition holds); for {@code KEEP}
 *                    it is the legal or assumed period, informational, or {@code null} when none is defined
 * @param reference   where the period comes from (SD section, ADR, schema comment), or {@code "UNDEFINED"} when the
 *                    documents give none and the value is an assumption
 * @param description what the rows are and what the period counts from
 */
public record RetentionRule(String code, String ownerModule, RetentionAction action, Duration period, String reference,
		String description) {

	private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

	public RetentionRule {
		Objects.requireNonNull(action, "action");
		if (code == null || !CODE.matcher(code).matches()) {
			throw new IllegalArgumentException("Retention rule code must be lower-kebab-case: " + code);
		}
		Objects.requireNonNull(ownerModule, "ownerModule");
		Objects.requireNonNull(reference, "reference");
		Objects.requireNonNull(description, "description");
		if (action != RetentionAction.KEEP && period == null) {
			throw new IllegalArgumentException("Retention rule " + code + " needs a period");
		}
		if (period != null && period.isNegative()) {
			throw new IllegalArgumentException("Retention rule " + code + " has a negative period");
		}
	}

	/** Rules the job executes ({@code DELETE}, {@code ANONYMISE}); the others are documentation. */
	public boolean executable() {
		return action == RetentionAction.DELETE || action == RetentionAction.ANONYMISE;
	}

	/** This rule with another period (an operator override). */
	public RetentionRule withPeriod(Duration other) {
		return new RetentionRule(code, ownerModule, action, other, reference, description);
	}
}
