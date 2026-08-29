package com.referralhub.common.events;

import java.time.Instant;
import java.util.UUID;

/** Emitted when a raw posting has been attached to a canonical job (new or existing). */
public record JobCanonicalized(
        UUID canonicalJobId,
        UUID rawPostingId,
        boolean createdNewCanonical,
        double matchScore,
        Instant decidedAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "job.canonicalized";
    }

    @Override
    public String aggregateType() {
        return "canonical_job";
    }

    @Override
    public UUID aggregateId() {
        return canonicalJobId;
    }

    @Override
    public String topic() {
        return Topics.JOBS_CANONICALIZED;
    }
}
