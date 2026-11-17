package com.qalsmartkeyboard.platform.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EncryptionContextTest {

	@Test
	void acceptsTableColumnAndUuidRow() {
		UUID row = UUID.randomUUID();
		EncryptionContext context = EncryptionContext.of("user_history_items", "input_ciphertext", row);
		assertThat(context.rowId()).isEqualTo(row.toString());
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "Users", "1table", "user-history", "user history", "a;drop" })
	void rejectsNonIdentifierTableAndColumn(String bad) {
		assertThatThrownBy(() -> EncryptionContext.of(bad, "label_ciphertext", "r1"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> EncryptionContext.of("user_destination_profiles", bad, "r1"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "has space", "tab\t", "ሰላም" })
	void rejectsBadRowIds(String bad) {
		assertThatThrownBy(() -> EncryptionContext.of("t", "c", bad)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsOverlongRowIdAndNulls() {
		assertThatThrownBy(() -> EncryptionContext.of("t", "c", "x".repeat(129)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> EncryptionContext.of("t", "c", (UUID) null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new EncryptionContext(null, "c", "r")).isInstanceOf(NullPointerException.class);
	}

	@Test
	void encryptedValueNeverPrintsCiphertext() {
		EncryptedValue value = new EncryptedValue(3, new byte[] { 1, 2, 3 });
		assertThat(value.toString()).isEqualTo("EncryptedValue[keyVersion=3, length=3]");
		assertThat(value).isEqualTo(new EncryptedValue(3, new byte[] { 1, 2, 3 }));
		assertThatThrownBy(() -> new EncryptedValue(0, new byte[0])).isInstanceOf(IllegalArgumentException.class);
	}
}
