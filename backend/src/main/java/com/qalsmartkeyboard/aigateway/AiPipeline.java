package com.qalsmartkeyboard.aigateway;

import java.lang.Character.UnicodeScript;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.qalsmartkeyboard.aigateway.pipeline.AiRequestLog;
import com.qalsmartkeyboard.aigateway.pipeline.AiUserContext;
import com.qalsmartkeyboard.aigateway.pipeline.Guardrails;
import com.qalsmartkeyboard.aigateway.pipeline.Guardrails.Flag;
import com.qalsmartkeyboard.aigateway.pipeline.LanguageCatalog;
import com.qalsmartkeyboard.aigateway.pipeline.OutputFormats;
import com.qalsmartkeyboard.aigateway.pipeline.PromptRenderer;
import com.qalsmartkeyboard.aigateway.pipeline.PromptTemplates;
import com.qalsmartkeyboard.aigateway.pipeline.PromptValues;
import com.qalsmartkeyboard.aigateway.spi.Addressee;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.ContextPart;
import com.qalsmartkeyboard.aigateway.spi.DetectRequest;
import com.qalsmartkeyboard.aigateway.spi.DetectResult;
import com.qalsmartkeyboard.aigateway.spi.MeteredUnit;
import com.qalsmartkeyboard.aigateway.spi.ProviderCallMetadata;
import com.qalsmartkeyboard.aigateway.spi.ProviderUsage;
import com.qalsmartkeyboard.aigateway.spi.QualityTier;
import com.qalsmartkeyboard.aigateway.spi.ResolvedProvider;
import com.qalsmartkeyboard.aigateway.spi.TransformRequest;
import com.qalsmartkeyboard.aigateway.spi.TransformResult;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.aigateway.spi.TranslateResult;
import com.qalsmartkeyboard.aigateway.spi.UserText;
import com.qalsmartkeyboard.entitlements.AccessState;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.entitlements.FeatureEntitlement;
import com.qalsmartkeyboard.entitlements.MeteringUnit;
import com.qalsmartkeyboard.entitlements.ResolvedEntitlements;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.privacy.ConsentGate;
import com.qalsmartkeyboard.privacy.ConsentType;
import com.qalsmartkeyboard.usage.Metering;
import com.qalsmartkeyboard.usage.QuotaExceededException;
import com.qalsmartkeyboard.usage.ReleaseReason;
import com.qalsmartkeyboard.usage.Reservation;
import com.qalsmartkeyboard.usage.ReservationClosedException;
import com.qalsmartkeyboard.usage.Settlement;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The server AI pipeline (B5.3, SD 11.1, ADR 0017). One call = one explicit user action:
 *
 * <ol>
 * <li><b>Account and consent.</b> Only an ACTIVE account may use server AI: SUSPENDED is 403
 * {@code ACCOUNT_SUSPENDED}, DELETION_PENDING/DELETED is 403 {@code DELETION_PENDING} (ADR 0007 D22), a merged guest
 * 401 {@code GUEST_SESSION_INVALID}. The {@code AI_PROCESSING} consent is checked only when
 * {@code qal.ai.pipeline.require-ai-processing-consent} is set: the System Design does not require it for explicit
 * server actions (SD 17 makes it a per-action client confirmation for PREFER_ON_DEVICE). Exception (D33): unmetered
 * DETECT_LANGUAGE also works while the deletion is pending. A user whose {@code ai_processing_preference} is
 * ON_DEVICE_ONLY gets 403 {@code ON_DEVICE_ONLY_PREFERRED} for every server AI call.</li>
 * <li><b>Validation</b> (no charge yet): text size ({@code INPUT_TOO_LONG}), active languages
 * ({@code LANGUAGE_NOT_SUPPORTED}), option values and the destination profile ({@code VALIDATION_FAILED}); the
 * addressee, style and cultural options are resolved request, then destination profile, then preferences.</li>
 * <li><b>Entitlement</b>: 403 {@code FEATURE_NOT_ENTITLED} when the plan does not give access.</li>
 * <li><b>Reserve</b> via {@link Metering} with the {@code Idempotency-Key} (estimate: 1 for REQUEST features, the input
 * characters for CHARACTER ones); 429 {@code QUOTA_EXCEEDED} calls no provider. Unmetered DETECT_LANGUAGE skips this.</li>
 * <li><b>Idempotency seam</b>: a new reservation is announced to {@link AiIdempotency#onReserved} (B5.4 request
 * fingerprint); a replayed key goes to {@link AiIdempotency#onReplay} (B5.4 cache) instead of a provider.</li>
 * <li><b>Prompt</b>: per candidate provider, the active versioned template for (feature, language, model family) is
 * rendered with option codes, the addressee sentence and Ethiopian-calendar context; user text is never inside it.</li>
 * <li><b>Route</b> via {@link AiRouter} (failover, breaker, kill switch, cost cap).</li>
 * <li><b>Guardrails</b> ({@link Guardrails}): a failed check gets one retry on the same provider, then one fallback
 * route over the providers not yet tried; if that fails too, 422 {@code OUTPUT_GUARDRAIL_FAILED}. All of it shares the
 * pipeline deadline.</li>
 * <li><b>Settle</b> with the serving provider's usage on success; <b>release</b> on every other path (provider failure,
 * guardrail failure, cancellation, unexpected exception), so a failed request is never charged.</li>
 * <li><b>Record</b> one {@code ai_requests} row (metadata only) and return the contract-shaped {@link AiResult}.</li>
 * </ol>
 *
 * <p><b>Transactions.</b> Must not be called inside a transaction: reserve, settle, release and the ledger insert are
 * each their own short transaction and the provider call holds no lock (ADR 0011).
 *
 * <p><b>No content anywhere.</b> Text stays in {@link UserText} and the result records; logs, metric tags, exceptions
 * and {@code ai_requests} get feature codes, provider codes, flag names, counts and error codes only.
 *
 * <p><b>Text actions and e-mail (ADR 0027).</b> Transforms take an optional output language (SUMMARIZE, EXPLAIN_TEXT,
 * REWRITE incl. SIMPLIFY, TONE_ADJUST; GRAMMAR_CORRECTION keeps the text's language). SMART_REPLY ({@link AiCommand.Reply})
 * and DRAFT_DOCUMENT ({@link AiCommand.Draft}) run through the same steps as a transform, with the user's second text
 * (notes, names) sent as separate {@link ContextPart}s and the output read by {@link OutputFormats}. Features listed in
 * {@link Settings#featureTimeouts()} get their own per-attempt timeout and deadline (an LLM writing an e-mail needs far
 * more than the keyboard's 4 s / 8 s).
 */
public class AiPipeline {

	private static final Logger log = LoggerFactory.getLogger(AiPipeline.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	private static final String ON_DEVICE_ONLY = "ON_DEVICE_ONLY";

	private static final Set<AiFeature> TRANSFORM_TASKS = EnumSet.of(AiFeature.GRAMMAR_CORRECTION, AiFeature.REWRITE,
			AiFeature.TONE_ADJUST, AiFeature.SUMMARIZE, AiFeature.EXPLAIN_TEXT, AiFeature.FORMAT_TEXT);
	private static final Set<String> STYLES = Set.of("LITERAL", "NATURAL", "POLITE", "PROFESSIONAL", "FRIENDLY");
	private static final Set<String> TONES = Set.of("FRIENDLY", "POLITE", "PROFESSIONAL", "CONCISE", "APOLOGETIC",
			"HONORIFIC", "ROMANTIC", "CONFIDENT", "FUNNY", "PERSUASIVE");
	/** Contract {@code EmailTone} (DRAFT_DOCUMENT). */
	private static final Set<String> EMAIL_TONES = Set.of("PROFESSIONAL", "FORMAL", "FRIENDLY", "CASUAL", "POLITE",
			"PERSUASIVE", "APOLOGETIC", "CONCISE");
	private static final Set<String> DRAFT_MODES = Set.of("NEW", "REPLY", "IMPROVE");
	private static final Set<String> DOCUMENT_TYPES = Set.of("EMAIL", "LETTER", "APPLICATION", "ANNOUNCEMENT",
			"OTHER", "JOB_APPLICATION", "CV", "COVER_LETTER", "BUSINESS_MESSAGE");
	/** The document types this gateway serves today (ADR 0048); the rest answer {@code NOT_AVAILABLE}. */
	private static final Set<String> SERVED_DOCUMENT_TYPES = Set.of("EMAIL", "JOB_APPLICATION", "CV",
			"COVER_LETTER", "BUSINESS_MESSAGE");
	/** Contract {@code TransformRequest.options.format} (FORMAT_TEXT, ADR 0048). */
	private static final Set<String> FORMATS = Set.of("BULLETS", "NUMBERED_LIST", "PARAGRAPHS", "REPORT",
			"MEETING_NOTES");
	/** Contract {@code RepliesRequest.intent} (ADR 0048). */
	private static final Set<String> INTENTS = Set.of("AGREE", "DISAGREE_POLITELY", "ASK_CLARIFICATION",
			"ACCEPT_INVITATION", "DECLINE_POLITELY", "THANK_SENDER");
	private static final Set<String> GRAMMAR_MODES = Set.of("GRAMMAR", "SPELLING_ONLY");
	private static final Set<String> REWRITE_MODES = Set.of("NATURAL", "SHORTEN", "EXPAND", "CLARIFY", "SIMPLIFY");
	private static final Set<String> LENGTHS = Set.of("SHORT", "MEDIUM", "LONG");
	private static final Set<String> CALENDARS = Set.of("GREGORIAN", "ETHIOPIAN");
	private static final Set<String> TIME_NOTATIONS = Set.of("INTERNATIONAL", "ETHIOPIAN");
	private static final Set<String> NUMERALS = Set.of("WESTERN", "ETHIOPIC");

	/** Input limits of the reply and draft fields (code points; contract maxLength, ADR 0027). */
	public static final int MAX_REPLY_CONTEXT_CHARACTERS = 5000;
	public static final int MAX_REPLY_INSTRUCTIONS_CHARACTERS = 1000;
	public static final int MAX_DRAFT_INSTRUCTIONS_CHARACTERS = 2000;
	/** The e-mail being answered (REPLY) and the draft to improve (IMPROVE): e-mails are longer than chat messages. */
	public static final int MAX_DRAFT_EMAIL_CHARACTERS = 8000;
	public static final int MAX_NAME_CHARACTERS = 120;
	public static final int MAX_VARIATION = 20;
	public static final int DEFAULT_REPLY_COUNT = 3;
	public static final int MAX_REPLY_COUNT = 5;

	/** A per-feature time budget: one provider attempt, and route + guardrail retry + fallback together. */
	public record Timeouts(Duration attemptTimeout, Duration deadline) {

		public Timeouts {
			if (attemptTimeout == null || deadline == null || !attemptTimeout.isPositive() || !deadline.isPositive()
					|| attemptTimeout.compareTo(deadline) > 0) {
				throw new IllegalArgumentException("invalid feature timeouts");
			}
		}
	}

	/**
	 * ADR 0027 defaults: the long-form features call an LLM that writes a whole summary, explanation, set of replies or
	 * e-mail; the keyboard's 4 s attempt / 8 s deadline (every other feature) would cut them off. The quick LLM-backed
	 * actions (translate, grammar, rewrite, tone) get 8 s / 12 s and 6 s / 10 s.
	 */
	public static final Map<AiFeature, Timeouts> DEFAULT_FEATURE_TIMEOUTS = Map.of(
			AiFeature.SUMMARIZE, new Timeouts(Duration.ofSeconds(12), Duration.ofSeconds(20)),
			AiFeature.EXPLAIN_TEXT, new Timeouts(Duration.ofSeconds(12), Duration.ofSeconds(20)),
			AiFeature.SMART_REPLY, new Timeouts(Duration.ofSeconds(12), Duration.ofSeconds(20)),
			AiFeature.DRAFT_DOCUMENT, new Timeouts(Duration.ofSeconds(20), Duration.ofSeconds(30)),
			// Quick actions that also route to the LLM: a real gpt-6-luna translation took ~4 s end to end, so the 4 s
			// attempt would time out; Google Translate stays a fast fallback inside the 12 s deadline.
			AiFeature.TRANSLATE, new Timeouts(Duration.ofSeconds(8), Duration.ofSeconds(12)),
			AiFeature.GRAMMAR_CORRECTION, new Timeouts(Duration.ofSeconds(6), Duration.ofSeconds(10)),
			AiFeature.REWRITE, new Timeouts(Duration.ofSeconds(6), Duration.ofSeconds(10)),
			AiFeature.TONE_ADJUST, new Timeouts(Duration.ofSeconds(6), Duration.ofSeconds(10)),
			AiFeature.FORMAT_TEXT, new Timeouts(Duration.ofSeconds(6), Duration.ofSeconds(10)));

	/**
	 * @param deadline                   time budget for all provider calls of one request: route, guardrail retry and
	 *                                   fallback together (the keyboard waits 8 s)
	 * @param requireAiProcessingConsent require the AI_PROCESSING consent for every server AI call
	 * @param maxTextCharacters          contract maxLength of translate/transform text (code points)
	 * @param maxDetectCharacters        contract maxLength of detect-language text
	 * @param featureTimeouts            features with their own attempt timeout and deadline (ADR 0027); every other
	 *                                   feature uses {@code deadline} and the router's attempt timeout
	 */
	public record Settings(Duration deadline, boolean requireAiProcessingConsent, int maxTextCharacters,
			int maxDetectCharacters, Map<AiFeature, Timeouts> featureTimeouts) {

		public Settings {
			if (deadline == null || !deadline.isPositive() || maxTextCharacters < 1 || maxDetectCharacters < 1) {
				throw new IllegalArgumentException("invalid pipeline settings");
			}
			featureTimeouts = featureTimeouts == null ? DEFAULT_FEATURE_TIMEOUTS : Map.copyOf(featureTimeouts);
		}

		/** The B5.3 shape, with {@link #DEFAULT_FEATURE_TIMEOUTS}. */
		public Settings(Duration deadline, boolean requireAiProcessingConsent, int maxTextCharacters,
				int maxDetectCharacters) {
			this(deadline, requireAiProcessingConsent, maxTextCharacters, maxDetectCharacters, null);
		}

		/** The time budget of one request of the feature. */
		public Duration deadlineFor(AiFeature feature) {
			Timeouts t = featureTimeouts.get(feature);
			return t == null ? deadline : t.deadline();
		}

		/** The per-attempt cap of the feature; null = the router's default. */
		public Duration attemptTimeoutFor(AiFeature feature) {
			Timeouts t = featureTimeouts.get(feature);
			return t == null ? null : t.attemptTimeout();
		}
	}

	private final EntitlementResolver entitlements;
	private final ConsentGate consents;
	private final Metering metering;
	private final AiRouter router;
	private final ProviderRegistry registry;
	private final PromptTemplates templates;
	private final PromptRenderer renderer;
	private final LanguageCatalog languages;
	private final AiUserContext users;
	private final Guardrails guardrails;
	private final AiRequestLog requests;
	private final AiIdempotency idempotency;
	private final AiHistorySink history;
	private final Clock clock;
	private final MeterRegistry meters;
	private final Settings settings;

	public AiPipeline(EntitlementResolver entitlements, ConsentGate consents, Metering metering, AiRouter router,
			ProviderRegistry registry, PromptTemplates templates, PromptRenderer renderer, LanguageCatalog languages,
			AiUserContext users, Guardrails guardrails, AiRequestLog requests, AiIdempotency idempotency, Clock clock,
			MeterRegistry meters, Settings settings) {
		this(entitlements, consents, metering, router, registry, templates, renderer, languages, users, guardrails,
				requests, idempotency, AiHistorySink.NONE, clock, meters, settings);
	}

	public AiPipeline(EntitlementResolver entitlements, ConsentGate consents, Metering metering, AiRouter router,
			ProviderRegistry registry, PromptTemplates templates, PromptRenderer renderer, LanguageCatalog languages,
			AiUserContext users, Guardrails guardrails, AiRequestLog requests, AiIdempotency idempotency,
			AiHistorySink history, Clock clock, MeterRegistry meters, Settings settings) {
		this.history = Objects.requireNonNull(history);
		this.entitlements = Objects.requireNonNull(entitlements);
		this.consents = Objects.requireNonNull(consents);
		this.metering = Objects.requireNonNull(metering);
		this.router = Objects.requireNonNull(router);
		this.registry = Objects.requireNonNull(registry);
		this.templates = Objects.requireNonNull(templates);
		this.renderer = Objects.requireNonNull(renderer);
		this.languages = Objects.requireNonNull(languages);
		this.users = Objects.requireNonNull(users);
		this.guardrails = Objects.requireNonNull(guardrails);
		this.requests = Objects.requireNonNull(requests);
		this.idempotency = Objects.requireNonNull(idempotency);
		this.clock = Objects.requireNonNull(clock);
		this.meters = meters;
		this.settings = Objects.requireNonNull(settings);
	}

	// ============================================================================================ entry point

	/**
	 * Runs one AI action end to end.
	 *
	 * @throws ApiException                 a contract Problem (refusal, validation, quota, provider or guardrail failure)
	 * @throws AiRequestCancelledException  the calling thread was interrupted (client gone): released, no body
	 * @throws IllegalStateException        called inside a transaction
	 */
	public AiResult execute(AiCommand command) {
		Objects.requireNonNull(command, "command");
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("AiPipeline must not run inside a transaction (ADR 0011)");
		}
		Instant started = clock.instant();
		AiFeature feature = command.feature();
		UUID user = command.caller().userId();

		// (1) account and consent
		ResolvedEntitlements resolved = entitlements.resolve(user);
		// D33: language detection stays available while the account deletion is pending (typing-adjacent, unmetered,
		// stores nothing); SUSPENDED and every other state are refused as before.
		boolean detectWhilePending = feature == AiFeature.DETECT_LANGUAGE && resolved.access() == AccessState.DELETED;
		if (resolved.access() != AccessState.GRANTED && !detectWhilePending) {
			count(feature, "ACCOUNT_" + resolved.access().name());
			throw denied(resolved.access());
		}
		// D33: the user's ON_DEVICE_ONLY preference is also enforced here: server AI is never called for them.
		if (users.defaults(user).aiProcessingPreference().equals(ON_DEVICE_ONLY)) {
			count(feature, ApiErrorCode.ON_DEVICE_ONLY_PREFERRED.name());
			throw new ApiException(ApiErrorCode.ON_DEVICE_ONLY_PREFERRED, HttpStatus.FORBIDDEN,
					"Server AI is turned off in the AI processing settings of this account.");
		}
		if (settings.requireAiProcessingConsent()) {
			try {
				consents.requireConsent(user, ConsentType.AI_PROCESSING);
			} catch (ApiException e) {
				count(feature, e.code().name());
				throw e;
			}
		}

		// validation and context: nothing is reserved yet, so a bad request costs nothing
		Prepared p;
		try {
			p = prepare(command);
		} catch (ApiException e) {
			count(feature, e.code().name());
			throw e;
		}

		// (2) entitlement
		FeatureEntitlement entitlement = resolved.feature(feature.name()).orElse(null);
		if (entitlement == null || !entitlement.accessEnabled()) {
			reject(p, started, ApiErrorCode.FEATURE_NOT_ENTITLED);
			throw new ApiException(ApiErrorCode.FEATURE_NOT_ENTITLED, HttpStatus.FORBIDDEN,
					"The current plan does not include " + feature.name() + ".");
		}
		if (entitlement.unit() == MeteringUnit.NONE) {
			return run(p, null, null, started); // DETECT_LANGUAGE: unmetered, no reservation, no key
		}

		// (3) reserve
		String key = command.caller().idempotencyKey();
		if (key == null || key.isBlank()) {
			count(feature, ApiErrorCode.VALIDATION_FAILED.name());
			throw validation("Idempotency-Key", "REQUIRED", "The Idempotency-Key header is required.");
		}
		Reservation reservation;
		try {
			reservation = metering.reserve(user, feature.name(), estimate(entitlement.unit(), p), key);
		} catch (QuotaExceededException e) {
			reject(p, started, ApiErrorCode.QUOTA_EXCEEDED);
			throw e;
		} catch (ApiException e) {
			if (e.code() == ApiErrorCode.FEATURE_NOT_ENTITLED) {
				reject(p, started, e.code());
			} else {
				count(feature, e.code().name());
			}
			throw e;
		}

		// (4) idempotency seam (B5.4)
		if (!reservation.replayed()) {
			try {
				idempotency.onReserved(command, reservation);
			} catch (RuntimeException e) {
				log.warn("AI idempotency reserve hook failed: {}", e.getClass().getSimpleName());
				increment("qal.ai.idempotency.hook-failures", feature);
			}
		}
		if (reservation.replayed()) {
			AiIdempotency.Decision decision = idempotency.onReplay(command, reservation);
			return switch (decision) {
				case AiIdempotency.Decision.Respond r -> {
					count(feature, "REPLAYED");
					yield r.result();
				}
				case AiIdempotency.Decision.Fail f -> {
					count(feature, f.problem() instanceof ApiException a ? "REPLAY_" + a.code().name() : "REPLAY_FAILED");
					throw f.problem();
				}
				case AiIdempotency.Decision.Recompute rc -> run(p, null, entitlement.unit(), started);
			};
		}
		return run(p, reservation, entitlement.unit(), started);
	}

	// ============================================================================================ validation

	/**
	 * The request with every default resolved, plus what the guardrails expect. Holds the text: never log it.
	 * {@code destinationLanguage} is the translation target, the output language of a transform that has one, the
	 * reply language or the e-mail language.
	 */
	private record Prepared(AiCommand command, AiFeature feature, UserText text, String sourceLanguage,
			String destinationLanguage, String language, UnicodeScript expectedScript, String expectedLanguage,
			String templateLanguage, String style, boolean preserveNames, Addressee addressee, String mode, String tone,
			String targetLength, boolean numbersMayChange, Map<String, String> promptValues, Extras extras) {
	}

	/**
	 * What only some features use (ADR 0027).
	 *
	 * @param checkInput     all user content the output is checked against (the main text plus notes)
	 * @param protectedInput the user content whose URLs, e-mails and numbers must survive; null = not checked
	 * @param crossLanguage  an explicit output language other than the input's (checked like a translation)
	 * @param parts          further user content for the provider, in order
	 * @param count          SMART_REPLY: replies asked for; 0 otherwise
	 * @param variation      0 = first answer
	 * @param includeSubject DRAFT_DOCUMENT: return the subject line
	 */
	private record Extras(UserText checkInput, UserText protectedInput, boolean crossLanguage, List<ContextPart> parts,
			int count, int variation, boolean includeSubject) {

		static Extras of(UserText text) {
			return new Extras(text, text, false, List.of(), 0, 0, true);
		}
	}

	private Prepared prepare(AiCommand command) {
		switch (command) {
			case AiCommand.Reply r -> {
				return prepareReply(r);
			}
			case AiCommand.Draft d -> {
				return prepareDraft(d);
			}
			default -> {
			}
		}
		int chars = command.text().characters();
		int max = command instanceof AiCommand.Detect ? settings.maxDetectCharacters() : settings.maxTextCharacters();
		if (command.text().value().isBlank()) {
			throw validation("text", "REQUIRED", "The text must not be empty.");
		}
		if (chars > max) {
			throw new ApiException(ApiErrorCode.INPUT_TOO_LONG, HttpStatus.BAD_REQUEST,
					"The text is longer than " + max + " characters.");
		}
		return switch (command) {
			case AiCommand.Translate t -> prepareTranslate(t);
			case AiCommand.Transform t -> prepareTransform(t);
			case AiCommand.Detect d -> prepareDetect(d);
			case AiCommand.Reply r -> prepareReply(r);
			case AiCommand.Draft d -> prepareDraft(d);
		};
	}

	private Prepared prepareTranslate(AiCommand.Translate t) {
		UUID user = t.caller().userId();
		AiUserContext.DestinationProfile profile = null;
		if (t.destinationProfileId() != null) {
			profile = users.profile(user, t.destinationProfileId()).orElseThrow(() -> validation(
					"destinationProfileId", "NOT_FOUND", "The destination profile does not exist."));
		}
		String destination = t.destinationLanguage() != null ? t.destinationLanguage()
				: profile != null ? profile.destinationLanguage() : null;
		if (destination == null) {
			throw validation("destinationLanguage", "REQUIRED", "The destination language is required.");
		}
		LanguageCatalog.Language dst = language(destination, "destinationLanguage");
		LanguageCatalog.Language src = t.sourceLanguage() == null ? null : language(t.sourceLanguage(), "sourceLanguage");
		if (src != null && src.code().equals(dst.code())) {
			throw validation("destinationLanguage", "SAME_LANGUAGE",
					"The destination language must differ from the source language.");
		}
		AiUserContext.Defaults defaults = users.defaults(user);
		String style = first(t.style(), profile == null ? null : profile.style(), defaults.translationStyle());
		check(style, STYLES, "style");
		Addressee addressee = addressee(t.addressee(), profile, defaults);
		AiCommand.CulturalOptions c = t.culturalOptions();
		String calendar = first(c.calendar(), defaults.calendar());
		String time = first(c.timeNotation(), defaults.timeNotation());
		String numerals = first(c.numerals(), defaults.numerals());
		check(calendar, CALENDARS, "culturalOptions.calendar");
		check(time, TIME_NOTATIONS, "culturalOptions.timeNotation");
		check(numerals, NUMERALS, "culturalOptions.numerals");
		boolean adaptIdioms = c.adaptIdioms() == null || c.adaptIdioms();
		boolean preserveNames = t.preserveNames() != null ? t.preserveNames() : defaults.preserveNames();
		// a conversion may legitimately change numbers: calendar dates, clock hours, numerals
		boolean conversions = c.calendar() != null || c.timeNotation() != null || "ETHIOPIC".equals(numerals)
				|| isEthiopic(dst) || (src != null && isEthiopic(src));
		LocalDate today = LocalDate.now(clock.withZone(defaults.timeZone()));
		Map<String, String> values = PromptValues.of(new PromptValues.Inputs(AiFeature.TRANSLATE, null, null, style,
				null, src == null ? null : src.code(), src == null ? null : src.englishName(), dst.code(),
				dst.englishName(), null, null, addressee.formality(), addressee.gender(), calendar, time, numerals,
				adaptIdioms, preserveNames, today));
		return new Prepared(t, AiFeature.TRANSLATE, t.text(), src == null ? null : src.code(), dst.code(), null,
				dst.script(), dst.code(), dst.code(), style, preserveNames, addressee, null, null, null, conversions,
				values, Extras.of(t.text()));
	}

	private Prepared prepareTransform(AiCommand.Transform t) {
		if (!TRANSFORM_TASKS.contains(t.task())) {
			throw validation("task", "NOT_AVAILABLE", "This task is not available.");
		}
		LanguageCatalog.Language lang = t.language() == null ? null : language(t.language(), "language");
		LanguageCatalog.Language out = t.outputLanguage() == null ? null
				: language(t.outputLanguage(), "outputLanguage");
		if (t.task() == AiFeature.GRAMMAR_CORRECTION && out != null) {
			// a correction keeps the language of the text: the output language may only name it
			if (lang != null && !lang.code().equals(out.code())) {
				throw validation("outputLanguage", "NOT_ALLOWED",
						"GRAMMAR_CORRECTION keeps the language of the text.");
			}
			lang = out;
			out = null;
		}
		switch (t.task()) {
			case GRAMMAR_CORRECTION -> check(t.mode(), GRAMMAR_MODES, "options.mode");
			case REWRITE -> check(t.mode(), REWRITE_MODES, "options.mode");
			default -> {
				if (t.mode() != null) {
					throw validation("options.mode", "NOT_ALLOWED", "This task takes no mode.");
				}
			}
		}
		if (t.task() == AiFeature.TONE_ADJUST && t.tone() == null) {
			throw validation("options.tone", "REQUIRED", "TONE_ADJUST needs a tone.");
		}
		check(t.tone(), TONES, "options.tone");
		if (t.task() == AiFeature.FORMAT_TEXT) {
			if (t.format() == null) {
				throw validation("options.format", "REQUIRED", "FORMAT_TEXT needs a format.");
			}
			check(t.format(), FORMATS, "options.format");
		} else if (t.format() != null) {
			throw validation("options.format", "NOT_ALLOWED", "This task takes no format.");
		}
		check(t.targetLength(), LENGTHS, "options.targetLength");
		AiUserContext.Defaults defaults = users.defaults(t.caller().userId());
		Addressee addressee = addressee(t.addressee(), null, defaults);
		UnicodeScript inputScript = lang != null ? lang.script() : dominantScript(t.text().value());
		boolean cross = out != null && differs(lang, inputScript, out);
		UnicodeScript script = out != null ? out.script() : inputScript;
		String expected = out != null ? out.code() : lang == null ? null : lang.code();
		// a change of language may convert numbers (Ethiopic numerals, Ethiopian calendar years)
		boolean conversions = cross && (isEthiopic(out) || inputScript == UnicodeScript.ETHIOPIC);
		LocalDate today = LocalDate.now(clock.withZone(defaults.timeZone()));
		int variation = variation(t.variation());
		Map<String, String> values = PromptValues.of(new PromptValues.Inputs(t.task(), t.mode(), t.tone(), null,
				t.targetLength(), null, null, null, null, lang == null ? null : lang.code(),
				lang == null ? null : lang.englishName(), addressee.formality(), addressee.gender(), null, null, null,
				true, true, today, out == null ? null : out.code(), out == null ? null : out.englishName(), null,
				variation));
		if (t.format() != null) {
			values.put("format", t.format());
		}
		// a summary or an explanation need not repeat every number or link of the text
		boolean keepsTokens = t.task() != AiFeature.SUMMARIZE && t.task() != AiFeature.EXPLAIN_TEXT;
		Extras extras = new Extras(t.text(), keepsTokens ? t.text() : null, cross, List.of(), 0, variation, true);
		return new Prepared(t, t.task(), t.text(), lang == null ? null : lang.code(), out == null ? null : out.code(),
				lang == null ? null : lang.code(), script, expected, lang == null ? "*" : lang.code(), null, true,
				addressee, t.mode(), t.tone(), t.targetLength(), conversions, values, extras);
	}

	private Prepared prepareReply(AiCommand.Reply r) {
		UserText context = r.text();
		if (context.value().isBlank()) {
			throw validation("contextText", "REQUIRED", "contextText must not be empty.");
		}
		tooLong(context, MAX_REPLY_CONTEXT_CHARACTERS, "contextText");
		UserText notes = nonBlank(r.instructions());
		tooLong(notes, MAX_REPLY_INSTRUCTIONS_CHARACTERS, "instructions");
		if (r.replyLanguage() == null) {
			throw validation("replyLanguage", "REQUIRED", "The reply language is required.");
		}
		LanguageCatalog.Language out = language(r.replyLanguage(), "replyLanguage");
		LanguageCatalog.Language ctx = r.contextLanguage() == null ? null
				: language(r.contextLanguage(), "contextLanguage");
		int count = r.count() == null ? DEFAULT_REPLY_COUNT : r.count();
		if (count < 1 || count > MAX_REPLY_COUNT) {
			throw validation("count", "INVALID_VALUE", "count must be between 1 and " + MAX_REPLY_COUNT + ".");
		}
		int variation = variation(r.variation());
		check(r.tone(), TONES, "tone");
		check(r.intent(), INTENTS, "intent");
		AiUserContext.Defaults defaults = users.defaults(r.caller().userId());
		Addressee addressee = addressee(r.addressee(), null, defaults);
		UnicodeScript inputScript = ctx != null ? ctx.script() : dominantScript(context.value());
		boolean cross = differs(ctx, inputScript, out);
		LocalDate today = LocalDate.now(clock.withZone(defaults.timeZone()));
		Map<String, String> values = PromptValues.of(new PromptValues.Inputs(AiFeature.SMART_REPLY, null, r.tone(),
				null, null, ctx == null ? null : ctx.code(), ctx == null ? null : ctx.englishName(), out.code(),
				out.englishName(), ctx == null ? null : ctx.code(), ctx == null ? null : ctx.englishName(),
				addressee.formality(), addressee.gender(), null, null, null, true, true, today, out.code(),
				out.englishName(), count, variation));
		if (r.intent() != null) {
			values.put("intent", r.intent());
		}
		List<ContextPart> parts = notes == null ? List.of() : List.of(new ContextPart(ContextPart.Kind.USER_NOTES, notes));
		// the replies are checked against everything the user gave; only the user's own notes must keep their numbers
		Extras extras = new Extras(joined(context, notes), notes, cross, parts, count, variation, true);
		return new Prepared(r, AiFeature.SMART_REPLY, context, ctx == null ? null : ctx.code(), out.code(),
				ctx == null ? null : ctx.code(), out.script(), out.code(), out.code(), null, true, addressee, null,
				r.tone(), null, false, values, extras);
	}

	private Prepared prepareDraft(AiCommand.Draft d) {
		String type = d.documentType() == null ? "EMAIL" : d.documentType();
		check(type, DOCUMENT_TYPES, "documentType");
		if (!SERVED_DOCUMENT_TYPES.contains(type)) {
			throw validation("documentType", "NOT_AVAILABLE", "This document type is not available.");
		}
		String mode = d.modeOrDefault();
		check(mode, DRAFT_MODES, "mode");
		UserText instructions = nonBlank(d.instructions());
		UserText original = nonBlank(d.originalEmail());
		UserText draft = nonBlank(d.draft());
		if (!"REPLY".equals(mode) && original != null) {
			throw validation("originalEmail", "NOT_ALLOWED", "originalEmail is only used with mode REPLY.");
		}
		if (!"IMPROVE".equals(mode) && draft != null) {
			throw validation("draft", "NOT_ALLOWED", "draft is only used with mode IMPROVE.");
		}
		switch (mode) {
			case "REPLY" -> {
				required(original, "originalEmail");
				required(instructions, "instructions");
			}
			case "IMPROVE" -> required(draft, "draft");
			default -> required(instructions, "instructions");
		}
		tooLong(instructions, MAX_DRAFT_INSTRUCTIONS_CHARACTERS, "instructions");
		tooLong(original, MAX_DRAFT_EMAIL_CHARACTERS, "originalEmail");
		tooLong(draft, MAX_DRAFT_EMAIL_CHARACTERS, "draft");
		UserText recipientName = nonBlank(d.recipientName());
		UserText recipientRole = nonBlank(d.recipientRole());
		UserText senderName = nonBlank(d.senderName());
		tooLong(recipientName, MAX_NAME_CHARACTERS, "recipient.name");
		tooLong(recipientRole, MAX_NAME_CHARACTERS, "recipient.role");
		tooLong(senderName, MAX_NAME_CHARACTERS, "senderName");
		if (d.language() == null) {
			throw validation("language", "REQUIRED", "The language of the e-mail is required.");
		}
		LanguageCatalog.Language out = language(d.language(), "language");
		String tone = d.tone() == null ? "PROFESSIONAL" : d.tone();
		check(tone, EMAIL_TONES, "tone");
		String length = d.length() == null ? "MEDIUM" : d.length();
		check(length, LENGTHS, "length");
		int variation = variation(d.variation());
		AiUserContext.Defaults defaults = users.defaults(d.caller().userId());
		Addressee addressee = addressee(d.addressee(), null, defaults);
		AiCommand.CulturalOptions c = d.culturalOptions();
		String calendar = first(c.calendar(), defaults.calendar());
		String time = first(c.timeNotation(), defaults.timeNotation());
		String numerals = first(c.numerals(), defaults.numerals());
		check(calendar, CALENDARS, "culturalOptions.calendar");
		check(time, TIME_NOTATIONS, "culturalOptions.timeNotation");
		check(numerals, NUMERALS, "culturalOptions.numerals");

		UserText main = d.text();
		UserText notes = "NEW".equals(mode) ? null : instructions;
		UserText all = joined(main, notes);
		UnicodeScript inputScript = dominantScript(all.value());
		boolean cross = differs(null, inputScript, out);
		boolean conversions = c.calendar() != null || c.timeNotation() != null || "ETHIOPIC".equals(numerals)
				|| isEthiopic(out) || inputScript == UnicodeScript.ETHIOPIC;
		LocalDate today = LocalDate.now(clock.withZone(defaults.timeZone()));
		Map<String, String> values = PromptValues.of(new PromptValues.Inputs(AiFeature.DRAFT_DOCUMENT, mode, tone,
				null, length, null, null, out.code(), out.englishName(), null, null, addressee.formality(),
				addressee.gender(), calendar, time, numerals, true, true, today, out.code(), out.englishName(), null,
				variation));
		values.put("documentType", type);
		List<ContextPart> parts = new ArrayList<>();
		if (notes != null) {
			parts.add(new ContextPart(ContextPart.Kind.USER_NOTES, notes));
		}
		if (recipientName != null) {
			parts.add(new ContextPart(ContextPart.Kind.RECIPIENT_NAME, recipientName));
		}
		if (recipientRole != null) {
			parts.add(new ContextPart(ContextPart.Kind.RECIPIENT_ROLE, recipientRole));
		}
		if (senderName != null) {
			parts.add(new ContextPart(ContextPart.Kind.SENDER_NAME, senderName));
		}
		// what the user wrote must keep its links and numbers: the description, the notes on a reply, the draft
		UserText protectedInput = switch (mode) {
			case "REPLY" -> instructions;
			default -> main;
		};
		Extras extras = new Extras(all, protectedInput, cross, List.copyOf(parts), 0, variation,
				d.includeSubject() == null || d.includeSubject());
		return new Prepared(d, AiFeature.DRAFT_DOCUMENT, main, null, out.code(), null, out.script(), out.code(),
				out.code(), null, true, addressee, mode, tone, length, conversions, values, extras);
	}

	private Prepared prepareDetect(AiCommand.Detect d) {
		for (String c : d.candidates()) {
			language(c, "candidates");
		}
		return new Prepared(d, AiFeature.DETECT_LANGUAGE, d.text(), null, null, null, null, null, "*", null, false,
				Addressee.UNSPECIFIED, null, null, null, false, Map.of(), Extras.of(d.text()));
	}

	/**
	 * True when the output language is not the input's: the known input language differs, or (unknown input language)
	 * the input is mostly in another script. Two Latin-script languages of unknown input count as the same.
	 */
	private static boolean differs(LanguageCatalog.Language input, UnicodeScript inputScript,
			LanguageCatalog.Language out) {
		if (input != null) {
			return !input.code().equals(out.code());
		}
		return inputScript != null && out.script() != null && inputScript != out.script();
	}

	private static UserText nonBlank(UserText t) {
		return t == null || t.value().isBlank() ? null : t;
	}

	private static void required(UserText t, String field) {
		if (t == null) {
			throw validation(field, "REQUIRED", field + " is required for this mode.");
		}
	}

	private static void tooLong(UserText t, int max, String field) {
		if (t != null && t.characters() > max) {
			throw new ApiException(ApiErrorCode.INPUT_TOO_LONG, HttpStatus.BAD_REQUEST,
					"The text in " + field + " is longer than " + max + " characters.",
					List.of(new ApiFieldError(field, "TOO_LONG", "At most " + max + " characters.")), Map.of());
		}
	}

	private static int variation(Integer v) {
		int value = v == null ? 0 : v;
		if (value < 0 || value > MAX_VARIATION) {
			throw validation("variation", "INVALID_VALUE", "variation must be between 0 and " + MAX_VARIATION + ".");
		}
		return value;
	}

	/** The main text and the notes as one text for the output checks (never sent anywhere). */
	private static UserText joined(UserText main, UserText notes) {
		return notes == null ? main : UserText.of(main.value() + "\n\n" + notes.value());
	}

	private LanguageCatalog.Language language(String code, String field) {
		return languages.active(code).orElseThrow(() -> new ApiException(ApiErrorCode.LANGUAGE_NOT_SUPPORTED,
				HttpStatus.BAD_REQUEST, "The language in " + field + " is not supported.",
				List.of(new ApiFieldError(field, "LANGUAGE_NOT_SUPPORTED", "This language is not supported.")),
				Map.of()));
	}

	/** SD 11.5: each addressee field from the request, then the destination profile, then the preferences. */
	private static Addressee addressee(Addressee request, AiUserContext.DestinationProfile profile,
			AiUserContext.Defaults defaults) {
		Addressee.Formality formality = request.formality() != null ? request.formality()
				: Addressee.Formality.valueOf(first(profile == null ? null : profile.formality(), defaults.formality()));
		Addressee.Gender gender = request.gender() != null ? request.gender()
				: Addressee.Gender.valueOf(first(profile == null ? null : profile.gender(), defaults.gender()));
		return new Addressee(formality, gender);
	}

	private static boolean isEthiopic(LanguageCatalog.Language l) {
		return l != null && l.script() == UnicodeScript.ETHIOPIC;
	}

	/** The script most of the text's letters are in; null without letters. */
	static UnicodeScript dominantScript(String text) {
		Map<UnicodeScript, Integer> counts = new HashMap<>();
		text.codePoints().filter(Character::isLetter).mapToObj(UnicodeScript::of)
				.filter(s -> s != UnicodeScript.COMMON && s != UnicodeScript.INHERITED)
				.forEach(s -> counts.merge(s, 1, Integer::sum));
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

	@SafeVarargs
	private static <T> T first(T... values) {
		for (T v : values) {
			if (v != null) {
				return v;
			}
		}
		return null;
	}

	private static void check(String value, Set<String> allowed, String field) {
		if (value != null && !allowed.contains(value)) {
			throw validation(field, "INVALID_VALUE", "The value of " + field + " is not allowed.");
		}
	}

	private static ApiException validation(String field, String code, String message) {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, message,
				List.of(new ApiFieldError(field, code, message)), Map.of());
	}

	private static long estimate(MeteringUnit unit, Prepared p) {
		return switch (unit) {
			case REQUEST -> 1L;
			case CHARACTER -> Math.max(1, p.text().characters());
			case SECOND, NONE -> throw new IllegalStateException("text AI is not metered in " + unit);
		};
	}

	// ============================================================================================ execution

	/** The outcome of routing with guardrails. {@code value} is the provider result on success. */
	private record Routed<T>(T value, RouteResult<T> serving, Prompt prompt, String errorCode, boolean cancelled,
			Set<Flag> flags, long providerMillis, List<String> providers) {

		boolean succeeded() {
			return value != null;
		}
	}

	/** A rendered prompt for one provider; {@code template} null = no active template. */
	private record Prompt(PromptTemplates.Template template, String instructions) {

		static final Prompt NONE = new Prompt(null, null);

		@Override
		public String toString() {
			return "Prompt[" + template + "]";
		}
	}

	/**
	 * Steps 5 to 10 for a request that may proceed: {@code reservation} null = nothing to settle (unmetered feature
	 * or an uncharged recompute); {@code unit} null = unmetered feature.
	 */
	private AiResult run(Prepared p, Reservation reservation, MeteringUnit unit, Instant started) {
		Routed<?> routed;
		try {
			routed = switch (p.command()) {
				case AiCommand.Translate t -> routeWithGuardrails(p, translateRoute(p), r -> r.translatedText().value());
				case AiCommand.Transform t -> routeWithGuardrails(p, transformRoute(p), r -> r.resultText().value());
				case AiCommand.Reply r -> routeWithGuardrails(p, transformRoute(p),
						res -> String.join("\n\n", replies(p, res)));
				case AiCommand.Draft d -> routeWithGuardrails(p, transformRoute(p), res -> checkedEmail(p, res));
				case AiCommand.Detect d -> routeWithGuardrails(p, detectRoute(p), null);
			};
		} catch (RuntimeException e) {
			log.error("AI {} pipeline failed: {}", p.feature(), e.getClass().getSimpleName());
			closeFailed(p, reservation, unit, started, null, ApiErrorCode.INTERNAL_ERROR.name(), ReleaseReason.FAILED,
					AiRequestLog.Status.FAILED);
			throw e;
		}

		if (routed.cancelled()) {
			boolean interrupted = Thread.interrupted(); // JDBC refuses to work on an interrupted thread
			try {
				closeFailed(p, reservation, unit, started, routed, null, ReleaseReason.CANCELLED,
						AiRequestLog.Status.CANCELLED);
			} finally {
				if (interrupted) {
					Thread.currentThread().interrupt();
				}
			}
			throw new AiRequestCancelledException();
		}
		if (!routed.succeeded()) {
			AiRequestLog.Status status = ApiErrorCode.CONTENT_REJECTED.name().equals(routed.errorCode())
					? AiRequestLog.Status.REJECTED : AiRequestLog.Status.FAILED;
			closeFailed(p, reservation, unit, started, routed, routed.errorCode(), ReleaseReason.FAILED, status);
			ApiErrorCode code = ApiErrorCode.valueOf(routed.errorCode());
			throw new ApiException(code, code.defaultStatus(), failureDetail(code));
		}
		return succeed(p, reservation, unit, started, routed);
	}

	private static String failureDetail(ApiErrorCode code) {
		return switch (code) {
			case OUTPUT_GUARDRAIL_FAILED -> "The result did not pass the output checks. Nothing was charged.";
			case CONTENT_REJECTED -> "The provider refused this content. Nothing was charged.";
			default -> "The request could not be completed. Nothing was charged.";
		};
	}

	// ----------------------------------------------------------------------------------- route + guardrails

	/** The per-feature route: the call renders nothing itself, it looks up the prompt prepared for its provider. */
	private record FeatureRoute<T>(RouteRequest<T> request, Map<String, Prompt> prompts,
			Function<T, ProviderCallMetadata> meta, Function<T, ProviderUsage> usage) {
	}

	private RouteRequest<TranslateResult> baseTranslate(Prepared p) {
		return RouteRequest.translate(new TranslateRequest(p.text(), p.sourceLanguage(), p.destinationLanguage(),
				p.style(), p.preserveNames(), p.addressee(), null), null, p.command().caller().dataPolicy());
	}

	private FeatureRoute<TranslateResult> translateRoute(Prepared p) {
		RouteRequest<TranslateResult> base = baseTranslate(p);
		Map<String, Prompt> prompts = prompts(p, base);
		RouteRequest<TranslateResult> request = base.withCall((adapter, timeout) -> adapter.translate(
				new TranslateRequest(p.text(), p.sourceLanguage(), p.destinationLanguage(), p.style(),
						p.preserveNames(), p.addressee(), prompts.getOrDefault(adapter.code(), Prompt.NONE).instructions()),
				timeout));
		return new FeatureRoute<>(request, prompts, TranslateResult::call, TranslateResult::usage);
	}

	private FeatureRoute<TransformResult> transformRoute(Prepared p) {
		Extras x = p.extras();
		TransformRequest spi = new TransformRequest(p.feature(), p.text(), p.language(), p.mode(), p.tone(),
				p.targetLength(), p.destinationLanguage(), p.addressee(), null, x.parts(), x.count(), x.variation());
		RouteRequest<TransformResult> base = RouteRequest.transform(spi, null, p.command().caller().dataPolicy());
		Map<String, Prompt> prompts = prompts(p, base);
		RouteRequest<TransformResult> request = base.withCall((adapter, timeout) -> adapter.llmTransform(
				spi.withInstructions(prompts.getOrDefault(adapter.code(), Prompt.NONE).instructions()), timeout));
		return new FeatureRoute<>(request, prompts, TransformResult::call, TransformResult::usage);
	}

	/** SMART_REPLY: the replies read from the provider's output (at most the requested count). */
	private static List<String> replies(Prepared p, TransformResult r) {
		return OutputFormats.replies(r.resultText().value(), p.extras().count());
	}

	/** DRAFT_DOCUMENT: what the guardrails check, subject and body; empty when there is no body. */
	private static String checkedEmail(Prepared p, TransformResult r) {
		OutputFormats.Email e = OutputFormats.email(r.resultText().value());
		return e.body().isBlank() ? "" : e.text();
	}

	private FeatureRoute<DetectResult> detectRoute(Prepared p) {
		AiCommand.Detect d = (AiCommand.Detect) p.command();
		RouteRequest<DetectResult> request = RouteRequest.detect(new DetectRequest(p.text(), d.candidates()), null,
				d.caller().dataPolicy());
		return new FeatureRoute<>(request, Map.of(), DetectResult::call, DetectResult::usage);
	}

	/**
	 * Renders the prompt for every provider the route may try, before any call: the template depends on the provider's
	 * model family, and rendering (or a template lookup failing) must never happen inside a provider attempt, where it
	 * would count against the provider's health.
	 */
	private Map<String, Prompt> prompts(Prepared p, RouteRequest<?> base) {
		Map<String, Prompt> prompts = new HashMap<>();
		List<ResolvedProvider> candidates = new ArrayList<>(registry.resolve(base.query()));
		candidates.addAll(registry.resolveLastResort(base.query()));
		for (ResolvedProvider c : candidates) {
			prompts.computeIfAbsent(c.code(), code -> templates
					.select(p.feature().name(), p.templateLanguage(), code, c.capability().modelCode())
					.map(t -> new Prompt(t, renderer.render(t.body(), p.promptValues()))).orElse(Prompt.NONE));
		}
		if (prompts.values().stream().anyMatch(pr -> pr.template() == null)) {
			increment("qal.ai.prompt.template.missing", p.feature());
		}
		return prompts;
	}

	/**
	 * Route, then guardrails; on a failed check one retry on the same provider, then one fallback route over the
	 * providers not yet tried, all inside {@link Settings#deadline()}.
	 *
	 * @param text the output text to check; null = no output checks (language detection)
	 */
	private <T> Routed<T> routeWithGuardrails(Prepared p, FeatureRoute<T> route, Function<T, String> text) {
		long start = System.nanoTime();
		Duration budget = settings.deadlineFor(p.feature());
		Duration attempt = settings.attemptTimeoutFor(p.feature()); // null = the router's default
		long deadline = budget.toNanos();
		Set<Flag> flags = EnumSet.noneOf(Flag.class);
		Set<String> tried = new LinkedHashSet<>();
		long[] providerNanos = { 0 };
		RouteRequest<T> base = route.request().withLimits(budget, attempt, 0);

		RouteResult<T> first = router.route(base);
		account(first, tried, providerNanos);
		if (!first.succeeded() || text == null) {
			return finish(route, first, flags, tried, providerNanos[0]);
		}
		Set<Flag> found = check(p, route, first, text);
		if (found.isEmpty()) {
			return finish(route, first, flags, tried, providerNanos[0]);
		}
		flags.addAll(found);
		guardrailFailed(p, found);

		for (int step = 0; step < 2; step++) {
			Duration left = Duration.ofNanos(deadline - (System.nanoTime() - start));
			if (!left.isPositive()) {
				break;
			}
			// step 0: one retry on the same provider; step 1: one fallback route over the providers not yet tried
			RouteRequest<T> next = step == 0 ? base.withLimits(left, attempt, 0).pinnedTo(first.providerCode())
					: base.withLimits(left, attempt, 0).excluding(tried);
			RouteResult<T> r = router.route(next);
			account(r, tried, providerNanos);
			if (r.outcome() == RouteResult.Outcome.CANCELLED || r.outcome() == RouteResult.Outcome.CONTENT_REJECTED) {
				return finish(route, r, flags, tried, providerNanos[0]);
			}
			if (!r.succeeded()) {
				continue; // a provider failure on the retry or fallback leaves the guardrail failure as the answer
			}
			Set<Flag> again = check(p, route, r, text);
			if (again.isEmpty()) {
				return finish(route, r, flags, tried, providerNanos[0]);
			}
			flags.addAll(again);
			guardrailFailed(p, again);
		}
		log.warn("AI {} output failed guardrails {} on providers {}; nothing charged", p.feature(), flags, tried);
		return new Routed<>(null, null, null, ApiErrorCode.OUTPUT_GUARDRAIL_FAILED.name(), false, flags,
				Duration.ofNanos(providerNanos[0]).toMillis(), List.copyOf(tried));
	}

	private static void account(RouteResult<?> r, Set<String> tried, long[] providerNanos) {
		for (RouteResult.Attempt a : r.attempts()) {
			tried.add(a.providerCode());
			providerNanos[0] += a.latency().toNanos();
		}
	}

	private <T> Routed<T> finish(FeatureRoute<T> route, RouteResult<T> r, Set<Flag> flags, Set<String> tried,
			long providerNanos) {
		if (r.succeeded()) {
			ProviderCallMetadata meta = route.meta().apply(r.value());
			return new Routed<>(r.value(), r, route.prompts().getOrDefault(r.providerCode(), Prompt.NONE), null, false,
					flags, meta.latency().toMillis(), List.copyOf(tried));
		}
		boolean cancelled = r.outcome() == RouteResult.Outcome.CANCELLED;
		return new Routed<>(null, r, lastPrompt(route, r), cancelled ? null : r.contractCode(), cancelled, flags,
				Duration.ofNanos(providerNanos).toMillis(), List.copyOf(tried));
	}

	private static Prompt lastPrompt(FeatureRoute<?> route, RouteResult<?> r) {
		if (r.attempts().isEmpty()) {
			return Prompt.NONE;
		}
		return route.prompts().getOrDefault(r.attempts().get(r.attempts().size() - 1).providerCode(), Prompt.NONE);
	}

	private <T> Set<Flag> check(Prepared p, FeatureRoute<T> route, RouteResult<T> r, Function<T, String> text) {
		Prompt prompt = route.prompts().getOrDefault(r.providerCode(), Prompt.NONE);
		Extras x = p.extras();
		return guardrails.check(x.checkInput().value(), x.protectedInput() == null ? null : x.protectedInput().value(),
				text.apply(r.value()), expectation(p, prompt));
	}

	private static Guardrails.Expectation expectation(Prepared p, Prompt prompt) {
		return new Guardrails.Expectation(p.feature(), p.expectedScript(), p.expectedLanguage(), p.mode(),
				p.numbersMayChange(), prompt.instructions(), p.extras().crossLanguage());
	}

	private void guardrailFailed(Prepared p, Set<Flag> flags) {
		if (meters != null) {
			for (Flag f : flags) {
				meters.counter("qal.ai.guardrail.failures", "feature", p.feature().name(), "check", f.name())
						.increment();
			}
		}
	}

	// ----------------------------------------------------------------------------------- settle / release

	private AiResult succeed(Prepared p, Reservation reservation, MeteringUnit unit, Instant started, Routed<?> routed) {
		ProviderUsage usage = switch (routed.value()) {
			case TranslateResult t -> t.usage();
			case TransformResult t -> t.usage();
			case DetectResult d -> d.usage();
			default -> throw new IllegalStateException("unknown provider result");
		};
		ProviderCallMetadata meta = switch (routed.value()) {
			case TranslateResult t -> t.call();
			case TransformResult t -> t.call();
			case DetectResult d -> d.call();
			default -> throw new IllegalStateException("unknown provider result");
		};

		AiResult.UsageSummary summary = null;
		String usageSource = "UNMETERED";
		long units = 0;
		BigDecimal credits = BigDecimal.ZERO;
		if (reservation != null) {
			long actual = usage.units(MeteredUnit.valueOf(unit.name()));
			try {
				Settlement s = metering.settle(reservation.id(), actual);
				summary = summary(s);
				usageSource = s.usageSource().name();
				units = s.chargedUnits();
				credits = s.creditsCharged();
			} catch (ReservationClosedException e) {
				// released meanwhile (expiry sweep): the user is not charged; answer anyway
				log.warn("AI {} reservation was closed ({}) before settling; not charged", p.feature(), e.status());
				summary = unmetered(p, unit);
			} catch (RuntimeException e) {
				log.error("AI {} settle failed: {}", p.feature(), e.getClass().getSimpleName());
				release(reservation, ReleaseReason.FAILED, p);
				record(p, reservation, started, routed, AiRequestLog.Status.FAILED, ApiErrorCode.INTERNAL_ERROR.name(),
						"UNMETERED", 0, BigDecimal.ZERO);
				throw e;
			}
		} else if (unit != null) {
			summary = unmetered(p, unit); // uncharged recompute of an already settled key
		}

		UUID requestId = reservation != null ? reservation.id() : uuid7(started);
		record(p, reservation, requestId, started, routed, AiRequestLog.Status.SUCCEEDED, null, usageSource, units,
				credits);
		AiResult result = response(p, routed, requestId, summary);
		if (reservation != null) {
			result = keepHistory(p, routed, requestId, result);
		}
		count(p.feature(), "SUCCEEDED");
		if (reservation != null) {
			try {
				idempotency.onCompleted(p.command(), requestId, result);
			} catch (RuntimeException e) {
				log.warn("AI idempotency hook failed: {}", e.getClass().getSimpleName());
				increment("qal.ai.idempotency.hook-failures", p.feature());
			}
		}
		return result;
	}

	/**
	 * B5.7: offers a newly charged, settled request that said {@code saveToHistory} to the history sink (which applies the
	 * user's gates), before the replay cache keeps the response, so a replay carries the same {@code historyItemId} and
	 * never writes again. Only the request that reserved its key offers: a replay answered from the cache and an
	 * uncharged recompute (no reservation) do not. A sink failure leaves the response without an item id.
	 */
	private AiResult keepHistory(Prepared p, Routed<?> routed, UUID requestId, AiResult result) {
		boolean save = switch (p.command()) {
			case AiCommand.Translate t -> t.saveToHistory();
			case AiCommand.Transform t -> t.saveToHistory();
			case AiCommand.Reply r -> r.saveToHistory();
			case AiCommand.Draft d -> d.saveToHistory();
			case AiCommand.Detect d -> false;
		};
		String output = switch (result) {
			case AiResult.TranslateResponse t -> t.translatedText();
			case AiResult.TransformResponse t -> t.resultText();
			case AiResult.RepliesResponse r -> String.join("\n\n",
					r.suggestions().stream().map(AiResult.RepliesResponse.Suggestion::text).toList());
			case AiResult.DraftResponse d -> d.subject() == null ? d.body() : d.subject() + "\n\n" + d.body();
			default -> null;
		};
		if (!save || output == null) {
			return result;
		}
		try {
			String detected = routed.value() instanceof TranslateResult t ? t.detectedSourceLanguage() : null;
			String source = p.sourceLanguage() != null ? p.sourceLanguage() : detected;
			return history.offer(new AiHistorySink.Entry(p.command().caller().userId(), requestId, p.feature().name(),
					source, p.destinationLanguage(), p.text().value(), output)).map(result::withHistoryItemId)
					.orElse(result);
		}
		catch (RuntimeException e) {
			log.warn("AI {} history write failed: {}", p.feature(), e.getClass().getSimpleName());
			increment("qal.ai.history.write-failures", p.feature());
			return result;
		}
	}

	private static AiResult.UsageSummary unmetered(Prepared p, MeteringUnit unit) {
		return new AiResult.UsageSummary(p.feature().name(), 0, unit.name(), "UNMETERED", null, null, null, null,
				null);
	}

	private static AiResult.UsageSummary summary(Settlement s) {
		boolean credits = s.creditUnits() > 0;
		return new AiResult.UsageSummary(s.featureCode(), s.chargedUnits(), s.unit().name(), s.usageSource().name(),
				s.remainingAllowance(),
				s.creditBalance() == null ? null : s.creditBalance().setScale(0, RoundingMode.FLOOR),
				credits ? s.creditsCharged() : null, credits && s.allowanceUnits() > 0 ? s.allowanceUnits() : null,
				s.resetAt());
	}

	/** Release (when reserved) and record a request that produced nothing for the user. Never throws. */
	private void closeFailed(Prepared p, Reservation reservation, MeteringUnit unit, Instant started, Routed<?> routed,
			String errorCode, ReleaseReason reason, AiRequestLog.Status status) {
		if (reservation != null) {
			release(reservation, reason, p);
		}
		record(p, reservation, started, routed, status, errorCode, "UNMETERED", 0, BigDecimal.ZERO);
		count(p.feature(), status == AiRequestLog.Status.CANCELLED ? "CANCELLED" : errorCode);
	}

	private void release(Reservation reservation, ReleaseReason reason, Prepared p) {
		try {
			metering.release(reservation.id(), reason);
		} catch (RuntimeException e) {
			// the expiry sweep releases it within the reservation TTL: still never charged
			log.error("AI {} release failed ({}); the sweeper will release it", p.feature(),
					e.getClass().getSimpleName());
		}
	}

	/** A refusal before any reservation (entitlement, quota): a REJECTED row without the key, so it can be retried. */
	private void reject(Prepared p, Instant started, ApiErrorCode code) {
		record(p, null, uuid7(started), started, null, AiRequestLog.Status.REJECTED, code.name(), "UNMETERED", 0,
				BigDecimal.ZERO);
		count(p.feature(), code.name());
	}

	private void record(Prepared p, Reservation reservation, Instant started, Routed<?> routed,
			AiRequestLog.Status status, String errorCode, String usageSource, long units, BigDecimal credits) {
		UUID id = reservation != null ? reservation.id() : uuid7(started);
		record(p, reservation, id, started, routed, status, errorCode, usageSource, units, credits);
	}

	private void record(Prepared p, Reservation reservation, UUID id, Instant started, Routed<?> routed,
			AiRequestLog.Status status, String errorCode, String usageSource, long units, BigDecimal credits) {
		try {
			ProviderUsage usage = null;
			ProviderCallMetadata meta = null;
			String detectedSource = null;
			if (routed != null && routed.value() != null) {
				switch (routed.value()) {
					case TranslateResult t -> {
						usage = t.usage();
						meta = t.call();
						detectedSource = t.detectedSourceLanguage();
					}
					case TransformResult t -> {
						usage = t.usage();
						meta = t.call();
					}
					case DetectResult d -> {
						usage = d.usage();
						meta = d.call();
					}
					default -> {
					}
				}
			}
			String provider = meta != null ? meta.providerCode()
					: routed != null && !routed.providers().isEmpty()
							? routed.providers().get(routed.providers().size() - 1) : null;
			PromptTemplates.Template template = routed == null || routed.prompt() == null ? null
					: routed.prompt().template();
			Collection<String> flags = routed == null ? List.of()
					: routed.flags().stream().map(Enum::name).toList();
			Instant completed = clock.instant();
			requests.write(new AiRequestLog.Entry(id, p.command().caller().userId(), p.command().caller().deviceId(),
					p.feature().name(), p.command().caller().surface().name(),
					p.sourceLanguage() != null ? p.sourceLanguage() : detectedSource, p.destinationLanguage(), provider,
					meta == null ? null : meta.modelCode(), template == null ? null : template.id(), status,
					usageSource, units, credits,
					usage != null ? usage.inputCharacters() : p.text().characters(),
					usage != null ? usage.outputCharacters() : 0, usage == null ? null : usage.inputTokens(),
					usage == null ? null : usage.outputTokens(), routed == null ? null : routed.providerMillis(), flags,
					reservation != null ? p.command().caller().idempotencyKey() : null, errorCode, started,
					completed.isBefore(started) ? started : completed));
		} catch (RuntimeException e) {
			// metadata is operational: losing a row must not change the user's outcome
			log.warn("Writing ai_requests for {} failed: {}", p.feature(), e.getClass().getSimpleName());
			increment("qal.ai.requests.write-failures", p.feature());
		}
	}

	// ----------------------------------------------------------------------------------- response mapping

	private AiResult response(Prepared p, Routed<?> routed, UUID requestId, AiResult.UsageSummary usage) {
		QualityTier tier = routed.serving().capability() == null ? null : routed.serving().capability().qualityTier();
		List<AiResult.AiNote> notes = notes(p, tier);
		return switch (p.command()) {
			case AiCommand.Reply r -> repliesResponse(p, r.tone(), (TransformResult) routed.value(), requestId, notes,
					tier, usage);
			case AiCommand.Draft d -> draftResponse(p, (TransformResult) routed.value(), requestId, notes, tier, usage);
			default -> providerResponse(p, routed, requestId, notes, tier, usage);
		};
	}

	private static AiResult repliesResponse(Prepared p, String tone, TransformResult result, UUID requestId,
			List<AiResult.AiNote> notes, QualityTier tier, AiResult.UsageSummary usage) {
		List<AiResult.RepliesResponse.Suggestion> suggestions = replies(p, result).stream()
				.map(s -> new AiResult.RepliesResponse.Suggestion(s, tone)).toList();
		return new AiResult.RepliesResponse(requestId, p.destinationLanguage(), suggestions, notes, tier, false, null,
				usage);
	}

	private static AiResult draftResponse(Prepared p, TransformResult result, UUID requestId,
			List<AiResult.AiNote> notes, QualityTier tier, AiResult.UsageSummary usage) {
		OutputFormats.Email e = OutputFormats.email(result.resultText().value());
		String subject = "REPLY".equals(p.mode()) ? OutputFormats.replySubject(e.subject()) : e.subject();
		if (!p.extras().includeSubject()) {
			subject = null;
		}
		return new AiResult.DraftResponse(requestId, p.mode(), p.destinationLanguage(), subject, e.body(),
				OutputFormats.placeholders(subject, e.body()), notes, tier, false, null, usage);
	}

	private AiResult providerResponse(Prepared p, Routed<?> routed, UUID requestId, List<AiResult.AiNote> notes,
			QualityTier tier, AiResult.UsageSummary usage) {
		return switch (routed.value()) {
			case TranslateResult t -> {
				List<AiResult.Alternative> alternatives = new ArrayList<>();
				for (UserText alt : t.alternatives()) {
					if (alternatives.size() < 4 && guardrails.check(p.text().value(), alt.value(),
							expectation(p, routed.prompt())).isEmpty()) {
						alternatives.add(new AiResult.Alternative(null, alt.value(), null));
					}
				}
				yield new AiResult.TranslateResponse(requestId, p.sourceLanguage(), t.detectedSourceLanguage(),
						t.detectionConfidence(), p.destinationLanguage(), t.translatedText().value(),
						List.copyOf(alternatives), notes, tier, false, null, usage);
			}
			case TransformResult t -> new AiResult.TransformResponse(requestId, p.feature().name(),
					p.destinationLanguage() != null ? p.destinationLanguage() : p.language(), t.resultText().value(),
					t.edits().stream().map(e -> new AiResult.TextEdit(e.start(), e.end(), null,
							e.replacement() == null ? null : e.replacement().value(), e.category())).toList(),
					List.of(), notes, tier, false, null, usage);
			case DetectResult d -> new AiResult.DetectLanguageResponse(
					d.languages().stream().map(l -> new AiResult.DetectLanguageResponse.DetectedLanguage(
							l.languageCode(), l.scriptCode(), l.confidence())).toList(),
					d.mixedLanguage(), d.romanizedNative());
			default -> throw new IllegalStateException("unknown provider result");
		};
	}

	/** Static notes only (SD 11.2 BETA notice, SD 11.5 ambiguity); never text derived from the user's content. */
	private static List<AiResult.AiNote> notes(Prepared p, QualityTier tier) {
		List<AiResult.AiNote> notes = new ArrayList<>();
		if (tier == QualityTier.BETA) {
			notes.add(new AiResult.AiNote("QUALITY", "Beta quality: please review the result before sending."));
		}
		if (p.feature() == AiFeature.TRANSLATE && p.expectedScript() == UnicodeScript.ETHIOPIC
				&& p.addressee().gender() == Addressee.Gender.UNKNOWN
				&& p.addressee().formality() != Addressee.Formality.HONORIFIC) {
			notes.add(new AiResult.AiNote("AMBIGUITY",
					"The addressee's gender is unknown, so a neutral form was requested. Check the form of address."));
		}
		return List.copyOf(notes);
	}

	// ----------------------------------------------------------------------------------- helpers

	private static ApiException denied(AccessState access) {
		return switch (access) {
			case SUSPENDED -> new ApiException(ApiErrorCode.ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN, null);
			case DELETED -> new ApiException(ApiErrorCode.DELETION_PENDING, HttpStatus.FORBIDDEN,
					"AI features are not available while the account deletion is pending.");
			case MERGED -> new ApiException(ApiErrorCode.GUEST_SESSION_INVALID, HttpStatus.UNAUTHORIZED, null);
			case PENDING, UNKNOWN, GRANTED -> new ApiException(ApiErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED,
					null);
		};
	}

	private void count(AiFeature feature, String outcome) {
		if (meters != null) {
			meters.counter("qal.ai.pipeline.requests", "feature", feature.name(), "outcome", outcome).increment();
		}
	}

	private void increment(String name, AiFeature feature) {
		if (meters != null) {
			meters.counter(name, "feature", feature.name()).increment();
		}
	}

	/** Time-ordered id for {@code ai_requests} rows without a reservation (V1: app-generated UUIDv7). */
	static UUID uuid7(Instant now) {
		long ms = now.toEpochMilli();
		long msb = (ms << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
		long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
		return new UUID(msb, lsb);
	}
}
