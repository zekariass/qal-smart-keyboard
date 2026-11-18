package com.qalsmartkeyboard.privacy;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.privacy.ConsentDtos.ConsentDto;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /me/consents} and {@code PUT /me/consents/{consentCode}} (System Design 19.3). {@code user_consents} is
 * append-only (a trigger rejects UPDATE and DELETE): every PUT, grant or withdrawal, appends one row with the version of the
 * text shown, the client platform and the time; the current state is the latest row per code. Re-sending the same decision
 * appends another row; a decision is never merged into an older one.
 */
@Service
class ConsentService {

	private static final int MAX_METADATA_CHARS = 2000;

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;
	private final ApplicationEventPublisher events;
	private final JsonMapper json;

	ConsentService(JdbcClient jdbc, TransactionTemplate tx, ApplicationEventPublisher events, JsonMapper json) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.events = events;
		this.json = json;
	}

	/** The latest decision per code that has one, with the time of the latest grant. */
	List<ConsentDto> latest(UUID userId) {
		return jdbc.sql("""
				SELECT DISTINCT ON (c.consent_code) c.id, c.consent_code, c.consent_version, c.is_granted, c.recorded_at,
				       (SELECT max(g.recorded_at) FROM user_consents g
				         WHERE g.user_id = c.user_id AND g.consent_code = c.consent_code AND g.is_granted) AS granted_at
				FROM user_consents c WHERE c.user_id = :user
				ORDER BY c.consent_code, c.recorded_at DESC, c.id DESC
				""").param("user", userId)
				.query((rs, n) -> map(rs.getObject("id", UUID.class), rs.getString("consent_code"),
						rs.getString("consent_version"), rs.getBoolean("is_granted"), rs.getTimestamp("recorded_at"),
						rs.getTimestamp("granted_at")))
				.list();
	}

	/**
	 * Every decision for the user, oldest first (all codes, or one when {@code type} is given). Java API only: the contract
	 * has no history operation; the data export (B6.x) reads it. {@code grantedAt} is the row time of a grant row.
	 */
	List<ConsentDto> history(UUID userId, ConsentType type) {
		return jdbc.sql("""
				SELECT id, consent_code, consent_version, is_granted, recorded_at FROM user_consents
				WHERE user_id = :user AND (CAST(:code AS text) IS NULL OR consent_code = :code)
				ORDER BY recorded_at, id
				""").param("user", userId).param("code", type == null ? null : type.name())
				.query((rs, n) -> map(rs.getObject("id", UUID.class), rs.getString("consent_code"),
						rs.getString("consent_version"), rs.getBoolean("is_granted"), rs.getTimestamp("recorded_at"),
						rs.getBoolean("is_granted") ? rs.getTimestamp("recorded_at") : null))
				.list();
	}

	/**
	 * Appends the decision, publishes {@link ConsentChanged} in the same transaction and returns the new state.
	 *
	 * @param platform {@code ANDROID}, {@code IOS}, {@code WEB} or {@code null}
	 * @throws ApiException 422 withdrawing {@code TERMS_ACCEPTANCE} or {@code PRIVACY_NOTICE}; 400 oversized details
	 */
	ConsentDto record(UUID userId, ConsentType type, String version, boolean granted, String platform,
			Map<String, Object> details) {
		if (!granted && !type.revocable()) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_CONTENT,
					type.name() + " cannot be withdrawn; delete the account instead.",
					List.of(new ApiFieldError("isGranted", "CONSENT_NOT_REVOCABLE", "This consent cannot be withdrawn.")),
					Map.of());
		}
		String metadata = details == null || details.isEmpty() ? "{}"
				: json.writeValueAsString(Map.of("details", details));
		if (metadata.length() > MAX_METADATA_CHARS) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
					List.of(new ApiFieldError("details", "TOO_LONG", "Details are too large.")), Map.of());
		}
		return tx.execute(status -> {
			UUID id = UUID.randomUUID();
			// clock_timestamp(), not NOW(): two decisions in quick succession must order by when they were recorded.
			Timestamp recorded = jdbc.sql("""
					INSERT INTO user_consents (id, user_id, consent_code, consent_version, is_granted, recorded_at,
					                           source_platform, metadata)
					VALUES (:id, :user, :code, :version, :granted, clock_timestamp(), :platform, CAST(:metadata AS jsonb))
					RETURNING recorded_at
					""").param("id", id).param("user", userId).param("code", type.name()).param("version", version)
					.param("granted", granted).param("platform", platform).param("metadata", metadata)
					.query(Timestamp.class).single();
			events.publishEvent(new ConsentChanged(userId, type, granted, id));
			Timestamp grantedAt = granted ? recorded
					: jdbc.sql("""
							SELECT max(recorded_at) FROM user_consents
							WHERE user_id = :user AND consent_code = :code AND is_granted
							""").param("user", userId).param("code", type.name()).query(Timestamp.class).optional()
							.orElse(null);
			return map(id, type.name(), version, granted, recorded, grantedAt);
		});
	}

	private static ConsentDto map(UUID id, String code, String version, boolean granted, Timestamp recordedAt,
			Timestamp grantedAt) {
		return new ConsentDto(id, code, version, granted, grantedAt == null ? null : grantedAt.toInstant(),
				granted ? null : recordedAt.toInstant(), recordedAt.toInstant());
	}
}
