package com.qalsmartkeyboard.preferences;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.preferences.DestinationDtos.Rule;
import com.qalsmartkeyboard.preferences.DestinationDtos.RulesDocument;

/**
 * {@code GET/PUT /me/app-destination-rules} (Android only; System Design 10.3, ADR 0012). The client sends
 * {@code appPackageHmac = HMAC-SHA256(account rule key, packageName)} as 64 lower-case hex characters and the server
 * stores only that, with the target (one of the caller's profiles, or an active language). Anything else, in particular
 * a package name (dots, letters outside {@code [0-9a-f]}, wrong length), is 400 {@code VALIDATION_FAILED} before it
 * reaches a query, a log line or a row.
 *
 * <p><b>Concurrency</b>: the same revision protocol as {@code /me/typing-languages}: a row in
 * {@code user_app_rule_revisions} (absent = 0) bumped with {@code UPDATE ... WHERE revision = ?}; a stale or future
 * revision is 409 {@code REVISION_CONFLICT} with {@code currentRevision}. The rules are rewritten in that transaction
 * under the per-user advisory lock shared with profile create/delete.
 *
 * <p><b>Validation</b> (400): at most 200 rules, hashes unique, exactly one of profile or language per rule, the
 * profile is the caller's, the language is active ({@code LANGUAGE_NOT_SUPPORTED}).
 */
@Service
class AppDestinationRulesService {

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;
	private final AppRuleKeys keys;

	AppDestinationRulesService(JdbcClient jdbc, TransactionTemplate tx, AppRuleKeys keys) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.keys = keys;
	}

	RulesDocument get(UUID userId) {
		int revision = jdbc.sql("SELECT revision FROM user_app_rule_revisions WHERE user_id = :user")
				.param("user", userId).query(Integer.class).optional().orElse(0);
		List<Rule> rules = jdbc.sql("""
				SELECT r.app_package_hmac, r.destination_profile_id, l.language_code, r.is_enabled
				FROM user_app_destination_rules r LEFT JOIN supported_languages l ON l.id = r.destination_language_id
				WHERE r.user_id = :user ORDER BY r.created_at, r.app_package_hmac
				""").param("user", userId)
				.query((rs, n) -> new Rule(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
						rs.getBoolean(4)))
				.list();
		return new RulesDocument(revision, rules);
	}

	/** The account's rule key, created on first read (idempotent, under the same per-user lock as the rules PUT). */
	AppRuleKeys.RuleKey key(UUID userId) {
		return tx.execute(status -> {
			DestinationProfilesService.lock(jdbc, userId);
			return keys.material(userId);
		});
	}

	/** @throws ApiException 400 invalid, 409 {@code REVISION_CONFLICT} */
	RulesDocument replace(UUID userId, RulesDocument in) {
		return tx.execute(status -> {
			DestinationProfilesService.lock(jdbc, userId);
			List<UUID> languages = validate(userId, in.rules());
			claimRevision(userId, in.revision());
			if (!in.rules().isEmpty()) {
				keys.getOrCreate(userId);
			}
			jdbc.sql("DELETE FROM user_app_destination_rules WHERE user_id = :user").param("user", userId).update();
			for (int i = 0; i < in.rules().size(); i++) {
				Rule rule = in.rules().get(i);
				jdbc.sql("""
						INSERT INTO user_app_destination_rules (user_id, app_package_hmac, destination_profile_id,
						    destination_language_id, is_enabled)
						VALUES (:user, :hmac, :profile, :language, :enabled)
						""").param("user", userId).param("hmac", rule.appPackageHmac())
						.param("profile", rule.destinationProfileId(), Types.OTHER)
						.param("language", languages.get(i), Types.OTHER)
						.param("enabled", rule.enabled() == null || rule.enabled()).update();
			}
			return get(userId);
		});
	}

	/** Revision +1 for a change made outside the PUT (profile delete, guest merge); creates the row at 1. */
	void bump(UUID userId) {
		bump(jdbc, userId);
	}

	static void bump(JdbcClient jdbc, UUID userId) {
		jdbc.sql("""
				INSERT INTO user_app_rule_revisions (user_id, revision) VALUES (:user, 1)
				ON CONFLICT (user_id) DO UPDATE SET revision = user_app_rule_revisions.revision + 1, updated_at = NOW()
				""").param("user", userId).update();
	}

	private List<UUID> validate(UUID userId, List<Rule> rules) {
		List<ApiFieldError> errors = new ArrayList<>();
		List<ApiFieldError> unsupported = new ArrayList<>();
		Set<String> hashes = new HashSet<>();
		Set<UUID> owned = new HashSet<>(jdbc.sql("SELECT id FROM user_destination_profiles WHERE user_id = :user")
				.param("user", userId).query(UUID.class).list());
		List<UUID> languages = new ArrayList<>();
		for (int i = 0; i < rules.size(); i++) {
			Rule rule = rules.get(i);
			String at = "rules[" + i + "].";
			if (!hashes.add(rule.appPackageHmac())) {
				errors.add(new ApiFieldError(at + "appPackageHmac", "DUPLICATE", "The app appears more than once."));
			}
			boolean profile = rule.destinationProfileId() != null;
			boolean language = rule.destinationLanguage() != null;
			UUID languageId = null;
			if (profile == language) {
				errors.add(new ApiFieldError(at + "destinationProfileId", "EXACTLY_ONE_TARGET",
						"Exactly one of destinationProfileId or destinationLanguage must be set."));
			}
			else if (profile && !owned.contains(rule.destinationProfileId())) {
				errors.add(new ApiFieldError(at + "destinationProfileId", "UNKNOWN_PROFILE",
						"The profile does not exist."));
			}
			else if (language) {
				languageId = jdbc.sql("SELECT id FROM supported_languages WHERE language_code = :code AND is_active")
						.param("code", rule.destinationLanguage()).query(UUID.class).optional().orElse(null);
				if (languageId == null) {
					unsupported.add(new ApiFieldError(at + "destinationLanguage", "LANGUAGE_NOT_SUPPORTED",
							"The language is not supported."));
				}
			}
			languages.add(languageId);
		}
		if (!unsupported.isEmpty()) {
			throw new ApiException(ApiErrorCode.LANGUAGE_NOT_SUPPORTED, HttpStatus.BAD_REQUEST, null, unsupported,
					Map.of());
		}
		if (!errors.isEmpty()) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null, errors, Map.of());
		}
		return languages;
	}

	private void claimRevision(UUID userId, int revision) {
		jdbc.sql("INSERT INTO user_app_rule_revisions (user_id, revision) VALUES (:user, 0) ON CONFLICT DO NOTHING")
				.param("user", userId).update();
		int bumped = jdbc.sql("""
				UPDATE user_app_rule_revisions SET revision = revision + 1, updated_at = NOW()
				WHERE user_id = :user AND revision = :revision
				""").param("user", userId).param("revision", revision).update();
		if (bumped == 0) {
			int current = jdbc.sql("SELECT revision FROM user_app_rule_revisions WHERE user_id = :user")
					.param("user", userId).query(Integer.class).single();
			throw PreferencesService.conflict(current);
		}
	}
}
