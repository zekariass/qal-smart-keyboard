package com.qalsmartkeyboard.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Thrown by any module to produce an RFC 9457 Problem response with a stable {@link ApiErrorCode}.
 *
 * <p>{@code detail} and extension members are sent to the client, so they must be safe: never put
 * user-supplied text (typed content, tokens, e-mail addresses) in them.
 */
public class ApiException extends RuntimeException {

	private final ApiErrorCode code;
	private final HttpStatus status;
	private final String detail;
	private final List<ApiFieldError> errors;
	private final Map<String, Object> extensions;

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
}
