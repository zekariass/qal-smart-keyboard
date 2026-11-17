package com.qalsmartkeyboard.identity.attestation;

import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
@Import(AttestationVerifierConfiguration.class)
class AttestationConfiguration {

	@Bean
	AttestationService attestationService(JdbcClient jdbc, AttestationVerifier verifier, RateLimiter limiter,
			AttestationProperties properties, PlatformTransactionManager transactionManager,
			ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters) {
		return new AttestationService(new NonceStore(jdbc), verifier, limiter, properties,
				new TransactionTemplate(transactionManager), clock.getIfAvailable(Clock::systemUTC), new SecureRandom(),
				meters.getIfAvailable());
	}
}
