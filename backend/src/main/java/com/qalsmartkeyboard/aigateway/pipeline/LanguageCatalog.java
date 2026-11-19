package com.qalsmartkeyboard.aigateway.pipeline;

import java.lang.Character.UnicodeScript;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Read-only view of {@code supported_languages} (owned by the languages module; read here like the registry and the
 * routing policies read it): whether a code is known and active, its English name for prompts and its ISO 15924
 * script for the output script guardrail. Cached as a snapshot like the other gateway catalogues.
 */
public final class LanguageCatalog {

	/**
	 * @param script     ISO 15924 code from the catalogue mapped to a Java script; null when unknown
	 * @param nativeName the autonym, or null
	 * @param scriptCode the catalogue's ISO 15924 code as stored ("Latn", "Hans"), or null
	 * @param rtl        written right to left
	 * @param typing     a keyboard (typing) language; false for translation-only rows (ADR 0040)
	 * @param sortOrder  catalogue order
	 */
	public record Language(String code, String englishName, UnicodeScript script, boolean active, String nativeName,
			String scriptCode, boolean rtl, boolean typing, int sortOrder) {

		public Language(String code, String englishName, UnicodeScript script, boolean active) {
			this(code, englishName, script, active, null, null, false, true, 100);
		}
	}

	/** Catalogue order: sort order, then English name, then code (GET /languages orders the same way). */
	public static final Comparator<Language> ORDER = Comparator.comparingInt(Language::sortOrder)
			.thenComparing(Language::englishName, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(Language::code);

	private final CachedSnapshot<Map<String, Language>> snapshot;

	public LanguageCatalog(Supplier<Map<String, Language>> source, Duration ttl, Clock clock) {
		this.snapshot = new CachedSnapshot<>("language catalogue", source, ttl, clock);
	}

	public static Supplier<Map<String, Language>> jdbc(JdbcClient jdbc) {
		return () -> {
			Map<String, Language> map = new HashMap<>();
			jdbc.sql("""
					SELECT language_code, english_name, native_name, script_code, writing_direction, is_active,
					       is_typing_language, sort_order
					  FROM supported_languages""").query(rs -> {
						String code = rs.getString("language_code");
						String scriptCode = rs.getString("script_code");
						map.put(code, new Language(code, rs.getString("english_name"), script(scriptCode),
								rs.getBoolean("is_active"), rs.getString("native_name"), scriptCode,
								"RTL".equals(rs.getString("writing_direction")), rs.getBoolean("is_typing_language"),
								rs.getInt("sort_order")));
					});
			return Map.copyOf(map);
		};
	}

	/**
	 * An ISO 15924 code ("Ethi", "Latn") as a Java script; null when absent or unknown. The ISO variants that are not
	 * Unicode script aliases map to the script their text is written in: Hans/Hant (Chinese) to Han, Kore (Korean) to
	 * Hangul. Jpan (Japanese mixes kana and kanji) stays null, so no single-script check applies.
	 */
	public static UnicodeScript script(String iso15924) {
		if (iso15924 == null || iso15924.isBlank()) {
			return null;
		}
		switch (iso15924) {
			case "Hans", "Hant" -> {
				return UnicodeScript.HAN;
			}
			case "Kore" -> {
				return UnicodeScript.HANGUL;
			}
			case "Jpan" -> {
				return null;
			}
			default -> {
			}
		}
		try {
			return UnicodeScript.forName(iso15924);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** The language when its code is in the catalogue and active. */
	public Optional<Language> active(String code) {
		Language l = code == null ? null : snapshot.get().get(code);
		return l != null && l.active() ? Optional.of(l) : Optional.empty();
	}

	/** Every active language in catalogue order ({@link #ORDER}). */
	public List<Language> allActive() {
		return snapshot.get().values().stream().filter(Language::active).sorted(ORDER).toList();
	}

	/** The language for a code, active or not. */
	public Optional<Language> find(String code) {
		return code == null ? Optional.empty() : Optional.ofNullable(snapshot.get().get(code));
	}
}
