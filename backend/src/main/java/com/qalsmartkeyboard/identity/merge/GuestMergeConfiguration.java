package com.qalsmartkeyboard.identity.merge;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.identity.GuestMergeParticipant;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.session.SessionService;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
class GuestMergeConfiguration {

	@Bean
	GuestMergeService guestMergeService(UserAccountStore accounts, SessionService sessions,
			ObjectProvider<GuestMergeParticipant> participants, UserDataEncryption encryption, OutboxPublisher outbox,
			JdbcClient jdbc, ObjectProvider<MeterRegistry> meters) {
		return new GuestMergeService(accounts, sessions, participants, encryption, outbox, jdbc,
				meters.getIfAvailable());
	}
}
