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
import com.qalsmartkeyboard.aigateway.pipeline.PromptRenderer;
import com.qalsmartkeyboard.aigateway.pipeline.PromptTemplates;
import com.qalsmartkeyboard.aigateway.pipeline.PromptValues;
import com.qalsmartkeyboard.aigateway.spi.Addressee;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
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
 */
public class AiPipeline {

	private static final Logger log = LoggerFactory.getLogger(AiPipeline.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	private static final String ON_DEVICE_ONLY = "ON_DEVICE_ONLY";

	private static final Set<AiFeature> TRANSFORM_TASKS = EnumSet.of(AiFeature.GRAMMAR_CORRECTION, AiFeature.REWRITE,
			AiFeature.TONE_ADJUST);
	private static final Set<String> STYLES = Set.of("LITERAL", "NATURAL", "POLITE", "PROFESSIONAL", "FRIENDLY");
	private static final Set<String> TONES = Set.of("FRIENDLY", "POLITE", "PROFESSIONAL", "CONCISE", "APOLOGETIC",
			"HONORIFIC");
	private static final Set<String> GRAMMAR_MODES = Set.of("GRAMMAR", "SPELLING_ONLY");
	private static final Set<String> REWRITE_MODES = Set.of("NATURAL", "SHORTEN", "EXPAND", "CLARIFY");
	private static final Set<String> LENGTHS = Set.of("SHORT", "MEDIUM", "LONG");
	private static final Set<String> CALENDARS = Set.of("GREGORIAN", "ETHIOPIAN");
	private static final Set<String> TIME_NOTATIONS = Set.of("INTERNATIONAL", "ETHIOPIAN");
	private static final Set<String> NUMERALS = Set.of("WESTERN", "ETHIOPIC");

	/**
	 * @param deadline                   time budget for all provider calls of one request: route, guardrail retry and
	 *                                   fallback together (the keyboard waits 8 s)
	 * @param requireAiProcessingConsent require the AI_PROCESSING consent for every server AI call
	 * @param maxTextCharacters          contract maxLength of translate/transform text (code points)
	 * @param maxDetectCharacters        contract maxLength of detect-language text
	 */
	public record Settings(Duration deadline, boolean requireAiProcessingConsent, int maxTextCharacters,
			int maxDetectCharacters) {

		public Settings {
			if (deadline == null || !deadline.isPositive() || maxTextCharacters < 1 || maxDetectCharacters < 1) {
				throw new IllegalArgumentException("invalid pipeline settings");
			}
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

	/** The request with every default resolved, plus what the guardrails expect. Holds the text: never log it. */
	private record Prepared(AiCommand command, AiFeature feature, UserText text, String sourceLanguage,
			String destinationLanguage, String language, UnicodeScript expectedScript, String expectedLanguage,
			String templateLanguage, String style, boolean preserveNames, Addressee addressee, String mode, String tone,
			String targetLength, boolean numbersMayChange, Map<String, String> promptValues) {
	}

	private Prepared prepare(AiCommand command) {
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
				values);
	}

	private Prepared prepareTransform(AiCommand.Transform t) {
		if (!TRANSFORM_TASKS.contains(t.task())) {
			throw validation("task", "NOT_AVAILABLE", "This task is not available.");
		}
		LanguageCatalog.Language lang = t.language() == null ? null : language(t.language(), "language");
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
		check(t.targetLength(), LENGTHS, "options.targetLength");
		AiUserContext.Defaults defaults = users.defaults(t.caller().userId());
		Addressee addressee = addressee(t.addressee(), null, defaults);
		UnicodeScript script = lang != null ? lang.script() : dominantScript(t.text().value());
		LocalDate today = LocalDate.now(clock.withZone(defaults.timeZone()));
		Map<String, String> values = PromptValues.of(new PromptValues.Inputs(t.task(), t.mode(), t.tone(), null,
				t.targetLength(), null, null, null, null, lang == null ? null : lang.code(),
				lang == null ? null : lang.englishName(), addressee.formality(), addressee.gender(), null, null, null,
				true, true, today));
		return new Prepared(t, t.task(), t.text(), lang == null ? null : lang.code(), null,
				lang == null ? null : lang.code(), script, lang == null ? null : lang.code(),
				lang == null ? "*" : lang.code(), null, true, addressee, t.mode(), t.tone(), t.targetLength(), false,
				values);
	}

	private Prepared prepareDetect(AiCommand.Detect d) {
		for (String c : d.candidates()) {
			language(c, "candidates");
		}
		return new Prepared(d, AiFeature.DETECT_LANGUAGE, d.text(), null, null, null, null, null, "*", null, false,
				Addressee.UNSPECIFIED, null, null, null, false, Map.of());
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
		return l.script() == UnicodeScript.ETHIOPIC;
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
		RouteRequest<TransformResult> base = RouteRequest.transform(new TransformRequest(p.feature(), p.text(),
				p.language(), p.mode(), p.tone(), p.targetLength(), null, p.addressee(), null), null,
				p.command().caller().dataPolicy());
		Map<String, Prompt> prompts = prompts(p, base);
		RouteRequest<TransformResult> request = base.withCall((adapter, timeout) -> adapter.llmTransform(
				new TransformRequest(p.feature(), p.text(), p.language(), p.mode(), p.tone(), p.targetLength(), null,
						p.addressee(), prompts.getOrDefault(adapter.code(), Prompt.NONE).instructions()),
				timeout));
		return new FeatureRoute<>(request, prompts, TransformResult::call, TransformResult::usage);
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
		long deadline = settings.deadline().toNanos();
		Set<Flag> flags = EnumSet.noneOf(Flag.class);
		Set<String> tried = new LinkedHashSet<>();
		long[] providerNanos = { 0 };
		RouteRequest<T> base = route.request().withLimits(settings.deadline(), null, 0);

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
			RouteRequest<T> next = step == 0 ? base.withLimits(left, null, 0).pinnedTo(first.providerCode())
					: base.withLimits(left, null, 0).excluding(tried);
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
		return guardrails.check(p.text().value(), text.apply(r.value()), expectation(p, prompt));
	}

	private static Guardrails.Expectation expectation(Prepared p, Prompt prompt) {
		return new Guardrails.Expectation(p.feature(), p.expectedScript(), p.expectedLanguage(), p.mode(),
				p.numbersMayChange(), prompt.instructions());
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
			case AiCommand.Detect d -> false;
		};
		String output = switch (result) {
			case AiResult.TranslateResponse t -> t.translatedText();
			case AiResult.TransformResponse t -> t.resultText();
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
			case TransformResult t -> new AiResult.TransformResponse(requestId, p.feature().name(), p.language(),
					t.resultText().value(),
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
