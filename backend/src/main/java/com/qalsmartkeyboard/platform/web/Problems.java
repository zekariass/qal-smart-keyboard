package com.qalsmartkeyboard.platform.web;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiFieldError;

/** Builds the contract's {@code Problem} body; shared by the MVC exception handler and the servlet writer. */
final class Problems {

	static final String BASE_URI_PROPERTY = "${qal.api.problem-base-uri:https://api.example.com/problems/}";
	static final int MAX_ERRORS = 50; // Problem.errors maxItems
	static final Set<String> STANDARD_MEMBERS = Set.of("type", "title", "status", "detail", "instance", "code",
			"correlationId", "errors");

	private Problems() {
	}

	static String normaliseBaseUri(String baseUri) {
		return baseUri.endsWith("/") ? baseUri : baseUri + "/";
	}

	static ProblemDetail build(String baseUri, String correlationId, ApiErrorCode code, HttpStatus status, String detail,
			List<ApiFieldError> errors, Map<String, Object> extensions) {
		ProblemDetail pd = ProblemDetail.forStatus(status);
		pd.setType(URI.create(baseUri + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
		pd.setTitle(code.title());
		if (detail != null) {
			pd.setDetail(detail);
		}
		pd.setProperty("code", code.name());
		pd.setProperty("correlationId", correlationId);
		if (errors != null && !errors.isEmpty()) {
			pd.setProperty("errors", errors.stream().limit(MAX_ERRORS).toList());
		}
		if (extensions != null) {
			extensions.forEach((k, v) -> {
				if (!STANDARD_MEMBERS.contains(k)) {
					pd.setProperty(k, v);
				}
			});
		}
		return pd;
	}
}
