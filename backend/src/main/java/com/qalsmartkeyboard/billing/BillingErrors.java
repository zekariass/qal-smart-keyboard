package com.qalsmartkeyboard.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;

/** Problem helpers and small utilities shared by the billing services. Details are static, safe English. */
final class BillingErrors {

	private BillingErrors() {
	}

	static ApiException invalid(String field, String code, String message) {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
				List.of(new ApiFieldError(field, code, message)), Map.of());
	}

	static ApiException problem(ApiErrorCode code, String detail) {
		return new ApiException(code, code.defaultStatus(), detail);
	}

	static ApiException notFound() {
		return new ApiException(ApiErrorCode.NOT_FOUND);
	}

	/** The {@code Idempotency-Key} header as a UUID (contract: format uuid). */
	static String idempotencyKey(String header) {
		if (header == null || header.isBlank()) {
			throw invalid("Idempotency-Key", "REQUIRED", "The Idempotency-Key header is required.");
		}
		try {
			return UUID.fromString(header.strip()).toString();
		}
		catch (IllegalArgumentException e) {
			throw invalid("Idempotency-Key", "INVALID_HEADER", "Must be a UUID.");
		}
	}

	static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static String sha256Hex(String text) {
		return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
	}
}
