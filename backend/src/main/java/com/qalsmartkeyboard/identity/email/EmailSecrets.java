package com.qalsmartkeyboard.identity.email;

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
 * Keyed hashing and sealing for {@code email_challenges} (V22), derived from the identity server secret with
 * HMAC-SHA-256 under labels distinct from phone OTP's ({@code qal.email.*} vs {@code qal.otp.*}):
 *
 * <ul>
 * <li>{@code email_hash} = hex HMAC(K_addr, normalised address): finds the challenges of one address (cooldown,
 * supersession, rate-limit key) without storing it in clear.</li>
 * <li>{@code code_hash} = {@code "v1:"} + hex HMAC(K_code, challengeId || purpose || code), compared in constant time.
 * Keyed, so a database leak does not allow brute-forcing the 10^6 code space offline.</li>
 * <li>{@code payload_ciphertext} = {@code 0x01 || nonce(12) || AES-256-GCM(K_enc, address \u0000 displayName) ||
 * tag(16)}, challenge id as associated data. Only pending sign-ups have one.</li>
 * </ul>
 */
final class EmailSecrets {

	static final int MIN_SECRET_BYTES = 32;
	private static final String CODE_HASH_PREFIX = "v1:";
	private static final byte FORMAT_V1 = 0x01;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final byte[] AAD_DOMAIN = "qal.email.payload.v1".getBytes(StandardCharsets.US_ASCII);

	/** What a pending sign-up seals. */
	record Payload(String address, String displayName) {

		@Override
		public String toString() {
			return "Payload[<redacted>]";
		}
	}

	private final SecretKeySpec addressHashKey;
	private final SecretKeySpec codeHashKey;
	private final SecretKeySpec payloadKey;
	private final SecureRandom random;

	/** @param secret at least 32 bytes; copied, the caller should wipe its array */
	EmailSecrets(byte[] secret, SecureRandom random) {
		if (secret == null || secret.length < MIN_SECRET_BYTES) {
			throw new IllegalArgumentException("The identity secret must be at least " + MIN_SECRET_BYTES + " bytes");
		}
		this.random = Objects.requireNonNull(random, "random");
		this.addressHashKey = derive(secret, "qal.email.address-hash.v1", "HmacSHA256");
		this.codeHashKey = derive(secret, "qal.email.code-hash.v1", "HmacSHA256");
		this.payloadKey = derive(secret, "qal.email.payload-enc.v1", "AES");
	}

	String addressHash(String normalizedAddress) {
		return HexFormat.of().formatHex(hmac(addressHashKey, normalizedAddress.getBytes(StandardCharsets.UTF_8)));
	}

	String codeHash(UUID challengeId, String purpose, String code) {
		return CODE_HASH_PREFIX + HexFormat.of().formatHex(codeMac(challengeId, purpose, code));
	}

	/** Constant-time comparison; an unknown stored format never matches (fail closed). */
	boolean codeMatches(UUID challengeId, String purpose, String code, String storedHash) {
		if (code == null || storedHash == null || !storedHash.startsWith(CODE_HASH_PREFIX)) {
			return false;
		}
		byte[] stored;
		try {
			stored = HexFormat.of().parseHex(storedHash.substring(CODE_HASH_PREFIX.length()));
		}
		catch (IllegalArgumentException e) {
			return false;
		}
		return MessageDigest.isEqual(stored, codeMac(challengeId, purpose, code));
	}

	byte[] seal(UUID challengeId, Payload payload) {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		String plain = payload.address() + '\u0000' + (payload.displayName() == null ? "" : payload.displayName());
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, payloadKey, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(aad(challengeId));
			byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length).put(FORMAT_V1).put(nonce).put(sealed).array();
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Cannot seal the sign-up payload", e);
		}
	}

	/** @throws IllegalStateException if the value was not sealed for this challenge with the current secret */
	Payload open(UUID challengeId, byte[] sealed) {
		if (sealed == null || sealed.length < 1 + NONCE_BYTES + TAG_BITS / 8 || sealed[0] != FORMAT_V1) {
			throw new IllegalStateException("Sign-up payload ciphertext is malformed");
		}
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, payloadKey, new GCMParameterSpec(TAG_BITS, sealed, 1, NONCE_BYTES));
			cipher.updateAAD(aad(challengeId));
			String plain = new String(cipher.doFinal(sealed, 1 + NONCE_BYTES, sealed.length - 1 - NONCE_BYTES),
					StandardCharsets.UTF_8);
			int separator = plain.indexOf('\u0000');
			String name = plain.substring(separator + 1);
			return new Payload(plain.substring(0, separator), name.isEmpty() ? null : name);
		}
		catch (GeneralSecurityException e) {
			// no cause: the secret may have rotated or the row was tampered with
			throw new IllegalStateException("Sign-up payload ciphertext could not be opened");
		}
	}

	private byte[] codeMac(UUID challengeId, String purpose, String code) {
		byte[] purposeBytes = purpose.getBytes(StandardCharsets.US_ASCII);
		byte[] codeBytes = code.getBytes(StandardCharsets.US_ASCII);
		return hmac(codeHashKey, ByteBuffer.allocate(16 + 1 + purposeBytes.length + codeBytes.length)
				.putLong(challengeId.getMostSignificantBits()).putLong(challengeId.getLeastSignificantBits())
				.put((byte) purposeBytes.length).put(purposeBytes).put(codeBytes).array());
	}

	private static byte[] aad(UUID challengeId) {
		return ByteBuffer.allocate(AAD_DOMAIN.length + 16).put(AAD_DOMAIN).putLong(challengeId.getMostSignificantBits())
				.putLong(challengeId.getLeastSignificantBits()).array();
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
		return "EmailSecrets[<redacted>]";
	}
}
