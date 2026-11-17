package com.qalsmartkeyboard.platform.crypto.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException.Reason;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.kms.Aes256Gcm;
import com.qalsmartkeyboard.platform.crypto.kms.KmsContext;

/**
 * User-content ciphertext format v1 (ADR 0005):
 *
 * <pre>
 * offset  size  field
 *   0      1    format = 0x01
 *   1      4    key_version, unsigned big-endian, 1..2^31-1 (fits user_data_keys.key_version INTEGER)
 *   5     12    nonce (random per value)
 *  17      n    AES-256-GCM ciphertext (n = plaintext length)
 *  17+n   16    GCM tag
 * </pre>
 *
 * Associated data (never stored, recomputed on decrypt):
 *
 * <pre>
 * "qal.user-content.v1" || format(1) || user_id(16, RFC 4122 byte order) || key_version(4)
 *   || len(2)||table || len(2)||column || len(2)||row_id        (len = UTF-8 byte length, big-endian)
 * </pre>
 */
final class CiphertextFormat {

	static final byte FORMAT_V1 = 0x01;
	static final int VERSION_OFFSET = 1;
	static final int NONCE_OFFSET = 5;
	static final int HEADER_BYTES = NONCE_OFFSET + Aes256Gcm.NONCE_BYTES;
	static final int MIN_BYTES = HEADER_BYTES + Aes256Gcm.TAG_BYTES;

	private static final byte[] AAD_DOMAIN = "qal.user-content.v1".getBytes(StandardCharsets.US_ASCII);

	/** Header fields of a parsed ciphertext; the body is {@code [HEADER_BYTES, length)}. */
	record Header(int keyVersion, byte[] nonce) {
	}

	private CiphertextFormat() {
	}

	static byte[] encode(int keyVersion, byte[] nonce, byte[] sealedBody) {
		if (keyVersion < 1) {
			throw new IllegalArgumentException("keyVersion must be positive");
		}
		if (nonce.length != Aes256Gcm.NONCE_BYTES || sealedBody.length < Aes256Gcm.TAG_BYTES) {
			throw new IllegalArgumentException("bad nonce or body length");
		}
		return ByteBuffer.allocate(HEADER_BYTES + sealedBody.length)
				.put(FORMAT_V1)
				.putInt(keyVersion)
				.put(nonce)
				.put(sealedBody)
				.array();
	}

	static Header parse(byte[] ciphertext) {
		if (ciphertext == null || ciphertext.length < MIN_BYTES) {
			throw new CiphertextRejectedException(Reason.MALFORMED);
		}
		if (ciphertext[0] != FORMAT_V1) {
			throw new CiphertextRejectedException(Reason.UNSUPPORTED_FORMAT);
		}
		int keyVersion = ByteBuffer.wrap(ciphertext, VERSION_OFFSET, 4).getInt();
		if (keyVersion < 1) {
			throw new CiphertextRejectedException(Reason.MALFORMED);
		}
		return new Header(keyVersion, Arrays.copyOfRange(ciphertext, NONCE_OFFSET, HEADER_BYTES));
	}

	static byte[] associatedData(UUID userId, int keyVersion, EncryptionContext context) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(96);
		out.writeBytes(AAD_DOMAIN);
		out.write(FORMAT_V1);
		out.writeBytes(ByteBuffer.allocate(20)
				.putLong(userId.getMostSignificantBits())
				.putLong(userId.getLeastSignificantBits())
				.putInt(keyVersion)
				.array());
		KmsContext.writeString(out, context.table());
		KmsContext.writeString(out, context.column());
		KmsContext.writeString(out, context.rowId());
		return out.toByteArray();
	}
}
