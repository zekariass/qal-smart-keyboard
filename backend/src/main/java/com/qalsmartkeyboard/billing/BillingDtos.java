package com.qalsmartkeyboard.billing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qalsmartkeyboard.billing.spi.Money;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the billing operations (contract schemas of the same names). */
final class BillingDtos {

	private BillingDtos() {
	}

	/** {@code Money}. */
	record MoneyDto(long amountMinor, String currency, int exponent) {

		static MoneyDto of(Money m) {
			return new MoneyDto(m.amountMinor(), m.currency(), m.exponent());
		}

		static MoneyDto of(long amountMinor, String currency, int exponent) {
			return new MoneyDto(amountMinor, currency, exponent);
		}
	}

	/** {@code Price}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PriceDto(String billingPeriod, String renewalModel, MoneyDto listPrice, String taxBehavior,
			String displayPrice) {
	}

	/** {@code Product}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ProductDto(String productCode, String productType, String displayName, String description, String planCode,
			Long creditUnits, Integer creditValidityDays, List<PriceDto> prices) {
	}

	record ProductListDto(List<ProductDto> items) {
	}

	/** {@code PaymentMethodOption}: public codes only, never account ids or secrets. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PaymentMethodOptionDto(String methodCode, String providerCode, String displayName, String methodType,
			Boolean supportsAutoRenew, Boolean requiresRedirect, MoneyDto minAmount, MoneyDto maxAmount,
			Integer priority, Boolean isDefault) {
	}

	/** {@code MarketResolution}. */
	record MarketResolutionDto(String source, String confidence, boolean globalDefault) {
	}

	/** {@code BillingOptions}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record BillingOptionsDto(String marketCountry, MarketResolutionDto marketResolution, String clientPlatform,
			String distributionChannel, String purchaseFlow, boolean purchaseUiAllowed, List<ProductDto> products,
			List<PaymentMethodOptionDto> paymentMethods, String notice, Instant policyEvaluatedAt) {
	}

	/** {@code CreateOrderRequest}. */
	record CreateOrderRequest(@NotBlank @Size(max = 60) String productCode,
			@NotNull @Pattern(regexp = "^(ONE_TIME|WEEKLY|MONTHLY|QUARTERLY|ANNUAL|LIFETIME)$") String billingPeriod,
			@NotNull @Pattern(regexp = "^[A-Z]{2}$") String marketCountry,
			@NotNull @Pattern(regexp = "^(WEB|ANDROID_GOOGLE_PLAY|ANDROID_DIRECT|IOS_APP_STORE|IOS_DIRECT|ADMIN|PARTNER)$") String salesChannel,
			@Pattern(regexp = "^(NONE|MANUAL_RENEW|AUTO_RENEW)$") String renewalPreference,
			@Size(max = 40) String promotionCode, UUID upgradeFromSubscriptionId) {
	}

	record OrderAmountsDto(MoneyDto list, MoneyDto discount, MoneyDto tax, MoneyDto total) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record FulfilmentDto(UUID subscriptionId, UUID creditLotId, UUID receiptId) {
	}

	/** {@code PaymentOrder}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PaymentOrderDto(UUID orderId, String orderReference, String orderType, String status, String productCode,
			String billingPeriod, String renewalModel, String marketCountry, String salesChannel, String taxBehavior,
			OrderAmountsDto amounts, String discountReason, Instant expiresAt, Instant paidAt,
			List<PaymentMethodOptionDto> allowedMethods, FulfilmentDto fulfilment, Instant createdAt) {
	}

	/** {@code CreateAttemptRequest}. {@code payerPhoneNumber} is write-only and not used by hosted checkout. */
	record CreateAttemptRequest(@NotBlank @Size(max = 60) String methodCode, @Size(max = 2048) String returnUrl,
			@Pattern(regexp = "^\\+[1-9][0-9]{6,14}$") String payerPhoneNumber, @Size(max = 35) String locale) {

		@Override
		public String toString() {
			return "CreateAttemptRequest[methodCode=" + methodCode + "]";
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record NextActionDto(String type, String checkoutUrl) {
	}

	/** {@code PaymentAttempt}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PaymentAttemptDto(UUID attemptId, int attemptNumber, String status, String methodCode, String providerCode,
			NextActionDto nextAction, String failureCode, String failureMessage, Instant expiresAt, Instant verifiedAt,
			Instant createdAt) {
	}

	/** {@code Subscription}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record SubscriptionDto(UUID subscriptionId, String planCode, String status, String sourceType, String billingMode,
			String providerCode, String methodCode, UUID mandateId, Instant currentPeriodStart, Instant currentPeriodEnd,
			Boolean autoRenewEnabled, Boolean cancelAtPeriodEnd, Instant nextBillingAt, Boolean renewable,
			String manageUrl) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record SubscriptionListDto(List<SubscriptionDto> items, String nextCursor) {
	}

	/**
	 * {@code StorePurchaseRequest}. Tokens are write-only and never printed. Only {@code REVENUECAT} is verified by this
	 * server (ADR 0046); the server reads the caller's customer instead of trusting a token.
	 */
	record StorePurchaseRequest(@NotNull @Pattern(regexp = "^(GOOGLE_PLAY|APPLE_APP_STORE|REVENUECAT)$") String provider,
			@NotBlank @Size(max = 255) String productId, @Size(max = 4096) String purchaseToken,
			@Size(max = 255) String packageName, @Size(max = 16384) String signedTransactionInfo,
			UUID appAccountToken) {

		@Override
		public String toString() {
			return "StorePurchaseRequest[provider=" + provider + ", productId=" + productId + "]";
		}
	}

	/** {@code StorePurchaseResult}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record StorePurchaseResultDto(UUID storePurchaseId, String productId, String status, String environment,
			Boolean autoRenewing, Instant acknowledgedAt, Instant expiresAt, SubscriptionDto subscription) {
	}

	/** {@code WebhookAck}. */
	record WebhookAckDto(boolean received) {
	}

	/** {@code ProviderRoute}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ProviderRouteDto(String providerCode, int priority, boolean enabled, String notes, Boolean providerActive,
			Boolean adapterRegistered) {
	}

	/** {@code ProviderRouteSet}. */
	record ProviderRouteSetDto(String countryCode, String clientPlatform, String distributionChannel, int revision,
			List<ProviderRouteDto> routes) {
	}

	/** {@code ProviderRouteSetUpdate}. */
	record ProviderRouteSetUpdate(@NotNull @Min(0) @Max(Integer.MAX_VALUE) Integer expectedRevision,
			@NotNull @Size(max = 20) List<@Valid @NotNull RouteInput> routes) {
	}

	record RouteInput(@NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String providerCode,
			Boolean enabled, @Size(max = 1000) String notes) {
	}

	/** {@code AdminBillingPrice}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AdminPriceDto(UUID priceId, String marketCountry, String salesChannel, MoneyDto amount, String taxBehavior,
			Integer taxRateBasisPoints, String billingPeriod, String renewalModel, int priceVersion, boolean active,
			Instant validFrom, Instant validUntil) {
	}

	/** {@code AdminBillingProduct}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AdminProductDto(String productCode, String productType, String displayName, String description,
			String productStatus, String planCode, Long creditUnits, Integer creditValidityDays,
			List<AdminPriceDto> prices) {
	}

	record AdminProductListDto(List<AdminProductDto> items) {
	}

	/** {@code AdminBillingProductUpdate}: every field optional, at least one present. */
	record AdminProductUpdate(@Size(min = 1, max = 120) String displayName, @Size(max = 2000) String description,
			@Pattern(regexp = "^(DRAFT|ACTIVE|RETIRED)$") String productStatus,
			@Min(1) @Max(1_000_000) Long creditUnits, @Min(1) @Max(3650) Integer creditValidityDays) {
	}

	/** {@code AdminBillingPriceUpdate}. */
	record AdminPriceUpdate(@NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency,
			@NotNull @Min(0) @Max(100_000_000_000L) Long amountMinor,
			@Pattern(regexp = "^(INCLUSIVE|EXCLUSIVE|NOT_APPLICABLE)$") String taxBehavior,
			@Min(0) @Max(10_000) Integer taxRateBasisPoints,
			@NotNull @Pattern(regexp = "^(ONE_TIME|WEEKLY|MONTHLY|QUARTERLY|ANNUAL|LIFETIME)$") String billingPeriod,
			@NotNull @Pattern(regexp = "^(NONE|MANUAL_RENEW|AUTO_RENEW|STORE_MANAGED)$") String renewalModel,
			Boolean active) {
	}

	/** One entry of {@code MarketOfferingSet.offerings}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record MarketOfferingDto(String offering, boolean enabled, String source, String notes) {
	}

	/** {@code MarketOfferingSet}. */
	record MarketOfferingSetDto(String countryCode, int revision, List<MarketOfferingDto> offerings) {
	}

	/** {@code MarketOfferingSetUpdate}. */
	record MarketOfferingSetUpdate(@NotNull @Min(0) @Max(Integer.MAX_VALUE) Integer expectedRevision,
			@NotNull @Size(max = 2) List<@Valid @NotNull MarketOfferingInput> offerings) {
	}

	record MarketOfferingInput(@NotNull @Pattern(regexp = "^(SUBSCRIPTIONS|AI_CREDITS)$") String offering,
			@NotNull Boolean enabled, @Size(max = 1000) String notes) {
	}
}
