package com.qalsmartkeyboard.billing.arifpay;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.qalsmartkeyboard.billing.spi.CheckoutRequest;
import com.qalsmartkeyboard.billing.spi.CheckoutSession;
import com.qalsmartkeyboard.billing.spi.Money;
import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.billing.spi.PaymentProvider;
import com.qalsmartkeyboard.billing.spi.PaymentProviderException;
import com.qalsmartkeyboard.billing.spi.PaymentProviderException.Kind;
import com.qalsmartkeyboard.billing.spi.ProviderPaymentStatus;
import com.qalsmartkeyboard.billing.spi.VerificationRequest;
import com.qalsmartkeyboard.billing.spi.VerificationResult;
import com.qalsmartkeyboard.billing.spi.WebhookAuthentication;
import com.qalsmartkeyboard.billing.spi.WebhookEvent;
import com.qalsmartkeyboard.billing.spi.WebhookRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ArifPay hosted checkout (ADR 0045; https://developer.arifpay.net/docs, checked against the live sandbox on
 * 2026-11-20).
 *
 * <ul>
 * <li><b>Auth:</b> header {@code x-arifpay-key} on every call. A sandbox key only works on the {@code /api/sandbox/...}
 * routes (the production route answers {@code 400 "Test API Key found"}), so routes follow the configured
 * environment.</li>
 * <li><b>Start:</b> {@code POST /api/[sandbox/]checkout/session} with {@code nonce} (our attempt id), success/cancel/error
 * URLs (our return URL), {@code notifyUrl} (our webhook URL plus a per-attempt token {@code t}), {@code expireDate}
 * (wall clock, see {@link ArifPayProperties#timeZone()}), one item at the server price in major units and {@code lang};
 * {@code paymentMethods} and {@code beneficiaries} are left to the merchant account; no phone or e-mail is sent. Answer
 * {@code {error, msg, data:{sessionId, paymentUrl, cancelUrl, totalAmount}}}; the total must equal ours.</li>
 * <li><b>Verify:</b> {@code GET /api/[sandbox/]checkout/session/{sessionId}} (merchant-key protected) answers the
 * session with {@code nonce}, {@code totalAmount}, {@code test} (sandbox flag) and {@code transaction}
 * {@code {transactionId, transactionStatus, paymentType, amount}}. SUCCESS needs a transaction id, an amount equal to the
 * session total and a {@code test} flag matching the environment. A fresh session reads PENDING with no transaction id
 * and no payment type: reported as NOT_STARTED. The documented {@code /api/verify/transaction?uuid=} route does not
 * exist on the sandbox gateway (404) and does not enforce the API key, so it is not used. Lost start answers are looked
 * up by nonce on production ({@code /api/checkout/getSessionByNonce/{nonce}}).</li>
 * <li><b>Notifications</b> are not signed by ArifPay and are not retried. {@link #authenticate} requires the token our
 * notify URL carries ({@code HMAC-SHA256(webhookSecret, "ARIFPAY|" + accountCode + "|" + nonce)}, base64url); a valid
 * notification is still only a trigger for {@link #verify}.</li>
 * </ul>
 * Logs carry outcomes, HTTP statuses, session ids and codes only: never the key, request or response bodies, phone
 * numbers or provider messages.
 */
public final class ArifPayProvider implements PaymentProvider {

	public static final String CODE = "ARIFPAY";
	static final String KEY_HEADER = "x-arifpay-key";
	static final String TOKEN_PARAM = "t";
	private static final int MAX_BODY_BYTES = 256 * 1024;
	private static final Logger log = LoggerFactory.getLogger(ArifPayProvider.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final DateTimeFormatter EXPIRE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	private final ArifPayProperties properties;
	private final byte[] webhookKey;
	private final RestClient client;

	ArifPayProvider(ArifPayProperties properties, byte[] webhookKey) {
		this.properties = Objects.requireNonNull(properties, "properties");
		if (properties.apiKey() == null) {
			throw new IllegalStateException("ArifPay API key is not set");
		}
		this.webhookKey = webhookKey.clone();
		HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NEVER).connectTimeout(properties.connectTimeout()).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(properties.readTimeout());
		String base = properties.effectiveBaseUrl().toString();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		this.client = RestClient.builder().requestFactory(factory).baseUrl(base).build();
	}

	@Override
	public String providerCode() {
		return CODE;
	}

	@Override
	public boolean supports(PaymentEnvironment environment) {
		return properties.environment() == environment;
	}

	@Override
	public String toString() {
		return "ArifPayProvider[" + properties + "]";
	}

	// ------------------------------------------------------------------------------------------------ checkout

	@Override
	public CheckoutSession initiateCheckout(CheckoutRequest request) {
		requireEnvironment(request.account().environment());
		Money amount = request.amount();
		ObjectNode body = JSON.createObjectNode();
		body.put("cancelUrl", request.cancelUrl().toString());
		body.put("errorUrl", request.errorUrl().toString());
		body.put("successUrl", request.successUrl().toString());
		body.put("notifyUrl", notifyUrl(request.webhookUrl(), request.account().accountCode(),
				request.merchantReference()));
		body.put("nonce", request.merchantReference());
		if (request.expiresAt() != null) {
			body.put("expireDate", EXPIRE.format(LocalDateTime.ofInstant(request.expiresAt().truncatedTo(
					ChronoUnit.SECONDS), properties.timeZone())));
		}
		ArrayNode items = body.putArray("items");
		ObjectNode item = items.addObject();
		item.put("name", request.itemName() == null ? "Qal subscription" : request.itemName());
		item.put("quantity", 1);
		item.put("price", amount.toMajor());
		if (request.orderReference() != null) {
			item.put("description", request.orderReference());
		}
		body.put("lang", properties.lang());

		JsonNode response = call("create", () -> client.post().uri(properties.effectiveCreatePath())
				.contentType(MediaType.APPLICATION_JSON).header(KEY_HEADER, properties.apiKey())
				.body(JSON.writeValueAsBytes(body)).exchange((req, res) -> read(res), false));
		JsonNode data = response.path("data");
		if (response.path("error").asBoolean(true) || !data.isObject()) {
			String msg = text(response.path("msg"));
			String lower = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
			if (lower.contains("api key")) {
				// "API Key Not found", or "Test API Key found" (a sandbox key on a production route)
				throw new PaymentProviderException(Kind.MISCONFIGURED, "API_KEY_REFUSED");
			}
			throw new PaymentProviderException(Kind.REJECTED,
					lower.contains("already exist") ? "DUPLICATE_NONCE" : "CREATE_REFUSED");
		}
		String sessionId = text(data.path("sessionId"));
		String paymentUrl = text(data.path("paymentUrl"));
		if (sessionId == null || paymentUrl == null) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "CREATE_INCOMPLETE");
		}
		URI checkout;
		try {
			checkout = URI.create(paymentUrl);
		}
		catch (IllegalArgumentException e) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "CREATE_BAD_URL");
		}
		String scheme = checkout.getScheme() == null ? "" : checkout.getScheme().toLowerCase(Locale.ROOT);
		boolean plainHttpAllowed = "http".equalsIgnoreCase(properties.effectiveBaseUrl().getScheme());
		if (!(scheme.equals("https") || plainHttpAllowed && scheme.equals("http")) || checkout.getHost() == null) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "CREATE_BAD_URL");
		}
		Money total = amount(data.path("totalAmount"), amount);
		if (total != null && !total.sameAmount(amount)) {
			log.error("ArifPay session total differs from the order sessionId={}", sessionId);
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "CREATE_AMOUNT_MISMATCH");
		}
		log.info("ArifPay checkout session created sessionId={} account={}", sessionId,
				request.account().accountCode());
		return new CheckoutSession(sessionId, checkout, request.expiresAt());
	}

	// ------------------------------------------------------------------------------------------------ verify

	@Override
	public VerificationResult verify(VerificationRequest request) {
		requireEnvironment(request.account().environment());
		String path;
		if (request.providerReference() != null) {
			path = properties.effectiveSessionPath().replace("{sessionId}", encode(request.providerReference()));
		}
		else if (properties.effectiveNoncePath() != null) {
			path = properties.effectiveNoncePath().replace("{nonce}", encode(request.merchantReference()));
		}
		else {
			return VerificationResult.unknown(null, request.merchantReference(), "NONCE_LOOKUP_UNSUPPORTED");
		}
		JsonNode response = call("verify", () -> client.get().uri(path).header(KEY_HEADER, properties.apiKey())
				.exchange((req, res) -> read(res), false));
		if (response.path("error").asBoolean(false)) {
			String msg = text(response.path("msg"));
			if (msg != null && msg.toLowerCase(Locale.ROOT).contains("api key")) {
				throw new PaymentProviderException(Kind.MISCONFIGURED, "API_KEY_REFUSED");
			}
			if (msg != null && msg.toLowerCase(Locale.ROOT).contains("not production")) {
				return VerificationResult.unknown(request.providerReference(), request.merchantReference(),
						"ENVIRONMENT_MISMATCH");
			}
			return VerificationResult.unknown(request.providerReference(), request.merchantReference(),
					"SESSION_NOT_FOUND");
		}
		return mapSession(response.path("data"), properties.environment(), request.expectedAmount());
	}

	/** Maps a checkout-session document to a {@link VerificationResult} (package-private for tests). */
	static VerificationResult mapSession(JsonNode data, PaymentEnvironment environment, Money expected) {
		if (!data.isObject()) {
			return VerificationResult.unknown(null, null, "SESSION_MISSING");
		}
		String sessionId = text(data.path("uuid"));
		String nonce = text(data.path("nonce"));
		JsonNode test = data.path("test");
		if (test.isBoolean() && test.asBoolean() != (environment == PaymentEnvironment.SANDBOX)) {
			return VerificationResult.unknown(sessionId, nonce, "ENVIRONMENT_MISMATCH");
		}
		JsonNode tx = data.path("transaction");
		String rawStatus = text(tx.path("transactionStatus"));
		String transactionId = text(tx.path("transactionId"));
		String paymentType = text(tx.path("paymentType"));
		Instant occurredAt = instant(text(tx.path("updatedAt")));
		ProviderPaymentStatus status = status(rawStatus, transactionId, paymentType);
		Money amount = null;
		if (status == ProviderPaymentStatus.SUCCEEDED) {
			Money paid = amount(tx.path("amount"), expected);
			Money total = amount(data.path("totalAmount"), expected);
			amount = paid != null && paid.sameAmount(total) ? paid : null;
		}
		String failure = switch (status) {
			case FAILED, CANCELLED, EXPIRED -> "ARIFPAY_" + status.name();
			case UNKNOWN -> rawStatus == null ? "NO_TRANSACTION" : "UNMAPPED_STATUS";
			default -> null;
		};
		return new VerificationResult(status, sessionId, transactionId, amount, nonce, paymentType, occurredAt,
				failure);
	}

	/**
	 * ArifPay transaction status to ours. SUCCESS without a transaction id is not trusted (UNKNOWN); PENDING before the
	 * payer picked a rail (no payment type, no transaction id) is NOT_STARTED. UNAUTHORIZED / FORBIDDEN are auth
	 * outcomes, not payment outcomes (UNKNOWN).
	 */
	static ProviderPaymentStatus status(String raw, String transactionId, String paymentType) {
		if (raw == null) {
			return ProviderPaymentStatus.UNKNOWN;
		}
		return switch (raw.strip().toUpperCase(Locale.ROOT)) {
			case "SUCCESS" -> transactionId == null ? ProviderPaymentStatus.UNKNOWN : ProviderPaymentStatus.SUCCEEDED;
			case "PENDING" -> transactionId == null && paymentType == null ? ProviderPaymentStatus.NOT_STARTED
					: ProviderPaymentStatus.PENDING;
			case "FAILED" -> ProviderPaymentStatus.FAILED;
			case "CANCELLED", "CANCELED" -> ProviderPaymentStatus.CANCELLED;
			case "EXPIRED" -> ProviderPaymentStatus.EXPIRED;
			default -> ProviderPaymentStatus.UNKNOWN;
		};
	}

	// ------------------------------------------------------------------------------------------------ webhooks

	@Override
	public WebhookAuthentication authenticate(WebhookRequest request) {
		String token = request.query().get(TOKEN_PARAM);
		if (token == null || token.isEmpty()) {
			return WebhookAuthentication.INVALID;
		}
		JsonNode body;
		try {
			body = JSON.readTree(request.body());
		}
		catch (JacksonException e) {
			return WebhookAuthentication.INVALID;
		}
		String nonce = body == null ? null : text(body.path("nonce"));
		if (nonce == null) {
			return WebhookAuthentication.INVALID;
		}
		byte[] expected = token(request.account().accountCode(), nonce).getBytes(StandardCharsets.US_ASCII);
		return MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.US_ASCII)) ? WebhookAuthentication.VALID
				: WebhookAuthentication.INVALID;
	}

	@Override
	public WebhookEvent parseWebhook(WebhookRequest request) {
		JsonNode body;
		try {
			body = JSON.readTree(request.body());
		}
		catch (JacksonException e) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "NOTIFY_NOT_JSON");
		}
		if (body == null || !body.isObject()) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "NOTIFY_NOT_OBJECT");
		}
		String sessionId = text(body.path("sessionId"));
		if (sessionId == null) {
			sessionId = text(body.path("uuid"));
		}
		String nonce = text(body.path("nonce"));
		String status = text(body.path("transactionStatus"));
		if (status == null) {
			status = text(body.path("transaction").path("transactionStatus"));
		}
		String transactionId = text(body.path("transaction").path("transactionId"));
		String paymentMethod = text(body.path("paymentMethod"));
		Map<String, Object> redacted = new LinkedHashMap<>();
		redacted.put("sessionId", sessionId);
		redacted.put("nonce", nonce);
		redacted.put("transactionStatus", status);
		redacted.put("transactionId", transactionId);
		redacted.put("paymentMethod", paymentMethod);
		JsonNode total = body.path("totalAmount");
		redacted.put("totalAmount", total.isNumber() ? total.decimalValue().toPlainString() : null);
		String type = "ARIFPAY_NOTIFY_" + (status == null ? "UNKNOWN" : status.toUpperCase(Locale.ROOT)
				.replaceAll("[^A-Z_]", ""));
		return new WebhookEvent(null, type.length() > 120 ? type.substring(0, 120) : type, sessionId, nonce,
				status(status, transactionId, paymentMethod), redacted);
	}

	/** Our notify URL with the per-attempt token. */
	String notifyUrl(URI webhookUrl, String accountCode, String nonce) {
		String base = webhookUrl.toString();
		return base + (base.contains("?") ? "&" : "?") + TOKEN_PARAM + "=" + token(accountCode, nonce);
	}

	String token(String accountCode, String nonce) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(webhookKey, "HmacSHA256"));
			byte[] digest = mac.doFinal(("ARIFPAY|" + accountCode + "|" + nonce).getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException(e);
		}
	}

	// ------------------------------------------------------------------------------------------------ http

	private interface Call {
		JsonNode run();
	}

	private JsonNode call(String operation, Call call) {
		try {
			return call.run();
		}
		catch (ResourceAccessException e) {
			log.warn("ArifPay {} unreachable ({})", operation, e.getClass().getSimpleName());
			throw new PaymentProviderException(Kind.UNAVAILABLE, "UNREACHABLE");
		}
		catch (PaymentProviderException e) {
			log.warn("ArifPay {} failed kind={} code={}", operation, e.kind(), e.safeCode());
			throw e;
		}
	}

	/** Reads a bounded JSON body; maps HTTP failures to {@link PaymentProviderException} kinds. */
	private static JsonNode read(ClientHttpResponse response) throws IOException {
		HttpStatusCode status = response.getStatusCode();
		byte[] bytes;
		try (InputStream in = response.getBody()) {
			bytes = in.readNBytes(MAX_BODY_BYTES);
		}
		JsonNode node = null;
		try {
			node = bytes.length == 0 ? null : JSON.readTree(bytes);
		}
		catch (JacksonException e) {
			node = null;
		}
		if (status.is5xxServerError()) {
			throw new PaymentProviderException(Kind.UNAVAILABLE, "HTTP_" + status.value());
		}
		if (status.value() == 401 || status.value() == 403) {
			throw new PaymentProviderException(Kind.MISCONFIGURED, "HTTP_" + status.value());
		}
		if (node == null || !node.isObject()) {
			throw new PaymentProviderException(status.is2xxSuccessful() ? Kind.INVALID_RESPONSE : Kind.REJECTED,
					"HTTP_" + status.value());
		}
		if (status.value() == 404 && !node.has("error")) {
			// a gateway 404 page (route missing), not an ArifPay answer
			throw new PaymentProviderException(Kind.MISCONFIGURED, "ROUTE_NOT_FOUND");
		}
		if (!status.is2xxSuccessful() && !node.has("error")) {
			throw new PaymentProviderException(Kind.REJECTED, "HTTP_" + status.value());
		}
		return node;
	}

	private void requireEnvironment(PaymentEnvironment environment) {
		if (!supports(environment)) {
			throw new PaymentProviderException(Kind.MISCONFIGURED, "ENVIRONMENT_MISMATCH");
		}
	}

	private static Money amount(JsonNode node, Money like) {
		if (node == null || !node.isNumber() && !node.isString()) {
			return null;
		}
		try {
			BigDecimal value = node.isNumber() ? node.decimalValue() : new BigDecimal(node.asString().strip());
			return Money.ofMajor(value, like.currency(), like.exponent());
		}
		catch (ArithmeticException | NumberFormatException e) {
			return null;
		}
	}

	private static Instant instant(String value) {
		if (value == null) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")).toInstant();
		}
		catch (DateTimeParseException e) {
			try {
				return OffsetDateTime.parse(value).toInstant();
			}
			catch (DateTimeParseException e2) {
				return null;
			}
		}
	}

	static String text(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		String s = node.asString();
		return s == null || s.isBlank() ? null : s;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
