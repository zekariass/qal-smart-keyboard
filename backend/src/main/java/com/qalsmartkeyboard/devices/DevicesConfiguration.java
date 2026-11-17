package com.qalsmartkeyboard.devices;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.DeviceSessions;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
class DevicesConfiguration {

	@Bean
	DeviceService deviceService(JdbcClient jdbc, DeviceSessions sessions, OutboxPublisher outbox,
			PlatformTransactionManager transactionManager, ObjectProvider<Clock> clock,
			ObjectProvider<MeterRegistry> meters) {
		return new DeviceService(new DeviceStore(jdbc), sessions, outbox, new TransactionTemplate(transactionManager),
				clock.getIfAvailable(Clock::systemUTC), meters.getIfAvailable());
	}
}
