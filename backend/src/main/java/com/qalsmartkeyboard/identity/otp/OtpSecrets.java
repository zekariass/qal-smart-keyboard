package com.qalsmartkeyboard.identity.otp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyed hashing and sealing for {@code otp_challenges}, all derived from one server secret
 * ({@code qal.identity.otp.secret-key}) with HMAC-SHA-256 under distinct labels:
 *
 * <ul>
 * <li>{@code phone_hash} = hex HMAC(K_phone, E.164). Deterministic, so the challenges of one number can be found
 * (cooldown, supersession, rate-limit key) without storing the number in clear.</li>
 * <li>{@code code_hash} = {@code "v1:"} + hex HMAC(K_code, challengeId (16 bytes) || code). Keyed, so a database leak
 * does not allow brute-forcing the 10^6 code space offline; bound to the challenge id, so a hash copied to another
 * challenge never matches. Compared in constant time.</li>
 * <li>{@code phone_ciphertext} (V3) = {@code 0x01 || nonce(12) || AES-256-GCM(K_enc, E.164) || tag(16)} with the
 * challenge id as associated data. A successful verify needs the number to find or create the account, and the verify
 * request carries only the challenge id. Rows are short-lived.</li>
 * </ul>
 * Rotating the secret invalidates only the challenges in flight (minutes).
 */
final class OtpSecrets {

	static final int MIN_SECRET_BYTES = 32;
	private static final String CODE_HASH_PREFIX = "v1:";
	private static final byte FORMAT_V1 = 0x01;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final byte[] PHONE_AAD_DOMAIN = "qal.otp.phone.v1".getBytes(StandardCharsets.US_ASCII);

	private final SecretKeySpec phoneHashKey;
	private final SecretKeySpec codeHashKey;
	private final SecretKeySpec phoneEncryptionKey;
	private final SecureRandom random;

	/** @param secret at least 32 bytes; copied, the caller should wipe its array */
	OtpSecrets(byte[] secret, SecureRandom random) {
		if (secret == null || secret.length < MIN_SECRET_BYTES) {
			throw new IllegalArgumentException("OTP secret must be at least " + MIN_SECRET_BYTES + " bytes");
		}
		this.random = Objects.requireNonNull(random, "random");
		this.phoneHashKey = derive(secret, "qal.otp.phone-hash.v1", "HmacSHA256");
		this.codeHashKey = derive(secret, "qal.otp.code-hash.v1", "HmacSHA256");
		this.phoneEncryptionKey = derive(secret, "qal.otp.phone-enc.v1", "AES");
	}

	String phoneHash(String e164) {
		return HexFormat.of().formatHex(hmac(phoneHashKey, e164.getBytes(StandardCharsets.US_ASCII)));
	}

	String codeHash(UUID challengeId, String code) {
		return CODE_HASH_PREFIX + HexFormat.of().formatHex(codeMac(challengeId, code));
	}

	/** Constant-time comparison; an unknown stored format never matches (fail closed). */
	boolean codeMatches(UUID challengeId, String code, String storedHash) {
		if (storedHash == null || !storedHash.startsWith(CODE_HASH_PREFIX)) {
			return false;
		}
		byte[] stored;
		try {
			stored = HexFormat.of().parseHex(storedHash.substring(CODE_HASH_PREFIX.length()));
		}
		catch (IllegalArgumentException e) {
			return false;
		}
		return MessageDigest.isEqual(stored, codeMac(challengeId, code));
	}

	byte[] sealPhone(UUID challengeId, String e164) {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, phoneEncryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(phoneAad(challengeId));
			byte[] sealed = cipher.doFinal(e164.getBytes(StandardCharsets.US_ASCII));
			return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length).put(FORMAT_V1).put(nonce).put(sealed).array();
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Cannot seal the challenge phone number", e);
		}
	}

	/** @throws IllegalStateException if the value was not sealed for this challenge with the current secret */
	String openPhone(UUID challengeId, byte[] sealed) {
		if (sealed == null || sealed.length < 1 + NONCE_BYTES + TAG_BITS / 8 || sealed[0] != FORMAT_V1) {
			throw new IllegalStateException("Challenge phone ciphertext is malformed");
		}
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, phoneEncryptionKey, new GCMParameterSpec(TAG_BITS, sealed, 1, NONCE_BYTES));
			cipher.updateAAD(phoneAad(challengeId));
			byte[] plain = cipher.doFinal(sealed, 1 + NONCE_BYTES, sealed.length - 1 - NONCE_BYTES);
			return new String(plain, StandardCharsets.US_ASCII);
		}
		catch (GeneralSecurityException e) {
			// no cause: the secret may have rotated or the row was tampered with; never carry provider details
			throw new IllegalStateException("Challenge phone ciphertext could not be opened");
		}
	}

	private byte[] codeMac(UUID challengeId, String code) {
		byte[] codeBytes = code.getBytes(StandardCharsets.US_ASCII);
		return hmac(codeHashKey, ByteBuffer.allocate(16 + codeBytes.length).putLong(challengeId.getMostSignificantBits())
				.putLong(challengeId.getLeastSignificantBits()).put(codeBytes).array());
	}

	private static byte[] phoneAad(UUID challengeId) {
		return ByteBuffer.allocate(PHONE_AAD_DOMAIN.length + 16).put(PHONE_AAD_DOMAIN)
				.putLong(challengeId.getMostSignificantBits()).putLong(challengeId.getLeastSignificantBits()).array();
	}

	private static SecretKeySpec derive(byte[] secret, String label, String algorithm) {
		byte[] derived = hmac(new SecretKeySpec(secret, "HmacSHA256"), label.getBytes(StandardCharsets.US_ASCII));
		try {
			return new SecretKeySpec(derived, algorithm);
		}
		finally {
			Arrays.fill(derived, (byte) 0);
		}
	}

	private static byte[] hmac(SecretKeySpec key, byte[] data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			return mac.doFinal(data);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC-SHA-256 unavailable", e);
		}
	}

	@Override
	public String toString() {
		return "OtpSecrets[<redacted>]";
	}
}
