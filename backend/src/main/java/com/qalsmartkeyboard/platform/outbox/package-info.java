/**
 * Transactional outbox API for other modules: {@link com.qalsmartkeyboard.platform.outbox.OutboxPublisher}
 * to write an event in the caller's transaction and {@link com.qalsmartkeyboard.platform.outbox.OutboxEventHandler}
 * to consume it. Dispatcher, scheduler wiring and persistence are package-private.
 */
@NamedInterface("outbox")
package com.qalsmartkeyboard.platform.outbox;

import org.springframework.modulith.NamedInterface;
