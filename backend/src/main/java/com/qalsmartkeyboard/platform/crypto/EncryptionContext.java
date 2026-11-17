package com.qalsmartkeyboard.platform.crypto;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Where an encrypted value is stored. It is bound into the AES-GCM associated data, so a ciphertext copied to
 * another table, column or row (or another user) does not decrypt.
 *
 * <p>The row id must be known before the row is inserted: generate the UUID in the application instead of
 * relying on the column's {@code DEFAULT gen_random_uuid()}.
 *
 * @param table  database table, lower-case SQL identifier (for example {@code user_history_items})
 * @param column ciphertext column, lower-case SQL identifier (for example {@code input_ciphertext})
 * @param rowId  stable identifier of the row, normally its primary key
 */
public record EncryptionContext(String table, String column, String rowId) {

	private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");
	private static final Pattern ROW_ID = Pattern.compile("[\\x21-\\x7E]{1,128}");

	public EncryptionContext {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(column, "column");
		Objects.requireNonNull(rowId, "rowId");
		if (!IDENTIFIER.matcher(table).matches()) {
			throw new IllegalArgumentException("table must be a lower-case SQL identifier");
		}
		if (!IDENTIFIER.matcher(column).matches()) {
			throw new IllegalArgumentException("column must be a lower-case SQL identifier");
		}
		if (!ROW_ID.matcher(rowId).matches()) {
			throw new IllegalArgumentException("rowId must be 1-128 printable ASCII characters without spaces");
		}
	}

	public static EncryptionContext of(String table, String column, UUID rowId) {
		return new EncryptionContext(table, column, Objects.requireNonNull(rowId, "rowId").toString());
	}

	public static EncryptionContext of(String table, String column, String rowId) {
		return new EncryptionContext(table, column, rowId);
	}
}
