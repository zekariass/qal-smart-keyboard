package com.qalsmartkeyboard.privacy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;

/**
 * The one place a feature asks "may I process this for this user?" (B3.5, System Design 19.3).
 *
 * <p>The latest decision per (user, consent code) wins (append-only {@code user_consents}, V1 index
 * {@code ix_user_consents_latest}): no row means not granted, a later withdrawal revokes an earlier grant and a later grant
 * restores it. The consent <em>version</em> is not compared: the System Design defines no minimum-version rule, so a grant
 * of an older text stays valid until the person decides again. Calls join the caller's transaction.
 */
@Component
public class ConsentGate {

	private final JdbcClient jdbc;

	ConsentGate(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** True when the user's latest decision for {@code type} is a grant. */
	public boolean hasConsent(UUID userId, ConsentType type) {
		return jdbc.sql("""
				SELECT is_granted FROM user_consents WHERE user_id = :user AND consent_code = :code
				ORDER BY recorded_at DESC, id DESC LIMIT 1
				""").param("user", userId).param("code", type.name()).query(Boolean.class).optional().orElse(false);
	}

	/** @throws ApiException 403 {@code CONSENT_REQUIRED} with the extension member {@code consentCode} */
	public void requireConsent(UUID userId, ConsentType type) {
		if (!hasConsent(userId, type)) {
			throw new ApiException(ApiErrorCode.CONSENT_REQUIRED, HttpStatus.FORBIDDEN,
					"This requires the " + type.name() + " consent.", List.of(), Map.of("consentCode", type.name()));
		}
	}
}
