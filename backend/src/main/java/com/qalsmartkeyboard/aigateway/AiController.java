package com.qalsmartkeyboard.aigateway;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.aigateway.AiDtos.Capabilities;
import com.qalsmartkeyboard.aigateway.pipeline.AiUserContext;
import com.qalsmartkeyboard.aigateway.pipeline.LanguageCatalog;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.ProviderCapability;
import com.qalsmartkeyboard.aigateway.spi.ProviderDescriptor;
import com.qalsmartkeyboard.aigateway.spi.QualityTier;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.entitlements.ResolvedEntitlements;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * The AI endpoints of the contract (B5.5): {@code translateText}, {@code transformText} (GRAMMAR_CORRECTION, REWRITE,
 * TONE_ADJUST, SUMMARIZE, EXPLAIN_TEXT; SCRIPT_CONVERSION answers 400 {@code NOT_AVAILABLE}), {@code suggestReplies}
 * (SMART_REPLY) and {@code draftDocument} (DRAFT_DOCUMENT e-mails, ADR 0027), {@code detectLanguage} and
 * {@code getAiCapabilities}. Each maps the body to an {@link AiCommand}, runs {@link AiPipeline#execute} and returns
 * the contract-shaped {@link AiResult}; every refusal and failure is already an {@code ApiException} with its contract
 * code (the global handler writes the Problem). {@code AiRequestCancelledException} (the calling thread was
 * interrupted: a client abort, ADR 0007 D29) is answered with an empty 499 nobody reads.
 *
 * <p>No user text is logged, counted, tagged or kept here; metadata lives in the pipeline's {@code ai_requests} row.
 * {@code /transliterate} exists only in the partner API and is not built here.
 */
@RestController
class AiController {

	/** Per user and minute. Metered features are also limited by their allowance; these bound abuse and cost. */
	static final RateLimitPolicy TRANSLATE_PER_USER = new RateLimitPolicy("ai.translate.user", 30,
			Duration.ofMinutes(1));
	static final RateLimitPolicy TRANSFORM_PER_USER = new RateLimitPolicy("ai.transform.user", 30,
			Duration.ofMinutes(1));
	static final RateLimitPolicy REPLIES_PER_USER = new RateLimitPolicy("ai.replies.user", 30, Duration.ofMinutes(1));
	/** An e-mail is a long LLM call: fewer per minute. */
	static final RateLimitPolicy DRAFTS_PER_USER = new RateLimitPolicy("ai.drafts.user", 10, Duration.ofMinutes(1));
	static final RateLimitPolicy DETECT_PER_USER = new RateLimitPolicy("ai.detect.user", 60, Duration.ofMinutes(1));
	static final RateLimitPolicy FEEDBACK_PER_USER = new RateLimitPolicy("ai.feedback.user", 60, Duration.ofMinutes(1));
	static final RateLimitPolicy CAPABILITIES_PER_CLIENT = new RateLimitPolicy("ai.capabilities.client", 120,
			Duration.ofMinutes(1));

	/** Not a registered status; the connection is gone, so the code only shows up in access logs and metrics. */
	static final int CLIENT_CLOSED_REQUEST = 499;

	private static final Set<AiFeature> TRANSFORM_TASKS = EnumSet.of(AiFeature.GRAMMAR_CORRECTION, AiFeature.REWRITE,
			AiFeature.TONE_ADJUST, AiFeature.SUMMARIZE, AiFeature.EXPLAIN_TEXT, AiFeature.SCRIPT_CONVERSION);
	/** The features this gateway serves today (the rest of {@link AiFeature} answers NOT_AVAILABLE or has no endpoint). */
	private static final Set<AiFeature> SERVED = EnumSet.of(AiFeature.TRANSLATE, AiFeature.DETECT_LANGUAGE,
			AiFeature.GRAMMAR_CORRECTION, AiFeature.REWRITE, AiFeature.TONE_ADJUST, AiFeature.SUMMARIZE,
			AiFeature.EXPLAIN_TEXT, AiFeature.SMART_REPLY, AiFeature.DRAFT_DOCUMENT);

	private final AiPipeline pipeline;
	private final RateLimiter limiter;
	private final ProviderRegistry registry;
	private final EntitlementResolver entitlements;
	private final AiUserContext users;
	private final AiGatewayProperties properties;
	private final Clock clock;
	private final AiRequestParser parser;
	private final AiFeedback feedback;
	private final LanguageCatalog languages;
	private final JsonMapper mapper;

	AiController(AiPipeline pipeline, RateLimiter limiter, ProviderRegistry registry, EntitlementResolver entitlements,
			AiUserContext users, AiGatewayProperties properties, ObjectProvider<Clock> clock, JsonMapper mapper,
			AiFeedback feedback, LanguageCatalog languages) {
		this.pipeline = pipeline;
		this.limiter = limiter;
		this.registry = registry;
		this.entitlements = entitlements;
		this.users = users;
		this.properties = properties;
		this.clock = clock.getIfAvailable(Clock::systemUTC);
		this.parser = new AiRequestParser(mapper);
		this.feedback = feedback;
		this.languages = languages;
		this.mapper = mapper;
	}

	// The bodies arrive as bytes and are read by AiRequestParser: a framework binding error would quote the value.

	@PostMapping(path = "/api/v1/ai/translate", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<AiResult> translateText(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestHeader(name = "Idempotency-Key") String idempotencyKey,
			@RequestHeader(name = "X-Client-Surface", required = false) String surface, @RequestBody byte[] body) {
		RateLimitDecision limit = limiter.tryConsume(TRANSLATE_PER_USER, caller.userId().toString()).orThrow();
		return run(parser.translate(body, caller(caller, key(idempotencyKey), surface)), limit);
	}

	@PostMapping(path = "/api/v1/ai/transform", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<AiResult> transformText(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestHeader(name = "Idempotency-Key") String idempotencyKey,
			@RequestHeader(name = "X-Client-Surface", required = false) String surface, @RequestBody byte[] body) {
		RateLimitDecision limit = limiter.tryConsume(TRANSFORM_PER_USER, caller.userId().toString()).orThrow();
		return run(parser.transform(body, caller(caller, key(idempotencyKey), surface), AiController::task), limit);
	}

	/** ADR 0027: reply suggestions (SMART_REPLY) to a message the user pasted, selected or shared. */
	@PostMapping(path = "/api/v1/ai/replies", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<AiResult> suggestReplies(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestHeader(name = "Idempotency-Key") String idempotencyKey,
			@RequestHeader(name = "X-Client-Surface", required = false) String surface, @RequestBody byte[] body) {
		RateLimitDecision limit = limiter.tryConsume(REPLIES_PER_USER, caller.userId().toString()).orThrow();
		return run(parser.replies(body, caller(caller, key(idempotencyKey), surface)), limit);
	}

	/** ADR 0027: write a new e-mail, a reply to a pasted e-mail or an improved draft (DRAFT_DOCUMENT). */
	@PostMapping(path = "/api/v1/ai/drafts", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<AiResult> draftDocument(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestHeader(name = "Idempotency-Key") String idempotencyKey,
			@RequestHeader(name = "X-Client-Surface", required = false) String surface, @RequestBody byte[] body) {
		RateLimitDecision limit = limiter.tryConsume(DRAFTS_PER_USER, caller.userId().toString()).orThrow();
		return run(parser.drafts(body, caller(caller, key(idempotencyKey), surface)), limit);
	}

	@PostMapping(path = "/api/v1/ai/detect-language", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<AiResult> detectLanguage(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestHeader(name = "X-Client-Surface", required = false) String surface, @RequestBody byte[] body) {
		limiter.tryConsume(DETECT_PER_USER, caller.userId().toString()).orThrow();
		return run(parser.detect(body, caller(caller, null, surface)), null);
	}

	/** B5.6 {@code submitAiFeedback}: see {@link AiFeedback}. 202 without a body. */
	@PostMapping(path = "/api/v1/ai/feedback", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<Void> submitAiFeedback(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestBody byte[] body) {
		limiter.tryConsume(FEEDBACK_PER_USER, caller.userId().toString()).orThrow();
		feedback.submit(caller.userId(), body);
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
	}

	/** The contract's {@code TransformTask}; null for anything else. */
	private static AiFeature task(String code) {
		AiFeature task = AiFeature.fromCode(code);
		return task != null && TRANSFORM_TASKS.contains(task) ? task : null;
	}

	/**
	 * Which tasks and language pairs the server can serve now, from the active providers' enabled BETA/PRODUCTION
	 * capabilities. Anonymous callers see all of them; a signed-in caller only those the plan includes (and nothing
	 * when the account may not use server AI or prefers ON_DEVICE_ONLY), so the UI never offers what would be refused.
	 * ADR 0040: {@code translationLanguages} is the translation catalogue those TRANSLATE capabilities cover (pair rows
	 * and the models' language sets), and the answer carries an ETag over everything but {@code generatedAt}, so a
	 * client revalidates its stored copy with {@code If-None-Match} and gets a 304.
	 */
	@GetMapping("/api/v1/ai/capabilities")
	ResponseEntity<Capabilities> getAiCapabilities(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
			HttpServletRequest request) {
		limiter.tryConsume(CAPABILITIES_PER_CLIENT,
				caller != null ? caller.userId().toString() : request.getRemoteAddr()).orThrow();
		Map<String, ProviderCapability> best = new LinkedHashMap<>();
		List<ProviderCapability> translations = new ArrayList<>();
		Predicate<AiFeature> visible = visibleTo(caller);
		for (ProviderDescriptor d : registry.activeDescriptors()) {
			for (ProviderCapability c : d.capabilities()) {
				if (!c.qualityTier().userExposed() || !SERVED.contains(c.feature())) {
					continue;
				}
				best.merge(c.feature() + "|" + c.sourceLanguage() + "|" + c.destinationLanguage(), c,
						AiController::better);
				if (c.feature() == AiFeature.TRANSLATE && visible.test(AiFeature.TRANSLATE)) {
					translations.add(c);
				}
			}
		}
		List<Capabilities.Capability> items = best.values().stream().filter(c -> visible.test(c.feature()))
				.sorted(Comparator.comparing((ProviderCapability c) -> c.feature().name())
						.thenComparing(c -> String.valueOf(c.sourceLanguage()))
						.thenComparing(c -> String.valueOf(c.destinationLanguage())))
				.map(this::capability).toList();
		List<Capabilities.TranslationLanguage> catalogue = translationLanguages(translations);
		String etag = etag(items, catalogue);
		CacheControl cache = CacheControl.noCache().cachePrivate();
		if (matches(ifNoneMatch, etag)) {
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(cache).eTag(etag).build();
		}
		return ResponseEntity.ok().cacheControl(cache).eTag(etag)
				.body(new Capabilities(clock.instant(), items, catalogue));
	}

	/**
	 * ADR 0040: every active catalogue language some visible TRANSLATE capability serves, in catalogue order. A language
	 * is a source when a row serves it as one (and detectable when such a row detects), a destination likewise; its
	 * tier is the best tier among the rows that serve it. Codes the catalogue does not know (or has deactivated) are
	 * left out: a request naming them is refused with LANGUAGE_NOT_SUPPORTED anyway.
	 */
	private List<Capabilities.TranslationLanguage> translationLanguages(List<ProviderCapability> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		List<Capabilities.TranslationLanguage> out = new ArrayList<>();
		for (LanguageCatalog.Language l : languages.allActive()) {
			boolean source = false;
			boolean destination = false;
			boolean detect = false;
			QualityTier tier = null;
			for (ProviderCapability c : rows) {
				boolean s = c.servesSource(l.code());
				boolean d = c.servesDestination(l.code());
				if (!s && !d) {
					continue;
				}
				source |= s;
				destination |= d;
				detect |= s && c.supportsAutoDetection();
				tier = tier == null || c.qualityTier().compareTo(tier) > 0 ? c.qualityTier() : tier;
			}
			if (source || destination) {
				out.add(new Capabilities.TranslationLanguage(l.code(), l.englishName(), l.nativeName(), l.scriptCode(),
						l.rtl() ? "RTL" : "LTR", source, destination, detect, tier, l.typing()));
			}
		}
		return out;
	}

	/** A strong validator over the items and the catalogue (not {@code generatedAt}): SHA-256, 32 hex digits, quoted. */
	private String etag(List<Capabilities.Capability> items, List<Capabilities.TranslationLanguage> catalogue) {
		try {
			byte[] body = mapper.writeValueAsBytes(new Capabilities(null, items, catalogue));
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
			return '"' + HexFormat.of().formatHex(hash, 0, 16) + '"';
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** {@code If-None-Match}: {@code *} or a list of (possibly weak) tags, one of which is {@code etag}. */
	static boolean matches(String ifNoneMatch, String etag) {
		if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
			return false;
		}
		for (String tag : ifNoneMatch.split(",")) {
			String t = tag.strip();
			if (t.equals("*") || t.equals(etag) || (t.startsWith("W/") && t.substring(2).equals(etag))) {
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------------- helpers

	private Capabilities.Capability capability(ProviderCapability c) {
		int pipelineMax = switch (c.feature()) {
			case DETECT_LANGUAGE -> properties.pipeline().maxDetectCharacters();
			case SMART_REPLY -> AiPipeline.MAX_REPLY_CONTEXT_CHARACTERS;
			case DRAFT_DOCUMENT -> AiPipeline.MAX_DRAFT_EMAIL_CHARACTERS;
			default -> properties.pipeline().maxTextCharacters();
		};
		int max = c.maxInputCharacters() == null ? pipelineMax : Math.min(pipelineMax, c.maxInputCharacters());
		return new Capabilities.Capability(c.feature().name(), c.sourceLanguage(), c.destinationLanguage(),
				c.qualityTier(), "SERVER", c.supportsAutoDetection(), false, max);
	}

	private ResponseEntity<AiResult> run(AiCommand command, RateLimitDecision limit) {
		AiResult result;
		try {
			result = pipeline.execute(command);
		} catch (AiRequestCancelledException e) {
			Thread.interrupted(); // the request is over; do not hand a flagged thread back to the container
			return ResponseEntity.status(CLIENT_CLOSED_REQUEST).build();
		}
		ResponseEntity.BodyBuilder ok = ResponseEntity.ok().cacheControl(CacheControl.noStore());
		if (limit != null) {
			HttpHeaders headers = new HttpHeaders();
			limit.headers().forEach(headers::set);
			ok.headers(headers);
		}
		return ok.body(result);
	}

	private static ProviderCapability better(ProviderCapability a, ProviderCapability b) {
		int tier = a.qualityTier().compareTo(b.qualityTier());
		if (tier != 0) {
			return tier > 0 ? a : b;
		}
		return limit(a) >= limit(b) ? a : b;
	}

	private static int limit(ProviderCapability c) {
		return c.maxInputCharacters() == null ? Integer.MAX_VALUE : c.maxInputCharacters();
	}

	private Predicate<AiFeature> visibleTo(AuthenticatedUser caller) {
		if (caller == null) {
			return f -> true;
		}
		if ("ON_DEVICE_ONLY".equals(users.defaults(caller.userId()).aiProcessingPreference())) {
			return f -> false;
		}
		ResolvedEntitlements resolved = entitlements.resolve(caller.userId());
		return switch (resolved.access()) {
			case GRANTED -> f -> resolved.isAllowed(f.name());
			case DELETED -> f -> f == AiFeature.DETECT_LANGUAGE; // D33
			case SUSPENDED, MERGED, PENDING, UNKNOWN -> f -> false;
		};
	}

	private static AiCommand.Caller caller(AuthenticatedUser caller, String key, String surface) {
		AiCommand.Surface parsed = null;
		if (surface != null && !surface.isBlank()) {
			try {
				parsed = AiCommand.Surface.valueOf(surface);
			} catch (IllegalArgumentException e) {
				throw validation("X-Client-Surface", "INVALID_VALUE", "The value of X-Client-Surface is not allowed.");
			}
		}
		return new AiCommand.Caller(caller.userId(), caller.deviceId(), parsed, key, null);
	}

	/** The contract says the key is a UUID. */
	private static String key(String header) {
		try {
			return UUID.fromString(header.trim()).toString();
		} catch (IllegalArgumentException e) {
			throw validation("Idempotency-Key", "INVALID_FORMAT", "The Idempotency-Key header must be a UUID.");
		}
	}

	private static ApiException validation(String field, String code, String message) {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, message,
				List.of(new ApiFieldError(field, code, message)), Map.of());
	}
}
