package com.qalsmartkeyboard.languages.pack;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Assembles and signs format-v1 containers (pack spec sections 3 and 4). Signing is deterministic, so the same key,
 * key id, manifest bytes and artifact always give the same container (the test vectors' {@code input} sections check this
 * byte for byte).
 */
public final class PackWriter {

	private PackWriter() {
	}

	/** Signs {@code SIGNATURE_CONTEXT || manifest} with {@code signer} and assembles the container. */
	public static byte[] sign(PackSigner signer, byte[] manifest, byte[] artifact) {
		byte[] signature = signer.sign(PackFormat.signingInput(manifest));
		return assemble(signer.keyId(), signature, manifest, artifact);
	}

	public static byte[] assemble(String keyId, byte[] signature, byte[] manifest, byte[] artifact) {
		byte[] id = keyId.getBytes(StandardCharsets.US_ASCII);
		if (!PackFormat.isValidKeyId(id)) {
			throw new IllegalArgumentException("key id must be 1-120 chars of [A-Za-z0-9._-]");
		}
		if (signature.length != Ed25519.SIGNATURE_BYTES) {
			throw new IllegalArgumentException("signature must be 64 bytes");
		}
		if (manifest.length == 0 || manifest[0] != '{') {
			throw new IllegalArgumentException("manifest must be a JSON object");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(87 + id.length + manifest.length + artifact.length);
		out.writeBytes(PackFormat.MAGIC);
		out.writeBytes(ByteBuffer.allocate(2).putShort((short) PackFormat.FORMAT_VERSION).array());
		out.write(id.length);
		out.writeBytes(id);
		out.writeBytes(signature);
		out.writeBytes(ByteBuffer.allocate(4).putInt(manifest.length).array());
		out.writeBytes(manifest);
		out.writeBytes(ByteBuffer.allocate(8).putLong(artifact.length).array());
		out.writeBytes(artifact);
		return out.toByteArray();
	}
}
