package com.referralhub.common.ids;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Identifier factory.
 *
 * <p>Rows in this system are written far more often than they are read by primary key, and
 * several tables (raw payloads, outbox, audit log) are append-only and time-ordered. Random
 * UUIDv4 primary keys scatter B-tree inserts across the whole index; a time-ordered identifier
 * keeps inserts clustered at the right edge of the index. We therefore mint UUIDv7-shaped
 * values: 48 bits of millisecond timestamp, then random bits, with the version/variant nibbles
 * set so the value is still a perfectly valid {@link UUID} for the {@code uuid} column type.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /** A fresh time-ordered (UUIDv7) identifier. */
    public static UUID next() {
        return at(Instant.now());
    }

    /** A time-ordered identifier stamped with a caller-supplied instant; used by tests. */
    public static UUID at(Instant instant) {
        long millis = instant.toEpochMilli();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= (long) (random[0] & 0x0F) << 8;   // 4 bits of entropy, version nibble cleared
        msb |= (random[1] & 0xFFL);
        msb |= 0x7000L;                          // version 7

        long lsb = 0;
        for (int i = 2; i < 10; i++) {
            lsb = (lsb << 8) | (random[i] & 0xFFL);
        }
        lsb = (lsb & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // RFC 4122 variant

        return new UUID(msb, lsb);
    }

    /** The millisecond timestamp embedded in a UUIDv7 produced by this class. */
    public static long timestampOf(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }
}
