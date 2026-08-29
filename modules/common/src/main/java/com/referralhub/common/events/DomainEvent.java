package com.referralhub.common.events;

import java.util.UUID;

/**
 * A fact that already happened. Implementations are records and are never mutated.
 *
 * <p>Every event carries the aggregate it belongs to, because the outbox relay uses the
 * aggregate id as the Kafka partition key: all events for one job (or one referral request)
 * land on the same partition and are therefore consumed in the order they were produced.
 */
public interface DomainEvent {

    String eventType();

    String aggregateType();

    UUID aggregateId();

    /** Kafka topic this event is published to. */
    String topic();
}
