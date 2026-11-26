package com.qalsmartkeyboard.privacy;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The consent codes (System Design 19.3, contract {@code ConsentCode}, V1 {@code user_consents} check constraint) and, in
 * one place, which features each one gates. A step that adds a consent-dependent feature calls
 * {@link ConsentGate#requireConsent} with the type listed here and adds the feature to its list; nothing else decides
 * "which consent does this feature need".
 *
 * <p>{@link #revocable()} is false for {@code TERMS_ACCEPTANCE} and {@code PRIVACY_NOTICE}: the contract says they cannot be
 * revoked (the way out is account deletion). Every other code can be granted and withdrawn any number of times.
 */
public enum ConsentType {

	/** Accepting the terms of use; a condition of having an account. */
	TERMS_ACCEPTANCE(false, "ACCOUNT"),
	/** Acknowledging the privacy notice; a condition of having an account. */
	PRIVACY_NOTICE(false, "ACCOUNT"),
	/** Server-side AI processing of typed text (SD 17: with PREFER_ON_DEVICE, fallback to the server needs consent). */
	AI_PROCESSING(true, "AI_SERVER_PROCESSING"),
	/** SD 12 and 19.3: AI history is written only with HISTORY_SYNC access and this consent. */
	SAVE_AI_HISTORY(true, "AI_HISTORY_WRITE", "PREFERENCES_AI_HISTORY_ENABLED"),
	/** Keeping voice recordings or transcripts in history. */
	SAVE_AUDIO_HISTORY(true, "AUDIO_HISTORY_WRITE"),
	/** SD 9: user dictionary sync is opt-in (consent plus entitlement). */
	DICTIONARY_SYNC(true, "USER_DICTIONARY_SYNC"),
	/** SD 11: learned style hints, derived on device from accepted AI results. */
	PERSONAL_STYLE(true, "PERSONAL_STYLE_HINTS_SYNC"),
	/** SD 12: encrypted feedback comment and attached input/output text. */
	FEEDBACK_TEXT(true, "AI_FEEDBACK_COMMENT", "AI_FEEDBACK_CONTENT"),
	/** V1 section 5: submitting words or corrections to the community pipeline. */
	COMMUNITY_CONTRIBUTION(true, "COMMUNITY_SUBMISSIONS"),
	/** SD 14: recurring-payment mandates; withdrawal cancels linked mandates (billing reacts to {@link ConsentChanged}). */
	PAYMENT_MANDATE(true, "PAYMENT_MANDATES"),
	/** SD 21: product analytics are off unless granted. */
	ANALYTICS(true, "PRODUCT_ANALYTICS"),
	/** Marketing messages. */
	MARKETING(true, "MARKETING_MESSAGES"),
	/**
	 * T8.15 (ADR 0050): the opt-in encrypted cloud backup of creative assets (Draw &amp; Sketch drawings). Withdrawal
	 * deletes every backed-up item (personalization reacts to {@link ConsentChanged}).
	 */
	CREATIVE_BACKUP(true, "CREATIVE_ASSET_BACKUP");

	private final boolean revocable;
	private final Set<String> features;

	ConsentType(boolean revocable, String... features) {
		this.revocable = revocable;
		this.features = Set.of(features);
	}

	public boolean revocable() {
		return revocable;
	}

	/** Names of the features that need this consent (informational; call sites use {@link ConsentGate}). */
	public Set<String> gatedFeatures() {
		return features;
	}

	public static Optional<ConsentType> parse(String code) {
		return Arrays.stream(values()).filter(t -> t.name().equals(code)).findFirst();
	}
}
