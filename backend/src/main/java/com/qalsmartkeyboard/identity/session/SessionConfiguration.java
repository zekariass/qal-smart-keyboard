package com.qalsmartkeyboard.identity.session;

import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.identity.token.TokenProperties;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
class SessionConfiguration {

	@Bean
	SessionService sessionService(JdbcClient jdbc, UserAccountStore accounts, AccessTokenService accessTokens,
			OutboxPublisher outbox, PlatformTransactionManager transactionManager, TokenProperties properties,
			ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters) {
		return new SessionService(new SessionStore(jdbc), accounts, accessTokens, new RefreshTokens(new SecureRandom()),
				outbox, new TransactionTemplate(transactionManager), properties.refreshTokenTtl(),
				properties.refreshReuseGrace(),
				clock.getIfAvailable(Clock::systemUTC), meters.getIfAvailable());
	}
}
