package com.qalsmartkeyboard.aigateway.adapter;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.qalsmartkeyboard.aigateway.spi.AiProvider;
import com.qalsmartkeyboard.aigateway.spi.ProviderException;

/**
 * The HTTP mechanics every JSON vendor adapter shares (ADR 0014 "Adapters", ADR 0023): Spring {@link RestClient} on one
 * JDK {@link HttpClient} per adapter (HTTP/1.1, no redirects, connect timeout from configuration), a read timeout equal
 * to the per-call deadline, error statuses mapped to {@link ProviderException} without ever reading the error body, and
 * I/O failures mapped to Timeout / Unavailable / Cancelled. Nothing is logged here and no exception carries a URL,
 * header, body or cause from the vendor.
 *
 * <p>Status mapping: 401/403 AuthenticationFailed; 402/429 QuotaExhaustedAtProvider (numeric {@code Retry-After}, else
 * {@code retry-after-ms}); 408/504 Timeout; 422/451 ContentRejected; any other 4xx/5xx Unavailable.
 */
final class JsonHttpTransport {

	/** A decoded 2xx JSON object answer. */
	record Response(Map<String, Object> json, HttpHeaders headers, long startNanos) {

		Duration elapsed() {
			return Duration.ofNanos(System.nanoTime() - startNanos);
		}

		String header(String name) {
			return headers.getFirst(name);
		}
	}

	private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
	};

	private final String providerCode;
	private final URI baseUrl;
	private final HttpClient httpClient;

	JsonHttpTransport(String providerCode, URI baseUrl, Duration connectTimeout) {
		this.providerCode = Objects.requireNonNull(providerCode, "providerCode");
		this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
		this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout).build();
	}

	/**
	 * POSTs {@code body} as JSON to {@code baseUrl + path} with the given extra headers and returns the decoded object.
	 *
	 * @throws ProviderException every failure, typed
	 * @throws IllegalArgumentException a non-positive timeout (programming error, not a provider failure)
	 */
	Response post(String path, Map<String, String> headers, Object body, Duration timeout) {
		AiProvider.requirePositive(timeout);
		if (Thread.currentThread().isInterrupted()) {
			throw new ProviderException.Cancelled(providerCode);
		}
		long start = System.nanoTime();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(timeout);
		RestClient client = RestClient.builder().requestFactory(factory).baseUrl(baseUrl.toString()).build();
		try {
			RestClient.RequestBodySpec spec = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
					.accept(MediaType.APPLICATION_JSON);
			headers.forEach((name, value) -> {
				if (value != null && !value.isBlank()) {
					spec.header(name, value);
				}
			});
			var entity = spec.body(body).retrieve()
					.onStatus(HttpStatusCode::isError, (req, res) -> {
						throw mapStatus(res.getStatusCode().value(), res.getHeaders());
					}).toEntity(JSON_OBJECT);
			Map<String, Object> json = entity.getBody();
			if (json == null) {
				throw new ProviderException.InvalidResponse(providerCode, null);
			}
			return new Response(json, entity.getHeaders(), start);
		} catch (ProviderException e) {
			throw e;
		} catch (RestClientException e) {
			throw mapFailure(e);
		}
	}

	ProviderException mapStatus(int status, HttpHeaders headers) {
		return switch (status) {
			case 401, 403 -> new ProviderException.AuthenticationFailed(providerCode);
			case 402, 429 -> new ProviderException.QuotaExhaustedAtProvider(providerCode, retryAfter(headers));
			case 408, 504 -> new ProviderException.Timeout(providerCode, null);
			case 422, 451 -> new ProviderException.ContentRejected(providerCode);
			default -> new ProviderException.Unavailable(providerCode, null);
		};
	}

	/** Cause chain walk only to classify; the original exception is dropped so no URL or body detail escapes. */
	private ProviderException mapFailure(RestClientException e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof InterruptedException) {
				Thread.currentThread().interrupt();
				return new ProviderException.Cancelled(providerCode);
			}
			if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
				return new ProviderException.Timeout(providerCode, null);
			}
		}
		if (Thread.currentThread().isInterrupted()) {
			return new ProviderException.Cancelled(providerCode);
		}
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof IOException) {
				return new ProviderException.Unavailable(providerCode, null);
			}
		}
		return new ProviderException.InvalidResponse(providerCode, null); // decoding or other client-side failure
	}

	/** {@code Retry-After} in whole seconds (HTTP-date form ignored), else OpenAI's {@code retry-after-ms}; null if neither. */
	static Duration retryAfter(HttpHeaders headers) {
		if (headers == null) {
			return null;
		}
		Duration seconds = parse(headers.getFirst(HttpHeaders.RETRY_AFTER), false);
		return seconds != null ? seconds : parse(headers.getFirst("retry-after-ms"), true);
	}

	private static Duration parse(String value, boolean millis) {
		if (value == null) {
			return null;
		}
		try {
			long n = Long.parseLong(value.trim());
			if (n < 0) {
				return null;
			}
			return millis ? Duration.ofMillis(n) : Duration.ofSeconds(n);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
