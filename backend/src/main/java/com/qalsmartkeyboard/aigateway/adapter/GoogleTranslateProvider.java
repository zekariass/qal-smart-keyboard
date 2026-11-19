package com.qalsmartkeyboard.aigateway.adapter;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.qalsmartkeyboard.aigateway.spi.AiProvider;
import com.qalsmartkeyboard.aigateway.spi.DetectRequest;
import com.qalsmartkeyboard.aigateway.spi.DetectResult;
import com.qalsmartkeyboard.aigateway.spi.DetectResult.DetectedLanguage;
import com.qalsmartkeyboard.aigateway.spi.ProviderCallMetadata;
import com.qalsmartkeyboard.aigateway.spi.ProviderException;
import com.qalsmartkeyboard.aigateway.spi.ProviderUsage;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.aigateway.spi.TranslateResult;
import com.qalsmartkeyboard.aigateway.spi.UserText;

/**
 * Google Cloud Translation - Basic (v2) adapter (ADR 0007 D46, ADR 0023): the TRANSLATE fallback behind OpenAI, never
 * used for LLM transforms; also language detection (seeded EXPERIMENTAL, so not routed until promoted).
 *
 * <p>Wire format: {@code POST {baseUrl}/language/translate/v2} and {@code .../v2/detect} with a JSON body
 * ({@code q}, {@code target}, optional {@code source}, {@code format: "text"}) and the API key in the
 * {@code x-goog-api-key} header, never in the query string (the URL would carry the key and, with {@code q} as a query
 * parameter, the user's text). Answers: {@code data.translations[0].translatedText} (+ {@code detectedSourceLanguage}
 * when no source was sent) and {@code data.detections[0][]} ({@code language}, deprecated {@code confidence}).
 *
 * <p>Basic offers no prompt, style or addressee control: those request fields are ignored and the pipeline guardrails
 * judge the output as for any provider. Usage is characters only (Basic bills characters, reports no tokens).
 */
public final class GoogleTranslateProvider implements AiProvider {

	public static final String CODE = "google-translate";
	/** {@code ai_models.model_code} of the V19 translation row. */
	public static final String MT_MODEL_CODE = "google-nmt";
	/** {@code ai_models.model_code} of the V19 language-identification row. */
	public static final String LID_MODEL_CODE = "google-lid";
	public static final URI DEFAULT_BASE_URL = URI.create("https://translation.googleapis.com");

	/** @param apiKey API key restricted to the Cloud Translation API; redacted in toString */
	public record Config(URI baseUrl, Duration connectTimeout, String apiKey) {

		public Config {
			baseUrl = baseUrl == null ? DEFAULT_BASE_URL : baseUrl;
			connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
			if (apiKey == null || apiKey.isBlank()) {
				throw new IllegalArgumentException("Google Translate API key (QAL_GOOGLE_TRANSLATE_API_KEY) is blank");
			}
		}

		@Override
		public String toString() {
			return "GoogleTranslateProvider.Config[baseUrl=" + baseUrl + ", apiKey=<redacted>]";
		}
	}

	private final Config config;
	private final JsonHttpTransport transport;

	public GoogleTranslateProvider(Config config) {
		this.config = Objects.requireNonNull(config, "config");
		this.transport = new JsonHttpTransport(CODE, config.baseUrl(), config.connectTimeout());
	}

	@Override
	public String code() {
		return CODE;
	}

	@Override
	public TranslateResult translate(TranslateRequest request, Duration timeout) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("q", request.text().value());
		body.put("target", request.destinationLanguage());
		boolean autoDetect = request.sourceLanguage() == null || request.sourceLanguage().isBlank();
		if (!autoDetect) {
			body.put("source", request.sourceLanguage());
		}
		body.put("format", "text");
		JsonHttpTransport.Response r = post("/language/translate/v2", body, timeout);

		Map<?, ?> first = firstOf(data(r).get("translations"));
		if (!(first.get("translatedText") instanceof String translated) || translated.isBlank()) {
			throw new ProviderException.InvalidResponse(CODE, null);
		}
		String detected = autoDetect && first.get("detectedSourceLanguage") instanceof String s && !s.isBlank()
				? catalogueCode(s) : null;
		UserText out = UserText.of(translated);
		return new TranslateResult(out, detected, null, List.of(), metadata(MT_MODEL_CODE, r),
				ProviderUsage.ofCharacters(request.text().characters(), out.characters()));
	}

	@Override
	public DetectResult detectLanguage(DetectRequest request, Duration timeout) {
		JsonHttpTransport.Response r = post("/language/translate/v2/detect", Map.<String, Object>of("q",request.text().value()),
				timeout);
		// data.detections is a list (one per q) of lists (ranked candidates)
		if (!(data(r).get("detections") instanceof List<?> perInput) || perInput.isEmpty()
				|| !(perInput.get(0) instanceof List<?> ranked) || ranked.isEmpty()) {
			throw new ProviderException.InvalidResponse(CODE, null);
		}
		List<DetectedLanguage> languages = new ArrayList<>();
		for (Object o : ranked) {
			if (o instanceof Map<?, ?> m && m.get("language") instanceof String lang && !lang.isBlank()) {
				double confidence = m.get("confidence") instanceof Number n ? n.doubleValue() : 1.0;
				String code = catalogueCode(lang);
				if (request.candidates().isEmpty() || request.candidates().contains(code)) {
					languages.add(new DetectedLanguage(code, null, confidence));
				}
			}
		}
		if (languages.isEmpty()) {
			languages.add(new DetectedLanguage("und", null, 0.0)); // as the mock: nothing among the candidates
		}
		return new DetectResult(languages, false, false, metadata(LID_MODEL_CODE, r),
				ProviderUsage.ofCharacters(request.text().characters(), 0));
	}

	/**
	 * ADR 0040: Google accepts the BCP 47 codes of the catalogue (he, jv, fil, zh-CN) but may report its legacy ones in
	 * detection results (iw, jw, tl, zh). They are mapped to the catalogue's code so the client can match them.
	 */
	public static String catalogueCode(String googleCode) {
		return switch (googleCode) {
			case "iw" -> "he";
			case "jw" -> "jv";
			case "tl" -> "fil";
			case "zh" -> "zh-CN";
			default -> googleCode;
		};
	}

	private JsonHttpTransport.Response post(String path, Map<String, Object> body, Duration timeout) {
		return transport.post(path, Map.of("x-goog-api-key", config.apiKey()), body, timeout);
	}

	private static Map<?, ?> data(JsonHttpTransport.Response r) {
		if (r.json().get("data") instanceof Map<?, ?> data) {
			return data;
		}
		throw new ProviderException.InvalidResponse(CODE, null);
	}

	private static Map<?, ?> firstOf(Object list) {
		if (list instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) {
			return m;
		}
		throw new ProviderException.InvalidResponse(CODE, null);
	}

	private static ProviderCallMetadata metadata(String modelCode, JsonHttpTransport.Response r) {
		return new ProviderCallMetadata(CODE, modelCode, r.header("x-request-id"), r.elapsed());
	}

	@Override
	public String toString() {
		return "GoogleTranslateProvider[" + config + "]";
	}
}
