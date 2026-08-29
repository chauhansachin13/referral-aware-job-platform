package com.referralhub.ingestion.raw;

import java.time.Instant;
import java.util.UUID;

/** A stored posting, as any downstream module reads it. */
public record RawPostingRecord(
        UUID id,
        UUID boardId,
        UUID companyId,
        String source,
        String externalId,
        String title,
        String descriptionHtml,
        String location,
        boolean remote,
        String department,
        String applyUrl,
        Instant postedAt,
        String contentHash,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant closedAt) {
}
