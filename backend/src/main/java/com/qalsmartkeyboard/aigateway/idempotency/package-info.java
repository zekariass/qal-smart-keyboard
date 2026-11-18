/**
 * AI idempotency (plan step B5.4, rule N5): request fingerprints and the encrypted, at most 10-minute replay cache
 * behind {@link com.qalsmartkeyboard.aigateway.AiIdempotency}. Internal to the AI gateway module; see ADR 0017
 * "Replay cache (B5.4)".
 */
package com.qalsmartkeyboard.aigateway.idempotency;
