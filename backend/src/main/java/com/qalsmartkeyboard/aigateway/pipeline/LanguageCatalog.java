package com.qalsmartkeyboard.aigateway.pipeline;

import java.lang.Character.UnicodeScript;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
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

	/** @param script ISO 15924 code from the catalogue mapped to a Java script; null when unknown */
	public record Language(String code, String englishName, UnicodeScript script, boolean active) {
	}

	private final CachedSnapshot<Map<String, Language>> snapshot;

	public LanguageCatalog(Supplier<Map<String, Language>> source, Duration ttl, Clock clock) {
		this.snapshot = new CachedSnapshot<>("language catalogue", source, ttl, clock);
	}

	public static Supplier<Map<String, Language>> jdbc(JdbcClient jdbc) {
		return () -> {
			Map<String, Language> map = new HashMap<>();
			jdbc.sql("SELECT language_code, english_name, script_code, is_active FROM supported_languages")
					.query(rs -> {
						String code = rs.getString("language_code");
						map.put(code, new Language(code, rs.getString("english_name"),
								script(rs.getString("script_code")), rs.getBoolean("is_active")));
					});
			return Map.copyOf(map);
		};
	}

	/** An ISO 15924 code ("Ethi", "Latn") as a Java script; null when absent or unknown. */
	public static UnicodeScript script(String iso15924) {
		if (iso15924 == null || iso15924.isBlank()) {
			return null;
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

	/** The language for a code, active or not. */
	public Optional<Language> find(String code) {
		return code == null ? Optional.empty() : Optional.ofNullable(snapshot.get().get(code));
	}
}
