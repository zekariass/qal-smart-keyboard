package com.qalsmartkeyboard.languages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.languages.LanguagesProperties.Artifacts;

class S3PresignedArtifactUrlSignerTest {

	static final InstantSource AWS_EXAMPLE_TIME = InstantSource.fixed(Instant.parse("2013-05-24T00:00:00Z"));

	static Artifacts settings(String endpoint, String bucket, Duration ttl, boolean pathStyle) {
		return new Artifacts(endpoint, bucket, "us-east-1", "AKIAIOSFODNN7EXAMPLE",
				"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", ttl, pathStyle);
	}

	/** The worked "query string authentication" GET example in the AWS S3 documentation. */
	@Test
	void reproducesTheAwsDocumentationExample() {
		var signer = new S3PresignedArtifactUrlSigner(
				settings("https://s3.amazonaws.com", "examplebucket", Duration.ofSeconds(86400), false), AWS_EXAMPLE_TIME);
		assertThat(signer.sign("s3://examplebucket/test.txt").toString()).isEqualTo(
				"https://examplebucket.s3.amazonaws.com/test.txt?X-Amz-Algorithm=AWS4-HMAC-SHA256"
						+ "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
						+ "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
						+ "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
	}

	@Test
	void pathStyleForMinioKeepsThePortAndEncodesKeys() {
		var signer = new S3PresignedArtifactUrlSigner(
				settings("http://localhost:9000", "qal-language-packs", Duration.ofMinutes(15), true), AWS_EXAMPLE_TIME);
		URI url = signer.sign("s3://qal-language-packs/am/dictionary/1.0.0 a.pack");
		assertThat(url.getScheme()).isEqualTo("http");
		assertThat(url.getHost()).isEqualTo("localhost");
		assertThat(url.getPort()).isEqualTo(9000);
		assertThat(url.getRawPath()).isEqualTo("/qal-language-packs/am/dictionary/1.0.0%20a.pack");
		assertThat(url.getRawQuery()).contains("X-Amz-Expires=900").contains("X-Amz-SignedHeaders=host")
				.containsPattern("X-Amz-Signature=[0-9a-f]{64}$");
		assertThat(url.toString()).doesNotContain("wJalrXUtnFEMI");
	}

	@Test
	void sameInputSameUrlDifferentTimeDifferentSignature() {
		Artifacts s = settings("http://localhost:9000", "b", Duration.ofMinutes(5), true);
		var a = new S3PresignedArtifactUrlSigner(s, AWS_EXAMPLE_TIME);
		var b = new S3PresignedArtifactUrlSigner(s, InstantSource.fixed(Instant.parse("2013-05-24T00:00:01Z")));
		assertThat(a.sign("s3://b/k")).isEqualTo(a.sign("s3://b/k"));
		assertThat(a.sign("s3://b/k")).isNotEqualTo(b.sign("s3://b/k"));
	}

	@Test
	void refusesOtherBucketsAndMalformedKeysAndForeignSchemes() {
		var signer = new S3PresignedArtifactUrlSigner(settings("http://localhost:9000", "qal-language-packs",
				Duration.ofMinutes(5), true), AWS_EXAMPLE_TIME);
		for (String bad : new String[] { "s3://qal-exports/a.zip", "s3://qal-language-packs", "s3://qal-language-packs/",
				"s3://qal-language-packs/../qal-exports/a", "s3://qal-language-packs/a//b", "s3://qal-language-packs/a?x=1",
				"http://cdn.example.com/a.pack", "file:///etc/passwd", "", "am/dictionary.pack" }) {
			assertThatThrownBy(() -> signer.sign(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> signer.sign(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void publicHttpsLocationsPassThroughUnsigned() {
		var signer = new S3PresignedArtifactUrlSigner(settings("http://localhost:9000", "b", Duration.ofMinutes(5), true),
				AWS_EXAMPLE_TIME);
		assertThat(signer.sign("https://cdn.example.com/packs/am.pack")).hasToString("https://cdn.example.com/packs/am.pack");
	}

	@Test
	void ttlOutsideSigV4LimitsIsRejectedAtStartup() {
		for (Duration bad : new Duration[] { Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(8),
				Duration.ofMillis(500) }) {
			assertThatThrownBy(() -> new S3PresignedArtifactUrlSigner(settings("http://x:9000", "b", bad, true),
					AWS_EXAMPLE_TIME)).isInstanceOf(IllegalStateException.class);
		}
		assertThatThrownBy(() -> new S3PresignedArtifactUrlSigner(settings("ftp://x", "b", Duration.ofMinutes(1), true),
				AWS_EXAMPLE_TIME)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void unconfiguredSignerFailsClosedForObjectStoreLocations() {
		var signer = new UnconfiguredArtifactUrlSigner();
		assertThatThrownBy(() -> signer.sign("s3://qal-language-packs/a.pack")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not configured");
		assertThat(signer.sign("https://cdn.example.com/a.pack")).hasToString("https://cdn.example.com/a.pack");
	}

	@Test
	void secretNeverAppearsInSettingsToString() {
		assertThat(settings("http://x:9000", "b", Duration.ofMinutes(1), true).toString())
				.doesNotContain("wJalrXUtnFEMI").doesNotContain("AKIAIOSFODNN7EXAMPLE");
	}
}
