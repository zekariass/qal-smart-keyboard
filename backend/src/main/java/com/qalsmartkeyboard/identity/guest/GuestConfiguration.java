package com.qalsmartkeyboard.identity.guest;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.AttestationChecker;
import com.qalsmartkeyboard.identity.DeviceRegistrar;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.attestation.AttestationProperties;
import com.qalsmartkeyboard.identity.session.SessionIssuer;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

import io.micrometer.core.instrument.MeterRegistry;

/** Wires guest creation. Its limits are {@code qal.identity.attestation.limits.guest-per-ip|guest-per-installation}. */
@Configuration(proxyBeanMethods = false)
class GuestConfiguration {

	@Bean
	GuestService guestService(AttestationChecker attestation, ObjectProvider<DeviceRegistrar> devices,
			UserAccountStore accounts, SessionIssuer sessions, RateLimiter limiter, AttestationProperties properties,
			PlatformTransactionManager transactionManager, ObjectProvider<Clock> clock,
			ObjectProvider<MeterRegistry> meters) {
		RateLimitPolicy perIp = properties.limits().guestPerIp().policy("guest.ip");
		RateLimitPolicy perInstallation = properties.limits().guestPerInstallation().policy("guest.installation");
		return new GuestService(attestation, devices, accounts, sessions, limiter, perIp, perInstallation,
				new TransactionTemplate(transactionManager), clock.getIfAvailable(Clock::systemUTC),
				meters.getIfAvailable());
	}
}
