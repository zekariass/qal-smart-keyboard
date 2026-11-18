package com.qalsmartkeyboard.platform.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;
import io.sentry.protocol.User;

/** ADR 0024: what the backend's {@code beforeSend} keeps and what it drops. */
class SentryScrubberTest {

	private static final String USER_TEXT = "selam, my phone is +251911000000";

	private static SentryEvent eventWithEverything() {
		SentryEvent event = new SentryEvent();
		SentryException ex = new SentryException();
		ex.setType("IllegalStateException");
		ex.setModule("java.lang");
		ex.setValue("could not translate: " + USER_TEXT);
		SentryStackFrame frame = new SentryStackFrame();
		frame.setModule("com.qalsmartkeyboard.aigateway.pipeline.AiPipeline");
		frame.setFunction("run");
		frame.setLineno(42);
		frame.setVars(Map.of("prompt", USER_TEXT));
		frame.setContextLine("String prompt = " + USER_TEXT);
		ex.setStacktrace(new SentryStackTrace(new ArrayList<>(List.of(frame))));
		event.setExceptions(new ArrayList<>(List.of(ex)));

		Message message = new Message();
		message.setMessage("Unhandled exception while serving request {}");
		message.setFormatted("Unhandled exception while serving request " + USER_TEXT);
		message.setParams(List.of(USER_TEXT));
		event.setMessage(message);

		User user = new User();
		user.setId("user-1");
		user.setEmail("a@example.com");
		user.setIpAddress("10.0.0.1");
		event.setUser(user);

		Request request = new Request();
		request.setMethod("POST");
		request.setUrl("https://api.example/api/v1/ai/actions?text=" + USER_TEXT + "#frag");
		request.setQueryString("text=" + USER_TEXT);
		request.setData(Map.of("text", USER_TEXT));
		request.setCookies("session=secret");
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Authorization", "Bearer secret-token");
		headers.put("Cookie", "session=secret");
		headers.put("X-Correlation-Id", "corr-1");
		headers.put("User-Agent", "QalAndroid/1.0");
		headers.put("X-App-Version", "1.0.0");
		headers.put("X-Forwarded-For", "196.188.0.1");
		headers.put("X-Mock-Scenario", USER_TEXT);
		request.setHeaders(headers);
		request.setEnvs(Map.of("QAL_OTP_SECRET_KEY", "secret"));
		request.setFragment("frag");
		event.setRequest(request);

		event.setExtra("draft", USER_TEXT);
		event.addBreadcrumb(new Breadcrumb("Calling provider with " + USER_TEXT));
		event.getContexts().put("MDC", new LinkedHashMap<>(Map.of("correlationId", "corr-1", "userText", USER_TEXT)));
		event.getContexts().put("Context Data", Map.of("x", USER_TEXT));
		event.getContexts().put("response", Map.of("body", USER_TEXT));
		event.getContexts().put("runtime", Map.of("name", "OpenJDK"));
		return event;
	}

	@Test
	void exceptionMessageIsAlwaysRedactedButTypeAndFramesStay() {
		SentryEvent event = SentryScrubber.scrub(eventWithEverything());
		SentryException ex = event.getExceptions().getFirst();
		assertEquals(SentryScrubber.REDACTED, ex.getValue());
		assertEquals("IllegalStateException", ex.getType());
		assertEquals("java.lang", ex.getModule());
		SentryStackFrame frame = ex.getStacktrace().getFrames().getFirst();
		assertEquals("run", frame.getFunction());
		assertEquals(42, frame.getLineno());
		assertNull(frame.getVars());
		assertNull(frame.getContextLine());
	}

	@Test
	void logMessageKeepsOnlyItsTemplate() {
		SentryEvent event = SentryScrubber.scrub(eventWithEverything());
		assertEquals("Unhandled exception while serving request {}", event.getMessage().getMessage());
		assertNull(event.getMessage().getFormatted());
		assertNull(event.getMessage().getParams());
	}

	@Test
	void requestKeepsMethodPathAndContentFreeHeadersOnly() {
		Request r = SentryScrubber.scrub(eventWithEverything()).getRequest();
		assertEquals("POST", r.getMethod());
		assertEquals("https://api.example/api/v1/ai/actions", r.getUrl());
		assertNull(r.getQueryString());
		assertNull(r.getData());
		assertNull(r.getCookies());
		assertNull(r.getEnvs());
		assertNull(r.getFragment());
		assertEquals(Map.of("X-Correlation-Id", "corr-1", "User-Agent", "QalAndroid/1.0", "X-App-Version", "1.0.0"), r.getHeaders());
	}

	@Test
	void userExtrasBreadcrumbsAndForeignContextsAreDropped() {
		SentryEvent event = SentryScrubber.scrub(eventWithEverything());
		assertNull(event.getUser());
		assertNull(event.getExtras());
		assertTrue(event.getBreadcrumbs().isEmpty());
		assertNull(event.getContexts().get("Context Data"));
		assertNull(event.getContexts().get("response"));
		assertEquals(Map.of("name", "OpenJDK"), event.getContexts().get("runtime"));
		assertEquals(Map.of("correlationId", "corr-1"), event.getContexts().get("MDC"));
	}

	@Test
	void nothingOfTheUserTextSurvivesSerialisation() throws Exception {
		SentryEvent event = SentryScrubber.scrub(eventWithEverything());
		StringWriter out = new StringWriter();
		new SentryOptions().getSerializer().serialize(event, out);
		String json = out.toString();
		assertFalse(json.contains("selam"), json);
		assertFalse(json.contains("+251911000000"), json);
		assertFalse(json.contains("secret"), json);
		assertFalse(json.contains("a@example.com"), json);
		assertFalse(json.contains("196.188"), json);
		assertTrue(json.contains("IllegalStateException"), json);
	}

	@Test
	void anEmptyEventPassesThrough() {
		SentryEvent event = new SentryEvent();
		assertSame(event, SentryScrubber.scrub(event));
	}

	@Test
	void everyBreadcrumbIsDropped() {
		assertNull(SentryScrubber.dropBreadcrumb(new Breadcrumb("GET /api/v1/me")));
	}

	@Test
	void stripQueryHandlesFragmentsAndPlainPaths() {
		assertEquals("/a/b", SentryScrubber.stripQuery("/a/b?x=1#f"));
		assertEquals("/a/b", SentryScrubber.stripQuery("/a/b#f?x=1"));
		assertEquals("/a/b", SentryScrubber.stripQuery("/a/b"));
		assertNull(SentryScrubber.stripQuery(null));
	}

	@Test
	void hardeningOverridesPropertiesThatWouldAddContent() {
		SentryOptions options = new SentryOptions();
		options.setSendDefaultPii(true);
		options.setMaxRequestBodySize(SentryOptions.RequestSize.ALWAYS);
		options.setTracesSampleRate(1.0);
		options.setAttachThreads(true);
		SentryConfiguration.harden(options, "staging");
		assertFalse(options.isSendDefaultPii());
		assertEquals(SentryOptions.RequestSize.NONE, options.getMaxRequestBodySize());
		assertNull(options.getTracesSampleRate());
		assertFalse(options.isAttachThreads());
		assertEquals(0, options.getMaxBreadcrumbs());
		assertEquals("staging", options.getEnvironment());
		SentryEvent scrubbed = options.getBeforeSend().execute(eventWithEverything(), new Hint());
		assertEquals(SentryScrubber.REDACTED, scrubbed.getExceptions().getFirst().getValue());
		assertNull(options.getBeforeBreadcrumb().execute(new Breadcrumb("x"), new Hint()));
	}

	@Test
	void environmentComesFromTheSettingThenTheProfiles() {
		assertEquals("staging", SentryConfiguration.resolveEnvironment(" staging ", new String[] { "prod" }));
		assertEquals("prod", SentryConfiguration.resolveEnvironment("", new String[] { "prod" }));
		assertEquals("dev,local", SentryConfiguration.resolveEnvironment(null, new String[] { "dev", "local" }));
		assertEquals("default", SentryConfiguration.resolveEnvironment("", new String[0]));
	}
}
