package com.qalsmartkeyboard.platform;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes an {@link ApiException} as the contract's {@code application/problem+json} body (with correlation id and the
 * exception's headers) directly to a servlet response, for code that runs outside Spring MVC, such as security filter
 * entry points. Inside controllers, throw the {@link ApiException} instead.
 */
public interface ProblemResponseWriter {

	void write(HttpServletRequest request, HttpServletResponse response, ApiException problem) throws IOException;
}
