package com.qalsmartkeyboard.aigateway;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.qalsmartkeyboard.aigateway.spi.Addressee;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.DataPolicyRequirement;
import com.qalsmartkeyboard.aigateway.spi.UserText;

/**
 * One explicit AI action for {@link AiPipeline#execute} (B5.3), shaped like the contract request bodies so the
 * endpoints (B5.5) only copy fields: {@link Translate} = {@code TranslateRequest}, {@link Transform} =
 * {@code TransformRequest} (GRAMMAR_CORRECTION, REWRITE, TONE_ADJUST), {@link Detect} = {@code DetectLanguageRequest}.
 * Enum-valued options travel as their contract names and are validated by the pipeline. Text is {@link UserText} only,
 * so printing a command never prints content.
 */
public sealed interface AiCommand permits AiCommand.Translate, AiCommand.Transform, AiCommand.Detect {

	/** Who asks and from where. */
	Caller caller();

	/** The catalogue feature ({@code feature_catalog.feature_code}). */
	AiFeature feature();

	/** The user's text (never logged, never stored). */
	UserText text();

	/** {@code ai_requests.surface} values a client can send ({@code X-Client-Surface}). */
	enum Surface {
		KEYBOARD, COMPANION
	}

	/**
	 * @param deviceId       the access token's device, or null
	 * @param surface        null = KEYBOARD
	 * @param idempotencyKey the {@code Idempotency-Key} header; required for metered features (contract: every AI POST)
	 * @param dataPolicy     data handling the route must satisfy (organisation ZDR, SD 13); null = standard
	 */
	record Caller(UUID userId, UUID deviceId, Surface surface, String idempotencyKey,
			DataPolicyRequirement dataPolicy) {

		public Caller {
			Objects.requireNonNull(userId, "userId");
			surface = surface == null ? Surface.KEYBOARD : surface;
			dataPolicy = dataPolicy == null ? DataPolicyRequirement.STANDARD : dataPolicy;
		}

		public static Caller of(UUID userId, String idempotencyKey) {
			return new Caller(userId, null, null, idempotencyKey, null);
		}
	}

	/** Contract {@code CulturalOptions}; null fields fall back to the user's preferences. */
	record CulturalOptions(String calendar, String timeNotation, String numerals, Boolean adaptIdioms) {

		public static final CulturalOptions NONE = new CulturalOptions(null, null, null, null);
	}

	/**
	 * Contract {@code TranslateRequest}.
	 *
	 * @param sourceLanguage       null = detect
	 * @param style                {@code TranslationStyle} or null (then profile, then preferences)
	 * @param preserveNames        null = preferences (default true)
	 * @param glossaryId           accepted but not applied yet (glossaries are a later step)
	 * @param addressee            per-field fallback to the destination profile, then preferences (SD 11.5)
	 * @param destinationProfileId fills addressee, style and glossary defaults; explicit fields win
	 * @param saveToHistory        the request's {@code saveToHistory} (default false); history is kept only when it is
	 *                             true and the user's history gates hold (B5.7)
	 */
	record Translate(Caller caller, UserText text, String sourceLanguage, String destinationLanguage, String style,
			Boolean preserveNames, UUID glossaryId, Addressee addressee, CulturalOptions culturalOptions,
			UUID destinationProfileId, boolean saveToHistory) implements AiCommand {

		public Translate(Caller caller, UserText text, String sourceLanguage, String destinationLanguage, String style,
				Boolean preserveNames, UUID glossaryId, Addressee addressee, CulturalOptions culturalOptions,
				UUID destinationProfileId) {
			this(caller, text, sourceLanguage, destinationLanguage, style, preserveNames, glossaryId, addressee,
					culturalOptions, destinationProfileId, false);
		}

		public Translate {
			Objects.requireNonNull(caller, "caller");
			Objects.requireNonNull(text, "text");
			addressee = addressee == null ? Addressee.UNSPECIFIED : addressee;
			culturalOptions = culturalOptions == null ? CulturalOptions.NONE : culturalOptions;
		}

		public static Translate of(Caller caller, String text, String source, String destination) {
			return new Translate(caller, UserText.of(text), source, destination, null, null, null, null, null, null);
		}

		@Override
		public AiFeature feature() {
			return AiFeature.TRANSLATE;
		}
	}

	/**
	 * Contract {@code TransformRequest} for the P0/P1 writing tasks.
	 *
	 * @param task     GRAMMAR_CORRECTION, REWRITE or TONE_ADJUST
	 * @param language language of the text; null = detect from its script
	 * @param mode     GRAMMAR | SPELLING_ONLY (grammar), NATURAL | SHORTEN | EXPAND | CLARIFY (rewrite)
	 * @param tone     required for TONE_ADJUST
	 * @param saveToHistory see {@link Translate}
	 */
	record Transform(Caller caller, AiFeature task, UserText text, String language, String mode, String tone,
			String targetLength, Addressee addressee, UUID glossaryId, boolean saveToHistory) implements AiCommand {

		public Transform(Caller caller, AiFeature task, UserText text, String language, String mode, String tone,
				String targetLength, Addressee addressee, UUID glossaryId) {
			this(caller, task, text, language, mode, tone, targetLength, addressee, glossaryId, false);
		}

		public Transform {
			Objects.requireNonNull(caller, "caller");
			Objects.requireNonNull(task, "task");
			Objects.requireNonNull(text, "text");
			addressee = addressee == null ? Addressee.UNSPECIFIED : addressee;
		}

		public static Transform of(Caller caller, AiFeature task, String text, String language) {
			return new Transform(caller, task, UserText.of(text), language, null, null, null, null, null);
		}

		@Override
		public AiFeature feature() {
			return task;
		}
	}

	/** Contract {@code DetectLanguageRequest}: unmetered, no idempotency key. */
	record Detect(Caller caller, UserText text, List<String> candidates) implements AiCommand {

		public Detect {
			Objects.requireNonNull(caller, "caller");
			Objects.requireNonNull(text, "text");
			candidates = candidates == null ? List.of() : List.copyOf(candidates);
		}

		@Override
		public AiFeature feature() {
			return AiFeature.DETECT_LANGUAGE;
		}
	}
}
