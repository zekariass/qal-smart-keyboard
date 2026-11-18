package com.qalsmartkeyboard.identity;

import java.util.List;
import java.util.Map;

/**
 * Where a {@link UserExportParticipant} puts its part of a data export (SD 19.4, plan B6.1, ADR 0022). The writer
 * records every section with its row count in the archive's manifest, so a reader can see what the export holds and
 * what it deliberately leaves out.
 */
public interface ExportWriter {

	/**
	 * Adds a section: a list of rows, each a JSON object ({@link ExportRows} builds them). An empty list is a section
	 * with zero rows (the manifest still lists it, so "nothing stored" is distinguishable from "not exported").
	 *
	 * @param code        stable camelCase id, the key of the section in the archive's {@code data} object
	 * @param dataClass   the SD 19.2 data class it belongs to (free text, shown in the manifest)
	 * @param description what a row is; also says what is left out of it, when something is
	 * @param rows        the rows; every value must be JSON-ready (see {@link ExportRows#normalise})
	 */
	void section(String code, String dataClass, String description, List<Map<String, Object>> rows);

	/**
	 * Records that something the user may expect is deliberately not in the archive, with the reason (shown in the
	 * manifest's {@code excluded} list).
	 */
	void excluded(String code, String reason);
}
