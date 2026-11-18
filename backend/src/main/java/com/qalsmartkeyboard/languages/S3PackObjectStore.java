package com.qalsmartkeyboard.languages;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Pack container storage on S3 or an S3-compatible store (MinIO) with AWS Signature Version 4 header authentication
 * ({@code PUT}/{@code GET} object, JDK HTTP client and crypto only, the same SigV4 primitives as
 * {@link S3PresignedArtifactUrlSigner}). Uses the write credential of {@code qal.languages.publishing.storage}; the
 * API's URL signer keeps its read-only one. Only the configured pack bucket is ever addressed.
 */
final class S3PackObjectStore implements PackObjectStore {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
			.withZone(ZoneOffset.UTC);
	private static final String ALGORITHM = "AWS4-HMAC-SHA256";
	private static final String SIGNED_HEADERS = "host;x-amz-content-sha256;x-amz-date";

	private final URI endpoint;
	private final String bucket;
	private final String region;
	private final String accessKey;
	private final byte[] secretKey;
	private final boolean pathStyle;
	private final InstantSource clock;
	private final HttpClient http;

	S3PackObjectStore(String endpoint, String bucket, String region, boolean pathStyle, String accessKey, String secretKey,
			InstantSource clock) {
		this.endpoint = URI.create(endpoint.trim());
		if (this.endpoint.getHost() == null
				|| !("http".equals(this.endpoint.getScheme()) || "https".equals(this.endpoint.getScheme()))) {
			throw new IllegalStateException("the pack object store endpoint must be an http(s) URL");
		}
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalStateException("qal.languages.artifacts.bucket is required");
		}
		this.bucket = bucket;
		this.region = region;
		this.pathStyle = pathStyle;
		this.accessKey = accessKey.trim();
		this.secretKey = secretKey.trim().getBytes(StandardCharsets.UTF_8);
		this.clock = clock;
		this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
				.build();
	}

	@Override
	public String put(String key, byte[] bytes) {
		requireKey(key);
		HttpResponse<Void> response = send("PUT", objectPath(key), bytes, HttpResponse.BodyHandlers.discarding());
		if (response.statusCode() / 100 != 2) {
			throw new IllegalStateException("The pack object store refused the upload (HTTP " + response.statusCode() + ")");
		}
		return "s3://" + bucket + "/" + key;
	}

	@Override
	public byte[] get(String location, long maxBytes) {
		String prefix = "s3://" + bucket + "/";
		if (location == null || !location.startsWith(prefix)) {
			throw new IllegalStateException("The pack location is not in the pack bucket");
		}
		String key = location.substring(prefix.length());
		requireKey(key);
		HttpResponse<InputStream> response = send("GET", objectPath(key), new byte[0],
				HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream in = response.body()) {
			if (response.statusCode() != 200) {
				throw new IllegalStateException("The pack object could not be read (HTTP " + response.statusCode() + ")");
			}
			byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
			if (data.length > maxBytes) {
				throw new IllegalStateException("The stored pack object is larger than the container limit");
			}
			return data;
		}
		catch (IOException e) {
			throw new IllegalStateException("The pack object could not be read", e);
		}
	}

	/** Creates the bucket if it does not exist (development and tests; production buckets are provisioned). */
	void createBucketIfMissing() {
		String path = basePath() + "/" + S3PresignedArtifactUrlSigner.encode(bucket, false);
		HttpResponse<Void> response = send("PUT", pathStyle ? path : basePath() + "/", new byte[0],
				HttpResponse.BodyHandlers.discarding());
		if (response.statusCode() / 100 != 2 && response.statusCode() != 409) {
			throw new IllegalStateException("Could not create the pack bucket (HTTP " + response.statusCode() + ")");
		}
	}

	private String objectPath(String key) {
		return basePath() + (pathStyle ? "/" + S3PresignedArtifactUrlSigner.encode(bucket, false) : "") + "/"
				+ S3PresignedArtifactUrlSigner.encode(key, true);
	}

	private String basePath() {
		return endpoint.getRawPath() == null ? "" : endpoint.getRawPath().replaceAll("/+$", "");
	}

	private <T> HttpResponse<T> send(String method, String canonicalUri, byte[] payload,
			HttpResponse.BodyHandler<T> handler) {
		String stamp = STAMP.format(clock.instant());
		String date = stamp.substring(0, 8);
		String scope = date + "/" + region + "/s3/aws4_request";
		String hostName = pathStyle ? endpoint.getHost() : bucket + "." + endpoint.getHost();
		String host = endpoint.getPort() == -1 ? hostName : hostName + ":" + endpoint.getPort();
		String payloadHash = sha256Hex(payload);

		String canonicalRequest = method + "\n" + canonicalUri + "\n\nhost:" + host + "\nx-amz-content-sha256:" + payloadHash
				+ "\nx-amz-date:" + stamp + "\n\n" + SIGNED_HEADERS + "\n" + payloadHash;
		String stringToSign = ALGORITHM + "\n" + stamp + "\n" + scope + "\n"
				+ S3PresignedArtifactUrlSigner.sha256Hex(canonicalRequest);
		byte[] signingKey = S3PresignedArtifactUrlSigner.hmac(S3PresignedArtifactUrlSigner.hmac(
				S3PresignedArtifactUrlSigner.hmac(S3PresignedArtifactUrlSigner
						.hmac(S3PresignedArtifactUrlSigner.concat("AWS4", secretKey), date), region), "s3"),
				"aws4_request");
		String signature = HexFormat.of().formatHex(S3PresignedArtifactUrlSigner.hmac(signingKey, stringToSign));
		String authorization = ALGORITHM + " Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + SIGNED_HEADERS
				+ ", Signature=" + signature;

		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.getScheme() + "://" + host + canonicalUri))
				.timeout(Duration.ofSeconds(60)).header("x-amz-date", stamp).header("x-amz-content-sha256", payloadHash)
				.header("Authorization", authorization).header("Content-Type", "application/octet-stream")
				.method(method, payload.length == 0 && "GET".equals(method) ? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofByteArray(payload))
				.build();
		try {
			return http.send(request, handler);
		}
		catch (IOException e) {
			throw new IllegalStateException("The pack object store is unreachable", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while calling the pack object store", e);
		}
	}

	private static void requireKey(String key) {
		if (key == null || key.isEmpty() || key.startsWith("/") || key.contains("..") || key.contains("//")
				|| key.contains("?") || key.contains("#")) {
			throw new IllegalArgumentException("Invalid object key");
		}
	}

	private static String sha256Hex(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
