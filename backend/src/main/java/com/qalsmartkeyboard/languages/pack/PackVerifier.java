package com.qalsmartkeyboard.languages.pack;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Verifies a container exactly as the core's {@code verify_pack} does (pack spec section 6), in the same order and with
 * the same reason codes, stopping at the first failure:
 *
 * <ol>
 * <li>container length within the limit ({@code TOO_LARGE});</li>
 * <li>magic ({@code MALFORMED});</li>
 * <li>format version 1 ({@code UNSUPPORTED_FORMAT_VERSION}; too short to hold it is {@code MALFORMED});</li>
 * <li>key id length and syntax, 64 signature bytes ({@code MALFORMED});</li>
 * <li>manifest length: non-zero ({@code MALFORMED}), within the limit ({@code TOO_LARGE}), present ({@code MALFORMED});</li>
 * <li>artifact length within the limit ({@code TOO_LARGE}) and exactly the remaining bytes ({@code MALFORMED});</li>
 * <li>key trusted ({@code UNKNOWN_KEY}), not revoked ({@code REVOKED_KEY}), strict Ed25519 signature over
 * {@code "QAL-PACK-MANIFEST-v1\0" || manifest} ({@code BAD_SIGNATURE});</li>
 * <li>manifest JSON and members ({@code INVALID_MANIFEST} / {@code UNSUPPORTED_RESOURCE_TYPE});</li>
 * <li>{@code signatureKeyId} equals the header key id ({@code KEY_MISMATCH});</li>
 * <li>artifact length equals {@code sizeBytes} ({@code SIZE_MISMATCH});</li>
 * <li>SHA-256 of the artifact equals {@code sha256} ({@code CHECKSUM_MISMATCH}).</li>
 * </ol>
 * No manifest member is read before the signature has verified. Lengths are checked against the limits and the buffer
 * before use; nothing is allocated from an unchecked length.
 */
public final class PackVerifier {

	private PackVerifier() {
	}

	/**
	 * Container sections located by steps 1-6 (framing only: nothing is authenticated).
	 *
	 * @param keyId header key id bytes
	 */
	public record Framing(byte[] keyId, byte[] signature, int manifestOffset, int manifestLength, int artifactOffset,
			int artifactLength) {
	}

	public static VerifiedPack verify(byte[] bytes, KeyRing keys, PackLimits limits) {
		Framing f = framing(bytes, limits);
		TrustedKey key = keys.lookup(f.keyId())
				.orElseThrow(() -> reject(PackRejectReason.UNKNOWN_KEY, "signing key is not trusted"));
		if (key.status() == KeyStatus.REVOKED) {
			throw reject(PackRejectReason.REVOKED_KEY, "signing key is revoked");
		}
		byte[] manifestBytes = Arrays.copyOfRange(bytes, f.manifestOffset(), f.manifestOffset() + f.manifestLength());
		if (!Ed25519.verifyStrict(key.publicKey(), PackFormat.signingInput(manifestBytes), f.signature())) {
			throw reject(PackRejectReason.BAD_SIGNATURE, "manifest signature is invalid");
		}

		// Authenticated from here on.
		PackManifest manifest = ManifestParser.parse(manifestBytes);
		if (!manifest.signatureKeyId().equals(key.keyId())) {
			throw reject(PackRejectReason.KEY_MISMATCH, "manifest signatureKeyId differs from the header key id");
		}
		if (!manifest.sizeBytes().equals(BigInteger.valueOf(f.artifactLength()))) {
			throw reject(PackRejectReason.SIZE_MISMATCH, "artifact size differs from sizeBytes");
		}
		String sha256 = sha256Hex(bytes, f.artifactOffset(), f.artifactLength());
		if (!sha256.equals(manifest.sha256())) {
			throw reject(PackRejectReason.CHECKSUM_MISMATCH, "artifact SHA-256 differs from sha256");
		}
		return new VerifiedPack(bytes, key, f.signature(), f.manifestOffset(), f.manifestLength(), f.artifactOffset(),
				f.artifactLength(), manifest);
	}

	/** Steps 1-6 only: size limit and framing. Used to read back a container whose integrity is anchored elsewhere. */
	public static Framing framing(byte[] bytes, PackLimits limits) {
		if (bytes.length > limits.maxContainerBytes()) {
			throw reject(PackRejectReason.TOO_LARGE, "container exceeds the size limit");
		}
		Reader r = new Reader(bytes);
		if (!r.has(8) || !Arrays.equals(bytes, 0, 8, PackFormat.MAGIC, 0, 8)) {
			throw reject(PackRejectReason.MALFORMED, "not a pack container (bad magic)");
		}
		r.skip(8);
		int version = (int) r.uint(2);
		if (version != PackFormat.FORMAT_VERSION) {
			throw reject(PackRejectReason.UNSUPPORTED_FORMAT_VERSION, "unsupported container format version");
		}
		int keyIdLength = (int) r.uint(1);
		int keyIdStart = r.take(keyIdLength);
		byte[] keyId = Arrays.copyOfRange(bytes, keyIdStart, keyIdStart + keyIdLength);
		if (!PackFormat.isValidKeyId(keyId)) {
			throw reject(PackRejectReason.MALFORMED, "invalid key id in header");
		}
		int signatureStart = r.take(Ed25519.SIGNATURE_BYTES);
		byte[] signature = Arrays.copyOfRange(bytes, signatureStart, signatureStart + Ed25519.SIGNATURE_BYTES);

		long manifestLength = r.uint(4);
		if (manifestLength == 0) {
			throw reject(PackRejectReason.MALFORMED, "empty manifest");
		}
		if (manifestLength > limits.maxManifestBytes()) {
			throw reject(PackRejectReason.TOO_LARGE, "manifest exceeds the size limit");
		}
		int manifestStart = r.take((int) manifestLength);

		BigInteger artifactLength = new BigInteger(1, r.bytes(8));
		if (artifactLength.compareTo(BigInteger.valueOf(limits.maxArtifactBytes())) > 0) {
			throw reject(PackRejectReason.TOO_LARGE, "artifact exceeds the size limit");
		}
		long remaining = bytes.length - r.pos;
		int cmp = Long.compare(remaining, artifactLength.longValueExact());
		if (cmp < 0) {
			throw truncated();
		}
		if (cmp > 0) {
			throw reject(PackRejectReason.MALFORMED, "trailing bytes after the artifact");
		}
		return new Framing(keyId, signature, manifestStart, (int) manifestLength, r.pos, (int) remaining);
	}

	static String sha256Hex(byte[] bytes, int offset, int length) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(bytes, offset, length);
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	public static String sha256Hex(byte[] bytes) {
		return sha256Hex(bytes, 0, bytes.length);
	}

	private static PackRejectedException reject(PackRejectReason reason, String detail) {
		return new PackRejectedException(reason, detail);
	}

	private static PackRejectedException truncated() {
		return reject(PackRejectReason.MALFORMED, "container is truncated");
	}

	/** Bounds-checked big-endian reader over untrusted bytes; running past the end is {@code MALFORMED}. */
	private static final class Reader {
		private final byte[] buf;
		private int pos;

		Reader(byte[] buf) {
			this.buf = buf;
		}

		boolean has(int n) {
			return n >= 0 && buf.length - pos >= n;
		}

		void skip(int n) {
			take(n);
		}

		/** Advances by {@code n} and returns the start offset. */
		int take(int n) {
			if (!has(n)) {
				throw truncated();
			}
			int start = pos;
			pos += n;
			return start;
		}

		long uint(int n) {
			int start = take(n);
			long v = 0;
			for (int i = 0; i < n; i++) {
				v = (v << 8) | (buf[start + i] & 0xff);
			}
			return v;
		}

		byte[] bytes(int n) {
			int start = take(n);
			return Arrays.copyOfRange(buf, start, start + n);
		}
	}
}
