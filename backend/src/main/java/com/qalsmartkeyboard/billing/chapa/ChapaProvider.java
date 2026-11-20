package com.qalsmartkeyboard.billing.chapa;

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
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.qalsmartkeyboard.billing.spi.ProviderRefundStatus;
import com.qalsmartkeyboard.billing.spi.RefundRequest;
import com.qalsmartkeyboard.billing.spi.RefundResult;
import com.qalsmartkeyboard.billing.spi.RefundStatusRequest;
import com.qalsmartkeyboard.billing.spi.VerificationRequest;
import com.qalsmartkeyboard.billing.spi.VerificationResult;
import com.qalsmartkeyboard.billing.spi.WebhookAuthentication;
import com.qalsmartkeyboard.billing.spi.WebhookEvent;
import com.qalsmartkeyboard.billing.spi.WebhookRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Chapa hosted checkout (ADR 0045 step A2; https://developer.chapa.co/, read on 2026-11-20). One host for test and
 * live mode ({@code https://api.chapa.co}); the secret key decides the mode. Auth: {@code Authorization: Bearer
 * <secret key>} on every call.
 *
 * <ul>
 * <li><b>Start</b> ({@code POST /v1/transaction/initialize}): {@code amount} (major units as a string, e.g.
 * {@code "299.00"}), {@code currency} ETB, {@code tx_ref} = our attempt id (Chapa refuses a reused {@code tx_ref}:
 * "Transaction reference has been used before"), {@code return_url} = our return URL, {@code callback_url} = the same
 * return URL without query (Chapa calls it with a GET; it only queues a verification) and {@code customization}
 * {@code {title, description}} (plain characters). No e-mail, name or phone is sent (all optional; data
 * minimisation). Answer {@code {message:"Hosted Link", status:"success", data:{checkout_url}}}. Chapa returns no
 * checkout id: the {@code tx_ref} is the provider reference.</li>
 * <li><b>Verify</b> ({@code GET /v1/transaction/verify/{tx_ref}}): {@code data{status: success|pending|failed, amount,
 * currency, mode: test|live, reference, tx_ref, method, updated_at}}. SUCCEEDED needs Chapa's {@code reference}, our
 * {@code tx_ref}, the currency of the order and a mode matching the environment; the core compares the amount. HTTP 404
 * "Payment not paid yet" / "Transaction not found" means nothing was paid (NOT_STARTED: the attempt expires after its
 * expiry). Refunded / reversed payments read UNKNOWN with a code (the reconciliation report shows them).</li>
 * <li><b>Webhooks</b> (dashboard URL {@code /billing/webhooks/CHAPA/{account}}): authenticated with the dashboard
 * "secret hash" exactly as documented: {@code x-chapa-signature} = hex HMAC-SHA256 of the payload (checked over the raw
 * body and over its compact re-serialisation, because Chapa's sample hashes {@code JSON.stringify(req.body)}), or
 * {@code chapa-signature} = the same payload HMAC (custom webhooks) or hex HMAC-SHA256 of the secret by itself (standard
 * webhooks; see {@link ChapaProperties#acceptSecretHashSignature()}). One valid header suffices. Events
 * {@code charge.success|failed/cancelled|refunded|reversed} carry {@code tx_ref}; payout events are not ours and are
 * dropped. Only a trigger for {@link #verify}.</li>
 * <li><b>Refund</b> ({@code POST /v1/refund/{chapa reference}}, form fields {@code amount}, {@code reason},
 * {@code reference} = our refund reference) and refund status ({@code GET /v1/refund/{ref}/verify}: initiated,
 * processing, refunded, reversed).</li>
 * <li><b>Method discovery:</b> Chapa documents no API listing a merchant's pay-in methods ({@code /v1/banks} is for
 * payouts), so none is implemented.</li>
 * </ul>
 * Logs carry outcomes, HTTP statuses, references and codes only: never the key, bodies, names, e-mails or phone
 * numbers, never Chapa's message text.
 */
public final class ChapaProvider implements PaymentProvider {

	public static final String CODE = "CHAPA";
	static final String SIGNATURE_HEADER = "x-chapa-signature";
	static final String SECRET_HASH_HEADER = "chapa-signature";
	private static final int MAX_BODY_BYTES = 256 * 1024;
	private static final Logger log = LoggerFactory.getLogger(ChapaProvider.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** One HTTP answer: status and the JSON object (null when the body was not a JSON object). */
	record Answer(int status, JsonNode body) {

		String message() {
			String m = text(body == null ? null : body.path("message"));
			return m == null ? "" : m.strip().toLowerCase(Locale.ROOT);
		}

		boolean success() {
			return status / 100 == 2 && body != null && "success".equalsIgnoreCase(text(body.path("status")));
		}
	}

	private final ChapaProperties properties;
	private final RestClient client;

	ChapaProvider(ChapaProperties properties) {
		this.properties = Objects.requireNonNull(properties, "properties");
		if (properties.secretKey() == null) {
			throw new IllegalStateException("Chapa secret key is not set");
		}
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
	public boolean supportsRefunds() {
		return true;
	}

	@Override
	public String toString() {
		return "ChapaProvider[" + properties + "]";
	}

	// ------------------------------------------------------------------------------------------------ checkout

	@Override
	public CheckoutSession initiateCheckout(CheckoutRequest request) {
		requireEnvironment(request.account().environment());
		Money amount = request.amount();
		String txRef = request.merchantReference();
		ObjectNode body = JSON.createObjectNode();
		body.put("amount", amount.toMajor().toPlainString());
		body.put("currency", amount.currency());
		body.put("tx_ref", txRef);
		if (request.successUrl() != null) {
			body.put("return_url", request.successUrl().toString());
			body.put("callback_url", withoutQuery(request.successUrl()).toString());
		}
		ObjectNode customization = body.putObject("customization");
		customization.put("title", plain(properties.title(), 16));
		String description = plain((request.itemName() == null ? "Qal subscription" : request.itemName())
				+ (request.orderReference() == null ? "" : " " + request.orderReference()), 50);
		customization.put("description", description);

		Answer answer = call("initialize", () -> client.post().uri("/v1/transaction/initialize")
				.contentType(MediaType.APPLICATION_JSON).header("Authorization", bearer())
				.body(JSON.writeValueAsBytes(body)).exchange((req, res) -> read(res), false));
		if (!answer.success()) {
			throw refusal("initialize", answer);
		}
		String checkoutUrl = text(answer.body().path("data").path("checkout_url"));
		if (checkoutUrl == null) {
			throw failure("initialize", Kind.INVALID_RESPONSE, "INITIALIZE_INCOMPLETE");
		}
		URI checkout;
		try {
			checkout = URI.create(checkoutUrl);
		}
		catch (IllegalArgumentException e) {
			throw failure("initialize", Kind.INVALID_RESPONSE, "INITIALIZE_BAD_URL");
		}
		String scheme = checkout.getScheme() == null ? "" : checkout.getScheme().toLowerCase(Locale.ROOT);
		boolean plainHttpAllowed = "http".equalsIgnoreCase(properties.effectiveBaseUrl().getScheme());
		if (!(scheme.equals("https") || plainHttpAllowed && scheme.equals("http")) || checkout.getHost() == null) {
			throw failure("initialize", Kind.INVALID_RESPONSE, "INITIALIZE_BAD_URL");
		}
		log.info("Chapa checkout initialized txRef={} account={}", txRef, request.account().accountCode());
		return new CheckoutSession(txRef, checkout, null);
	}

	// ------------------------------------------------------------------------------------------------ verify

	@Override
	public VerificationResult verify(VerificationRequest request) {
		requireEnvironment(request.account().environment());
		String txRef = request.merchantReference();
		Answer answer = call("verify", () -> client.get().uri("/v1/transaction/verify/{txRef}", txRef)
				.header("Authorization", bearer()).exchange((req, res) -> read(res), false));
		if (answer.status() == 404) {
			// "Payment not paid yet" (status null) or "Invalid transaction or Transaction not found": nothing paid
			String code = answer.message().contains("not paid") ? "NOT_PAID_YET" : "TRANSACTION_NOT_FOUND";
			return new VerificationResult(ProviderPaymentStatus.NOT_STARTED, txRef, null, null, txRef, null, null,
					code);
		}
		if (!answer.success()) {
			throw refusal("verify", answer);
		}
		return mapTransaction(answer.body().path("data"), properties.environment(), request.expectedAmount(), txRef);
	}

	/** Maps a verify {@code data} document (package-private for tests). */
	static VerificationResult mapTransaction(JsonNode data, PaymentEnvironment environment, Money expected,
			String txRef) {
		if (data == null || !data.isObject()) {
			return VerificationResult.unknown(txRef, txRef, "TRANSACTION_MISSING");
		}
		String reportedTxRef = text(data.path("tx_ref"));
		if (reportedTxRef == null || !reportedTxRef.equals(txRef)) {
			return VerificationResult.unknown(txRef, reportedTxRef, "TX_REF_MISMATCH");
		}
		String mode = text(data.path("mode"));
		if (mode != null && !mode.equalsIgnoreCase(environment == PaymentEnvironment.SANDBOX ? "test" : "live")) {
			return VerificationResult.unknown(txRef, txRef, "ENVIRONMENT_MISMATCH");
		}
		String reference = text(data.path("reference"));
		String rawStatus = text(data.path("status"));
		ProviderPaymentStatus status = status(rawStatus, reference);
		Money amount = null;
		if (status == ProviderPaymentStatus.SUCCEEDED) {
			String currency = text(data.path("currency"));
			amount = expected.currency().equalsIgnoreCase(currency) ? amount(data.path("amount"), expected) : null;
		}
		String failure = switch (status) {
			case FAILED, CANCELLED, EXPIRED -> "CHAPA_" + status.name();
			case UNKNOWN -> unknownCode(rawStatus);
			default -> null;
		};
		String method = text(data.path("method"));
		if (method == null) {
			method = text(data.path("payment_method"));
		}
		if (method != null && method.length() > 60) {
			method = method.substring(0, 60);
		}
		return new VerificationResult(status, txRef, reference, amount, txRef, method,
				instant(text(data.path("updated_at"))), failure);
	}

	/**
	 * Chapa payment status to ours: success (with Chapa's reference), pending, failed, cancelled ("failed/cancelled"
	 * is how Chapa writes the failed webhook), expired. Refunded / reversed are not payment outcomes for an open attempt:
	 * UNKNOWN with a code.
	 */
	static ProviderPaymentStatus status(String raw, String reference) {
		if (raw == null) {
			return ProviderPaymentStatus.UNKNOWN;
		}
		return switch (raw.strip().toLowerCase(Locale.ROOT)) {
			case "success", "successful" -> reference == null ? ProviderPaymentStatus.UNKNOWN
					: ProviderPaymentStatus.SUCCEEDED;
			case "pending" -> ProviderPaymentStatus.PENDING;
			case "failed", "failed/cancelled" -> ProviderPaymentStatus.FAILED;
			case "cancelled", "canceled" -> ProviderPaymentStatus.CANCELLED;
			case "expired" -> ProviderPaymentStatus.EXPIRED;
			default -> ProviderPaymentStatus.UNKNOWN;
		};
	}

	private static String unknownCode(String raw) {
		if (raw == null) {
			return "NO_STATUS";
		}
		return switch (raw.strip().toLowerCase(Locale.ROOT)) {
			case "refunded" -> "CHAPA_REFUNDED";
			case "reversed" -> "CHAPA_REVERSED";
			case "success", "successful" -> "NO_REFERENCE";
			default -> "UNMAPPED_STATUS";
		};
	}

	// ------------------------------------------------------------------------------------------------ webhooks

	@Override
	public WebhookAuthentication authenticate(WebhookRequest request) {
		String secret = properties.webhookSecret();
		if (secret == null) {
			return WebhookAuthentication.INVALID;
		}
		String payloadSignature = header(request, SIGNATURE_HEADER);
		String secretSignature = header(request, SECRET_HASH_HEADER);
		if (payloadSignature == null && secretSignature == null) {
			return WebhookAuthentication.INVALID;
		}
		byte[] key = secret.getBytes(StandardCharsets.UTF_8);
		String rawHmac = hmacHex(key, request.body());
		String compactHmac = compact(request.body()).map(b -> hmacHex(key, b)).orElse(null);
		if (matches(payloadSignature, rawHmac) || matches(payloadSignature, compactHmac)
				|| matches(secretSignature, rawHmac) || matches(secretSignature, compactHmac)) {
			return WebhookAuthentication.VALID;
		}
		if (properties.acceptSecretHashSignature() && matches(secretSignature, hmacHex(key, key))) {
			return WebhookAuthentication.VALID;
		}
		return WebhookAuthentication.INVALID;
	}

	@Override
	public WebhookEvent parseWebhook(WebhookRequest request) {
		JsonNode body;
		try {
			body = JSON.readTree(request.body());
		}
		catch (JacksonException e) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "WEBHOOK_NOT_JSON");
		}
		if (body == null || !body.isObject()) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "WEBHOOK_NOT_OBJECT");
		}
		String event = text(body.path("event"));
		String type = text(body.path("type"));
		if (event != null && event.toLowerCase(Locale.ROOT).startsWith("payout")
				|| type != null && type.equalsIgnoreCase("Payout")) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "WEBHOOK_NOT_A_CHARGE");
		}
		String txRef = text(body.path("tx_ref"));
		if (txRef == null) {
			txRef = text(body.path("trx_ref"));
		}
		String reference = text(body.path("reference"));
		String status = text(body.path("status"));
		Map<String, Object> redacted = new LinkedHashMap<>();
		redacted.put("event", event);
		redacted.put("type", type);
		redacted.put("txRef", txRef);
		redacted.put("reference", reference);
		redacted.put("status", status);
		redacted.put("amount", text(body.path("amount")));
		redacted.put("currency", text(body.path("currency")));
		redacted.put("mode", text(body.path("mode")));
		redacted.put("paymentMethod", text(body.path("payment_method")));
		String eventType = "CHAPA_" + (event == null ? "UNKNOWN" : event.toUpperCase(Locale.ROOT)
				.replaceAll("[^A-Z0-9_.]", "_"));
		String externalId = reference == null ? null
				: truncate("chapa:" + (event == null ? "" : event) + ":" + reference + ":" + (status == null ? ""
						: status), 255);
		return new WebhookEvent(externalId, truncate(eventType, 120), txRef, txRef, status(status, reference),
				redacted);
	}

	// ------------------------------------------------------------------------------------------------ refunds

	@Override
	public RefundResult refund(RefundRequest request) {
		requireEnvironment(request.account().environment());
		String chapaReference = request.providerTransactionReference();
		if (chapaReference == null) {
			throw new PaymentProviderException(Kind.REJECTED, "NO_PROVIDER_REFERENCE");
		}
		StringBuilder form = new StringBuilder();
		form.append("amount=").append(encode(request.amount().toMajor().toPlainString()));
		form.append("&reference=").append(encode(request.refundReference()));
		if (request.reason() != null) {
			form.append("&reason=").append(encode(plain(request.reason(), 100)));
		}
		Answer answer = call("refund", () -> client.post().uri("/v1/refund/{reference}", chapaReference)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Authorization", bearer())
				.body(form.toString()).exchange((req, res) -> read(res), false));
		if (!answer.success()) {
			throw refusal("refund", answer);
		}
		JsonNode data = answer.body().path("data");
		String ref = text(data.path("ref"));
		if (ref == null) {
			ref = text(data.path("ref_id"));
		}
		if (ref == null) {
			throw failure("refund", Kind.INVALID_RESPONSE, "REFUND_INCOMPLETE");
		}
		log.info("Chapa refund initiated refundReference={} account={}", request.refundReference(),
				request.account().accountCode());
		return new RefundResult(refundStatus(text(data.path("status"))), ref, amount(data.path("amount"),
				request.amount()), null);
	}

	@Override
	public RefundResult refundStatus(RefundStatusRequest request) {
		requireEnvironment(request.account().environment());
		if (request.providerRefundReference() == null) {
			return new RefundResult(ProviderRefundStatus.UNKNOWN, null, null, "NO_PROVIDER_REFUND_REFERENCE");
		}
		Answer answer = call("refund-verify", () -> client.get()
				.uri("/v1/refund/{ref}/verify", request.providerRefundReference())
				.header("Authorization", bearer()).exchange((req, res) -> read(res), false));
		if (answer.status() == 404) {
			return new RefundResult(ProviderRefundStatus.UNKNOWN, request.providerRefundReference(), null,
					"REFUND_NOT_FOUND");
		}
		if (!answer.success()) {
			throw refusal("refund-verify", answer);
		}
		JsonNode data = answer.body().path("data");
		ProviderRefundStatus status = refundStatus(text(data.path("status")));
		return new RefundResult(status, request.providerRefundReference(), null,
				status == ProviderRefundStatus.FAILED ? "CHAPA_REFUND_REVERSED" : null);
	}

	/** Chapa refund status: "Refund Initiated" / initiated / processing, refunded, reversed. */
	static ProviderRefundStatus refundStatus(String raw) {
		if (raw == null) {
			return ProviderRefundStatus.UNKNOWN;
		}
		String s = raw.strip().toLowerCase(Locale.ROOT);
		if (s.contains("initiated") || s.equals("processing") || s.equals("pending")) {
			return ProviderRefundStatus.PENDING;
		}
		if (s.equals("refunded") || s.equals("success") || s.equals("completed")) {
			return ProviderRefundStatus.SUCCEEDED;
		}
		if (s.equals("reversed") || s.equals("failed") || s.equals("rejected")) {
			return ProviderRefundStatus.FAILED;
		}
		return ProviderRefundStatus.UNKNOWN;
	}

	// ------------------------------------------------------------------------------------------------ http

	private interface Call {
		Answer run();
	}

	private Answer call(String operation, Call call) {
		try {
			return call.run();
		}
		catch (ResourceAccessException e) {
			log.warn("Chapa {} unreachable ({})", operation, e.getClass().getSimpleName());
			throw new PaymentProviderException(Kind.UNAVAILABLE, "UNREACHABLE");
		}
		catch (PaymentProviderException e) {
			log.warn("Chapa {} failed kind={} code={}", operation, e.kind(), e.safeCode());
			throw e;
		}
	}

	/** Reads a bounded JSON body. 5xx is UNAVAILABLE; 401/403 MISCONFIGURED; everything else goes to the caller. */
	private static Answer read(ClientHttpResponse response) throws IOException {
		int status = response.getStatusCode().value();
		byte[] bytes;
		try (InputStream in = response.getBody()) {
			bytes = in.readNBytes(MAX_BODY_BYTES);
		}
		if (status >= 500) {
			throw new PaymentProviderException(Kind.UNAVAILABLE, "HTTP_" + status);
		}
		if (status == 401 || status == 403) {
			// bad key, or a test key used on a live transaction and vice versa
			throw new PaymentProviderException(Kind.MISCONFIGURED, "HTTP_" + status);
		}
		if (status == 429) {
			throw new PaymentProviderException(Kind.UNAVAILABLE, "HTTP_429");
		}
		JsonNode node;
		try {
			node = bytes.length == 0 ? null : JSON.readTree(bytes);
		}
		catch (JacksonException e) {
			node = null;
		}
		if (node != null && !node.isObject()) {
			node = null;
		}
		if (node == null && status / 100 == 2) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "HTTP_" + status);
		}
		return new Answer(status, node);
	}

	/** A non-success answer as an exception with a safe code (Chapa's message is matched, never logged). */
	private PaymentProviderException refusal(String operation, Answer answer) {
		String message = answer.message();
		Kind kind = Kind.REJECTED;
		String code;
		if (answer.body() == null) {
			code = "HTTP_" + answer.status();
		}
		else if (message.contains("used before")) {
			code = "DUPLICATE_TX_REF";
		}
		else if (message.contains("api key") || message.contains("authorization") || message.contains("disabled")
				|| message.contains("can't receive") || message.contains("can’t receive")) {
			kind = Kind.MISCONFIGURED;
			code = "ACCOUNT_REFUSED";
		}
		else if (message.contains("currency")) {
			code = "CURRENCY_REFUSED";
		}
		else if (message.contains("exceed")) {
			code = "AMOUNT_REFUSED";
		}
		else {
			code = answer.status() / 100 == 2 ? "NOT_SUCCESS" : "HTTP_" + answer.status();
			if (answer.status() / 100 == 2) {
				kind = Kind.INVALID_RESPONSE;
			}
		}
		return failure(operation, kind, code);
	}

	private PaymentProviderException failure(String operation, Kind kind, String code) {
		return new PaymentProviderException(kind, code);
	}

	private String bearer() {
		return "Bearer " + properties.secretKey();
	}

	private void requireEnvironment(PaymentEnvironment environment) {
		if (!supports(environment)) {
			throw new PaymentProviderException(Kind.MISCONFIGURED, "ENVIRONMENT_MISMATCH");
		}
	}

	private static String header(WebhookRequest request, String name) {
		String v = request.header(name);
		return v == null || v.isBlank() ? null : v.strip();
	}

	private static boolean matches(String presented, String expected) {
		if (presented == null || expected == null) {
			return false;
		}
		return MessageDigest.isEqual(presented.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
				expected.getBytes(StandardCharsets.US_ASCII));
	}

	static String hmacHex(byte[] key, byte[] message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(message));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The body as Node's {@code JSON.stringify(JSON.parse(body))} would write it (compact, key order kept). */
	private static java.util.Optional<byte[]> compact(byte[] body) {
		try {
			JsonNode node = JSON.readTree(body);
			return node == null ? java.util.Optional.empty() : java.util.Optional.of(JSON.writeValueAsBytes(node));
		}
		catch (JacksonException e) {
			return java.util.Optional.empty();
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
			return Instant.parse(value);
		}
		catch (DateTimeParseException e) {
			return null;
		}
	}

	/** Letters, digits, spaces, '-', '_' and '.' only (Chapa validates customization text), at most {@code max}. */
	static String plain(String value, int max) {
		String s = value.replaceAll("[^A-Za-z0-9 ._-]", " ").replaceAll("\\s+", " ").strip();
		return s.length() <= max ? s : s.substring(0, max).strip();
	}

	static URI withoutQuery(URI uri) {
		String s = uri.toString();
		int q = s.indexOf('?');
		return URI.create(q < 0 ? s : s.substring(0, q));
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

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}
}
