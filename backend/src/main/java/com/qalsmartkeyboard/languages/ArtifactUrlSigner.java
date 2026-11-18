package com.qalsmartkeyboard.languages;

import java.net.URI;

/**
 * Turns the storage location kept in {@code language_resources.artifact_uri} into a URL a client can download from.
 *
 * <p>Stored forms: {@code s3://bucket/key} (object store, signed and short-lived) or {@code https://...} (a public CDN
 * URL, returned unchanged). Anything else is refused. The URL is a convenience for the transfer only: the client
 * authenticates the content with the pack signature and SHA-256, never the URL.
 */
interface ArtifactUrlSigner {

	/**
	 * @throws IllegalArgumentException if {@code artifactUri} is not a supported or allowed location
	 * @throws IllegalStateException    if signing is not configured
	 */
	URI sign(String artifactUri);
}
