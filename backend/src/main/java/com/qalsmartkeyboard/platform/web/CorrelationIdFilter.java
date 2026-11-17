package com.qalsmartkeyboard.platform.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.qalsmartkeyboard.platform.CorrelationId;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Establishes the correlation ID for every request: an inbound {@code X-Correlation-Id} is kept only when it is
 * well formed (1-100 characters from {@code A-Z a-z 0-9 . _ : -}; the contract caps the length at 100 and the
 * charset keeps it safe for logs and headers), otherwise a UUID is generated. The ID goes into the MDC, a request
 * attribute and the response header before the chain runs, so every response carries it, errors and 404s included.
 * Runs first, ahead of Spring Security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".ID";
	private static final Pattern WELL_FORMED = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String inbound = request.getHeader(CorrelationId.HEADER);
		String id = inbound != null && WELL_FORMED.matcher(inbound).matches() ? inbound : UUID.randomUUID().toString();
		request.setAttribute(REQUEST_ATTRIBUTE, id);
		response.setHeader(CorrelationId.HEADER, id);
		MDC.put(CorrelationId.MDC_KEY, id);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(CorrelationId.MDC_KEY);
		}
	}

	static String idOf(HttpServletRequest request) {
		return request.getAttribute(REQUEST_ATTRIBUTE) instanceof String s ? s : UUID.randomUUID().toString();
	}
}
