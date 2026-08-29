package com.referralhub.common.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes one batch of outbox rows to Kafka.
 *
 * <p>Kept as its own bean so the transaction boundary is a real proxy call from the scheduler
 * rather than a self-invocation. The claim, the sends and the "mark published" update all run
 * inside a single transaction: if the process dies mid-batch the row locks are released, the
 * rows stay unpublished, and the next pass re-sends them. That is at-least-once delivery, which
 * is why every consumer in this system is idempotent.
 */
@Component
public class OutboxDrainer {

    private static final Logger log = LoggerFactory.getLogger(OutboxDrainer.class);

    private final OutboxStore store;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;

    public OutboxDrainer(OutboxStore store,
                         KafkaTemplate<String, String> kafka,
                         OutboxProperties properties) {
        this.store = store;
        this.kafka = kafka;
        this.properties = properties;
    }

    /** @return how many rows were successfully published. */
    @Transactional
    public int drainOnce() {
        List<OutboxRecord> batch = store.claimBatch(properties.getBatchSize(), properties.getMaxAttempts());
        if (batch.isEmpty()) {
            return 0;
        }

        List<UUID> published = new ArrayList<>(batch.size());
        List<CompletableFuture<?>> inFlight = new ArrayList<>(batch.size());
        List<OutboxRecord> ordered = new ArrayList<>(batch.size());

        for (OutboxRecord record : batch) {
            inFlight.add(kafka.send(record.topic(), record.partitionKey(), record.payload()));
            ordered.add(record);
        }

        for (int i = 0; i < inFlight.size(); i++) {
            OutboxRecord record = ordered.get(i);
            try {
                inFlight.get(i).get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                published.add(record.id());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                store.markFailed(record.id(), "interrupted while awaiting broker ack");
                break;
            } catch (Exception e) {
                log.warn("Outbox publish failed for {} (attempt {}): {}",
                        record.id(), record.attempts() + 1, e.toString());
                store.markFailed(record.id(), e.toString());
            }
        }

        store.markPublished(published);
        return published.size();
    }
}
