package com.qalsmartkeyboard.preferences;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.preferences.PreferencesDtos.PreferencesDto;

/**
 * {@code GET/PUT /me/preferences} (System Design 10.1). One document per user with an integer {@code revision}: no row
 * yet = revision 0 and the documented defaults; the first write must send revision 0 and creates the row at revision 1;
 * every later write must send the revision it read.
 *
 * <p><b>Concurrency</b>: the write is a single statement that checks the revision it was given
 * ({@code UPDATE ... SET revision = revision + 1 WHERE user_id = ? AND revision = ?}; the first write is
 * {@code INSERT ... ON CONFLICT DO NOTHING}). PostgreSQL row-locks the row, so a second writer with the same revision
 * waits, re-evaluates the {@code WHERE} against the committed row, matches nothing and gets 409 {@code REVISION_CONFLICT}.
 * Exactly one of any number of writers that read the same revision wins; nothing is lost.
 */
@Service
class PreferencesService {

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;

	PreferencesService(JdbcClient jdbc, TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.tx = tx;
	}

	PreferencesDto get(UUID userId) {
		return read(userId).orElseGet(PreferencesService::defaults);
	}

	/** @throws ApiException 400 unsupported language, 403 history without consent, 409 {@code REVISION_CONFLICT} */
	PreferencesDto replace(UUID userId, PreferencesDto in) {
		return tx.execute(status -> {
			UUID source = language("defaultSourceLanguage", in.defaultSourceLanguage());
			UUID destination = language("defaultDestinationLanguage", in.defaultDestinationLanguage());
			UUID voice = language("preferredVoiceLanguage", in.preferredVoiceLanguage());
			boolean history = Boolean.TRUE.equals(in.historyEnabled());
			if (history && !hasHistoryConsent(userId)) {
				throw new ApiException(ApiErrorCode.CONSENT_REQUIRED, HttpStatus.FORBIDDEN,
						"Saving history requires the SAVE_AI_HISTORY consent.");
			}
			Map<String, Object> p = new HashMap<>();
			p.put("user", userId);
			p.put("source", source);
			p.put("destination", destination);
			p.put("voice", voice);
			p.put("detection", or(in.sourceDetectionMode(), "AUTO"));
			p.put("translationMode", or(in.translationMode(), "MANUAL"));
			p.put("preserveNames", in.preserveNamesInTranslation() == null || in.preserveNamesInTranslation());
			p.put("style", or(in.defaultTranslationStyle(), "NATURAL"));
			p.put("formality", or(in.defaultFormality(), "INFORMAL"));
			p.put("gender", or(in.defaultAddresseeGender(), "UNKNOWN"));
			p.put("calendar", or(in.calendarSystem(), "GREGORIAN"));
			p.put("time", or(in.timeNotation(), "INTERNATIONAL"));
			p.put("numerals", or(in.numeralStyle(), "WESTERN"));
			p.put("voiceMode", or(in.voiceInputLanguageMode(), "MANUAL"));
			p.put("ai", or(in.aiProcessingPreference(), "SERVER"));
			p.put("history", history);
			p.put("retention", in.historyRetentionDays() == null ? 30 : in.historyRetentionDays());
			p.put("analytics", Boolean.TRUE.equals(in.analyticsOptIn()));
			p.put("theme", or(in.keyboardTheme(), "SYSTEM"));
			p.put("haptics", in.hapticsEnabled() == null || in.hapticsEnabled());
			p.put("sound", Boolean.TRUE.equals(in.soundEnabled()));
			p.put("revision", in.revision());
			int changed = in.revision() == 0 ? insert(p) : update(p);
			if (changed == 0) {
				throw conflict(currentRevision(userId));
			}
			return read(userId).orElseThrow();
		});
	}

	private int insert(Map<String, Object> p) {
		return jdbc.sql("""
				INSERT INTO user_preferences (user_id, default_source_language_id, default_destination_language_id,
				    source_detection_mode, translation_mode, preserve_names_in_translation, default_translation_style,
				    default_formality, default_addressee_gender, calendar_system, time_notation, numeral_style,
				    voice_input_language_mode, preferred_voice_language_id, ai_processing_preference, ai_history_enabled,
				    history_retention_days, analytics_opt_in, keyboard_theme, haptics_enabled, sound_enabled, revision)
				VALUES (:user, :source, :destination, :detection, :translationMode, :preserveNames, :style, :formality,
				    :gender, :calendar, :time, :numerals, :voiceMode, :voice, :ai, :history, :retention, :analytics,
				    :theme, :haptics, :sound, 1)
				ON CONFLICT (user_id) DO NOTHING
				""").params(p).update();
	}

	private int update(Map<String, Object> p) {
		return jdbc.sql("""
				UPDATE user_preferences SET default_source_language_id = :source,
				    default_destination_language_id = :destination, source_detection_mode = :detection,
				    translation_mode = :translationMode, preserve_names_in_translation = :preserveNames,
				    default_translation_style = :style, default_formality = :formality,
				    default_addressee_gender = :gender, calendar_system = :calendar, time_notation = :time,
				    numeral_style = :numerals, voice_input_language_mode = :voiceMode,
				    preferred_voice_language_id = :voice, ai_processing_preference = :ai, ai_history_enabled = :history,
				    history_retention_days = :retention, analytics_opt_in = :analytics, keyboard_theme = :theme,
				    haptics_enabled = :haptics, sound_enabled = :sound, revision = revision + 1, updated_at = NOW()
				WHERE user_id = :user AND revision = :revision
				""").params(p).update();
	}

	private Optional<PreferencesDto> read(UUID userId) {
		return jdbc.sql("""
				SELECT p.*, src.language_code AS source_code, dst.language_code AS destination_code,
				       voc.language_code AS voice_code
				FROM user_preferences p
				LEFT JOIN supported_languages src ON src.id = p.default_source_language_id
				LEFT JOIN supported_languages dst ON dst.id = p.default_destination_language_id
				LEFT JOIN supported_languages voc ON voc.id = p.preferred_voice_language_id
				WHERE p.user_id = :user
				""").param("user", userId)
				.query((rs, n) -> new PreferencesDto(rs.getInt("revision"), rs.getString("source_code"),
						rs.getString("source_detection_mode"), rs.getString("destination_code"),
						rs.getString("translation_mode"), rs.getString("default_translation_style"),
						rs.getBoolean("preserve_names_in_translation"), rs.getString("voice_input_language_mode"),
						rs.getString("voice_code"), rs.getString("calendar_system"), rs.getString("time_notation"),
						rs.getString("numeral_style"), rs.getString("default_formality"),
						rs.getString("default_addressee_gender"), rs.getString("ai_processing_preference"),
						rs.getBoolean("ai_history_enabled"), rs.getInt("history_retention_days"),
						rs.getString("keyboard_theme"), rs.getBoolean("haptics_enabled"),
						rs.getBoolean("sound_enabled"), rs.getBoolean("analytics_opt_in"),
						rs.getTimestamp("updated_at").toInstant()))
				.optional();
	}

	/** What a user who never synced reads: revision 0 and the documented defaults. */
	static PreferencesDto defaults() {
		return new PreferencesDto(0, null, "AUTO", null, "MANUAL", "NATURAL", true, "MANUAL", null, "GREGORIAN",
				"INTERNATIONAL", "WESTERN", "INFORMAL", "UNKNOWN", "SERVER", false, 30, "SYSTEM", true, false, false,
				null);
	}

	private int currentRevision(UUID userId) {
		return jdbc.sql("SELECT revision FROM user_preferences WHERE user_id = :user").param("user", userId)
				.query(Integer.class).optional().orElse(0);
	}

	private UUID language(String field, String code) {
		if (code == null) {
			return null;
		}
		return jdbc.sql("SELECT id FROM supported_languages WHERE language_code = :code AND is_active")
				.param("code", code).query(UUID.class).optional()
				.orElseThrow(() -> new ApiException(ApiErrorCode.LANGUAGE_NOT_SUPPORTED, HttpStatus.BAD_REQUEST, null,
						List.of(new ApiFieldError(field, "LANGUAGE_NOT_SUPPORTED", "The language is not supported.")),
						Map.of()));
	}

	/** The latest SAVE_AI_HISTORY consent record decides (append-only log, V1 section 7). */
	private boolean hasHistoryConsent(UUID userId) {
		return jdbc.sql("""
				SELECT is_granted FROM user_consents WHERE user_id = :user AND consent_code = 'SAVE_AI_HISTORY'
				ORDER BY recorded_at DESC, id DESC LIMIT 1
				""").param("user", userId).query(Boolean.class).optional().orElse(false);
	}

	/** 409 {@code REVISION_CONFLICT}; the extension member {@code currentRevision} tells the client what to re-read. */
	static ApiException conflict(int currentRevision) {
		return new ApiException(ApiErrorCode.REVISION_CONFLICT, HttpStatus.CONFLICT,
				"The document changed since it was read. Read it again, merge and retry.", List.of(),
				Map.of("currentRevision", currentRevision));
	}

	private static String or(String value, String fallback) {
		return value == null ? fallback : value;
	}
}
