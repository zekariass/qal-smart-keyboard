package com.qalsmartkeyboard.billing;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.billing.BillingDtos.AdminPriceUpdate;
import com.qalsmartkeyboard.billing.BillingDtos.AdminProductDto;
import com.qalsmartkeyboard.billing.BillingDtos.AdminProductListDto;
import com.qalsmartkeyboard.billing.BillingDtos.AdminProductUpdate;
import com.qalsmartkeyboard.billing.BillingDtos.BillingOptionsDto;
import com.qalsmartkeyboard.billing.BillingDtos.MarketOfferingSetDto;
import com.qalsmartkeyboard.billing.BillingDtos.MarketOfferingSetUpdate;
import com.qalsmartkeyboard.billing.BillingDtos.CreateAttemptRequest;
import com.qalsmartkeyboard.billing.BillingDtos.CreateOrderRequest;
import com.qalsmartkeyboard.billing.BillingDtos.PaymentAttemptDto;
import com.qalsmartkeyboard.billing.BillingDtos.PaymentOrderDto;
import com.qalsmartkeyboard.billing.BillingDtos.ProductListDto;
import com.qalsmartkeyboard.billing.BillingDtos.ProviderRouteSetDto;
import com.qalsmartkeyboard.billing.BillingDtos.ProviderRouteSetUpdate;
import com.qalsmartkeyboard.billing.BillingDtos.StorePurchaseRequest;
import com.qalsmartkeyboard.billing.BillingDtos.StorePurchaseResultDto;
import com.qalsmartkeyboard.billing.BillingDtos.SubscriptionListDto;
import com.qalsmartkeyboard.billing.BillingDtos.WebhookAckDto;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.platform.geo.CountryResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * The billing operations of the contract (tags Billing, Webhooks, Admin) implemented in ADR 0045:
 * {@code getBillingOptions}, {@code listBillingProducts}, {@code createPaymentOrder}, {@code getPaymentOrder},
 * {@code cancelPaymentOrder}, {@code createPaymentAttempt}, {@code getPaymentAttempt}, {@code returnFromCheckout},
 * {@code listSubscriptions}, {@code receiveGatewayWebhook}, {@code getProviderRoutes}, {@code replaceProviderRoutes};
 * and in ADR 0046 {@code verifyStorePurchase} and {@code receiveStoreNotification} (RevenueCat); and in T8.10 the
 * catalogue admin ({@code listAdminBillingProducts}, {@code updateAdminBillingProduct}, {@code setAdminBillingPrice})
 * and market offerings ({@code getMarketOfferings}, {@code replaceMarketOfferings}).
 * Responses are {@code no-store}.
 */
@RestController
class BillingController {

	private final CountryResolver countries;
	private final BillingCatalog catalog;
	private final OrderService orders;
	private final AttemptService attempts;
	private final CheckoutReturn returns;
	private final SubscriptionQueries subscriptions;
	private final WebhookIntake webhooks;
	private final ProviderRouteAdmin routes;
	private final StorePurchases storePurchases;
	private final StoreNotificationIntake storeNotifications;
	private final CatalogAdmin catalogAdmin;
	private final MarketOfferingAdmin offerings;

	BillingController(CountryResolver countries, BillingCatalog catalog, OrderService orders, AttemptService attempts,
			CheckoutReturn returns, SubscriptionQueries subscriptions, WebhookIntake webhooks,
			ProviderRouteAdmin routes, StorePurchases storePurchases, StoreNotificationIntake storeNotifications,
			CatalogAdmin catalogAdmin, MarketOfferingAdmin offerings) {
		this.countries = countries;
		this.catalog = catalog;
		this.orders = orders;
		this.attempts = attempts;
		this.returns = returns;
		this.subscriptions = subscriptions;
		this.webhooks = webhooks;
		this.routes = routes;
		this.storePurchases = storePurchases;
		this.storeNotifications = storeNotifications;
		this.catalogAdmin = catalogAdmin;
		this.offerings = offerings;
	}

	@GetMapping("/api/v1/billing/options")
	ResponseEntity<BillingOptionsDto> options(@RequestParam("clientPlatform") String clientPlatform,
			@RequestParam("distributionChannel") String distributionChannel,
			@RequestParam(name = "marketCountry", required = false) String marketCountry,
			@RequestParam(name = "storefront", required = false) String storefront, HttpServletRequest request) {
		if (marketCountry != null && !marketCountry.matches("[A-Z]{2}")) {
			throw BillingErrors.invalid("marketCountry", "INVALID", "ISO 3166-1 alpha-2 country code.");
		}
		Channel channel = Channel.of(clientPlatform, distributionChannel).orElseThrow(() -> BillingErrors
				.invalid("distributionChannel", "NOT_SUPPORTED", "This platform and channel cannot buy."));
		return noStore(catalog.options(countries.resolve(request), channel));
	}

	@GetMapping("/api/v1/billing/products")
	ResponseEntity<ProductListDto> products(@RequestParam("marketCountry") String marketCountry,
			@RequestParam("salesChannel") String salesChannel) {
		if (!marketCountry.matches("[A-Z]{2}")) {
			throw BillingErrors.invalid("marketCountry", "INVALID", "ISO 3166-1 alpha-2 country code.");
		}
		if (Channel.ofSalesChannel(salesChannel).isEmpty()) {
			throw BillingErrors.invalid("salesChannel", "INVALID", "Unknown sales channel.");
		}
		return noStore(new ProductListDto(catalog.listed(marketCountry, salesChannel)));
	}

	@PostMapping(path = "/api/v1/billing/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<PaymentOrderDto> createOrder(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestHeader(name = "X-Client-Platform", required = false) String platform,
			@Valid @RequestBody CreateOrderRequest body, HttpServletRequest request) {
		PaymentOrderDto order = orders.create(user, idempotencyKey, body, platform, countries.resolve(request));
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(order);
	}

	@GetMapping("/api/v1/billing/orders/{orderId}")
	ResponseEntity<PaymentOrderDto> getOrder(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable UUID orderId) {
		return noStore(orders.get(user.userId(), orderId));
	}

	@PostMapping("/api/v1/billing/orders/{orderId}/cancel")
	ResponseEntity<PaymentOrderDto> cancelOrder(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable UUID orderId) {
		return noStore(orders.cancel(user.userId(), orderId));
	}

	@PostMapping(path = "/api/v1/billing/orders/{orderId}/attempts", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<PaymentAttemptDto> createAttempt(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable UUID orderId, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody CreateAttemptRequest body) {
		PaymentAttemptDto attempt = attempts.create(user, orderId, idempotencyKey, body);
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(attempt);
	}

	@GetMapping("/api/v1/billing/orders/{orderId}/attempts/{attemptId}")
	ResponseEntity<PaymentAttemptDto> getAttempt(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable UUID orderId, @PathVariable UUID attemptId) {
		return noStore(attempts.get(user.userId(), orderId, attemptId));
	}

	@GetMapping("/api/v1/billing/return/{attemptId}")
	ResponseEntity<Void> returnFromCheckout(@PathVariable UUID attemptId,
			@RequestParam(name = "result", required = false) String result) {
		URI target = returns.handle(attemptId, result);
		return ResponseEntity.status(HttpStatus.SEE_OTHER).cacheControl(CacheControl.noStore())
				.header(HttpHeaders.LOCATION, target.toString()).build();
	}

	@GetMapping("/api/v1/me/subscriptions")
	ResponseEntity<SubscriptionListDto> listSubscriptions(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam(name = "cursor", required = false) String cursor,
			@RequestParam(name = "limit", required = false) Integer limit) {
		if (cursor != null && cursor.length() > 512) {
			throw BillingErrors.invalid("cursor", "INVALID", "The cursor is not valid.");
		}
		return noStore(subscriptions.list(user.userId(), cursor, limit));
	}

	@PostMapping("/api/v1/billing/webhooks/{providerCode}/{accountCode}")
	ResponseEntity<WebhookAckDto> webhook(@PathVariable String providerCode, @PathVariable String accountCode,
			HttpServletRequest request) throws IOException {
		if (!providerCode.matches("[A-Z][A-Z0-9_]{0,39}") || !accountCode.matches("[A-Za-z0-9_-]{1,60}")) {
			throw BillingErrors.notFound();
		}
		byte[] body;
		try (InputStream in = request.getInputStream()) {
			body = in.readNBytes(WebhookIntake.MAX_BODY_BYTES + 1);
		}
		Map<String, List<String>> headers = headers(request);
		Map<String, String> query = new LinkedHashMap<>();
		String raw = request.getQueryString();
		if (raw != null) {
			for (String pair : raw.split("&")) {
				int eq = pair.indexOf('=');
				String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
				String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
				query.putIfAbsent(name, value);
			}
		}
		webhooks.receive(providerCode, accountCode, body, headers, query);
		return noStore(new WebhookAckDto(true));
	}

	@PostMapping(path = "/api/v1/billing/store-purchases", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<StorePurchaseResultDto> storePurchase(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody StorePurchaseRequest body) {
		return noStore(storePurchases.report(user, idempotencyKey, body));
	}

	@PostMapping("/api/v1/billing/store-notifications/{providerCode}")
	ResponseEntity<WebhookAckDto> storeNotification(@PathVariable String providerCode, HttpServletRequest request)
			throws IOException {
		if (!providerCode.matches("[A-Z][A-Z0-9_]{0,39}")) {
			throw BillingErrors.notFound();
		}
		byte[] body;
		try (InputStream in = request.getInputStream()) {
			body = in.readNBytes(StoreNotificationIntake.MAX_BODY_BYTES + 1);
		}
		storeNotifications.receive(providerCode, body, headers(request));
		return noStore(new WebhookAckDto(true));
	}

	private static Map<String, List<String>> headers(HttpServletRequest request) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		for (String name : Collections.list(request.getHeaderNames())) {
			headers.put(name.toLowerCase(Locale.ROOT), new ArrayList<>(Collections.list(request.getHeaders(name))));
		}
		return headers;
	}

	@GetMapping("/admin/v1/billing/provider-routes/{countryCode}/{clientPlatform}/{distributionChannel}")
	ResponseEntity<ProviderRouteSetDto> getRoutes(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable String countryCode, @PathVariable String clientPlatform,
			@PathVariable String distributionChannel) {
		return noStore(routes.get(user, countryCode, clientPlatform, distributionChannel));
	}

	@PutMapping(path = "/admin/v1/billing/provider-routes/{countryCode}/{clientPlatform}/{distributionChannel}",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<ProviderRouteSetDto> replaceRoutes(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable String countryCode, @PathVariable String clientPlatform,
			@PathVariable String distributionChannel, @Valid @RequestBody ProviderRouteSetUpdate body) {
		return noStore(routes.replace(user, countryCode, clientPlatform, distributionChannel, body));
	}

	@GetMapping("/admin/v1/billing/products")
	ResponseEntity<AdminProductListDto> adminProducts(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam(name = "marketCountry", required = false) String marketCountry) {
		return noStore(catalogAdmin.list(user, marketCountry));
	}

	@PatchMapping(path = "/admin/v1/billing/products/{productCode}", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<AdminProductDto> updateAdminProduct(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable String productCode, @Valid @RequestBody AdminProductUpdate body) {
		return noStore(catalogAdmin.update(user, productCode, body));
	}

	@PutMapping(path = "/admin/v1/billing/products/{productCode}/prices/{marketCountry}/{salesChannel}",
			consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<AdminProductDto> setAdminPrice(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable String productCode, @PathVariable String marketCountry, @PathVariable String salesChannel,
			@Valid @RequestBody AdminPriceUpdate body) {
		return noStore(catalogAdmin.setPrice(user, productCode, marketCountry, salesChannel, body));
	}

	@GetMapping("/admin/v1/billing/market-offerings/{countryCode}")
	ResponseEntity<MarketOfferingSetDto> getOfferings(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable String countryCode) {
		return noStore(offerings.get(user, countryCode));
	}

	@PutMapping(path = "/admin/v1/billing/market-offerings/{countryCode}", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<MarketOfferingSetDto> replaceOfferings(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable String countryCode, @Valid @RequestBody MarketOfferingSetUpdate body) {
		return noStore(offerings.replace(user, countryCode, body));
	}

	private static <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
