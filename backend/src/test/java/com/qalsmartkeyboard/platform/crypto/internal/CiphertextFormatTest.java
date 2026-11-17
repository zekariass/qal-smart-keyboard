package com.qalsmartkeyboard.platform.crypto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.AEADBadTagException;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException.Reason;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.kms.Aes256Gcm;

class CiphertextFormatTest {

	private static final HexFormat HEX = HexFormat.of();
	private static final UUID USER = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
	private static final EncryptionContext CONTEXT = EncryptionContext.of("t", "c", "r");

	@Test
	void encodesHeaderThenBody() {
		byte[] nonce = HEX.parseHex("0102030405060708090a0b0c");
		byte[] body = new byte[16 + 3];
		byte[] encoded = CiphertextFormat.encode(0x01020304, nonce, body);

		assertThat(encoded).hasSize(17 + 19);
		assertThat(HEX.formatHex(encoded, 0, 17)).isEqualTo("01" + "01020304" + "0102030405060708090a0b0c");
		CiphertextFormat.Header header = CiphertextFormat.parse(encoded);
		assertThat(header.keyVersion()).isEqualTo(0x01020304);
		assertThat(header.nonce()).isEqualTo(nonce);
		assertThat(CiphertextFormat.MIN_BYTES).isEqualTo(33);
	}

	@Test
	void rejectsMalformedHeaders() {
		byte[] valid = CiphertextFormat.encode(1, new byte[12], new byte[16]);

		assertReason(null, Reason.MALFORMED);
		assertReason(new byte[32], Reason.MALFORMED);
		byte[] format2 = valid.clone();
		format2[0] = 0x02;
		assertReason(format2, Reason.UNSUPPORTED_FORMAT);
		byte[] versionZero = valid.clone();
		versionZero[4] = 0;
		assertReason(versionZero, Reason.MALFORMED);
		byte[] negativeVersion = valid.clone();
		negativeVersion[1] = (byte) 0x80;
		assertReason(negativeVersion, Reason.MALFORMED);
	}

	@Test
	void associatedDataMatchesTheDocumentedLayout() {
		String expected = HEX.formatHex("qal.user-content.v1".getBytes(StandardCharsets.US_ASCII))
				+ "01"
				+ "00112233445566778899aabbccddeeff"
				+ "00000007"
				+ "0001" + "74"   // "t"
				+ "0001" + "63"   // "c"
				+ "0001" + "72";  // "r"
		assertThat(HEX.formatHex(CiphertextFormat.associatedData(USER, 7, CONTEXT))).isEqualTo(expected);
	}

	@Test
	void associatedDataSeparatesEveryField() {
		byte[] base = CiphertextFormat.associatedData(USER, 1, EncryptionContext.of("ab", "c", "r"));

		assertThat(CiphertextFormat.associatedData(UUID.randomUUID(), 1, EncryptionContext.of("ab", "c", "r")))
				.isNotEqualTo(base);
		assertThat(CiphertextFormat.associatedData(USER, 2, EncryptionContext.of("ab", "c", "r"))).isNotEqualTo(base);
		assertThat(CiphertextFormat.associatedData(USER, 1, EncryptionContext.of("a", "bc", "r")))
				.isNotEqualTo(base); // length prefixes make field boundaries unambiguous
		assertThat(CiphertextFormat.associatedData(USER, 1, EncryptionContext.of("ab", "c", "r2")))
				.isNotEqualTo(base);
	}

	@Test
	void sameKeyButAnotherUsersAssociatedDataFailsAuthentication() throws Exception {
		SecureRandom random = new SecureRandom();
		byte[] key = new byte[32];
		byte[] nonce = new byte[12];
		random.nextBytes(key);
		random.nextBytes(nonce);
		byte[] sealed = Aes256Gcm.seal(key, nonce, CiphertextFormat.associatedData(USER, 1, CONTEXT),
				"hello".getBytes(StandardCharsets.UTF_8));

		assertThat(Aes256Gcm.open(key, nonce, CiphertextFormat.associatedData(USER, 1, CONTEXT), sealed, 0,
				sealed.length)).asString(StandardCharsets.UTF_8).isEqualTo("hello");
		byte[] otherUser = CiphertextFormat.associatedData(UUID.randomUUID(), 1, CONTEXT);
		assertThatThrownBy(() -> Aes256Gcm.open(key, nonce, otherUser, sealed, 0, sealed.length))
				.isInstanceOf(AEADBadTagException.class);
	}

	private static void assertReason(byte[] ciphertext, Reason reason) {
		assertThatThrownBy(() -> CiphertextFormat.parse(ciphertext))
				.isInstanceOfSatisfying(CiphertextRejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
	}
}
