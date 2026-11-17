package com.qalsmartkeyboard.platform.outbox;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** RFC 9562 UUIDv7: 48-bit unix-ms timestamp, then random bits (D18: high-volume tables use UUIDv7). */
final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuid7() {
    }

    static UUID generate(Clock clock) {
        long ms = clock.millis();
        long msb = (ms << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
