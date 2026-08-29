package com.referralhub.dedup.consume;

import com.referralhub.common.consume.IdempotentConsumer;
import com.referralhub.common.events.JobIngested;
import com.referralhub.common.events.Topics;
import com.referralhub.common.json.Json;
import com.referralhub.dedup.DedupService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Canonicalizes each ingested posting.
 *
 * <p>The idempotency key is {@code rawPostingId:contentHash}, not the posting id alone. That
 * distinction matters: a redelivery of the same event must be skipped, but a genuine second
 * ingestion of the same posting after its description changed must be processed, and keying on
 * the posting id alone would silently discard the update.
 */
@Component
@ConditionalOnProperty(prefix = "referralhub.dedup", name = "consumer-enabled",
        havingValue = "true", matchIfMissing = true)
public class JobIngestedListener {

    private static final Logger log = LoggerFactory.getLogger(JobIngestedListener.class);
    private static final String GROUP = "dedup";

    private final IdempotentConsumer consumer;
    private final DedupService dedupService;

    public JobIngestedListener(IdempotentConsumer consumer, DedupService dedupService) {
        this.consumer = consumer;
        this.dedupService = dedupService;
    }

    @KafkaListener(topics = Topics.JOBS_INGESTED, groupId = GROUP)
    public void onJobIngested(ConsumerRecord<String, String> record) {
        JobIngested event = Json.read(record.value(), JobIngested.class);
        String key = event.rawPostingId() + ":" + event.contentHash();

        boolean handled = consumer.handle(GROUP, key, event,
                e -> dedupService.canonicalize(e.rawPostingId()));

        if (!handled) {
            log.debug("Skipped already-canonicalized posting {}", event.rawPostingId());
        }
    }
}
