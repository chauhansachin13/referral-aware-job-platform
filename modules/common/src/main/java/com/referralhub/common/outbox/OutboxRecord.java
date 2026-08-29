package com.referralhub.common.outbox;

import java.time.Instant;
import java.util.UUID;

/** One pending row of the outbox, as claimed by the relay. */
public record OutboxRecord(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String topic,
        String partitionKey,
        String payload,
        Instant occurredAt,
        int attempts) {
}
