package com.qalsmartkeyboard.platform;

import org.springframework.http.HttpStatus;

/**
 * Stable machine-readable error codes returned in the {@code code} member of a Problem response.
 *
 * <p>The set is exactly the list in the {@code Problem.code} description of {@code contracts/openapi.yaml}
 * (the contract is authoritative; System Design 14.4 lists some older names). Each code carries a safe
 * English title and the default HTTP status; modules may override the status via
 * {@link ApiException#ApiException(ApiErrorCode, HttpStatus, String)}. Clients switch on the code, never the title.
 */
public enum ApiErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
	INPUT_TOO_LONG(HttpStatus.BAD_REQUEST, "Input is too long"),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
	TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token expired"),
	REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "Refresh token reuse detected"),
	OTP_INVALID(HttpStatus.UNAUTHORIZED, "One-time code is invalid"),
	OTP_EXPIRED(HttpStatus.UNAUTHORIZED, "One-time code expired"),
	OTP_TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "Too many one-time code attempts"),
	EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email is already registered"),
	ATTESTATION_REQUIRED(HttpStatus.FORBIDDEN, "Device attestation required"),
	ATTESTATION_FAILED(HttpStatus.FORBIDDEN, "Device attestation failed"),
	ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "Account suspended"),
	DELETION_PENDING(HttpStatus.FORBIDDEN, "Account deletion is pending"),
	GUEST_NOT_ALLOWED(HttpStatus.FORBIDDEN, "Not available to guest sessions"),
	FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
	NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
	REVISION_CONFLICT(HttpStatus.CONFLICT, "Revision conflict"),
	IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "Idempotency key reused with a different request"),
	REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "Request is already in progress"),
	CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "Consent required"),
	FEATURE_NOT_ENTITLED(HttpStatus.FORBIDDEN, "Feature not included in the current plan"),
	QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Allowance used"),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
	LANGUAGE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "Language not supported"),
	LANGUAGE_PAIR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Language pair currently unavailable"),
	PROFILE_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "Profile is not active"),
	OUTPUT_GUARDRAIL_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "Output rejected by guardrails"),
	CONTENT_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT, "Content rejected"),
	PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Provider temporarily unavailable"),
	PROVIDER_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "Provider timed out"),
	ZERO_RETENTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Zero-retention routing unavailable"),
	UPLOAD_EXPIRED(HttpStatus.BAD_REQUEST, "Upload expired"),
	AUDIO_TOO_LONG(HttpStatus.BAD_REQUEST, "Audio is too long"),
	UNSUPPORTED_AUDIO_FORMAT(HttpStatus.BAD_REQUEST, "Unsupported audio format"),
	REALTIME_SESSION_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "Realtime session limit reached"),
	BILLING_POLICY_DENIED(HttpStatus.FORBIDDEN, "Billing policy does not allow this action"),
	PRODUCT_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT, "Product not available"),
	PRICE_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT, "Price not available"),
	PROMOTION_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Promotion is not valid"),
	PAYMENT_METHOD_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT, "Payment method unavailable"),
	ORDER_NOT_PAYABLE(HttpStatus.CONFLICT, "Order cannot be paid"),
	ORDER_EXPIRED(HttpStatus.CONFLICT, "Order expired"),
	PAYMENT_VERIFICATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "Payment verification failed"),
	MANDATE_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT, "Mandate not supported"),
	STORE_PURCHASE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Store purchase is not valid"),
	STORE_PURCHASE_ALREADY_LINKED(HttpStatus.CONFLICT, "Store purchase already linked"),
	SUBSCRIPTION_NOT_RENEWABLE(HttpStatus.CONFLICT, "Subscription cannot be renewed"),
	SEAT_UNAVAILABLE(HttpStatus.CONFLICT, "No seat available"),
	API_KEY_INVALID(HttpStatus.UNAUTHORIZED, "API key is invalid"),
	API_SCOPE_MISSING(HttpStatus.FORBIDDEN, "API key scope missing"),
	WEBHOOK_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "Webhook signature invalid"),
	APP_VERSION_UNSUPPORTED(HttpStatus.UPGRADE_REQUIRED, "App version is no longer supported"),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

	private final HttpStatus defaultStatus;
	private final String title;

	ApiErrorCode(HttpStatus defaultStatus, String title) {
		this.defaultStatus = defaultStatus;
		this.title = title;
	}

	public HttpStatus defaultStatus() {
		return defaultStatus;
	}

	/** Short, safe, static English summary. Never contains user-supplied text. */
	public String title() {
		return title;
	}
}
