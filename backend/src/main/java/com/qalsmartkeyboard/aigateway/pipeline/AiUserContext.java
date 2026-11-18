package com.qalsmartkeyboard.aigateway.pipeline;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The per-user defaults the pipeline fills a request with (SD 11.5: addressee "from the request, then the destination
 * profile, then user preferences"), read-only from {@code user_preferences}, {@code user_destination_profiles} and
 * {@code users.time_zone}. Only enum and language columns are read; the profile label is ciphertext and is never
 * touched. A port so a later step can serve it from the preferences module's API instead of SQL.
 */
public interface AiUserContext {

	/** Preference defaults; every field non-null (V1 column defaults when the user has no preferences row). */
	record Defaults(String formality, String gender, String translationStyle, boolean preserveNames, String calendar,
			String timeNotation, String numerals, ZoneId timeZone) {

		public static final ZoneId MARKET_ZONE = ZoneId.of("Africa/Addis_Ababa");

		public static final Defaults NONE = new Defaults("INFORMAL", "UNKNOWN", "NATURAL", true, "GREGORIAN",
				"INTERNATIONAL", "WESTERN", MARKET_ZONE);
	}

	/**
	 * A destination profile's defaults (contract {@code DestinationProfile}).
	 *
	 * @param tone null when the profile has no tone (V1 default {@code NATURAL}, which the contract omits)
	 * @param style null when unset
	 */
	record DestinationProfile(UUID id, String destinationLanguage, String formality, String gender, String tone,
			String style, UUID glossaryId) {
	}

	Defaults defaults(UUID userId);

	/** The profile when it exists and belongs to the user. */
	Optional<DestinationProfile> profile(UUID userId, UUID profileId);

	static AiUserContext jdbc(JdbcClient jdbc) {
		return new AiUserContext() {
			@Override
			public Defaults defaults(UUID userId) {
				return jdbc.sql("""
						SELECT u.time_zone, p.default_formality, p.default_addressee_gender, p.default_translation_style,
						       p.preserve_names_in_translation, p.calendar_system, p.time_notation, p.numeral_style
						  FROM users u LEFT JOIN user_preferences p ON p.user_id = u.id
						 WHERE u.id = :user
						""").param("user", userId).query((rs, n) -> {
					Defaults d = Defaults.NONE;
					String formality = rs.getString("default_formality");
					if (formality == null) {
						return new Defaults(d.formality(), d.gender(), d.translationStyle(), d.preserveNames(),
								d.calendar(), d.timeNotation(), d.numerals(), zone(rs.getString("time_zone")));
					}
					return new Defaults(formality, rs.getString("default_addressee_gender"),
							rs.getString("default_translation_style"), rs.getBoolean("preserve_names_in_translation"),
							rs.getString("calendar_system"), rs.getString("time_notation"),
							rs.getString("numeral_style"), zone(rs.getString("time_zone")));
				}).optional().orElse(Defaults.NONE);
			}

			@Override
			public Optional<DestinationProfile> profile(UUID userId, UUID profileId) {
				return jdbc.sql("""
						SELECT p.id, l.language_code, p.formality, p.addressee_gender, p.default_tone, p.default_style,
						       p.glossary_id
						  FROM user_destination_profiles p JOIN supported_languages l ON l.id = p.destination_language_id
						 WHERE p.id = :id AND p.user_id = :user
						""").param("id", profileId).param("user", userId).query((rs, n) -> {
					String tone = rs.getString("default_tone");
					return new DestinationProfile(rs.getObject("id", UUID.class), rs.getString("language_code"),
							rs.getString("formality"), rs.getString("addressee_gender"),
							"NATURAL".equals(tone) ? null : tone, rs.getString("default_style"),
							rs.getObject("glossary_id", UUID.class));
				}).optional();
			}

			private static ZoneId zone(String id) {
				if (id == null) {
					return Defaults.MARKET_ZONE;
				}
				try {
					return ZoneId.of(id);
				} catch (DateTimeException e) {
					return Defaults.MARKET_ZONE;
				}
			}
		};
	}
}
