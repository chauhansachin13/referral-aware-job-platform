package com.referralhub.search.consume;

import com.referralhub.common.consume.IdempotentConsumer;
import com.referralhub.common.events.JobCanonicalized;
import com.referralhub.common.events.Topics;
import com.referralhub.common.json.Json;
import com.referralhub.search.index.JobIndexer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the index in step with the canonical job table.
 *
 * <p>Its own consumer group, so indexing progress is independent of dedup's: replaying the index
 * after a mapping change means resetting one group's offsets, not re-running deduplication.
 */
@Component
@ConditionalOnProperty(prefix = "referralhub.search", name = "indexer-enabled",
        havingValue = "true", matchIfMissing = true)
public class JobCanonicalizedListener {

    private static final Logger log = LoggerFactory.getLogger(JobCanonicalizedListener.class);
    private static final String GROUP = "search-indexer";

    private final IdempotentConsumer consumer;
    private final JobIndexer indexer;

    public JobCanonicalizedListener(IdempotentConsumer consumer, JobIndexer indexer) {
        this.consumer = consumer;
        this.indexer = indexer;
    }

    @KafkaListener(topics = Topics.JOBS_CANONICALIZED, groupId = GROUP)
    public void onJobCanonicalized(ConsumerRecord<String, String> record) {
        JobCanonicalized event = Json.read(record.value(), JobCanonicalized.class);
        // Keyed by the posting that triggered it: two different postings merging into the same
        // canonical job are two distinct reasons to refresh the document.
        String key = event.canonicalJobId() + ":" + event.rawPostingId();

        boolean handled = consumer.handle(GROUP, key, event,
                e -> indexer.index(e.canonicalJobId()));

        if (!handled) {
            log.debug("Skipped duplicate index request for {}", event.canonicalJobId());
        }
    }
}
