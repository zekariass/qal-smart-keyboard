package com.qalsmartkeyboard.preferences;

import java.util.ArrayList;
import java.util.HashMap;
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
import com.qalsmartkeyboard.preferences.PreferencesDtos.TypingLanguageDto;
import com.qalsmartkeyboard.preferences.PreferencesDtos.TypingLanguagesDto;

/**
 * {@code GET/PUT /me/typing-languages} (System Design 10.2). The PUT replaces the user's whole ordered list.
 *
 * <p><b>Revision and concurrency</b>: the list has no parent row, so its revision lives in
 * {@code user_typing_language_revisions} (V7; absent = 0). A write first makes sure that row exists, then runs
 * {@code UPDATE ... SET revision = revision + 1 WHERE user_id = ? AND revision = ?}. That statement row-locks the user's
 * revision row until the transaction ends, so concurrent writers of one user are serialised; a writer that read an older
 * revision matches nothing and gets 409 {@code REVISION_CONFLICT}. The rows are rewritten inside the same transaction,
 * under that lock, so a conflict never leaves a half-written list.
 *
 * <p><b>Validation</b> (400 {@code VALIDATION_FAILED} with field errors unless noted): at most 20 distinct languages, at
 * most one {@code isPrimary}, unique {@code sortOrder} (default 100), the input mode must fit the language (Ge'ez-script
 * languages am/ti: DIRECT_SCRIPT_LAYOUT, PHONETIC_LATIN_TO_NATIVE or LATIN_PASSTHROUGH; every other language
 * LATIN_PASSTHROUGH only: the core's {@code mode_fits_language} with the contract's enum names), a profile code exactly
 * when the mode is PHONETIC_LATIN_TO_NATIVE; unknown or inactive language 400 {@code LANGUAGE_NOT_SUPPORTED}.
 *
 * <p><b>F14 (profile retirement never blocks an edit)</b>: the profile code is resolved to the <em>newest ACTIVE</em>
 * profile of that language and code. If none is active, the row keeps the profile it already has when that profile has
 * this code (RETIRED, or an older version): the write, including a reorder, succeeds and the row is stored unchanged
 * (the V1 trigger only validates a row whose mode, profile or language changed; rows are updated in place, never
 * deleted and re-inserted, so it stays quiet). Only a code the row did not already carry is 422
 * {@code PROFILE_NOT_ACTIVE} (unknown, DRAFT, RETIRED or another language's code). Reads return the stored code as it
 * is: the fallback to a newer active profile and the "profile changed" notice are the client's job (System Design 10.2;
 * Android {@code ProfileResolver}), and a GET never writes.
 */
@Service
class TypingLanguagesService {

	static final int MAX_LANGUAGES = 20;
	private static final int DEFAULT_SORT_ORDER = 100;
	private static final String PHONETIC = "PHONETIC_LATIN_TO_NATIVE";
	private static final String DIRECT = "DIRECT_SCRIPT_LAYOUT";

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;

	TypingLanguagesService(JdbcClient jdbc, TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.tx = tx;
	}

	TypingLanguagesDto get(UUID userId) {
		int revision = jdbc.sql("SELECT revision FROM user_typing_language_revisions WHERE user_id = :user")
				.param("user", userId).query(Integer.class).optional().orElse(0);
		List<TypingLanguageDto> languages = jdbc.sql("""
				SELECT l.language_code, t.is_enabled, t.is_primary, t.sort_order, t.input_mode, p.profile_code,
				       t.conversion_trigger, t.show_transliteration_candidates, t.unknown_token_behavior
				FROM user_typing_languages t
				JOIN supported_languages l ON l.id = t.language_id
				LEFT JOIN transliteration_profiles p ON p.id = t.transliteration_profile_id
				WHERE t.user_id = :user
				ORDER BY t.sort_order, l.language_code
				""").param("user", userId)
				.query((rs, n) -> new TypingLanguageDto(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3),
						rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8),
						rs.getString(9)))
				.list();
		return new TypingLanguagesDto(revision, languages);
	}

	/** @throws ApiException 400 invalid, 409 {@code REVISION_CONFLICT}, 422 {@code PROFILE_NOT_ACTIVE} */
	TypingLanguagesDto replace(UUID userId, TypingLanguagesDto in) {
		return tx.execute(status -> {
			Map<String, LanguageRow> catalogue = validate(in);
			claimRevision(userId, in.revision());
			resolveAndWrite(userId, in.languages(), catalogue);
			return get(userId);
		});
	}

	private record LanguageRow(UUID id, String script) {
	}

	private Map<String, LanguageRow> validate(TypingLanguagesDto in) {
		List<ApiFieldError> errors = new ArrayList<>();
		List<ApiFieldError> unsupported = new ArrayList<>();
		Map<String, LanguageRow> catalogue = new HashMap<>();
		Set<String> codes = new HashSet<>();
		Set<Integer> orders = new HashSet<>();
		int primaries = 0;
		List<TypingLanguageDto> items = in.languages();
		for (int i = 0; i < items.size(); i++) {
			TypingLanguageDto item = items.get(i);
			String at = "languages[" + i + "].";
			if (!codes.add(item.languageCode())) {
				errors.add(new ApiFieldError(at + "languageCode", "DUPLICATE", "The language appears more than once."));
			}
			if (!orders.add(sortOrder(item))) {
				errors.add(new ApiFieldError(at + "sortOrder", "DUPLICATE", "The sort order is not unique."));
			}
			if (Boolean.TRUE.equals(item.isPrimary()) && ++primaries > 1) {
				errors.add(new ApiFieldError(at + "isPrimary", "MULTIPLE_PRIMARY", "At most one language is primary."));
			}
			LanguageRow row = catalogue.computeIfAbsent(item.languageCode(), this::language);
			if (row == null) {
				catalogue.remove(item.languageCode());
				unsupported.add(
						new ApiFieldError(at + "languageCode", "LANGUAGE_NOT_SUPPORTED", "The language is not supported."));
				continue;
			}
			if (!modeFits(item.inputMode(), row.script())) {
				errors.add(new ApiFieldError(at + "inputMode", "MODE_NOT_FOR_LANGUAGE",
						"The input mode is not available for this language."));
			}
			boolean phonetic = PHONETIC.equals(item.inputMode());
			boolean hasCode = item.transliterationProfileCode() != null && !item.transliterationProfileCode().isBlank();
			if (phonetic != hasCode) {
				errors.add(new ApiFieldError(at + "transliterationProfileCode", phonetic ? "REQUIRED" : "NOT_ALLOWED",
						phonetic ? "A profile code is required for phonetic input."
								: "A profile code is only allowed for phonetic input."));
			}
		}
		if (!unsupported.isEmpty()) {
			throw new ApiException(ApiErrorCode.LANGUAGE_NOT_SUPPORTED, HttpStatus.BAD_REQUEST, null, unsupported,
					Map.of());
		}
		if (!errors.isEmpty()) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null, errors, Map.of());
		}
		return catalogue;
	}

	private LanguageRow language(String code) {
		return jdbc.sql("SELECT id, script_code FROM supported_languages WHERE language_code = :code AND is_active"
				+ " AND is_typing_language") // ADR 0040: translation-only languages are not typing languages
				.param("code", code).query((rs, n) -> new LanguageRow(rs.getObject(1, UUID.class), rs.getString(2)))
				.optional().orElse(null);
	}

	/** The core's {@code mode_fits_language}: native layout and phonetic input exist for Ge'ez-script languages (am, ti). */
	private static boolean modeFits(String mode, String script) {
		return !DIRECT.equals(mode) && !PHONETIC.equals(mode) || "Ethi".equals(script);
	}

	private static int sortOrder(TypingLanguageDto item) {
		return item.sortOrder() == null ? DEFAULT_SORT_ORDER : item.sortOrder();
	}

	/** Takes the per-user row lock and bumps the revision, or throws 409. */
	private void claimRevision(UUID userId, int revision) {
		jdbc.sql("INSERT INTO user_typing_language_revisions (user_id, revision) VALUES (:user, 0) ON CONFLICT DO NOTHING")
				.param("user", userId).update();
		int bumped = jdbc.sql("""
				UPDATE user_typing_language_revisions SET revision = revision + 1, updated_at = NOW()
				WHERE user_id = :user AND revision = :revision
				""").param("user", userId).param("revision", revision).update();
		if (bumped == 0) {
			int current = jdbc.sql("SELECT revision FROM user_typing_language_revisions WHERE user_id = :user")
					.param("user", userId).query(Integer.class).single();
			throw PreferencesService.conflict(current);
		}
	}

	private record Existing(UUID languageId, UUID profileId) {
	}

	private void resolveAndWrite(UUID userId, List<TypingLanguageDto> items, Map<String, LanguageRow> catalogue) {
		Map<UUID, Existing> existing = new HashMap<>();
		jdbc.sql("SELECT language_id, transliteration_profile_id FROM user_typing_languages WHERE user_id = :user")
				.param("user", userId)
				.query((rs, n) -> new Existing(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list()
				.forEach(e -> existing.put(e.languageId(), e));

		// resolve every profile first: a 422 must leave the list untouched (the transaction rolls back anyway)
		List<UUID> profiles = new ArrayList<>();
		List<ApiFieldError> notActive = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			TypingLanguageDto item = items.get(i);
			UUID languageId = catalogue.get(item.languageCode()).id();
			UUID profile = null;
			if (PHONETIC.equals(item.inputMode())) {
				profile = resolveProfile(languageId, item.transliterationProfileCode(), existing.get(languageId));
				if (profile == null) {
					notActive.add(new ApiFieldError("languages[" + i + "].transliterationProfileCode",
							"PROFILE_NOT_ACTIVE", "The profile is not an active profile of this language."));
				}
			}
			profiles.add(profile);
		}
		if (!notActive.isEmpty()) {
			throw new ApiException(ApiErrorCode.PROFILE_NOT_ACTIVE, HttpStatus.UNPROCESSABLE_CONTENT, null, notActive,
					Map.of());
		}

		Set<UUID> keep = new HashSet<>();
		items.forEach(item -> keep.add(catalogue.get(item.languageCode()).id()));
		for (UUID languageId : existing.keySet()) {
			if (!keep.contains(languageId)) {
				jdbc.sql("DELETE FROM user_typing_languages WHERE user_id = :user AND language_id = :language")
						.param("user", userId).param("language", languageId).update();
			}
		}
		// the partial unique index allows one enabled primary: clear the old one before setting the new one
		jdbc.sql("UPDATE user_typing_languages SET is_primary = FALSE WHERE user_id = :user AND is_primary")
				.param("user", userId).update();
		for (int i = 0; i < items.size(); i++) {
			TypingLanguageDto item = items.get(i);
			UUID languageId = catalogue.get(item.languageCode()).id();
			var statement = jdbc.sql(existing.containsKey(languageId) ? UPDATE : INSERT).param("user", userId)
					.param("language", languageId).param("enabled", item.enabled())
					.param("primary", Boolean.TRUE.equals(item.isPrimary())).param("sort", sortOrder(item))
					.param("mode", item.inputMode()).param("profile", profiles.get(i))
					.param("trigger", item.conversionTrigger() == null ? "LIVE_COMPOSING" : item.conversionTrigger())
					.param("candidates", item.showTransliterationCandidates() == null
							|| item.showTransliterationCandidates())
					.param("unknown",
							item.unknownTokenBehavior() == null ? "PRESERVE_LATIN" : item.unknownTokenBehavior());
			statement.update();
		}
	}

	private UUID resolveProfile(UUID languageId, String code, Existing current) {
		UUID active = jdbc.sql("""
				SELECT id FROM transliteration_profiles
				WHERE language_id = :language AND profile_code = :code AND profile_status = 'ACTIVE'
				ORDER BY created_at DESC, id DESC LIMIT 1
				""").param("language", languageId).param("code", code).query(UUID.class).optional().orElse(null);
		if (active != null) {
			return active;
		}
		if (current != null && current.profileId() != null) {
			boolean sameCode = jdbc.sql("""
					SELECT EXISTS (SELECT 1 FROM transliteration_profiles
					               WHERE id = :id AND language_id = :language AND profile_code = :code)
					""").param("id", current.profileId()).param("language", languageId).param("code", code)
					.query(Boolean.class).single();
			if (sameCode) {
				return current.profileId(); // F14: kept as it is, even when RETIRED
			}
		}
		return null;
	}

	private static final String INSERT = """
			INSERT INTO user_typing_languages (user_id, language_id, is_enabled, is_primary, sort_order, input_mode,
			    transliteration_profile_id, conversion_trigger, show_transliteration_candidates, unknown_token_behavior)
			VALUES (:user, :language, :enabled, :primary, :sort, :mode, :profile, :trigger, :candidates, :unknown)
			""";

	private static final String UPDATE = """
			UPDATE user_typing_languages SET is_enabled = :enabled, is_primary = :primary, sort_order = :sort,
			    input_mode = :mode, transliteration_profile_id = :profile, conversion_trigger = :trigger,
			    show_transliteration_candidates = :candidates, unknown_token_behavior = :unknown, updated_at = NOW()
			WHERE user_id = :user AND language_id = :language
			""";
}
