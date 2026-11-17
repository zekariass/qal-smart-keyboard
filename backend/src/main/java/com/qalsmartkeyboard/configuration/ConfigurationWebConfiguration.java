package com.qalsmartkeyboard.configuration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies {@link AppVersionInterceptor} to the client API.
 *
 * <p>Covered: {@code /api/v1/**}. Exempt:
 * <ul>
 * <li>{@code /api/v1/config}: an outdated client must still be able to learn that it has to update;</li>
 * <li>{@code /api/v1/billing/webhooks/**} and {@code /api/v1/billing/store-notifications/**}: server-to-server
 * callbacks from payment providers and app stores, which are not app clients and send no app headers;</li>
 * </ul>
 * Not covered at all: {@code /partner/v1/**} (API-key integrations, no app version), {@code /actuator/**} and anything
 * outside {@code /api/v1}.
 */
@Component
class ConfigurationWebConfiguration implements WebMvcConfigurer {

	static final String[] EXEMPT = { "/api/v1/config", "/api/v1/billing/webhooks/**",
			"/api/v1/billing/store-notifications/**" };

	private final ObjectProvider<ClientConfigService> service;

	/** Lazy: web-slice tests of other modules load this class without the configuration beans (the gate is then off). */
	ConfigurationWebConfiguration(ObjectProvider<ClientConfigService> service) {
		this.service = service;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new AppVersionInterceptor(service)).addPathPatterns("/api/v1/**")
				.excludePathPatterns(EXEMPT);
	}
}
