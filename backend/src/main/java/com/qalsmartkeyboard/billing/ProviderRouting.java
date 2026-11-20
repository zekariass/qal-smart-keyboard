package com.qalsmartkeyboard.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.qalsmartkeyboard.billing.RoutingData.Method;
import com.qalsmartkeyboard.billing.RoutingData.Policy;
import com.qalsmartkeyboard.billing.RoutingData.Route;
import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.platform.geo.CountryResolution.Confidence;

/**
 * The provider resolution layer (ADR 0045): {@code (billing country, client channel)} to the ordered payment options.
 * No country is named in code; everything comes from {@code billing_provider_routes}, {@code billing_market_channel_policies}
 * and the provider/account/method rows.
 *
 * <ol>
 * <li><b>Market.</b> The billing country counts when its confidence reaches {@code qal.billing.min-country-confidence}
 * (default MEDIUM: a trusted IP country); otherwise the market is {@code ZZ}, the global default.</li>
 * <li><b>Routes.</b> The market's own routes for the channel; when it has none, the {@code ZZ} routes (global
 * default, RevenueCat).</li>
 * <li><b>Policy</b> (SD 16.3 rule 6, fail closed): the market's own policy row; only when the routes came from
 * {@code ZZ} and the market has no row of its own, the {@code ZZ} row. It must be APPROVED, inside its validity window
 * and allow the purchase UI, else nothing is offered.</li>
 * <li><b>Each route by priority</b> is offered when: enabled; provider {@code is_active} and {@code ACTIVE} (rule 1); an
 * adapter is registered for the environment (rule 2); the provider type fits the policy's purchase flow (store
 * providers need {@code STORE_BILLING}; gateways need {@code LOCAL_GATEWAY_IN_APP} or {@code EXTERNAL_WEB_CHECKOUT} and
 * {@code external_payment_allowed}); and, for gateways, at least one account method passes rules 3-5 for the market,
 * environment, currency and amount. Recurring (rule 7) is not offered by this layer (MANUAL_RENEW only).</li>
 * </ol>
 * The first offered option is the default. Every skipped route is reported with its reason (admin view, logs).
 */
final class ProviderRouting {

	static final String GLOBAL = "ZZ";

	/** What to route. {@code currency}/{@code amountMinor} narrow gateway methods when known (an order). */
	record Query(String country, Confidence confidence, Channel channel, String currency, Long amountMinor) {
	}

	record MethodOption(String providerCode, int priority, Method method) {
	}

	record ProviderOption(String providerCode, String providerType, int priority, List<MethodOption> methods) {
	}

	record RouteEvaluation(String providerCode, int priority, boolean offered, String reason) {
	}

	/**
	 * @param marketCountry     the market the decision is for ({@code ZZ} when the country is unknown or not trusted)
	 * @param routeScope        whose routes applied ({@code marketCountry} or {@code ZZ})
	 * @param purchaseFlow      the policy's flow, {@code NONE} when denied
	 * @param purchaseUiAllowed whether any purchase UI may be shown
	 * @param denial            why nothing may be offered (null when allowed)
	 */
	record Decision(String marketCountry, String routeScope, String purchaseFlow, boolean purchaseUiAllowed,
			String denial, List<ProviderOption> options, List<RouteEvaluation> evaluations) {

		boolean globalDefault() {
			return GLOBAL.equals(routeScope);
		}

		List<MethodOption> methods() {
			List<MethodOption> all = new ArrayList<>();
			options.forEach(o -> all.addAll(o.methods()));
			return all;
		}

		Optional<MethodOption> method(String methodCode) {
			return methods().stream().filter(m -> m.method().methodCode().equals(methodCode)).findFirst();
		}
	}

	private final RoutingData data;
	private final ProviderRegistry registry;
	private final PaymentEnvironment environment;
	private final Confidence minConfidence;
	private final Clock clock;

	ProviderRouting(RoutingData data, ProviderRegistry registry, PaymentEnvironment environment,
			Confidence minConfidence, Clock clock) {
		this.data = data;
		this.registry = registry;
		this.environment = environment;
		this.minConfidence = minConfidence;
		this.clock = clock;
	}

	/** The market a resolved country maps to: the country when trusted enough, else {@code ZZ}. */
	String market(String country, Confidence confidence) {
		return country != null && confidence != null && confidence.atLeast(minConfidence) ? country : GLOBAL;
	}

	Decision decide(Query q) {
		String market = market(q.country(), q.confidence());
		String platform = q.channel().platform();
		String distribution = q.channel().distribution();
		List<Route> routes = data.routes(market, platform, distribution);
		String scope = market;
		if (routes.isEmpty() && !GLOBAL.equals(market)) {
			routes = data.routes(GLOBAL, platform, distribution);
			scope = GLOBAL;
		}
		Optional<Policy> policy = data.policy(market, platform, distribution);
		if (policy.isEmpty() && GLOBAL.equals(scope) && !GLOBAL.equals(market)) {
			policy = data.policy(GLOBAL, platform, distribution);
		}
		String denial = policyDenial(policy);
		List<RouteEvaluation> evaluations = new ArrayList<>();
		List<ProviderOption> options = new ArrayList<>();
		if (denial != null) {
			routes.forEach(r -> evaluations.add(new RouteEvaluation(r.providerCode(), r.priority(), false, denial)));
			return new Decision(market, scope, "NONE", false, denial, List.of(), List.copyOf(evaluations));
		}
		Policy p = policy.orElseThrow();
		Set<String> seenMethods = new LinkedHashSet<>();
		for (Route r : routes) {
			String reason = routeDenial(r, p);
			List<MethodOption> methods = List.of();
			if (reason == null && isGateway(r.providerType())) {
				methods = new ArrayList<>();
				for (Method m : data.methods(r.providerId(), market, environment, q.currency())) {
					if (inRange(m, q.amountMinor()) && seenMethods.add(m.methodCode())) {
						methods.add(new MethodOption(r.providerCode(), r.priority(), m));
					}
				}
				if (methods.isEmpty()) {
					reason = "NO_ELIGIBLE_ACCOUNT_METHOD";
				}
			}
			else if (reason == null) {
				methods = List.of(new MethodOption(r.providerCode(), r.priority(), storeMethod(r)));
			}
			evaluations.add(new RouteEvaluation(r.providerCode(), r.priority(), reason == null,
					reason == null ? "OFFERED" : reason));
			if (reason == null) {
				options.add(new ProviderOption(r.providerCode(), r.providerType(), r.priority(), List.copyOf(methods)));
			}
		}
		return new Decision(market, scope, p.purchaseFlow(), true, null, List.copyOf(options),
				List.copyOf(evaluations));
	}

	private String policyDenial(Optional<Policy> policy) {
		if (policy.isEmpty()) {
			return "POLICY_MISSING";
		}
		Policy p = policy.get();
		Instant now = clock.instant();
		if (!"APPROVED".equals(p.approvalStatus())) {
			return "POLICY_" + p.approvalStatus();
		}
		if (p.effectiveFrom() != null && p.effectiveFrom().isAfter(now)
				|| p.effectiveUntil() != null && !p.effectiveUntil().isAfter(now)) {
			return "POLICY_NOT_IN_EFFECT";
		}
		if (!p.purchaseUiAllowed() || "NONE".equals(p.purchaseFlow()) || "CONSUMPTION_ONLY".equals(p.purchaseFlow())) {
			return "POLICY_NO_PURCHASE_UI";
		}
		return null;
	}

	private String routeDenial(Route r, Policy p) {
		if (!r.enabled()) {
			return "ROUTE_DISABLED";
		}
		if (!r.providerActive() || !"ACTIVE".equals(r.providerStatus())) {
			return "PROVIDER_INACTIVE";
		}
		if (!registry.registered(r.providerCode(), environment)) {
			return "NO_ADAPTER";
		}
		if ("APP_STORE".equals(r.providerType())) {
			return "STORE_BILLING".equals(p.purchaseFlow()) ? null : "FLOW_MISMATCH";
		}
		boolean gatewayFlow = "LOCAL_GATEWAY_IN_APP".equals(p.purchaseFlow())
				|| "EXTERNAL_WEB_CHECKOUT".equals(p.purchaseFlow());
		if (!gatewayFlow) {
			return "FLOW_MISMATCH";
		}
		return p.externalPaymentAllowed() ? null : "EXTERNAL_PAYMENT_NOT_ALLOWED";
	}

	private static boolean isGateway(String providerType) {
		return !"APP_STORE".equals(providerType);
	}

	private static boolean inRange(Method m, Long amount) {
		if (amount == null) {
			return true;
		}
		return (m.minAmountMinor() == null || amount >= m.minAmountMinor())
				&& (m.maxAmountMinor() == null || amount <= m.maxAmountMinor());
	}

	/** Store-managed providers have no gateway method rows; the app launches the store flow for the provider. */
	private static Method storeMethod(Route r) {
		return new Method(null, null, null, null, null, null, r.providerCode(), null, r.providerCode(), "APP_STORE",
				false, true, null, null);
	}
}
