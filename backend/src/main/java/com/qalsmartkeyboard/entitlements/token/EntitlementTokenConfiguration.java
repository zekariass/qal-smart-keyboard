package com.qalsmartkeyboard.entitlements.token;

import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.qalsmartkeyboard.entitlements.EntitlementResolver;

/** Entitlement-token keys and issuer. Public so slice tests can import it. Startup fails without a signing key. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EntitlementTokenProperties.class)
public class EntitlementTokenConfiguration {

	@Bean
	EntitlementTokenService entitlementTokenService(EntitlementResolver resolver,
			EntitlementTokenProperties properties, Environment environment, ObjectProvider<Clock> clock) {
		EntitlementTokenKeys keys = EntitlementTokenKeys.load(properties, environment, new SecureRandom());
		return new EntitlementTokenService(resolver, keys, properties, clock.getIfAvailable(Clock::systemUTC));
	}
}
