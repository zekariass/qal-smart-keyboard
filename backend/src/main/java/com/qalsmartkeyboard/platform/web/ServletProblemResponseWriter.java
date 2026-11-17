package com.qalsmartkeyboard.platform.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.CorrelationId;
import com.qalsmartkeyboard.platform.ProblemResponseWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Servlet-level twin of {@link ProblemExceptionHandler}: the same members ({@code type, title, status, detail,
 * instance, code, correlationId, errors} + extensions), the same correlation id and the exception's headers.
 */
@Component
public class ServletProblemResponseWriter implements ProblemResponseWriter {

	private final String problemBaseUri;
	private final JsonMapper json;

	public ServletProblemResponseWriter(@Value(Problems.BASE_URI_PROPERTY) String problemBaseUri, JsonMapper json) {
		this.problemBaseUri = Problems.normaliseBaseUri(problemBaseUri);
		this.json = json;
	}

	@Override
	public void write(HttpServletRequest request, HttpServletResponse response, ApiException problem)
			throws IOException {
		if (response.isCommitted()) {
			return;
		}
		String correlationId = CorrelationIdFilter.idOf(request);
		ProblemDetail pd = Problems.build(problemBaseUri, correlationId, problem.code(), problem.status(),
				problem.detail(), problem.errors(), problem.extensions());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", pd.getType().toString());
		body.put("title", pd.getTitle());
		body.put("status", pd.getStatus());
		if (pd.getDetail() != null) {
			body.put("detail", pd.getDetail());
		}
		body.put("instance", request.getRequestURI());
		if (pd.getProperties() != null) {
			body.putAll(pd.getProperties());
		}
		response.resetBuffer();
		response.setStatus(problem.status().value());
		problem.headers().forEach(response::setHeader);
		response.setHeader(CorrelationId.HEADER, correlationId);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getOutputStream().write(json.writeValueAsBytes(body));
		response.flushBuffer();
	}
}
