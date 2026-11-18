package com.qalsmartkeyboard.platform.observability;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import io.sentry.Sentry;
import io.sentry.SentryOptions;

/**
 * Crash reporting with Sentry (ADR 0007 D47, ADR 0024). The Spring Boot 4 starter initialises the SDK; with
 * {@code sentry.dsn} blank ({@code QAL_SENTRY_DSN} unset: local, CI, tests) the SDK is disabled and sends nothing.
 *
 * <p>This bean hardens the options whatever the properties say, so a stray {@code SENTRY_*} variable cannot turn
 * content on: no default PII, no request bodies, no tracing, no Sentry Logs, the {@link SentryScrubber} as
 * {@code beforeSend}, and every breadcrumb dropped. The environment is {@code QAL_SENTRY_ENVIRONMENT} when set, else the
 * active Spring profiles ({@code default} without one).
 */
@Configuration(proxyBeanMethods = false)
class SentryConfiguration {

	@Bean
	Sentry.OptionsConfiguration<SentryOptions> qalSentryOptions(Environment environment,
			@Value("${qal.observability.sentry.environment:}") String configuredEnvironment) {
		String resolved = resolveEnvironment(configuredEnvironment, environment.getActiveProfiles());
		return options -> harden(options, resolved);
	}

	static void harden(SentryOptions options, String environment) {
		options.setSendDefaultPii(false);
		options.setMaxRequestBodySize(SentryOptions.RequestSize.NONE);
		options.setTracesSampleRate(null);
		options.setTracesSampler(null);
		options.getLogs().setEnabled(false);
		options.setAttachStacktrace(false);
		options.setAttachThreads(false);
		options.setMaxBreadcrumbs(0);
		options.setBeforeSend((event, hint) -> SentryScrubber.scrub(event));
		options.setBeforeBreadcrumb((breadcrumb, hint) -> SentryScrubber.dropBreadcrumb(breadcrumb));
		options.setEnvironment(environment);
	}

	static String resolveEnvironment(String configured, String[] activeProfiles) {
		if (configured != null && !configured.isBlank()) {
			return configured.trim();
		}
		if (activeProfiles == null || activeProfiles.length == 0) {
			return "default";
		}
		return Arrays.stream(activeProfiles).collect(Collectors.joining(","));
	}
}
