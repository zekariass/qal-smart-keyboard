package com.qalsmartkeyboard.identity.token;

import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Access-token keys, issuer and the {@link JwtDecoder} used by the API security chain. Public so slice tests can import it. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TokenProperties.class)
public class TokenConfiguration {

	@Bean
	AccessTokenService accessTokenService(TokenProperties properties, Environment environment,
			ObjectProvider<Clock> clock) {
		AccessTokenKeys keys = AccessTokenKeys.load(properties, environment, new SecureRandom());
		return new AccessTokenService(keys, properties, clock.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	JwtDecoder accessTokenDecoder(AccessTokenService tokens) {
		return tokens.decoder();
	}
}
