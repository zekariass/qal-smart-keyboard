package com.qalsmartkeyboard.preferences;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.preferences.DestinationDtos.Profile;
import com.qalsmartkeyboard.preferences.DestinationDtos.ProfileInput;

/**
 * {@code /me/destination-profiles} (System Design 10.3, ADR 0012). The label is user-authored text and is stored only as
 * ciphertext in {@code label_ciphertext}: the row id is generated <em>before</em> encrypting because the ciphertext is
 * bound to user, key version, table, column and that id. Responses decrypt for the owner.
 *
 * <p><b>Validation</b>: label 1-80 characters and not blank; the destination language must be active
 * ({@code 400 LANGUAGE_NOT_SUPPORTED}); formality, addressee gender, tone and style are the contract enums (an absent
 * tone is stored as NATURAL and read back without {@code defaultTone}, which is not a contract tone); a glossary must be
 * one of the caller's own; at most 100 profiles per user (a defensive cap: neither contract nor System Design names one;
 * 400 {@code VALIDATION_FAILED}).
 *
 * <p><b>Concurrency</b>: the contract gives profiles no revision, so a PUT is last-writer-wins on the whole profile; each
 * write is one statement on one row. Create and delete take the same per-user advisory lock as the app rules, so the
 * profile cap holds and a rule cannot be written for a profile being deleted. Deleting a profile removes the rules that
 * point at it (foreign key cascade) and bumps the rules revision so devices re-read them.
 *
 * <p>The contract has no way to mark a default profile; {@code is_default} is never set here (guest merge may carry it).
 */
@Service
class DestinationProfilesService {

	static final int MAX_PROFILES = 100;
	static final String TABLE = PreferencesGuestMerge.PROFILES;
	private static final String COLUMN = "label_ciphertext";
	private static final int DEFAULT_SORT_ORDER = 100;

	private static final String SELECT = """
			SELECT p.id, p.label_ciphertext, l.language_code, p.formality, p.addressee_gender, p.default_tone,
			       p.default_style, p.glossary_id, p.sort_order, p.created_at, p.updated_at
			FROM user_destination_profiles p JOIN supported_languages l ON l.id = p.destination_language_id
			""";

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;
	private final UserDataEncryption encryption;
	private final AppDestinationRulesService rules;

	DestinationProfilesService(JdbcClient jdbc, TransactionTemplate tx, UserDataEncryption encryption,
			AppDestinationRulesService rules) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.encryption = encryption;
		this.rules = rules;
	}

	List<Profile> list(UUID userId) {
		return jdbc.sql(SELECT + " WHERE p.user_id = :user ORDER BY p.sort_order, p.created_at, p.id")
				.param("user", userId).query((rs, n) -> map(userId, rs)).list();
	}

	Profile create(UUID userId, ProfileInput in) {
		return tx.execute(status -> {
			lock(jdbc, userId);
			UUID language = validate(userId, in);
			int count = jdbc.sql("SELECT count(*) FROM user_destination_profiles WHERE user_id = :user")
					.param("user", userId).query(Integer.class).single();
			if (count >= MAX_PROFILES) {
				throw invalid("label", "LIMIT_REACHED", "At most " + MAX_PROFILES + " destination profiles are allowed.");
			}
			UUID id = UUID.randomUUID(); // before encrypting: the id is part of the ciphertext's binding
			EncryptedValue label = encryption.encryptString(userId, EncryptionContext.of(TABLE, COLUMN, id),
					in.label());
			jdbc.sql("""
					INSERT INTO user_destination_profiles (id, user_id, label_ciphertext, key_version,
					    destination_language_id, formality, addressee_gender, default_tone, default_style, glossary_id,
					    sort_order)
					VALUES (:id, :user, :label, :keyVersion, :language, :formality, :gender, :tone, :style, :glossary,
					    :sort)
					""").param("id", id).param("user", userId).param("label", label.ciphertext(), Types.BINARY)
					.param("keyVersion", label.keyVersion()).param("language", language)
					.param("formality", or(in.formality(), "INFORMAL"))
					.param("gender", or(in.addresseeGender(), "UNKNOWN")).param("tone", or(in.defaultTone(), "NATURAL"))
					.param("style", in.defaultStyle(), Types.VARCHAR).param("glossary", in.glossaryId(), Types.OTHER)
					.param("sort", sort(in)).update();
			return get(userId, id);
		});
	}

	/** @throws ApiException 404 when the profile is not the caller's */
	Profile replace(UUID userId, UUID id, ProfileInput in) {
		return tx.execute(status -> {
			UUID language = validate(userId, in);
			EncryptedValue label = encryption.encryptString(userId, EncryptionContext.of(TABLE, COLUMN, id),
					in.label());
			int changed = jdbc.sql("""
					UPDATE user_destination_profiles SET label_ciphertext = :label, key_version = :keyVersion,
					    destination_language_id = :language, formality = :formality, addressee_gender = :gender,
					    default_tone = :tone, default_style = :style, glossary_id = :glossary, sort_order = :sort,
					    updated_at = NOW()
					WHERE id = :id AND user_id = :user
					""").param("label", label.ciphertext(), Types.BINARY).param("keyVersion", label.keyVersion())
					.param("language", language).param("formality", or(in.formality(), "INFORMAL"))
					.param("gender", or(in.addresseeGender(), "UNKNOWN")).param("tone", or(in.defaultTone(), "NATURAL"))
					.param("style", in.defaultStyle(), Types.VARCHAR).param("glossary", in.glossaryId(), Types.OTHER)
					.param("sort", sort(in)).param("id", id).param("user", userId).update();
			if (changed == 0) {
				throw notFound();
			}
			return get(userId, id);
		});
	}

	void delete(UUID userId, UUID id) {
		tx.executeWithoutResult(status -> {
			lock(jdbc, userId);
			int pointing = jdbc.sql("""
					SELECT count(*) FROM user_app_destination_rules WHERE user_id = :user AND destination_profile_id = :id
					""").param("user", userId).param("id", id).query(Integer.class).single();
			int deleted = jdbc.sql("DELETE FROM user_destination_profiles WHERE id = :id AND user_id = :user")
					.param("id", id).param("user", userId).update();
			if (deleted == 0) {
				throw notFound();
			}
			if (pointing > 0) {
				rules.bump(userId);
			}
		});
	}

	/** Serialises the writers of one user's profiles and rules (released at the end of the transaction). */
	static void lock(JdbcClient jdbc, UUID userId) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "destination:" + userId)
				.query().singleRow();
	}

	private Profile get(UUID userId, UUID id) {
		return jdbc.sql(SELECT + " WHERE p.user_id = :user AND p.id = :id").param("user", userId).param("id", id)
				.query((rs, n) -> map(userId, rs)).optional().orElseThrow(DestinationProfilesService::notFound);
	}

	private Profile map(UUID userId, ResultSet rs) throws SQLException {
		UUID id = rs.getObject("id", UUID.class);
		String label = encryption.decryptString(userId, EncryptionContext.of(TABLE, COLUMN, id),
				rs.getBytes("label_ciphertext"));
		String tone = rs.getString("default_tone");
		return new Profile(id, label, rs.getString("language_code"), rs.getString("formality"),
				rs.getString("addressee_gender"), "NATURAL".equals(tone) ? null : tone, rs.getString("default_style"),
				rs.getObject("glossary_id", UUID.class), rs.getInt("sort_order"),
				rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
	}

	private UUID validate(UUID userId, ProfileInput in) {
		if (in.label().isBlank()) {
			throw invalid("label", "BLANK", "The label must not be blank.");
		}
		UUID language = jdbc.sql("SELECT id FROM supported_languages WHERE language_code = :code AND is_active")
				.param("code", in.destinationLanguage()).query(UUID.class).optional()
				.orElseThrow(() -> new ApiException(ApiErrorCode.LANGUAGE_NOT_SUPPORTED, HttpStatus.BAD_REQUEST, null,
						List.of(new ApiFieldError("destinationLanguage", "LANGUAGE_NOT_SUPPORTED",
								"The language is not supported.")),
						Map.of()));
		if (in.glossaryId() != null) {
			boolean own = jdbc.sql("SELECT EXISTS (SELECT 1 FROM glossaries WHERE id = :id AND user_id = :user)")
					.param("id", in.glossaryId()).param("user", userId).query(Boolean.class).single();
			if (!own) {
				throw invalid("glossaryId", "UNKNOWN_GLOSSARY", "The glossary does not exist.");
			}
		}
		return language;
	}

	private static int sort(ProfileInput in) {
		return in.sortOrder() == null ? DEFAULT_SORT_ORDER : in.sortOrder();
	}

	private static String or(String value, String fallback) {
		return value == null ? fallback : value;
	}

	static ApiException invalid(String field, String code, String message) {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
				List.of(new ApiFieldError(field, code, message)), Map.of());
	}

	static ApiException notFound() {
		return new ApiException(ApiErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, null);
	}
}
