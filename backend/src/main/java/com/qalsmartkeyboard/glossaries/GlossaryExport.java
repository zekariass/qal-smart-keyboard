package com.qalsmartkeyboard.glossaries;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.ExportRows;
import com.qalsmartkeyboard.identity.ExportWriter;
import com.qalsmartkeyboard.identity.UserExportParticipant;

/** Data export (B6.1, ADR 0022) of the person's own glossaries and their entries (organisation glossaries are not theirs). */
@Component
class GlossaryExport implements UserExportParticipant {

	private final JdbcClient jdbc;

	GlossaryExport(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int exportOrder() {
		return GLOSSARIES;
	}

	@Override
	public void exportUser(UUID userId, ExportWriter out) {
		Map<String, Object> user = Map.of("u", userId);
		out.section("glossaries", "Account and preferences", "Personal glossaries (name, languages, status, enforcement).",
				ExportRows.query(jdbc, """
						SELECT g.id, g.name, s.language_code AS source_language, t.language_code AS target_language,
						       g.glossary_status, g.enforcement, g.created_at, g.updated_at
						FROM glossaries g JOIN supported_languages s ON s.id = g.source_language_id
						JOIN supported_languages t ON t.id = g.target_language_id
						WHERE g.user_id = :u ORDER BY g.created_at, g.id
						""", user));
		out.section("glossaryEntries", "Account and preferences", "The terms of the personal glossaries.",
				ExportRows.query(jdbc, """
						SELECT e.id, e.glossary_id, e.source_term, e.target_term, e.case_sensitive, e.do_not_translate,
						       e.notes, e.created_at, e.updated_at
						FROM glossary_entries e JOIN glossaries g ON g.id = e.glossary_id
						WHERE g.user_id = :u ORDER BY e.created_at, e.id
						""", user));
	}
}
