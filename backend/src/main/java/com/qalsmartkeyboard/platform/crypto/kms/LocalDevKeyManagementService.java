package com.qalsmartkeyboard.platform.crypto.kms;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import javax.crypto.AEADBadTagException;

import com.qalsmartkeyboard.platform.crypto.KeyManagementException;

/**
 * Development/test KMS: wraps data keys with AES-256-GCM under a KEK held in memory. The KEK comes from
 * configuration ({@code qal.crypto.local-kms.kek}, env {@code QAL_LOCAL_KMS_KEK}) or, only when explicitly
 * allowed, is generated at startup and lost on restart. It is never in source code. The Spring configuration
 * refuses to create this adapter under the {@code prod}, {@code production} or {@code staging} profiles.
 *
 * <p>Wrapped format: {@code 0x01 || nonce(12) || AES-GCM(KEK, dek) || tag(16)}; associated data is
 * {@code "qal.local-kms.v1" || len||keyId || canonical(context)}.
 */
public final class LocalDevKeyManagementService implements KeyManagementService {

	/** Prefix of every key id this adapter issues, so rows wrapped by it are recognisable. */
	public static final String KEY_ID_PREFIX = "local:";

	static final byte FORMAT_V1 = 0x01;
	private static final byte[] DOMAIN = "qal.local-kms.v1".getBytes(StandardCharsets.US_ASCII);

	private final String keyId;
	private final byte[] kek;
	private final SecureRandom random;

	/**
	 * @param keyId identifier stored in {@code kms_key_id}; must start with {@value #KEY_ID_PREFIX}
	 * @param kek   32-byte key-encryption key; copied, the caller should wipe its array
	 */
	public LocalDevKeyManagementService(String keyId, byte[] kek, SecureRandom random) {
		this.keyId = Objects.requireNonNull(keyId, "keyId");
		if (!keyId.startsWith(KEY_ID_PREFIX) || keyId.length() > 255) {
			throw new IllegalArgumentException("local KMS key id must start with '" + KEY_ID_PREFIX + "'");
		}
		if (kek == null || kek.length != Aes256Gcm.KEY_BYTES) {
			throw new IllegalArgumentException("local KMS KEK must be 32 bytes");
		}
		this.kek = kek.clone();
		this.random = Objects.requireNonNull(random, "random");
	}

	@Override
	public String activeKeyId() {
		return keyId;
	}

	@Override
	public WrappedKey wrap(byte[] plaintextKey, Map<String, String> context) {
		byte[] nonce = new byte[Aes256Gcm.NONCE_BYTES];
		random.nextBytes(nonce);
		byte[] sealed = Aes256Gcm.seal(kek, nonce, associatedData(keyId, context), plaintextKey);
		byte[] out = new byte[1 + nonce.length + sealed.length];
		out[0] = FORMAT_V1;
		System.arraycopy(nonce, 0, out, 1, nonce.length);
		System.arraycopy(sealed, 0, out, 1 + nonce.length, sealed.length);
		return new WrappedKey(keyId, out);
	}

	@Override
	public byte[] unwrap(WrappedKey wrappedKey, Map<String, String> context) {
		if (!keyId.equals(wrappedKey.kmsKeyId())) {
			throw new KeyManagementException("Data key was wrapped under a key-encryption key this KMS does not hold");
		}
		byte[] in = wrappedKey.ciphertext();
		int headerLength = 1 + Aes256Gcm.NONCE_BYTES;
		if (in.length < headerLength + Aes256Gcm.TAG_BYTES || in[0] != FORMAT_V1) {
			throw new KeyManagementException("Wrapped data key is malformed");
		}
		byte[] nonce = Arrays.copyOfRange(in, 1, headerLength);
		try {
			return Aes256Gcm.open(kek, nonce, associatedData(wrappedKey.kmsKeyId(), context), in, headerLength,
					in.length - headerLength);
		} catch (AEADBadTagException e) {
			throw new KeyManagementException("Wrapped data key failed authentication");
		}
	}

	private static byte[] associatedData(String keyId, Map<String, String> context) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(DOMAIN);
		KmsContext.writeString(out, keyId);
		out.writeBytes(KmsContext.canonicalBytes(context));
		return out.toByteArray();
	}
}
