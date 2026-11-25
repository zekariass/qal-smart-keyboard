package com.qalsmartkeyboard.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.billing.ProviderRouting.Decision;
import com.qalsmartkeyboard.billing.ProviderRouting.Query;
import com.qalsmartkeyboard.billing.ProviderRouting.RouteEvaluation;
import com.qalsmartkeyboard.billing.RoutingData.Method;
import com.qalsmartkeyboard.billing.RoutingData.Policy;
import com.qalsmartkeyboard.billing.RoutingData.Route;
import com.qalsmartkeyboard.billing.spi.CheckoutRequest;
import com.qalsmartkeyboard.billing.spi.CheckoutSession;
import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.billing.spi.PaymentProvider;
import com.qalsmartkeyboard.billing.spi.StoreBillingProvider;
import com.qalsmartkeyboard.billing.spi.VerificationRequest;
import com.qalsmartkeyboard.billing.spi.VerificationResult;
import com.qalsmartkeyboard.billing.spi.WebhookAuthentication;
import com.qalsmartkeyboard.billing.spi.WebhookEvent;
import com.qalsmartkeyboard.billing.spi.WebhookRequest;
import com.qalsmartkeyboard.platform.geo.CountryResolution.Confidence;

/** ADR 0045 provider resolution: explicit policy, global fallback, eligibility gates, channels, config switches. */
class ProviderRoutingTest {

	static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
	static final Channel DIRECT = new Channel("ANDROID", "DIRECT_DOWNLOAD");
	static final Channel PLAY = new Channel("ANDROID", "GOOGLE_PLAY");
	static final UUID ARIFPAY = UUID.randomUUID();
	static final UUID CHAPA = UUID.randomUUID();
	static final UUID REVENUECAT = UUID.randomUUID();

	/** In-memory routes, policies and methods. */
	static final class Data implements RoutingData {
		final Map<String, List<Route>> routes = new HashMap<>();
		final Map<String, Policy> policies = new HashMap<>();
		final Map<UUID, List<Method>> methods = new HashMap<>();

		@Override
		public List<Route> routes(String country, String platform, String distribution) {
			return routes.getOrDefault(country + platform + distribution, List.of());
		}

		@Override
		public Optional<Policy> policy(String country, String platform, String distribution) {
			return Optional.ofNullable(policies.get(country + platform + distribution));
		}

		@Override
		public List<Method> methods(UUID providerId, String country, PaymentEnvironment env, String currency) {
			return methods.getOrDefault(providerId, List.of()).stream()
					.filter(m -> "ET".equals(country) && (currency == null || currency.equals(m.currencyCode())))
					.toList();
		}
	}

	static Route route(String code, UUID id, String type, boolean active, int priority) {
		return new Route(code, id, type, active ? "ACTIVE" : "CONFIGURATION_REQUIRED", active, priority, true);
	}

	static Method method(String code, Long min, Long max) {
		return new Method(UUID.randomUUID(), "acct-" + code.toLowerCase(), "ETB", null, UUID.randomUUID(),
				UUID.randomUUID(), code, null, code, "HOSTED_CHECKOUT", true, false, min, max);
	}

	static Policy approved(String flow, boolean external) {
		return new Policy(flow, "APPROVED", true, external, NOW.minus(1, ChronoUnit.DAYS), null);
	}

	static PaymentProvider gateway(String code) {
		return new PaymentProvider() {
			public String providerCode() {
				return code;
			}

			public boolean supports(PaymentEnvironment environment) {
				return environment == PaymentEnvironment.SANDBOX;
			}

			public CheckoutSession initiateCheckout(CheckoutRequest request) {
				throw new UnsupportedOperationException();
			}

			public VerificationResult verify(VerificationRequest request) {
				throw new UnsupportedOperationException();
			}

			public WebhookAuthentication authenticate(WebhookRequest request) {
				return WebhookAuthentication.INVALID;
			}

			public WebhookEvent parseWebhook(WebhookRequest request) {
				throw new UnsupportedOperationException();
			}
		};
	}

	static StoreBillingProvider store(String code) {
		return new StoreBillingProvider() {
			public String providerCode() {
				return code;
			}

			public boolean supports(PaymentEnvironment environment) {
				return true;
			}

			public com.qalsmartkeyboard.billing.spi.WebhookAuthentication authenticateNotification(
					com.qalsmartkeyboard.billing.spi.StoreNotificationRequest request) {
				throw new UnsupportedOperationException();
			}

			public com.qalsmartkeyboard.billing.spi.StoreNotification parseNotification(
					com.qalsmartkeyboard.billing.spi.StoreNotificationRequest request) {
				throw new UnsupportedOperationException();
			}

			public com.qalsmartkeyboard.billing.spi.StoreCustomer customer(String appUserId) {
				throw new UnsupportedOperationException();
			}
		};
	}

	Data data;
	List<PaymentProvider> gateways;
	List<StoreBillingProvider> stores;

	@BeforeEach
	void seed() {
		data = new Data();
		gateways = new ArrayList<>(List.of(gateway("ARIFPAY")));
		stores = new ArrayList<>();
		// V25 seed shape: ET -> ARIFPAY(1), CHAPA(2) for direct and Play; ZZ -> REVENUECAT
		for (Channel c : List.of(DIRECT, PLAY)) {
			data.routes.put("ET" + c.platform() + c.distribution(), List.of(route("ARIFPAY", ARIFPAY, "PAYMENT_GATEWAY", true, 1),
					route("CHAPA", CHAPA, "PAYMENT_GATEWAY", false, 2)));
			data.routes.put("ZZ" + c.platform() + c.distribution(), List.of(route("REVENUECAT", REVENUECAT, "APP_STORE", false, 1)));
			data.policies.put("ZZ" + c.platform() + c.distribution(), approved("STORE_BILLING", false));
		}
		data.policies.put("ETANDROIDDIRECT_DOWNLOAD", approved("LOCAL_GATEWAY_IN_APP", true));
		data.methods.put(ARIFPAY, List.of(method("ARIFPAY_CHECKOUT", 100L, 10_000_000L)));
	}

	ProviderRouting routing(Confidence min) {
		return new ProviderRouting(data, new ProviderRegistry(gateways, stores), PaymentEnvironment.SANDBOX, min,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	Decision decide(String country, Confidence confidence, Channel channel) {
		return routing(Confidence.MEDIUM).decide(new Query(country, confidence, channel, null, null));
	}

	@Test
	void explicitPolicyOffersArifPayFirstAndReportsSkippedChapa() {
		Decision d = decide("ET", Confidence.HIGH, DIRECT);
		assertThat(d.marketCountry()).isEqualTo("ET");
		assertThat(d.globalDefault()).isFalse();
		assertThat(d.purchaseFlow()).isEqualTo("LOCAL_GATEWAY_IN_APP");
		assertThat(d.options()).extracting(ProviderRouting.ProviderOption::providerCode).containsExactly("ARIFPAY");
		assertThat(d.methods().get(0).method().methodCode()).isEqualTo("ARIFPAY_CHECKOUT");
		assertThat(d.evaluations()).extracting(RouteEvaluation::providerCode, RouteEvaluation::reason)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("ARIFPAY", "OFFERED"),
						org.assertj.core.groups.Tuple.tuple("CHAPA", "PROVIDER_INACTIVE"));
	}

	@Test
	void countryWithoutRoutesFallsBackToTheGlobalDefault() {
		Decision d = decide("US", Confidence.HIGH, PLAY);
		assertThat(d.marketCountry()).isEqualTo("US");
		assertThat(d.routeScope()).isEqualTo("ZZ");
		assertThat(d.globalDefault()).isTrue();
		// RevenueCat is represented, but has no adapter and is inactive until step A2: nothing is offered
		assertThat(d.options()).isEmpty();
		assertThat(d.evaluations()).extracting(RouteEvaluation::providerCode).containsExactly("REVENUECAT");
	}

	@Test
	void globalStoreOptionAppearsOnceItsAdapterIsRegisteredAndActive() {
		data.routes.put("ZZANDROIDGOOGLE_PLAY", List.of(route("REVENUECAT", REVENUECAT, "APP_STORE", true, 1)));
		stores.add(store("REVENUECAT"));
		Decision d = decide("US", Confidence.HIGH, PLAY);
		assertThat(d.options()).extracting(ProviderRouting.ProviderOption::providerCode).containsExactly("REVENUECAT");
		assertThat(d.methods().get(0).method().methodType()).isEqualTo("APP_STORE");
		assertThat(d.methods().get(0).method().accountId()).isNull();
	}

	@Test
	void hintsOnlyOrUnknownCountryUseTheGlobalDefault() {
		assertThat(decide("ET", Confidence.LOW, DIRECT).marketCountry()).isEqualTo("ZZ");
		assertThat(decide(null, Confidence.NONE, DIRECT).routeScope()).isEqualTo("ZZ");
		// an operator may accept agreeing hints (LOW) on purpose
		assertThat(routing(Confidence.LOW).decide(new Query("ET", Confidence.LOW, DIRECT, null, null)).marketCountry())
				.isEqualTo("ET");
	}

	@Test
	void missingOrUnapprovedPolicyFailsClosed() {
		Decision play = decide("ET", Confidence.HIGH, PLAY); // ET + Play: no approved policy in this fixture (fail closed)
		assertThat(play.purchaseUiAllowed()).isFalse();
		assertThat(play.purchaseFlow()).isEqualTo("NONE");
		assertThat(play.denial()).isEqualTo("POLICY_MISSING");
		assertThat(play.options()).isEmpty();

		data.policies.put("ETANDROIDGOOGLE_PLAY", new Policy("LOCAL_GATEWAY_IN_APP", "REVIEW_REQUIRED", false, false,
				NOW.minus(1, ChronoUnit.DAYS), null));
		assertThat(decide("ET", Confidence.HIGH, PLAY).denial()).isEqualTo("POLICY_REVIEW_REQUIRED");

		data.policies.put("ETANDROIDGOOGLE_PLAY", new Policy("LOCAL_GATEWAY_IN_APP", "APPROVED", true, true,
				NOW.minus(2, ChronoUnit.DAYS), NOW.minus(1, ChronoUnit.DAYS)));
		assertThat(decide("ET", Confidence.HIGH, PLAY).denial()).isEqualTo("POLICY_NOT_IN_EFFECT");

		data.policies.put("ETANDROIDGOOGLE_PLAY", approved("LOCAL_GATEWAY_IN_APP", true));
		assertThat(decide("ET", Confidence.HIGH, PLAY).options()).hasSize(1);
	}

	@Test
	void aStoreProviderIsNeverOfferedToABuildWithoutStoreBilling() {
		// even an (erroneously) approved STORE_BILLING policy for the sideloaded APK offers nothing: no Play Billing there
		data.routes.put("ZZANDROIDDIRECT_DOWNLOAD", List.of(route("REVENUECAT", REVENUECAT, "APP_STORE", true, 1)));
		data.policies.put("ZZANDROIDDIRECT_DOWNLOAD", approved("STORE_BILLING", false));
		stores.add(store("REVENUECAT"));
		Decision d = decide("US", Confidence.HIGH, DIRECT);
		assertThat(d.options()).isEmpty();
		assertThat(d.evaluations().get(0).reason()).isEqualTo("NO_STORE_IN_CHANNEL");
		assertThat(d.policyFlow()).isEqualTo("STORE_BILLING");
		// the same provider is offered to the Play build
		data.routes.put("ZZANDROIDGOOGLE_PLAY", List.of(route("REVENUECAT", REVENUECAT, "APP_STORE", true, 1)));
		assertThat(decide("US", Confidence.HIGH, PLAY).options()).hasSize(1);
		// the policy's own flow is reported even when it is not approved
		data.policies.put("ZZANDROIDDIRECT_DOWNLOAD", new Policy("STORE_BILLING", "REVIEW_REQUIRED", false, false, null,
				null));
		assertThat(decide("US", Confidence.HIGH, DIRECT).policyFlow()).isEqualTo("STORE_BILLING");
		assertThat(decide("US", Confidence.HIGH, DIRECT).purchaseFlow()).isEqualTo("NONE");
	}

	@Test
	void aCountryWithOwnRoutesNeverBorrowsTheGlobalPolicy() {
		data.policies.remove("ETANDROIDDIRECT_DOWNLOAD");
		data.policies.put("ZZANDROIDDIRECT_DOWNLOAD", approved("LOCAL_GATEWAY_IN_APP", true));
		assertThat(decide("ET", Confidence.HIGH, DIRECT).denial()).isEqualTo("POLICY_MISSING");
	}

	@Test
	void gatewayNeedsExternalPaymentAllowedAndAMatchingFlow() {
		data.policies.put("ETANDROIDDIRECT_DOWNLOAD", approved("LOCAL_GATEWAY_IN_APP", false));
		assertThat(decide("ET", Confidence.HIGH, DIRECT).evaluations().get(0).reason())
				.isEqualTo("EXTERNAL_PAYMENT_NOT_ALLOWED");
		data.policies.put("ETANDROIDDIRECT_DOWNLOAD", approved("STORE_BILLING", false));
		assertThat(decide("ET", Confidence.HIGH, DIRECT).evaluations().get(0).reason()).isEqualTo("FLOW_MISMATCH");
	}

	@Test
	void noAdapterOrDisabledRouteOrNoMethodIsSkipped() {
		gateways.clear();
		assertThat(decide("ET", Confidence.HIGH, DIRECT).evaluations().get(0).reason()).isEqualTo("NO_ADAPTER");
		gateways.add(gateway("ARIFPAY"));
		data.routes.put("ETANDROIDDIRECT_DOWNLOAD", List.of(new Route("ARIFPAY", ARIFPAY, "PAYMENT_GATEWAY",
				"ACTIVE", true, 1, false)));
		assertThat(decide("ET", Confidence.HIGH, DIRECT).evaluations().get(0).reason()).isEqualTo("ROUTE_DISABLED");
		data.routes.put("ETANDROIDDIRECT_DOWNLOAD", List.of(route("ARIFPAY", ARIFPAY, "PAYMENT_GATEWAY", true, 1)));
		data.methods.put(ARIFPAY, List.of());
		assertThat(decide("ET", Confidence.HIGH, DIRECT).evaluations().get(0).reason())
				.isEqualTo("NO_ELIGIBLE_ACCOUNT_METHOD");
	}

	@Test
	void amountOutsideTheMethodRangeOrOtherCurrencyIsNotOffered() {
		ProviderRouting r = routing(Confidence.MEDIUM);
		assertThat(r.decide(new Query("ET", Confidence.HIGH, DIRECT, "ETB", 29_900L)).options()).hasSize(1);
		assertThat(r.decide(new Query("ET", Confidence.HIGH, DIRECT, "ETB", 50L)).options()).isEmpty();
		assertThat(r.decide(new Query("ET", Confidence.HIGH, DIRECT, "ETB", 20_000_000L)).options()).isEmpty();
		assertThat(r.decide(new Query("ET", Confidence.HIGH, DIRECT, "USD", 29_900L)).options()).isEmpty();
	}

	@Test
	void switchingTheDefaultToChapaIsAConfigurationChange() {
		gateways.add(gateway("CHAPA"));
		data.methods.put(CHAPA, List.of(method("CHAPA_TELEBIRR", null, null)));
		// the admin replace operation: CHAPA first, ARIFPAY second, CHAPA activated by an operator
		data.routes.put("ETANDROIDDIRECT_DOWNLOAD", List.of(route("CHAPA", CHAPA, "PAYMENT_GATEWAY", true, 1),
				route("ARIFPAY", ARIFPAY, "PAYMENT_GATEWAY", true, 2)));
		Decision d = decide("ET", Confidence.HIGH, DIRECT);
		assertThat(d.options()).extracting(ProviderRouting.ProviderOption::providerCode).containsExactly("CHAPA",
				"ARIFPAY");
		assertThat(d.methods().get(0).method().methodCode()).isEqualTo("CHAPA_TELEBIRR");
	}

	@Test
	void movingEthiopiaToRevenueCatIsAConfigurationChange() {
		stores.add(store("REVENUECAT"));
		data.routes.put("ETANDROIDGOOGLE_PLAY", List.of(route("REVENUECAT", REVENUECAT, "APP_STORE", true, 1)));
		data.policies.put("ETANDROIDGOOGLE_PLAY", approved("STORE_BILLING", false));
		Decision d = decide("ET", Confidence.HIGH, PLAY);
		assertThat(d.purchaseFlow()).isEqualTo("STORE_BILLING");
		assertThat(d.options()).extracting(ProviderRouting.ProviderOption::providerCode).containsExactly("REVENUECAT");
	}

	@Test
	void channelsAreIndependent() {
		assertThat(decide("ET", Confidence.HIGH, DIRECT).purchaseUiAllowed()).isTrue();
		assertThat(decide("ET", Confidence.HIGH, PLAY).purchaseUiAllowed()).isFalse();
		assertThat(decide("ET", Confidence.HIGH, new Channel("IOS", "APPLE_APP_STORE")).routeScope()).isEqualTo("ZZ");
	}

	@Test
	void sameMethodCodeFromTwoProvidersIsOfferedOnce() {
		gateways.add(gateway("CHAPA"));
		data.methods.put(CHAPA, List.of(method("ARIFPAY_CHECKOUT", null, null)));
		data.routes.put("ETANDROIDDIRECT_DOWNLOAD", List.of(route("ARIFPAY", ARIFPAY, "PAYMENT_GATEWAY", true, 1),
				route("CHAPA", CHAPA, "PAYMENT_GATEWAY", true, 2)));
		Decision d = decide("ET", Confidence.HIGH, DIRECT);
		assertThat(d.methods()).hasSize(1);
		assertThat(d.method("ARIFPAY_CHECKOUT").orElseThrow().providerCode()).isEqualTo("ARIFPAY");
	}
}
