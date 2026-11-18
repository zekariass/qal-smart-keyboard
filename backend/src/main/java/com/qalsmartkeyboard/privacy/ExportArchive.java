package com.qalsmartkeyboard.privacy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.qalsmartkeyboard.identity.ExportWriter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Collects the sections the {@link com.qalsmartkeyboard.identity.UserExportParticipant}s write and renders the archive
 * (ADR 0022): one JSON document
 *
 * <pre>
 * { "manifest": { format, formatVersion, exportId, userId, generatedAt, linkExpiresAt,
 *                 sections: [ { code, dataClass, description, rows } ... ],
 *                 excluded: [ { code, reason } ... ], notes: [ ... ] },
 *   "data": { "&lt;section code&gt;": [ row, ... ], ... } }
 * </pre>
 *
 * The manifest lists every section with its row count, so a reader (and the tests) can check the data against it, and
 * everything deliberately left out with the reason. Keystrokes, drafts and audio never reach the server and are listed
 * as excluded for that reason.
 */
final class ExportArchive implements ExportWriter {

	static final String FORMAT = "qal-data-export";
	static final int FORMAT_VERSION = 1;

	private final Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
	private final List<Map<String, Object>> sections = new ArrayList<>();
	private final List<Map<String, Object>> excluded = new ArrayList<>();

	ExportArchive() {
		excluded("keystrokesAndDrafts", "Everything you type stays on your device (SD 19.1); the server never receives it, "
				+ "so there is nothing to export.");
		excluded("audio", "Audio is processed in memory or a temporary object and deleted afterwards; it is not stored.");
		excluded("auditAndSecurityRecords", "Audit events, abuse and security records, outbox events and operational logs "
				+ "are internal records about the service, not your data (SD 19.2); they are not exported.");
	}

	@Override
	public void section(String code, String dataClass, String description, List<Map<String, Object>> rows) {
		if (data.containsKey(code)) {
			throw new IllegalStateException("section " + code + " written twice");
		}
		data.put(code, List.copyOf(rows));
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("code", code);
		entry.put("dataClass", dataClass);
		entry.put("description", description);
		entry.put("rows", rows.size());
		sections.add(entry);
	}

	@Override
	public void excluded(String code, String reason) {
		excluded.add(Map.of("code", code, "reason", reason));
	}

	int sectionCount() {
		return sections.size();
	}

	long rowCount() {
		return data.values().stream().mapToLong(List::size).sum();
	}

	/** The archive as UTF-8 JSON. */
	byte[] render(JsonMapper mapper, UUID exportId, UUID userId, Instant generatedAt, Instant linkExpiresAt) {
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("format", FORMAT);
		manifest.put("formatVersion", FORMAT_VERSION);
		manifest.put("exportId", exportId.toString());
		manifest.put("userId", userId.toString());
		manifest.put("generatedAt", generatedAt.toString());
		manifest.put("linkExpiresAt", linkExpiresAt.toString());
		manifest.put("sections", sections);
		manifest.put("excluded", excluded);
		manifest.put("notes", List.of(
				"Timestamps are ISO-8601 in UTC. Languages are BCP-47 codes. Text you wrote or that was stored encrypted "
						+ "(history, dictionary words, labels, feedback) is decrypted here.",
				"App destination rules are listed as hashes: the server never receives app package names, so the names "
						+ "exist only on your devices.",
				"Opt-in history shows items that have not expired; deleted dictionary words are not included."));
		Map<String, Object> archive = new LinkedHashMap<>();
		archive.put("manifest", manifest);
		archive.put("data", data);
		return mapper.writeValueAsBytes(archive);
	}
}
