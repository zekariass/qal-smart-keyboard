package com.qalsmartkeyboard.languages;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for {@link ArtifactUrlSigner}: resolves {@code s3://bucket/key} to a deterministic fake HTTPS URL and
 * records what it was asked to sign. {@code ftp://} and other schemes are refused like the real adapter refuses them.
 */
final class FakeArtifactUrlSigner implements ArtifactUrlSigner {

	static final String HOST = "https://packs.fake.invalid/";
	private final List<String> requested = new CopyOnWriteArrayList<>();

	@Override
	public URI sign(String artifactUri) {
		requested.add(artifactUri);
		if (artifactUri != null && artifactUri.startsWith("s3://qal-language-packs/")) {
			return URI.create(HOST + artifactUri.substring("s3://qal-language-packs/".length()) + "?sig=fake&expires=900");
		}
		if (S3PresignedArtifactUrlSigner.isHttps(artifactUri)) {
			return URI.create(artifactUri);
		}
		throw new IllegalArgumentException("Unsupported artifact location");
	}

	List<String> requested() {
		return List.copyOf(requested);
	}

	void reset() {
		requested.clear();
	}
}
