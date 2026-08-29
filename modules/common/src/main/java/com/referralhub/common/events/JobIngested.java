package com.referralhub.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted once per posting whose content hash changed (or that was seen for the first time).
 * A crawl that produced a 304, or a 200 whose body hashed to the value we already had, emits
 * nothing — that is the whole point of hashing before publishing.
 */
public record JobIngested(
        UUID rawPostingId,
        UUID companyId,
        String source,
        String externalId,
        String title,
        String contentHash,
        Instant fetchedAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "job.ingested";
    }

    @Override
    public String aggregateType() {
        return "raw_posting";
    }

    @Override
    public UUID aggregateId() {
        return rawPostingId;
    }

    @Override
    public String topic() {
        return Topics.JOBS_INGESTED;
    }
}
