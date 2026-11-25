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
 * {@code TransformRequest} (GRAMMAR_CORRECTION, REWRITE, TONE_ADJUST, SUMMARIZE, EXPLAIN_TEXT), {@link Reply} =
 * {@code RepliesRequest} (SMART_REPLY), {@link Draft} = {@code DraftRequest} (DRAFT_DOCUMENT), {@link Detect} =
 * {@code DetectLanguageRequest}, {@link Chat} = {@code ChatRequest} (AI_CHAT, ADR 0048). Enum-valued options travel as their contract names and are validated by the pipeline.
 * Text is {@link UserText} only, so printing a command never prints content.
 */
public sealed interface AiCommand
		permits AiCommand.Translate, AiCommand.Transform, AiCommand.Reply, AiCommand.Draft, AiCommand.Detect,
		AiCommand.Chat {

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
	 * Contract {@code TransformRequest} for the writing tasks.
	 *
	 * @param task     GRAMMAR_CORRECTION, REWRITE, TONE_ADJUST, SUMMARIZE or EXPLAIN_TEXT
	 * @param language language of the text; null = detect from its script
	 * @param mode     GRAMMAR | SPELLING_ONLY (grammar), NATURAL | SHORTEN | EXPAND | CLARIFY | SIMPLIFY (rewrite)
	 * @param tone     required for TONE_ADJUST
	 * @param saveToHistory see {@link Translate}
	 * @param outputLanguage the request's {@code outputLanguage} (or {@code options.explainIn} for EXPLAIN_TEXT); null =
	 *                       the language of the text (ADR 0027)
	 * @param variation      0..20, null = 0; a higher number asks for a different version (regenerate)
	 * @param format         contract {@code options.format} (ADR 0048); required for FORMAT_TEXT, refused for the
	 *                       other tasks
	 */
	record Transform(Caller caller, AiFeature task, UserText text, String language, String mode, String tone,
			String targetLength, Addressee addressee, UUID glossaryId, boolean saveToHistory, String outputLanguage,
			Integer variation, String format) implements AiCommand {

		public Transform(Caller caller, AiFeature task, UserText text, String language, String mode, String tone,
				String targetLength, Addressee addressee, UUID glossaryId, boolean saveToHistory,
				String outputLanguage, Integer variation) {
			this(caller, task, text, language, mode, tone, targetLength, addressee, glossaryId, saveToHistory,
					outputLanguage, variation, null);
		}

		public Transform(Caller caller, AiFeature task, UserText text, String language, String mode, String tone,
				String targetLength, Addressee addressee, UUID glossaryId, boolean saveToHistory,
				String outputLanguage) {
			this(caller, task, text, language, mode, tone, targetLength, addressee, glossaryId, saveToHistory,
					outputLanguage, null);
		}

		public Transform(Caller caller, AiFeature task, UserText text, String language, String mode, String tone,
				String targetLength, Addressee addressee, UUID glossaryId, boolean saveToHistory) {
			this(caller, task, text, language, mode, tone, targetLength, addressee, glossaryId, saveToHistory, null);
		}

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

	/**
	 * Contract {@code RepliesRequest} (SMART_REPLY, ADR 0027): reply suggestions to a message the user explicitly
	 * pasted, selected or shared.
	 *
	 * @param text            {@code contextText}, the message being answered
	 * @param contextLanguage language of the message; null = unknown
	 * @param replyLanguage   the language the replies are written in (required by the contract; validated by the
	 *                        pipeline)
	 * @param count           1..5, null = 3
	 * @param tone            contract {@code Tone}; null = match the conversation
	 * @param instructions    the user's own notes on what to say (any language); null = suggest freely
	 * @param variation       0..20, null = 0; a higher number asks for a different set (regenerate)
	 * @param saveToHistory   see {@link Translate}
	 * @param intent          contract {@code intent} (ADR 0048): a preset stance every reply takes; null = varied
	 */
	record Reply(Caller caller, UserText text, String contextLanguage, String replyLanguage, Integer count,
			String tone, Addressee addressee, UserText instructions, Integer variation, boolean saveToHistory,
			String intent) implements AiCommand {

		public Reply(Caller caller, UserText text, String contextLanguage, String replyLanguage, Integer count,
				String tone, Addressee addressee, UserText instructions, Integer variation, boolean saveToHistory) {
			this(caller, text, contextLanguage, replyLanguage, count, tone, addressee, instructions, variation,
					saveToHistory, null);
		}

		public Reply {
			Objects.requireNonNull(caller, "caller");
			Objects.requireNonNull(text, "text");
			addressee = addressee == null ? Addressee.UNSPECIFIED : addressee;
		}

		@Override
		public AiFeature feature() {
			return AiFeature.SMART_REPLY;
		}
	}

	/**
	 * Contract {@code DraftRequest} (DRAFT_DOCUMENT, ADR 0027): an e-mail written from the user's description (NEW), a
	 * reply to a pasted e-mail (REPLY) or an improved draft (IMPROVE). Every text is optional here; which ones a mode
	 * needs is validated by the pipeline, field by field.
	 *
	 * @param documentType   EMAIL (null = EMAIL); the other contract types are not served yet
	 * @param mode           NEW | REPLY | IMPROVE, null = NEW
	 * @param language       the language the e-mail is written in (contract {@code language}; required)
	 * @param instructions   what the e-mail must say, in the user's words (contract {@code instructions}, or the older
	 *                       {@code purpose} with its {@code keyPoints}); required for NEW and REPLY
	 * @param originalEmail  the e-mail being answered (REPLY)
	 * @param draft          the user's draft (IMPROVE)
	 * @param tone           contract {@code EmailTone}, null = PROFESSIONAL
	 * @param length         SHORT | MEDIUM | LONG, null = MEDIUM
	 * @param includeSubject null = true
	 * @param addressee      the recipient's formality and gender
	 * @param variation      0..20, null = 0; a higher number asks for a different version (regenerate)
	 */
	record Draft(Caller caller, String documentType, String mode, String language, UserText instructions,
			UserText originalEmail, UserText draft, String tone, String length, Boolean includeSubject,
			UserText recipientName, UserText recipientRole, Addressee addressee, UserText senderName,
			CulturalOptions culturalOptions, Integer variation, boolean saveToHistory) implements AiCommand {

		public Draft {
			Objects.requireNonNull(caller, "caller");
			addressee = addressee == null ? Addressee.UNSPECIFIED : addressee;
			culturalOptions = culturalOptions == null ? CulturalOptions.NONE : culturalOptions;
		}

		@Override
		public AiFeature feature() {
			return AiFeature.DRAFT_DOCUMENT;
		}

		/** The mode, NEW when absent; an unknown value is returned as it is (the pipeline refuses it). */
		public String modeOrDefault() {
			return mode == null ? "NEW" : mode;
		}

		/**
		 * The main text sent to the provider: the description (NEW), the e-mail answered (REPLY) or the draft
		 * (IMPROVE); empty when the mode's text is missing (the pipeline refuses that before anything else).
		 */
		@Override
		public UserText text() {
			UserText t = switch (modeOrDefault()) {
				case "REPLY" -> originalEmail;
				case "IMPROVE" -> draft;
				default -> instructions;
			};
			return t == null ? UserText.of("") : t;
		}
	}

	/**
	 * Contract {@code ChatRequest} (AI_CHAT, ADR 0048): one stateless turn of the writing-assistant chat. The client
	 * sends the turns it shows, the server keeps nothing. Every text is {@link UserText}.
	 *
	 * @param messages      the visible turns, oldest first; the last must be a USER turn (validated by the pipeline)
	 * @param language      the language the reply is written in; null = the language of the last user turn
	 * @param fieldText     the text of the host field the user is writing in (the keyboard's Ask-AI handoff), or null
	 * @param saveToHistory the request's {@code saveToHistory}; chat is never saved, so {@code true} is refused
	 */
	record Chat(Caller caller, List<Turn> messages, String language, UserText fieldText, boolean saveToHistory)
			implements AiCommand {

		/** Contract {@code ChatMessage}; {@code role} is validated by the pipeline (USER or ASSISTANT only). */
		public record Turn(String role, UserText text) {

			public Turn {
				Objects.requireNonNull(text, "text");
			}
		}

		public Chat {
			Objects.requireNonNull(caller, "caller");
			messages = messages == null ? List.of() : List.copyOf(messages);
		}

		@Override
		public AiFeature feature() {
			return AiFeature.AI_CHAT;
		}

		/** The last turn's text (the one answered); empty when there is none (the pipeline refuses that). */
		@Override
		public UserText text() {
			return messages.isEmpty() ? UserText.of("") : messages.get(messages.size() - 1).text();
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
