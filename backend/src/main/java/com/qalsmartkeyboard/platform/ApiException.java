package com.qalsmartkeyboard.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

/**
 * Thrown by any module to produce an RFC 9457 Problem response with a stable {@link ApiErrorCode}.
 *
 * <p>{@code detail} and extension members are sent to the client, so they must be safe: never put
 * user-supplied text (typed content, tokens, e-mail addresses) in them. The same applies to response
 * {@linkplain #headers() headers} (for example {@code Retry-After} and {@code RateLimit-*} of a 429), which must be
 * server-generated values.
 */
public class ApiException extends RuntimeException {

	private final ApiErrorCode code;
	private final HttpStatus status;
	private final String detail;
	private final List<ApiFieldError> errors;
	private final Map<String, Object> extensions;
	private final Map<String, String> headers;

	/** Headers the platform sets itself on every Problem; a module cannot override them. */
	private static final Set<String> RESERVED_HEADERS = Set.of("content-type", "content-length", "x-correlation-id");

	public ApiException(ApiErrorCode code) {
		this(code, code.defaultStatus(), null);
	}

	public ApiException(ApiErrorCode code, HttpStatus status, String detail) {
		this(code, status, detail, List.of(), Map.of());
	}

	/**
	 * @param extensions extra Problem members (for example {@code featureCode}, {@code resetAt} of a quota problem);
	 *                   keys must not collide with standard members.
	 */
	public ApiException(ApiErrorCode code, HttpStatus status, String detail, List<ApiFieldError> errors,
			Map<String, Object> extensions) {
		this(code, status, detail, errors, extensions, Map.of());
	}

	/**
	 * @param headers extra response headers (for example {@code Retry-After}, {@code RateLimit-Remaining}); names
	 *                {@code Content-Type}, {@code Content-Length} and {@code X-Correlation-Id} are reserved for the
	 *                platform and rejected
	 */
	public ApiException(ApiErrorCode code, HttpStatus status, String detail, List<ApiFieldError> errors,
			Map<String, Object> extensions, Map<String, String> headers) {
		// The message is for logs only; it is never sent to the client.
		super(code + (detail == null ? "" : ": " + detail));
		if (status.value() < 400) {
			throw new IllegalArgumentException("Problem status must be 4xx or 5xx");
		}
		this.code = code;
		this.status = status;
		this.detail = detail;
		this.errors = List.copyOf(errors);
		this.extensions = new LinkedHashMap<>(extensions);
		for (String name : headers.keySet()) {
			if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
				throw new IllegalArgumentException("Header " + name + " is set by the platform");
			}
		}
		this.headers = Map.copyOf(headers);
	}

	public ApiErrorCode code() {
		return code;
	}

	public HttpStatus status() {
		return status;
	}

	public String detail() {
		return detail;
	}

	public List<ApiFieldError> errors() {
		return errors;
	}

	public Map<String, Object> extensions() {
		return extensions;
	}

	/** Extra response headers of the Problem (immutable). */
	public Map<String, String> headers() {
		return headers;
	}
}
