package com.qalsmartkeyboard.aigateway.adapter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.qalsmartkeyboard.aigateway.spi.DetectRequest;
import com.qalsmartkeyboard.aigateway.spi.DetectResult;
import com.qalsmartkeyboard.aigateway.spi.DetectResult.DetectedLanguage;
import com.qalsmartkeyboard.aigateway.spi.ProviderCallMetadata;
import com.qalsmartkeyboard.aigateway.spi.ProviderException;
import com.qalsmartkeyboard.aigateway.spi.ProviderUsage;
import com.qalsmartkeyboard.aigateway.spi.TransformRequest;
import com.qalsmartkeyboard.aigateway.spi.TransformResult;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.aigateway.spi.TranslateResult;
import com.qalsmartkeyboard.aigateway.spi.UserText;

/**
 * HTTP/JSON adapter skeleton (B5.1): the shape of a vendor adapter, wired to the placeholder JSON dialect of the local
 * WireMock stubs in {@code infra/compose/wiremock/mappings/ai-*.json} (OI-P5). It is NOT a vendor integration: the real
 * vendors are chosen at gate G0 and each gets its own adapter with its own wire format; this class documents and tests
 * the mechanics every one of them needs:
 * <ul>
 * <li>Spring {@link RestClient} on a shared JDK {@link HttpClient} (HTTP/1.1, no redirects) with the connect timeout
 * from configuration and a read timeout equal to the per-call {@code timeout}.</li>
 * <li>Bearer key from configuration only; never logged, never in an exception.</li>
 * <li>HTTP error status to typed {@link ProviderException} without ever reading the error body; I/O failures to
 * Timeout / Unavailable / Cancelled; undecodable or incomplete JSON to InvalidResponse. Nothing is logged here.</li>
 * <li>Optional {@code X-Mock-Scenario} header (dev and test only) to drive the stub scenarios {@code slow}/{@code error}.</li>
 * </ul>
 * Status mapping: 401/403 AuthenticationFailed; 402/429 QuotaExhaustedAtProvider (Retry-After honoured when numeric);
 * 408/504 Timeout; 422/451 ContentRejected; any other 4xx/5xx Unavailable (a 4xx other than those means our
 * integration is wrong, which is a provider-side outage to callers and an alert to operators).
 */
public final class HttpJsonAiProvider implements AiProvider {

	/**
	 * @param apiKey bearer credential from configuration (may be blank for the local stubs); redacted in toString
	 * @param mockScenario value of {@code X-Mock-Scenario} sent with every call; blank = header not sent
	 */
	public record Config(String code, URI baseUrl, Duration connectTimeout, String apiKey, String mockScenario) {

		public Config {
			Objects.requireNonNull(code, "code");
			Objects.requireNonNull(baseUrl, "baseUrl");
			connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
		}

		@Override
		public String toString() {
			return "Config[code=" + code + ", baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<redacted>") + "]";
		}
	}

	private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
	};

	private final Config config;
	private final HttpClient httpClient;

	public HttpJsonAiProvider(Config config) {
		this.config = config;
		this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NEVER).connectTimeout(config.connectTimeout()).build();
	}

	@Override
	public String code() {
		return config.code();
	}

	@Override
	public TranslateResult translate(TranslateRequest request, Duration timeout) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("text", request.text().value());
		putIfPresent(body, "source_language", request.sourceLanguage());
		body.put("target_language", request.destinationLanguage());
		putIfPresent(body, "style", request.style());
		body.put("preserve_names", request.preserveNames());
		Exchange x = post("/ai/v1/translate", body, timeout);
		String translation = requiredString(x.json, "translation");
		UserText out = UserText.of(translation);
		return new TranslateResult(out, request.sourceLanguage() == null ? string(x.json, "source_language") : null, null,
				List.of(), x.metadata(string(x.json, "model")), usage(request.text(), out, x.json));
	}

	@Override
	public TransformResult llmTransform(TransformRequest request, Duration timeout) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("task", request.task().name());
		body.put("text", request.text().value());
		putIfPresent(body, "language", request.language());
		putIfPresent(body, "mode", request.mode());
		putIfPresent(body, "tone", request.tone());
		putIfPresent(body, "target_length", request.targetLength());
		putIfPresent(body, "explain_in", request.explainIn());
		putIfPresent(body, "instructions", request.instructions());
		Exchange x = post("/ai/v1/llm/chat", body, timeout);
		String finish = string(x.json, "finish_reason");
		if ("content_filter".equals(finish)) {
			throw new ProviderException.ContentRejected(code());
		}
		if (finish != null && !"stop".equals(finish)) {
			throw new ProviderException.InvalidResponse(code(), null); // truncated or unknown: cannot be shown or settled
		}
		UserText out = UserText.of(requiredString(x.json, "output"));
		return new TransformResult(out, List.of(), x.metadata(string(x.json, "model")),
				usage(request.text(), out, x.json));
	}

	@Override
	public DetectResult detectLanguage(DetectRequest request, Duration timeout) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("text", request.text().value());
		if (!request.candidates().isEmpty()) {
			body.put("candidates", request.candidates());
		}
		Exchange x = post("/ai/v1/detect-language", body, timeout);
		List<DetectedLanguage> languages = new ArrayList<>();
		languages.add(new DetectedLanguage(requiredString(x.json, "language"), string(x.json, "script"),
				number(x.json, "confidence", 1.0)));
		if (x.json.get("alternatives") instanceof List<?> alternatives) {
			for (Object o : alternatives) {
				if (o instanceof Map<?, ?> m && m.get("language") instanceof String lang) {
					languages.add(new DetectedLanguage(lang, null,
							m.get("confidence") instanceof Number n ? n.doubleValue() : 0.0));
				}
			}
		}
		return new DetectResult(languages, false, false, x.metadata(string(x.json, "model")),
				ProviderUsage.ofCharacters(request.text().characters(), 0));
	}

	// ------------------------------------------------------------------ transport

	private record Exchange(Map<String, Object> json, String requestId, long startNanos, String providerCode) {
		ProviderCallMetadata metadata(String model) {
			return new ProviderCallMetadata(providerCode, model, requestId != null ? requestId : string(json, "id"),
					Duration.ofNanos(System.nanoTime() - startNanos));
		}
	}

	private Exchange post(String path, Map<String, Object> body, Duration timeout) {
		AiProvider.requirePositive(timeout);
		long start = System.nanoTime();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(timeout);
		RestClient client = RestClient.builder().requestFactory(factory).baseUrl(config.baseUrl().toString()).build();
		try {
			RestClient.RequestBodySpec spec = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
					.accept(MediaType.APPLICATION_JSON);
			if (config.apiKey() != null && !config.apiKey().isBlank()) {
				spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
			}
			if (config.mockScenario() != null && !config.mockScenario().isBlank()) {
				spec.header("X-Mock-Scenario", config.mockScenario());
			}
			var responseEntity = spec.body(body).retrieve()
					.onStatus(HttpStatusCode::isError, (req, res) -> {
						throw mapStatus(res.getStatusCode().value(), res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
					}).toEntity(JSON_OBJECT);
			Map<String, Object> json = responseEntity.getBody();
			if (json == null) {
				throw new ProviderException.InvalidResponse(code(), null);
			}
			return new Exchange(json, responseEntity.getHeaders().getFirst("X-Request-Id"), start, code());
		} catch (ProviderException e) {
			throw e;
		} catch (RestClientException e) {
			throw mapFailure(e);
		}
	}

	private ProviderException mapStatus(int status, String retryAfter) {
		return switch (status) {
			case 401, 403 -> new ProviderException.AuthenticationFailed(code());
			case 402, 429 -> new ProviderException.QuotaExhaustedAtProvider(code(), seconds(retryAfter));
			case 408, 504 -> new ProviderException.Timeout(code(), null);
			case 422, 451 -> new ProviderException.ContentRejected(code());
			default -> new ProviderException.Unavailable(code(), null);
		};
	}

	/** Cause chain walk only to classify; the original exception is dropped so no URL or body detail escapes. */
	private ProviderException mapFailure(RestClientException e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof InterruptedException) {
				Thread.currentThread().interrupt();
				return new ProviderException.Cancelled(code());
			}
			if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
				return new ProviderException.Timeout(code(), null);
			}
		}
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof IOException) {
				return new ProviderException.Unavailable(code(), null);
			}
		}
		return new ProviderException.InvalidResponse(code(), null); // decoding or other client-side failure
	}

	private static Duration seconds(String retryAfter) {
		try {
			return retryAfter == null ? null : Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ JSON helpers

	private ProviderUsage usage(UserText in, UserText out, Map<String, Object> json) {
		Long inTokens = null;
		Long outTokens = null;
		if (json.get("usage") instanceof Map<?, ?> u) {
			inTokens = u.get("input_tokens") instanceof Number n ? n.longValue() : null;
			outTokens = u.get("output_tokens") instanceof Number n ? n.longValue() : null;
		}
		return new ProviderUsage(in.characters(), out.characters(), inTokens, outTokens, null);
	}

	private static void putIfPresent(Map<String, Object> body, String key, String value) {
		if (value != null && !value.isBlank()) {
			body.put(key, value);
		}
	}

	private static String string(Map<String, Object> json, String key) {
		return json.get(key) instanceof String s ? s : null;
	}

	private String requiredString(Map<String, Object> json, String key) {
		String s = string(json, key);
		if (s == null || s.isEmpty()) {
			throw new ProviderException.InvalidResponse(code(), null);
		}
		return s;
	}

	private static double number(Map<String, Object> json, String key, double fallback) {
		return json.get(key) instanceof Number n ? n.doubleValue() : fallback;
	}
}
