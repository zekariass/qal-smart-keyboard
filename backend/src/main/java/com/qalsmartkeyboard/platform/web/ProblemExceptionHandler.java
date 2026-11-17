package com.qalsmartkeyboard.platform.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.CorrelationId;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

/**
 * Global mapping of every error to an RFC 9457 {@code application/problem+json} body that matches the
 * {@code Problem} schema of {@code contracts/openapi.yaml}: {@code type, title, status, code, correlationId}
 * plus {@code detail}, {@code errors} and module-supplied extension members when present.
 *
 * <p>Privacy: no message of a caught exception, rejected value or request text is ever copied into a response.
 * Titles, details and field messages are static strings; field names come from the server's own DTO/parameter
 * declarations. Spring adds {@code instance} (the request path) itself.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);
	private static final int MAX_ERRORS = 50; // Problem.errors maxItems

	private final String problemBaseUri;

	ProblemExceptionHandler(@Value("${qal.api.problem-base-uri:https://api.example.com/problems/}") String problemBaseUri) {
		this.problemBaseUri = problemBaseUri.endsWith("/") ? problemBaseUri : problemBaseUri + "/";
	}

	// ------------------------------------------------------------------ module exceptions

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Object> handleApiException(ApiException ex, HttpServletRequest request) {
		if (ex.status().is5xxServerError()) {
			log.error("API exception {} ({})", ex.code(), ex.status().value(), ex);
		}
		return problem(request, ex.code(), ex.status(), ex.detail(), ex.errors(), ex.extensions(), HttpHeaders.EMPTY);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
		List<ApiFieldError> errors = new ArrayList<>();
		for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
			String path = v.getPropertyPath().toString();
			String field = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
			errors.add(fieldError(field, v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()));
		}
		return problem(request, ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null, errors, null, HttpHeaders.EMPTY);
	}

	/** Spring Security exceptions thrown inside controllers must not become 500s. */
	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
		return problem(request, ApiErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, null, List.of(), null, HttpHeaders.EMPTY);
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<Object> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
		return problem(request, ApiErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, null, List.of(), null, HttpHeaders.EMPTY);
	}

	/** Last resort: nothing about the exception reaches the client. */
	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unhandled exception while serving request", ex);
		return problem(request, ApiErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, null, List.of(), null,
				HttpHeaders.EMPTY);
	}

	// ------------------------------------------------------------------ Spring MVC exceptions

	/** Every framework exception (400, 404, 405, 406, 415, ...) is funnelled through here by the base class. */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest webRequest) {
		HttpServletRequest request = ((ServletWebRequest) webRequest).getRequest();
		HttpStatus status = HttpStatus.resolve(statusCode.value());
		if (status == null) {
			status = statusCode.is5xxServerError() ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST;
		}
		if (status.is5xxServerError()) {
			log.error("Framework exception mapped to {}", status.value(), ex);
		}
		ApiErrorCode code = switch (status) {
			case NOT_FOUND -> ApiErrorCode.NOT_FOUND;
			default -> status.is5xxServerError() ? ApiErrorCode.INTERNAL_ERROR : ApiErrorCode.VALIDATION_FAILED;
		};
		return problem(request, code, status, null, frameworkErrors(ex), null, headers);
	}

	private static List<ApiFieldError> frameworkErrors(Exception ex) {
		List<ApiFieldError> errors = new ArrayList<>();
		if (ex instanceof MethodArgumentNotValidException manv) {
			for (ObjectError e : manv.getBindingResult().getAllErrors()) {
				errors.add(fieldError(e instanceof FieldError fe ? fe.getField() : null, e.getCode()));
			}
		}
		else if (ex instanceof HandlerMethodValidationException hmve) {
			for (ParameterValidationResult result : hmve.getParameterValidationResults()) {
				if (result instanceof ParameterErrors pe) {
					for (FieldError fe : pe.getFieldErrors()) {
						errors.add(fieldError(fe.getField(), fe.getCode()));
					}
					for (ObjectError oe : pe.getGlobalErrors()) {
						errors.add(fieldError(null, oe.getCode()));
					}
				}
				else {
					String field = result.getMethodParameter().getParameterName();
					for (MessageSourceResolvable r : result.getResolvableErrors()) {
						String[] codes = r.getCodes();
						errors.add(fieldError(field, codes == null || codes.length == 0 ? null : codes[codes.length - 1]));
					}
				}
			}
		}
		else if (ex instanceof HttpMessageNotReadableException) {
			errors.add(new ApiFieldError(null, "MALFORMED_REQUEST_BODY",
					"The request body is missing or is not valid JSON for this endpoint."));
		}
		else if (ex instanceof MissingServletRequestParameterException mspre) {
			errors.add(fieldError(mspre.getParameterName(), "NotNull"));
		}
		else if (ex instanceof TypeMismatchException tme) {
			errors.add(fieldError(tme.getPropertyName(), "TypeMismatch"));
		}
		else if (ex instanceof ServletRequestBindingException) {
			errors.add(new ApiFieldError(null, "INVALID_REQUEST", "The request is missing or has an invalid parameter or header."));
		}
		return errors;
	}

	// ------------------------------------------------------------------ building the Problem

	private ResponseEntity<Object> problem(HttpServletRequest request, ApiErrorCode code, HttpStatus status,
			String detail, List<ApiFieldError> errors, java.util.Map<String, Object> extensions, HttpHeaders extraHeaders) {
		ProblemDetail pd = ProblemDetail.forStatus(status);
		pd.setType(URI.create(problemBaseUri + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
		pd.setTitle(code.title());
		if (detail != null) {
			pd.setDetail(detail);
		}
		pd.setProperty("code", code.name());
		pd.setProperty("correlationId", correlationId(request));
		if (errors != null && !errors.isEmpty()) {
			pd.setProperty("errors", errors.stream().limit(MAX_ERRORS).toList());
		}
		if (extensions != null) {
			extensions.forEach((k, v) -> {
				if (!List.of("type", "title", "status", "detail", "instance", "code", "correlationId", "errors").contains(k)) {
					pd.setProperty(k, v);
				}
			});
		}
		HttpHeaders headers = new HttpHeaders();
		headers.putAll(extraHeaders);
		headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		headers.set(CorrelationId.HEADER, correlationId(request));
		return ResponseEntity.status(status).headers(headers).body(pd);
	}

	private static String correlationId(HttpServletRequest request) {
		return CorrelationIdFilter.idOf(request);
	}

	private static ApiFieldError fieldError(String field, String constraint) {
		String code = constraintCode(constraint);
		String name = field == null ? "The request" : field;
		String message = "REQUIRED".equals(code) ? name + " is required." : name + " is invalid.";
		return new ApiFieldError(field, code, message);
	}

	/** NotNull/NotBlank/NotEmpty become REQUIRED; other constraints become UPPER_SNAKE of the annotation name. */
	static String constraintCode(String constraint) {
		if (constraint == null || constraint.isBlank()) {
			return "INVALID";
		}
		return switch (constraint) {
			case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
			default -> constraint.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
		};
	}
}
