package com.qalsmartkeyboard.platform.observability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.protocol.Contexts;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;
import io.sentry.protocol.SentryThread;

/**
 * The content-free filter for crash reports (ADR 0024, rule N2): Sentry's {@code beforeSend} and
 * {@code beforeBreadcrumb}. Pure functions over the event, no I/O, so they are unit-tested directly.
 *
 * <p>Rule for messages: an exception's message ({@code value}) is <b>always</b> replaced by {@link #REDACTED}. Only the
 * exception type, module and stack frames are sent. A message can quote anything the code had at hand (a SQL
 * parameter, a request field, a provider's echo of a prompt), and an allow-list of "safe" messages would rot, so there
 * is none. A log event keeps only its message template (a literal in our code, for example
 * {@code "Unhandled exception while serving request"}); the formatted text and the arguments are dropped.
 *
 * <p>Also dropped: every breadcrumb (they come from log lines), the user, request bodies, cookies, the query string,
 * the fragment, environment variables, all request headers except a short allow-list of content-free client headers,
 * extras, stack-frame local variables and every context except the runtime/os/app/trace ones and the MDC correlation
 * id.
 */
public final class SentryScrubber {

	/** What replaces an exception message or a dropped value. */
	public static final String REDACTED = "[redacted]";

	/** Request headers that carry no user content and help triage (lower case). */
	static final Set<String> ALLOWED_HEADERS = Set.of("user-agent", "x-correlation-id", "x-request-id", "x-app-version",
			"x-client-platform", "x-client-surface", "content-type", "accept", "content-length");

	/** Contexts the SDK fills with facts about the process, not the user. */
	static final Set<String> ALLOWED_CONTEXTS = Set.of("os", "runtime", "app", "trace", "spring");

	/** MDC keys kept (logback appender puts the MDC in the "MDC" context). */
	static final Set<String> ALLOWED_MDC = Set.of("correlationId");

	private SentryScrubber() {
	}

	/** {@code beforeSend}: never returns null (a crash is always worth its type and stack), only less. */
	public static SentryEvent scrub(SentryEvent event) {
		List<SentryException> exceptions = event.getExceptions();
		if (exceptions != null) {
			for (SentryException e : exceptions) {
				if (e.getValue() != null) {
					e.setValue(REDACTED);
				}
				scrubFrames(e.getStacktrace());
			}
		}
		List<SentryThread> threads = event.getThreads();
		if (threads != null) {
			for (SentryThread t : threads) {
				scrubFrames(t.getStacktrace());
			}
		}
		Message message = event.getMessage();
		if (message != null) {
			// Keep the template only: it is a literal from our code. Arguments and the formatted text may carry data.
			message.setFormatted(null);
			message.setParams(null);
		}
		event.setUser(null);
		event.setExtras(null);
		event.setBreadcrumbs(new ArrayList<>());
		event.setRequest(scrubRequest(event.getRequest()));
		scrubContexts(event.getContexts());
		return event;
	}

	/** {@code beforeBreadcrumb}: none at all on the server (they come from log lines and HTTP clients). */
	public static Breadcrumb dropBreadcrumb(Breadcrumb breadcrumb) {
		return null;
	}

	static Request scrubRequest(Request request) {
		if (request == null) {
			return null;
		}
		Request clean = new Request();
		clean.setMethod(request.getMethod());
		clean.setUrl(stripQuery(request.getUrl()));
		clean.setBodySize(request.getBodySize());
		Map<String, String> headers = request.getHeaders();
		if (headers != null) {
			Map<String, String> kept = new LinkedHashMap<>();
			headers.forEach((name, value) -> {
				if (name != null && ALLOWED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
					kept.put(name, value);
				}
			});
			clean.setHeaders(kept);
		}
		// Not copied: data (body), cookies, query string, fragment, env, others, api target, unknown.
		return clean;
	}

	static String stripQuery(String url) {
		if (url == null) {
			return null;
		}
		int cut = url.length();
		int q = url.indexOf('?');
		int h = url.indexOf('#');
		if (q >= 0) {
			cut = q;
		}
		if (h >= 0 && h < cut) {
			cut = h;
		}
		return url.substring(0, cut);
	}

	private static void scrubFrames(SentryStackTrace trace) {
		if (trace == null || trace.getFrames() == null) {
			return;
		}
		for (SentryStackFrame f : trace.getFrames()) {
			f.setVars(null);
			f.setContextLine(null);
			f.setPreContext(null);
			f.setPostContext(null);
		}
		trace.setRegisters(null);
	}

	private static void scrubContexts(Contexts contexts) {
		for (String key : Collections.list(contexts.keys())) {
			if ("MDC".equals(key)) {
				contexts.put(key, onlyAllowedMdc(contexts.get(key)));
			} else if (!ALLOWED_CONTEXTS.contains(key)) {
				contexts.remove(key);
			}
		}
	}

	private static Map<String, Object> onlyAllowedMdc(Object mdc) {
		Map<String, Object> kept = new LinkedHashMap<>();
		if (mdc instanceof Map<?, ?> map) {
			map.forEach((k, v) -> {
				if (k instanceof String s && ALLOWED_MDC.contains(s)) {
					kept.put(s, v);
				}
			});
		}
		return kept;
	}
}
