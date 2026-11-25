package com.qalsmartkeyboard.billing.revenuecat;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.billing.spi.PaymentProviderException;
import com.qalsmartkeyboard.billing.spi.PaymentProviderException.Kind;
import com.qalsmartkeyboard.billing.spi.StoreBillingProvider;
import com.qalsmartkeyboard.billing.spi.StoreCustomer;
import com.qalsmartkeyboard.billing.spi.StoreNonSubscription;
import com.qalsmartkeyboard.billing.spi.StoreNotification;
import com.qalsmartkeyboard.billing.spi.StoreNotificationRequest;
import com.qalsmartkeyboard.billing.spi.StoreSubscription;
import com.qalsmartkeyboard.billing.spi.WebhookAuthentication;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * RevenueCat as the global store-billing provider (ADR 0046; https://www.revenuecat.com/docs, read on 2026-11-20).
 *
 * <ul>
 * <li><b>Webhooks</b> ({@code POST /billing/store-notifications/REVENUECAT}): the {@code Authorization} header must be
 * exactly the value configured in the dashboard (constant-time compare). When a signing secret is configured, also
 * {@code X-RevenueCat-Webhook-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, "<t>." + raw body)>} at most
 * {@link RevenueCatProperties#signatureTolerance()} old. Body {@code {"api_version":"1.0","event":{...}}}; the event
 * {@code id} is the dedupe key (retries reuse it). {@code subscriber_attributes} (e-mail, phone, name) and experiments
 * are dropped before anything is stored.</li>
 * <li><b>Server-side verification</b>: {@code GET /v1/subscribers/{app_user_id}} with {@code Authorization: Bearer
 * <secret key>} (the endpoint RevenueCat recommends calling after every webhook; it creates an empty customer when the
 * id is new, harmless because the id is our own user id). Each {@code subscriber.subscriptions.<product>} entry
 * becomes a {@link StoreSubscription}: {@code expires_date}, {@code purchase_date}, {@code original_purchase_date},
 * {@code grace_period_expires_date}, {@code unsubscribe_detected_at}, {@code billing_issues_detected_at},
 * {@code refunded_at}, {@code auto_resume_date}, {@code is_sandbox}, {@code store}, {@code store_transaction_id}
 * (string or number), {@code period_type}, {@code ownership_type}, {@code product_plan_identifier} (Play base plan, when
 * present). Each {@code subscriber.non_subscriptions.<product>[]} entry (one-time purchases: the credit-pack
 * consumables, T8.10) becomes a {@link StoreNonSubscription}: {@code id}, {@code store_transaction_id} (string or number;
 * for Play the order id {@code GPA.xxxx}, the same value webhooks carry as {@code transaction_id}), {@code purchase_date},
 * {@code store}, {@code is_sandbox} and, when RevenueCat reports one, {@code refunded_at} (the v1 docs show only
 * {@code id}, {@code is_sandbox}, {@code purchase_date} and {@code store}; the other fields are read when present, and
 * an entry without {@code store_transaction_id} is never granted by the core).</li>
 * <li>Webhook events name their store transactions in {@code transaction_id} / {@code original_transaction_id}
 * ({@code NON_RENEWING_PURCHASE}, and {@code CANCELLATION} for a refunded one-time purchase).</li>
 * </ul>
 * Logs carry outcomes, HTTP statuses and codes only: never keys, header values, bodies or user attributes.
 */
public final class RevenueCatProvider implements StoreBillingProvider {

	public static final String CODE = "REVENUECAT";
	static final String SIGNATURE_HEADER = "x-revenuecat-webhook-signature";
	private static final int MAX_BODY_BYTES = 512 * 1024;
	private static final Logger log = LoggerFactory.getLogger(RevenueCatProvider.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Set<String> DROPPED_EVENT_FIELDS = Set.of("subscriber_attributes", "experiments");

	private final RevenueCatProperties properties;
	private final RestClient client;
	private final Clock clock;

	RevenueCatProvider(RevenueCatProperties properties, Clock clock) {
		this.properties = Objects.requireNonNull(properties, "properties");
		this.clock = Objects.requireNonNull(clock, "clock");
		if (properties.secretApiKey() == null) {
			throw new IllegalStateException("RevenueCat secret API key is not set");
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
	public String toString() {
		return "RevenueCatProvider[" + properties + "]";
	}

	// ------------------------------------------------------------------------------------------------ webhooks

	@Override
	public WebhookAuthentication authenticateNotification(StoreNotificationRequest request) {
		String expected = properties.webhookAuth();
		String presented = request.header("authorization");
		if (expected == null || presented == null || !MessageDigest.isEqual(
				presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
			return WebhookAuthentication.INVALID;
		}
		if (properties.webhookSigningSecret() != null && !signatureValid(request)) {
			return WebhookAuthentication.INVALID;
		}
		return WebhookAuthentication.VALID;
	}

	private boolean signatureValid(StoreNotificationRequest request) {
		String header = request.header(SIGNATURE_HEADER);
		if (header == null) {
			return false;
		}
		String t = null;
		List<String> v1 = new ArrayList<>();
		for (String part : header.split(",")) {
			int eq = part.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String name = part.substring(0, eq).strip();
			String value = part.substring(eq + 1).strip();
			if (name.equals("t")) {
				t = value;
			}
			else if (name.equals("v1")) {
				v1.add(value.toLowerCase(Locale.ROOT));
			}
		}
		if (t == null || v1.isEmpty() || !t.matches("[0-9]{1,12}")) {
			return false;
		}
		long age = clock.instant().getEpochSecond() - Long.parseLong(t);
		if (Math.abs(age) > properties.signatureTolerance().toSeconds()) {
			return false;
		}
		byte[] prefix = (t + ".").getBytes(StandardCharsets.UTF_8);
		byte[] signed = new byte[prefix.length + request.body().length];
		System.arraycopy(prefix, 0, signed, 0, prefix.length);
		System.arraycopy(request.body(), 0, signed, prefix.length, request.body().length);
		byte[] expected = hmacHex(properties.webhookSigningSecret().getBytes(StandardCharsets.UTF_8), signed)
				.getBytes(StandardCharsets.US_ASCII);
		for (String candidate : v1) {
			if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII))) {
				return true;
			}
		}
		return false;
	}

	@Override
	public StoreNotification parseNotification(StoreNotificationRequest request) {
		JsonNode root;
		try {
			root = JSON.readTree(request.body());
		}
		catch (JacksonException e) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "WEBHOOK_NOT_JSON");
		}
		JsonNode event = root == null ? null : root.path("event");
		if (event == null || !event.isObject()) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "WEBHOOK_NO_EVENT");
		}
		String id = text(event.path("id"));
		String type = text(event.path("type"));
		if (id == null || type == null) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "WEBHOOK_NO_ID");
		}
		Set<String> users = new LinkedHashSet<>();
		addText(users, event.path("app_user_id"));
		addText(users, event.path("original_app_user_id"));
		addTexts(users, event.path("aliases"));
		addTexts(users, event.path("transferred_to"));
		addTexts(users, event.path("transferred_from"));
		Map<String, Object> redacted = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> field : event.properties()) {
			if (!DROPPED_EVENT_FIELDS.contains(field.getKey())) {
				redacted.put(field.getKey(), JSON.treeToValue(field.getValue(), Object.class));
			}
		}
		redacted.put("api_version", text(root.path("api_version")));
		String env = text(event.path("environment"));
		PaymentEnvironment environment = env == null ? null
				: "SANDBOX".equalsIgnoreCase(env) ? PaymentEnvironment.SANDBOX : PaymentEnvironment.PRODUCTION;
		JsonNode ts = event.path("event_timestamp_ms");
		Instant eventTime = ts.isNumber() ? Instant.ofEpochMilli(ts.asLong()) : clock.instant();
		String product = text(event.path("new_product_id"));
		if (product == null) {
			product = text(event.path("product_id"));
		}
		Set<String> transactions = new LinkedHashSet<>();
		addText(transactions, event.path("transaction_id"));
		addText(transactions, event.path("original_transaction_id"));
		return new StoreNotification(truncate(id, 255), truncate(type.toUpperCase(Locale.ROOT), 120),
				List.copyOf(users), product, environment, eventTime, redacted, "TEST".equalsIgnoreCase(type),
				List.copyOf(transactions));
	}

	// ------------------------------------------------------------------------------------------------ REST

	@Override
	public StoreCustomer customer(String appUserId) {
		JsonNode body;
		try {
			body = client.get().uri("/v1/subscribers/{id}", appUserId)
					.header("Authorization", "Bearer " + properties.secretApiKey())
					.header("Accept", "application/json").exchange((req, res) -> read(res), false);
		}
		catch (ResourceAccessException e) {
			log.warn("RevenueCat subscriber read unreachable ({})", e.getClass().getSimpleName());
			throw new PaymentProviderException(Kind.UNAVAILABLE, "UNREACHABLE");
		}
		catch (PaymentProviderException e) {
			log.warn("RevenueCat subscriber read failed kind={} code={}", e.kind(), e.safeCode());
			throw e;
		}
		return mapSubscriber(appUserId, body);
	}

	/** Maps a {@code GET /v1/subscribers} answer (package-private for tests). */
	static StoreCustomer mapSubscriber(String appUserId, JsonNode body) {
		JsonNode subscriber = body.path("subscriber");
		if (!subscriber.isObject()) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "NO_SUBSCRIBER");
		}
		List<StoreSubscription> subscriptions = new ArrayList<>();
		JsonNode subs = subscriber.path("subscriptions");
		if (subs.isObject()) {
			for (Map.Entry<String, JsonNode> e : subs.properties()) {
				JsonNode s = e.getValue();
				if (!s.isObject()) {
					continue;
				}
				String key = e.getKey();
				String plan = text(s.path("product_plan_identifier"));
				int colon = key.indexOf(':');
				String productId = colon > 0 ? key.substring(0, colon) : key;
				if (plan == null && colon > 0) {
					plan = key.substring(colon + 1);
				}
				subscriptions.add(new StoreSubscription(productId, plan, lower(text(s.path("store"))),
						s.path("is_sandbox").asBoolean(false) ? PaymentEnvironment.SANDBOX : PaymentEnvironment.PRODUCTION,
						instant(s.path("purchase_date")), instant(s.path("original_purchase_date")),
						instant(s.path("expires_date")), instant(s.path("grace_period_expires_date")),
						instant(s.path("unsubscribe_detected_at")), instant(s.path("billing_issues_detected_at")),
						instant(s.path("refunded_at")), instant(s.path("auto_resume_date")),
						truncate(text(s.path("store_transaction_id")), 255), lower(text(s.path("period_type"))),
						text(s.path("ownership_type"))));
			}
		}
		List<StoreNonSubscription> oneTime = new ArrayList<>();
		JsonNode non = subscriber.path("non_subscriptions");
		if (non.isObject()) {
			for (Map.Entry<String, JsonNode> e : non.properties()) {
				if (!e.getValue().isArray()) {
					continue;
				}
				for (JsonNode t : e.getValue()) {
					if (!t.isObject()) {
						continue;
					}
					oneTime.add(new StoreNonSubscription(truncate(e.getKey(), 255), truncate(text(t.path("id")), 255),
							truncate(text(t.path("store_transaction_id")), 255), lower(text(t.path("store"))),
							t.path("is_sandbox").asBoolean(false) ? PaymentEnvironment.SANDBOX
									: PaymentEnvironment.PRODUCTION,
							instant(t.path("purchase_date")), instant(t.path("refunded_at"))));
				}
			}
		}
		String management = text(subscriber.path("management_url"));
		if (management != null && !management.startsWith("https://")) {
			management = null;
		}
		return new StoreCustomer(appUserId, management, subscriptions, oneTime);
	}

	private static JsonNode read(ClientHttpResponse response) throws IOException {
		int status = response.getStatusCode().value();
		byte[] bytes;
		try (InputStream in = response.getBody()) {
			bytes = in.readNBytes(MAX_BODY_BYTES);
		}
		if (status >= 500 || status == 429) {
			throw new PaymentProviderException(Kind.UNAVAILABLE, "HTTP_" + status);
		}
		if (status == 401 || status == 403) {
			throw new PaymentProviderException(Kind.MISCONFIGURED, "HTTP_" + status);
		}
		if (status / 100 != 2) {
			throw new PaymentProviderException(Kind.REJECTED, "HTTP_" + status);
		}
		try {
			JsonNode node = bytes.length == 0 ? null : JSON.readTree(bytes);
			if (node == null || !node.isObject()) {
				throw new PaymentProviderException(Kind.INVALID_RESPONSE, "NOT_AN_OBJECT");
			}
			return node;
		}
		catch (JacksonException e) {
			throw new PaymentProviderException(Kind.INVALID_RESPONSE, "NOT_JSON");
		}
	}

	// ------------------------------------------------------------------------------------------------ helpers

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

	private static void addText(Set<String> into, JsonNode node) {
		String s = text(node);
		if (s != null && s.length() <= 255) {
			into.add(s);
		}
	}

	private static void addTexts(Set<String> into, JsonNode node) {
		if (node != null && node.isArray()) {
			for (JsonNode n : node) {
				addText(into, n);
			}
		}
	}

	private static Instant instant(JsonNode node) {
		String s = text(node);
		if (s == null) {
			return null;
		}
		try {
			return Instant.parse(s);
		}
		catch (DateTimeParseException e) {
			return null;
		}
	}

	static String text(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		String s = node.asString();
		return s == null || s.isBlank() ? null : s;
	}

	private static String lower(String s) {
		return s == null ? null : s.toLowerCase(Locale.ROOT);
	}

	private static String truncate(String s, int max) {
		return s == null || s.length() <= max ? s : s.substring(0, max);
	}
}
