package com.qalsmartkeyboard.languages;

import java.net.URI;

/**
 * Used when no object-store credentials are configured (development, tests without a signer). Fails closed: it never
 * invents or leaks a location. Public {@code https://} locations need no signature and still work.
 */
final class UnconfiguredArtifactUrlSigner implements ArtifactUrlSigner {

	@Override
	public URI sign(String artifactUri) {
		if (S3PresignedArtifactUrlSigner.isHttps(artifactUri)) {
			return URI.create(artifactUri);
		}
		throw new IllegalStateException(
				"Pack download URLs are not configured: set qal.languages.artifacts.endpoint, access-key and secret-key");
	}
}
