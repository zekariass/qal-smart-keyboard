package com.qalsmartkeyboard.notifications.email;

import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.qalsmartkeyboard.notifications.EmailCodeSender;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Selects the e-mail provider (ADR 0033): {@code none} (default), {@code log} or {@code capture}. The two mocks are
 * refused under a production-like profile; an unknown value stops the application. Whether identity may run without
 * a provider is identity's decision (required e-mail verification needs one, see {@code EmailAuthConfiguration}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailProperties.class)
class EmailConfiguration {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";
	static final String ENDPOINT_PROPERTY = "qal.notifications.email.capture.endpoint-enabled";

	@Bean
	EmailCodeSender emailCodeSender(EmailProperties properties, Environment environment,
			ObjectProvider<MeterRegistry> meters) {
		return new TemplatedEmailCodeSender(provider(properties, environment), meters.getIfAvailable());
	}

	/** @return the provider, {@code null} for {@code none} */
	static EmailSender provider(EmailProperties properties, Environment environment) {
		String provider = properties.provider() == null ? "" : properties.provider().trim().toLowerCase(Locale.ROOT);
		boolean productionLike = environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES));
		return switch (provider) {
			case "none", "" -> null;
			case "log" -> {
				if (productionLike) {
					throw new IllegalStateException(
							"The 'log' e-mail provider is refused under the prod/production/staging profiles");
				}
				yield new LoggingEmailSender(properties.log().includeMessage());
			}
			case "capture" -> {
				if (productionLike) {
					throw new IllegalStateException(
							"The 'capture' e-mail provider is refused under the prod/production/staging profiles");
				}
				yield new CapturingEmailSender();
			}
			default -> throw new IllegalStateException("Unknown qal.notifications.email.provider; use 'none', 'log' or "
					+ "'capture' (no real vendor adapter exists yet, ADR 0033)");
		};
	}

	/** Anonymous access to {@code /dev/email/**} only; evaluated before the API chain. */
	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE + 1)
	@ConditionalOnProperty(name = ENDPOINT_PROPERTY, havingValue = "true")
	SecurityFilterChain devEmailCaptureSecurity(HttpSecurity http) throws Exception {
		return http.securityMatcher("/dev/email/**").csrf(csrf -> csrf.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
	}
}
