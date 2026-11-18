package com.qalsmartkeyboard.languages;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 query-string pre-signing of {@code GET} object requests, for S3 and S3-compatible stores
 * (MinIO). Implemented directly (JDK crypto only) instead of pulling in the AWS SDK; it is checked against the worked
 * example in the AWS documentation.
 *
 * <p>Only {@code s3://<configured bucket>/<key>} locations are signed, so a bad row cannot make the API hand out a
 * URL for another bucket (exports, audio). Only the {@code host} header is signed; the payload is
 * {@code UNSIGNED-PAYLOAD}.
 */
final class S3PresignedArtifactUrlSigner implements ArtifactUrlSigner {

	static final long MAX_EXPIRES_SECONDS = 604_800;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
			.withZone(ZoneOffset.UTC);
	private static final String ALGORITHM = "AWS4-HMAC-SHA256";

	private final URI endpoint;
	private final String bucket;
	private final String region;
	private final String accessKey;
	private final byte[] secretKey;
	private final long ttlSeconds;
	private final boolean pathStyle;
	private final InstantSource clock;

	S3PresignedArtifactUrlSigner(LanguagesProperties.Artifacts settings, InstantSource clock) {
		this.endpoint = URI.create(settings.endpoint().trim());
		if (endpoint.getHost() == null || endpoint.getScheme() == null
				|| !(endpoint.getScheme().equals("http") || endpoint.getScheme().equals("https"))) {
			throw new IllegalStateException("qal.languages.artifacts.endpoint must be an http(s) URL");
		}
		this.bucket = settings.bucket();
		this.region = settings.region();
		this.accessKey = settings.accessKey().trim();
		this.secretKey = settings.secretKey().trim().getBytes(StandardCharsets.UTF_8);
		Duration ttl = settings.urlTtl();
		if (ttl.isNegative() || ttl.isZero() || ttl.toSeconds() > MAX_EXPIRES_SECONDS || ttl.toSeconds() < 1) {
			throw new IllegalStateException("qal.languages.artifacts.url-ttl must be between 1 second and 7 days");
		}
		this.ttlSeconds = ttl.toSeconds();
		this.pathStyle = settings.pathStyle();
		this.clock = clock;
	}

	static boolean isHttps(String uri) {
		return uri != null && uri.regionMatches(true, 0, "https://", 0, 8) && uri.length() > 8;
	}

	@Override
	public URI sign(String artifactUri) {
		if (isHttps(artifactUri)) {
			return URI.create(artifactUri);
		}
		if (artifactUri == null || !artifactUri.startsWith("s3://")) {
			throw new IllegalArgumentException("Unsupported artifact location");
		}
		String rest = artifactUri.substring("s3://".length());
		int slash = rest.indexOf('/');
		if (slash <= 0 || slash == rest.length() - 1 || !rest.substring(0, slash).equals(bucket)) {
			throw new IllegalArgumentException("Artifact location is not in the pack bucket");
		}
		String key = rest.substring(slash + 1);
		if (key.contains("..") || key.contains("//") || key.contains("?") || key.contains("#")) {
			throw new IllegalArgumentException("Invalid artifact key");
		}
		return presign(key);
	}

	private URI presign(String key) {
		String stamp = STAMP.format(clock.instant());
		String date = stamp.substring(0, 8);
		String scope = date + "/" + region + "/s3/aws4_request";

		String hostName = pathStyle ? endpoint.getHost() : bucket + "." + endpoint.getHost();
		String host = endpoint.getPort() == -1 ? hostName : hostName + ":" + endpoint.getPort();
		String basePath = endpoint.getRawPath() == null ? "" : endpoint.getRawPath().replaceAll("/+$", "");
		String canonicalUri = basePath + (pathStyle ? "/" + encode(bucket, false) : "") + "/" + encode(key, true);

		// Query parameters in canonical order (sorted by name, values percent-encoded).
		String query = "X-Amz-Algorithm=" + ALGORITHM + "&X-Amz-Credential=" + encode(accessKey + "/" + scope, false)
				+ "&X-Amz-Date=" + stamp + "&X-Amz-Expires=" + ttlSeconds + "&X-Amz-SignedHeaders=host";
		String canonicalRequest = "GET\n" + canonicalUri + "\n" + query + "\nhost:" + host + "\n\nhost\nUNSIGNED-PAYLOAD";
		String stringToSign = ALGORITHM + "\n" + stamp + "\n" + scope + "\n" + sha256Hex(canonicalRequest);

		byte[] signingKey = hmac(hmac(hmac(hmac(concat("AWS4", secretKey), date), region), "s3"), "aws4_request");
		String signature = HexFormat.of().formatHex(hmac(signingKey, stringToSign));
		return URI.create(endpoint.getScheme() + "://" + host + canonicalUri + "?" + query + "&X-Amz-Signature=" + signature);
	}

	/** RFC 3986 percent-encoding as SigV4 requires; {@code keepSlash} for object keys. */
	static String encode(String value, boolean keepSlash) {
		StringBuilder out = new StringBuilder();
		for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
			char c = (char) (b & 0xff);
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
					|| c == '.' || c == '~' || (keepSlash && c == '/')) {
				out.append(c);
			}
			else {
				out.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xff));
			}
		}
		return out.toString();
	}

	static byte[] concat(String prefix, byte[] secret) {
		byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
		byte[] out = new byte[p.length + secret.length];
		System.arraycopy(p, 0, out, 0, p.length);
		System.arraycopy(secret, 0, out, p.length, secret.length);
		return out;
	}

	static byte[] hmac(byte[] key, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 unavailable", e);
		}
	}

	static String sha256Hex(String data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
