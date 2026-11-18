package com.qalsmartkeyboard.languages.pack;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Endorsement of an additional signing key by an already trusted one (pack spec section 7.4), stored in
 * {@code resource_signing_keys.endorsed_by_key_id} / {@code endorsement_signature} and announced by
 * {@code GET /resource-signing-keys}:
 *
 * <pre>
 * message   = "QAL-KEY-ENDORSEMENT-v1" || 0x00 || u8 len(keyId) || keyId (ASCII) || publicKey (32 raw bytes)
 * signature = Ed25519-Sign(endorsing key, message)          (pure Ed25519, verified strictly)
 * endorsementSignature = standard base64 with padding of the 64 signature bytes
 * </pre>
 *
 * The context differs from the pack signature's ({@code QAL-PACK-MANIFEST-v1\0}), so neither kind of signature can be
 * replayed as the other. The endorsement covers the key id and the public key, not the status: hosts may apply a
 * {@code REVOKED} status from any announcement (it only removes trust) but never trust an unendorsed key.
 */
public final class KeyEndorsement {

	public static final byte[] CONTEXT = "QAL-KEY-ENDORSEMENT-v1\0".getBytes(StandardCharsets.US_ASCII);

	private KeyEndorsement() {
	}

	public static byte[] message(String keyId, byte[] publicKey) {
		byte[] id = keyId.getBytes(StandardCharsets.US_ASCII);
		if (!PackFormat.isValidKeyId(id)) {
			throw new IllegalArgumentException("key id must be 1-120 chars of [A-Za-z0-9._-]");
		}
		if (publicKey == null || publicKey.length != Ed25519.PUBLIC_KEY_BYTES) {
			throw new IllegalArgumentException("public key must be 32 bytes");
		}
		byte[] out = new byte[CONTEXT.length + 1 + id.length + publicKey.length];
		System.arraycopy(CONTEXT, 0, out, 0, CONTEXT.length);
		out[CONTEXT.length] = (byte) id.length;
		System.arraycopy(id, 0, out, CONTEXT.length + 1, id.length);
		System.arraycopy(publicKey, 0, out, CONTEXT.length + 1 + id.length, publicKey.length);
		return out;
	}

	/** The base64 endorsement of ({@code keyId}, {@code publicKey}) by {@code endorser}. */
	public static String sign(PackSigner endorser, String keyId, byte[] publicKey) {
		return Base64.getEncoder().encodeToString(endorser.sign(message(keyId, publicKey)));
	}

	public static boolean verify(byte[] endorserPublicKey, String keyId, byte[] publicKey, String endorsementSignature) {
		if (endorsementSignature == null) {
			return false;
		}
		byte[] signature;
		try {
			signature = Base64.getDecoder().decode(endorsementSignature);
		}
		catch (IllegalArgumentException e) {
			return false;
		}
		return Ed25519.verifyStrict(endorserPublicKey, message(keyId, publicKey), signature);
	}
}
