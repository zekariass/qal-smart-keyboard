package com.qalsmartkeyboard.identity;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * Helpers for {@link UserExportParticipant}s: runs a query and turns every row into a JSON-ready, camelCase map
 * (timestamps as ISO-8601 UTC strings, UUIDs and decimals as strings, arrays as lists, {@code jsonb} as a JSON tree,
 * {@code inet} as text). Binary columns (ciphertext, hashes) are never exported: a participant selects them, decrypts
 * them in its row mapper and drops them; a {@code byte[]} that is still in the row afterwards is a bug and fails the
 * export instead of leaking bytes.
 */
public final class ExportRows {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private ExportRows() {
	}

	/** Rows of {@code sql} (named parameters from {@code params}), as they are. */
	public static List<Map<String, Object>> query(JdbcClient jdbc, String sql, Map<String, Object> params) {
		return query(jdbc, sql, params, UnaryOperator.identity());
	}

	/**
	 * Rows of {@code sql}, each passed through {@code mapper} (which gets the camelCase row with {@code byte[]} values
	 * still in it, and returns the row to export; it may decrypt, rename and remove columns).
	 */
	public static List<Map<String, Object>> query(JdbcClient jdbc, String sql, Map<String, Object> params,
			UnaryOperator<Map<String, Object>> mapper) {
		List<Map<String, Object>> raw = jdbc.sql(sql).params(params).query().listOfRows();
		List<Map<String, Object>> rows = new ArrayList<>(raw.size());
		for (Map<String, Object> r : raw) {
			Map<String, Object> row = new LinkedHashMap<>();
			r.forEach((column, value) -> row.put(camel(column), value instanceof byte[] ? value : normalise(value)));
			Map<String, Object> mapped = mapper.apply(row);
			mapped.forEach((column, value) -> {
				if (value instanceof byte[]) {
					throw new IllegalStateException("binary column " + column + " must not be exported");
				}
			});
			rows.add(mapped);
		}
		return rows;
	}

	/** {@code snake_case} to {@code camelCase}. */
	public static String camel(String column) {
		StringBuilder out = new StringBuilder(column.length());
		boolean up = false;
		for (char c : column.toLowerCase(Locale.ROOT).toCharArray()) {
			if (c == '_') {
				up = true;
			}
			else {
				out.append(up ? Character.toUpperCase(c) : c);
				up = false;
			}
		}
		return out.toString();
	}

	/** The JSON-ready form of one JDBC value (see the class comment). */
	public static Object normalise(Object value) {
		if (value == null || value instanceof String || value instanceof Boolean || value instanceof Integer
				|| value instanceof Long || value instanceof Short) {
			return value;
		}
		if (value instanceof Timestamp t) {
			return t.toInstant().toString();
		}
		if (value instanceof OffsetDateTime t) {
			return t.toInstant().toString();
		}
		if (value instanceof LocalDateTime t) {
			return t.toInstant(ZoneOffset.UTC).toString();
		}
		if (value instanceof Instant t) {
			return t.toString();
		}
		if (value instanceof UUID || value instanceof java.sql.Date || value instanceof java.sql.Time) {
			return value.toString();
		}
		if (value instanceof BigDecimal d) {
			return d.stripTrailingZeros().toPlainString();
		}
		if (value instanceof Number n) {
			return n;
		}
		if ("org.postgresql.util.PGobject".equals(value.getClass().getName())) {
			// the driver is a runtime dependency only: read the type through reflection
			String type = pgType(value);
			String text = value.toString();
			return text != null && ("jsonb".equals(type) || "json".equals(type)) ? JSON.readTree(text) : text;
		}
		if (value instanceof Array a) {
			try {
				Object[] items = (Object[]) a.getArray();
				List<Object> list = new ArrayList<>(items.length);
				for (Object item : items) {
					list.add(normalise(item));
				}
				return list;
			}
			catch (SQLException e) {
				throw new IllegalStateException("array column could not be read", e);
			}
		}
		return value.toString();
	}

	private static String pgType(Object pgObject) {
		try {
			return (String) pgObject.getClass().getMethod("getType").invoke(pgObject);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException("PGobject type could not be read", e);
		}
	}
}
