/**
 * Retention API for the modules (B6.3, ADR 0020): a module describes how long each data class it owns is kept
 * ({@link com.qalsmartkeyboard.platform.retention.RetentionRule}) and purges it in bounded batches
 * ({@link com.qalsmartkeyboard.platform.retention.RetentionParticipant}); the privacy module owns the registry, the
 * configuration and the scheduled job.
 */
@NamedInterface("retention")
package com.qalsmartkeyboard.platform.retention;

import org.springframework.modulith.NamedInterface;
