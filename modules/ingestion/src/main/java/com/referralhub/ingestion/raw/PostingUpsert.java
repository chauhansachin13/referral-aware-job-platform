package com.referralhub.ingestion.raw;

import java.util.UUID;

/** What happened to one posting during a crawl. */
public record PostingUpsert(UUID id, String externalId, String title, String contentHash,
                            boolean changed, boolean firstSeen) {
}
