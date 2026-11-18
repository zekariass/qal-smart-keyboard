package com.qalsmartkeyboard.languages.pack;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

/**
 * Ed25519 (RFC 8032, "pure") on the JDK ({@code Signature "Ed25519"}, JEP 339) with the <b>strict</b> verification rules
 * the pack format requires (spec section 4, {@code ed25519-dalek verify_strict}):
 * <ul>
 * <li>{@code S} must be canonical ({@code S < L}), so signatures are not malleable;</li>
 * <li>{@code R} must be a canonical encoding ({@code y < p}) of a curve point that is not of small order;</li>
 * <li>the public key must be a canonical encoding of a curve point that is not of small order.</li>
 * </ul>
 * These checks are made here explicitly instead of relying on the JDK's behaviour for edge cases; the JDK then performs
 * the group equation. The cross-language vectors in {@code core/tests/vectors/pack-v1/} (including the malleable
 * signature) pin the result.
 */
public final class Ed25519 {

	public static final int PUBLIC_KEY_BYTES = 32;
	public static final int SEED_BYTES = 32;
	public static final int SIGNATURE_BYTES = 64;

	private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
	/** Group order L = 2^252 + 27742317777372353535851937790883648493. */
	private static final BigInteger L = BigInteger.TWO.pow(252)
			.add(new BigInteger("27742317777372353535851937790883648493"));
	/** d = -121665 / 121666 mod p. */
	private static final BigInteger D = BigInteger.valueOf(-121665)
			.multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
	/** sqrt(-1) = 2^((p-1)/4) mod p. */
	private static final BigInteger SQRT_M1 = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
	/** y of the order-8 points (the other order-8 y is p - Y8). */
	private static final BigInteger Y8 = new BigInteger(
			"2707385501144840649318225287225658788936804267575313519463743609750303402022");
	/**
	 * The y coordinates of all eight small-order points: identity (1), order 2 (p-1), order 4 (0), order 8 (Y8, p-Y8).
	 * Each y with either x sign covers every small-order point.
	 */
	private static final Set<BigInteger> SMALL_ORDER_Y = Set.of(BigInteger.ONE, P.subtract(BigInteger.ONE), BigInteger.ZERO,
			Y8, P.subtract(Y8));

	/** DER prefix of an Ed25519 SubjectPublicKeyInfo (RFC 8410). */
	private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
	/** DER prefix of an Ed25519 PKCS#8 v1 private key (RFC 8410). */
	private static final byte[] PKCS8_PREFIX = HexFormat.of().parseHex("302e020100300506032b657004220420");

	private Ed25519() {
	}

	/** A usable verification key: 32 bytes, canonical, on the curve, not of small order (pack spec section 7.1). */
	public static boolean isValidPublicKey(byte[] raw) {
		return raw != null && raw.length == PUBLIC_KEY_BYTES && decodesToLargeOrderPoint(raw);
	}

	/**
	 * Strict verification of {@code signature} over {@code message}. Never throws for bad input; returns false.
	 */
	public static boolean verifyStrict(byte[] publicKey, byte[] message, byte[] signature) {
		if (signature == null || signature.length != SIGNATURE_BYTES || message == null || !isValidPublicKey(publicKey)) {
			return false;
		}
		byte[] r = Arrays.copyOfRange(signature, 0, 32);
		byte[] s = Arrays.copyOfRange(signature, 32, 64);
		if (littleEndian(s).compareTo(L) >= 0) {
			return false; // non-canonical S (malleability)
		}
		if (!decodesToLargeOrderPoint(r)) {
			return false; // non-canonical, off-curve or small-order R
		}
		try {
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(publicKey(publicKey));
			verifier.update(message);
			return verifier.verify(signature);
		}
		catch (GeneralSecurityException | RuntimeException e) {
			return false;
		}
	}

	/** Signs {@code message} with {@code key} (deterministic, RFC 8032). */
	public static byte[] sign(PrivateKey key, byte[] message) {
		try {
			Signature signer = Signature.getInstance("Ed25519");
			signer.initSign(key);
			signer.update(message);
			return signer.sign();
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Ed25519 signing failed", e);
		}
	}

	/**
	 * The key pair of a 32-byte RFC 8032 seed. The JDK has no API to derive the public key from a private key, so the
	 * generator is fed the seed as its "randomness" (it reads exactly 32 bytes for the private key).
	 */
	public static KeyPair keyPairFromSeed(byte[] seed) {
		if (seed == null || seed.length != SEED_BYTES) {
			throw new IllegalArgumentException("An Ed25519 seed has 32 bytes");
		}
		byte[] copy = seed.clone();
		SecureRandom fixed = new SecureRandom() {
			private static final long serialVersionUID = 1L;
			private boolean used;

			@Override
			public void nextBytes(byte[] bytes) {
				if (used || bytes.length != SEED_BYTES) {
					throw new IllegalStateException("unexpected randomness request");
				}
				used = true;
				System.arraycopy(copy, 0, bytes, 0, SEED_BYTES);
				Arrays.fill(copy, (byte) 0);
			}
		};
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
			generator.initialize(NamedParameterSpec.ED25519, fixed);
			KeyPair pair = generator.generateKeyPair();
			byte[] expected = privateKey(seed).getEncoded();
			if (!Arrays.equals(expected, pair.getPrivate().getEncoded())) {
				throw new IllegalStateException("Ed25519 key derivation did not use the seed");
			}
			return pair;
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Ed25519 unavailable", e);
		}
	}

	/** A private key from its 32-byte seed. */
	public static PrivateKey privateKey(byte[] seed) {
		if (seed == null || seed.length != SEED_BYTES) {
			throw new IllegalArgumentException("An Ed25519 seed has 32 bytes");
		}
		byte[] der = concat(PKCS8_PREFIX, seed);
		try {
			return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Not an Ed25519 seed", e);
		}
		finally {
			Arrays.fill(der, (byte) 0);
		}
	}

	/** A JDK public key from its 32 raw bytes (no validity check; see {@link #isValidPublicKey}). */
	public static PublicKey publicKey(byte[] raw) {
		if (raw == null || raw.length != PUBLIC_KEY_BYTES) {
			throw new IllegalArgumentException("An Ed25519 public key has 32 bytes");
		}
		try {
			return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(concat(SPKI_PREFIX, raw)));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalArgumentException("Not an Ed25519 public key", e);
		}
	}

	/** The 32 raw bytes of a JDK Ed25519 public key. */
	public static byte[] rawPublicKey(PublicKey key) {
		byte[] der = key.getEncoded();
		if (der == null || der.length != SPKI_PREFIX.length + PUBLIC_KEY_BYTES
				|| !Arrays.equals(der, 0, SPKI_PREFIX.length, SPKI_PREFIX, 0, SPKI_PREFIX.length)) {
			throw new IllegalArgumentException("Not an Ed25519 public key");
		}
		return Arrays.copyOfRange(der, SPKI_PREFIX.length, der.length);
	}

	// ------------------------------------------------------------------ point decoding (RFC 8032 section 5.1.3)

	/** True if {@code enc} is a canonical encoding of a curve point whose order is not small. */
	static boolean decodesToLargeOrderPoint(byte[] enc) {
		if (enc == null || enc.length != 32) {
			return false;
		}
		byte[] yBytes = enc.clone();
		boolean sign = (yBytes[31] & 0x80) != 0;
		yBytes[31] &= 0x7f;
		BigInteger y = littleEndian(yBytes);
		if (y.compareTo(P) >= 0) {
			return false; // non-canonical
		}
		if (SMALL_ORDER_Y.contains(y)) {
			return false;
		}
		BigInteger y2 = y.multiply(y).mod(P);
		BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
		BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
		BigInteger x2 = u.multiply(v.modInverse(P)).mod(P);
		if (x2.signum() == 0) {
			return !sign; // x = 0 with the sign bit set is not a valid encoding (cannot happen here: y = +-1 is excluded)
		}
		BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
		if (!x.multiply(x).mod(P).equals(x2)) {
			x = x.multiply(SQRT_M1).mod(P);
			if (!x.multiply(x).mod(P).equals(x2)) {
				return false; // not on the curve
			}
		}
		return true;
	}

	private static BigInteger littleEndian(byte[] bytes) {
		byte[] be = new byte[bytes.length];
		for (int i = 0; i < bytes.length; i++) {
			be[i] = bytes[bytes.length - 1 - i];
		}
		return new BigInteger(1, be);
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}
}
