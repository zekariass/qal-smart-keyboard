package com.qalsmartkeyboard.privacy;

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
import java.util.Locale;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Export archive storage on S3 or an S3-compatible store (MinIO) with AWS Signature Version 4 header authentication
 * ({@code PUT}, {@code GET}, {@code DELETE} object; JDK HTTP client and crypto only, no SDK, the same primitives as the
 * language pack store). Only the configured export bucket is ever addressed and object keys are checked, so a bad row
 * cannot make the backend touch another path.
 */
final class S3ExportObjectStore implements ExportObjectStore {

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

	S3ExportObjectStore(String endpoint, String bucket, String region, boolean pathStyle, String accessKey,
			String secretKey, InstantSource clock) {
		this.endpoint = URI.create(endpoint.trim());
		if (this.endpoint.getHost() == null
				|| !("http".equals(this.endpoint.getScheme()) || "https".equals(this.endpoint.getScheme()))) {
			throw new IllegalStateException("qal.privacy.export.storage.endpoint must be an http(s) URL");
		}
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalStateException("qal.privacy.export.storage.bucket is required");
		}
		this.bucket = bucket.trim();
		this.region = region;
		this.pathStyle = pathStyle;
		this.accessKey = accessKey.trim();
		this.secretKey = secretKey.trim().getBytes(StandardCharsets.UTF_8);
		this.clock = clock;
		this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
				.build();
	}

	@Override
	public void put(String key, byte[] bytes) {
		requireKey(key);
		HttpResponse<Void> response = send("PUT", objectPath(key), bytes, HttpResponse.BodyHandlers.discarding());
		if (response.statusCode() / 100 != 2) {
			throw new IllegalStateException("The export object store refused the upload (HTTP " + response.statusCode() + ")");
		}
	}

	@Override
	public Optional<byte[]> get(String key, long maxBytes) {
		requireKey(key);
		HttpResponse<InputStream> response = send("GET", objectPath(key), new byte[0],
				HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream in = response.body()) {
			if (response.statusCode() == 404) {
				return Optional.empty();
			}
			if (response.statusCode() != 200) {
				throw new IllegalStateException("The export object could not be read (HTTP " + response.statusCode() + ")");
			}
			byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
			if (data.length > maxBytes) {
				throw new IllegalStateException("The stored export object is larger than the archive limit");
			}
			return Optional.of(data);
		}
		catch (IOException e) {
			throw new IllegalStateException("The export object could not be read", e);
		}
	}

	@Override
	public void delete(String key) {
		requireKey(key);
		HttpResponse<Void> response = send("DELETE", objectPath(key), new byte[0], HttpResponse.BodyHandlers.discarding());
		if (response.statusCode() / 100 != 2 && response.statusCode() != 404) {
			throw new IllegalStateException("The export object store refused the delete (HTTP " + response.statusCode() + ")");
		}
	}

	/** Creates the bucket if it does not exist (development and tests; production buckets are provisioned). */
	void createBucketIfMissing() {
		String path = basePath() + "/" + encode(bucket, false);
		HttpResponse<Void> response = send("PUT", pathStyle ? path : basePath() + "/", new byte[0],
				HttpResponse.BodyHandlers.discarding());
		if (response.statusCode() / 100 != 2 && response.statusCode() != 409) {
			throw new IllegalStateException("Could not create the export bucket (HTTP " + response.statusCode() + ")");
		}
	}

	private String objectPath(String key) {
		return basePath() + (pathStyle ? "/" + encode(bucket, false) : "") + "/" + encode(key, true);
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
				+ sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
		byte[] signingKey = hmac(hmac(hmac(hmac(concat("AWS4", secretKey), date), region), "s3"), "aws4_request");
		String signature = HexFormat.of().formatHex(hmac(signingKey, stringToSign));
		String authorization = ALGORITHM + " Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + SIGNED_HEADERS
				+ ", Signature=" + signature;

		boolean noBody = payload.length == 0 && !"PUT".equals(method);
		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.getScheme() + "://" + host + canonicalUri))
				.timeout(Duration.ofSeconds(60)).header("x-amz-date", stamp).header("x-amz-content-sha256", payloadHash)
				.header("Authorization", authorization).header("Content-Type", "application/octet-stream")
				.method(method, noBody ? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofByteArray(payload))
				.build();
		try {
			return http.send(request, handler);
		}
		catch (IOException e) {
			throw new IllegalStateException("The export object store is unreachable", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while calling the export object store", e);
		}
	}

	private static void requireKey(String key) {
		if (key == null || key.isEmpty() || key.startsWith("/") || key.contains("..") || key.contains("//")
				|| key.contains("?") || key.contains("#")) {
			throw new IllegalArgumentException("Invalid object key");
		}
	}

	/** RFC 3986 percent-encoding as SigV4 requires; {@code keepSlash} for object keys. */
	private static String encode(String value, boolean keepSlash) {
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

	private static byte[] concat(String prefix, byte[] secret) {
		byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
		byte[] out = new byte[p.length + secret.length];
		System.arraycopy(p, 0, out, 0, p.length);
		System.arraycopy(secret, 0, out, p.length, secret.length);
		return out;
	}

	private static byte[] hmac(byte[] key, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 unavailable", e);
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
