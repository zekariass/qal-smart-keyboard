package com.qalsmartkeyboard.languages.pack;

import java.util.Arrays;

/** A container that passed every verification step. Holds the container bytes (not copied). */
public final class VerifiedPack {

	private final byte[] container;
	private final TrustedKey key;
	private final byte[] signature;
	private final int manifestOffset;
	private final int manifestLength;
	private final int artifactOffset;
	private final int artifactLength;
	private final PackManifest manifest;

	VerifiedPack(byte[] container, TrustedKey key, byte[] signature, int manifestOffset, int manifestLength,
			int artifactOffset, int artifactLength, PackManifest manifest) {
		this.container = container;
		this.key = key;
		this.signature = signature;
		this.manifestOffset = manifestOffset;
		this.manifestLength = manifestLength;
		this.artifactOffset = artifactOffset;
		this.artifactLength = artifactLength;
		this.manifest = manifest;
	}

	public PackManifest manifest() {
		return manifest;
	}

	/** The key that signed the manifest (its status at verification time). */
	public TrustedKey key() {
		return key;
	}

	public byte[] signature() {
		return signature.clone();
	}

	/** The exact signed manifest bytes. */
	public byte[] manifestBytes() {
		return Arrays.copyOfRange(container, manifestOffset, manifestOffset + manifestLength);
	}

	/** The artifact, whose size and SHA-256 match the manifest. */
	public byte[] artifact() {
		return Arrays.copyOfRange(container, artifactOffset, artifactOffset + artifactLength);
	}

	public int artifactLength() {
		return artifactLength;
	}

	/** The whole container. */
	public byte[] container() {
		return container.clone();
	}

	public int containerLength() {
		return container.length;
	}
}
