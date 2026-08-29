package com.referralhub.common.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link OutboxDrainer} on a timer and keeps the outbox from growing forever.
 *
 * <p>Every replica runs this. Concurrency safety comes from {@code SKIP LOCKED} in the claim
 * query, not from leader election, so scaling the deployment scales the relay too.
 */
@Component
@ConditionalOnProperty(prefix = "referralhub.outbox", name = "relay-enabled",
        havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxDrainer drainer;
    private final OutboxStore store;
    private final OutboxProperties properties;
    private final Counter publishedCounter;

    public OutboxRelay(OutboxDrainer drainer,
                       OutboxStore store,
                       OutboxProperties properties,
                       MeterRegistry meters) {
        this.drainer = drainer;
        this.store = store;
        this.properties = properties;
        this.publishedCounter = Counter.builder("referralhub.outbox.published")
                .description("Outbox rows successfully published to Kafka")
                .register(meters);

        meters.gauge("referralhub.outbox.pending", this, r -> r.store.countPending());
        meters.gauge("referralhub.outbox.poisoned", this,
                r -> r.store.countPoisoned(r.properties.getMaxAttempts()));
    }

    @Scheduled(fixedDelayString = "${referralhub.outbox.poll-interval-millis:500}")
    public void relay() {
        try {
            int published = drainer.drainOnce();
            if (published > 0) {
                publishedCounter.increment(published);
            }
        } catch (Exception e) {
            // Never let a relay failure kill the scheduler thread.
            log.error("Outbox relay pass failed", e);
        }
    }

    /** Keeps the table small; published rows are only kept for post-incident forensics. */
    @Scheduled(cron = "${referralhub.outbox.reap-cron:0 */15 * * * *}")
    public void reap() {
        try {
            int deleted = store.deletePublishedBefore(Instant.now().minus(properties.getRetention()));
            if (deleted > 0) {
                log.info("Reaped {} published outbox rows", deleted);
            }
        } catch (Exception e) {
            log.error("Outbox reaper pass failed", e);
        }
    }
}
