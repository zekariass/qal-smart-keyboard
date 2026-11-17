package com.qalsmartkeyboard.platform.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param dispatcherEnabled start the db-scheduler poller (disable in tests that drive the dispatcher by hand)
 * @param pollInterval      delay between dispatcher runs
 * @param batchSize         events claimed per run
 * @param lease             how long a claim is held; an expired lease is reclaimed (crash recovery)
 * @param baseBackoff       delay after the first failed attempt; doubles each attempt
 * @param maxBackoff        upper bound for the retry delay
 */
@ConfigurationProperties("qal.outbox")
record OutboxProperties(
        @DefaultValue("true") boolean dispatcherEnabled,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("5s") Duration baseBackoff,
        @DefaultValue("1h") Duration maxBackoff) {
}
