package com.referralhub.dedup.canonical;

import java.time.Instant;
import java.util.UUID;

/** One real job, however many boards advertise it. */
public record CanonicalJob(
        UUID id,
        UUID companyId,
        String title,
        String canonicalRole,
        String canonicalLevel,
        String specialization,
        String descriptionHtml,
        String location,
        boolean remote,
        int[] signature,
        int sourceCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant closedAt) {
}
