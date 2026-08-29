package com.referralhub.ingestion.board;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One company's job board on one ATS, plus everything the crawler remembers about it.
 *
 * <p>The conditional-fetch validators and the adaptive interval live on this row rather than in
 * Redis because losing them is not free: a flushed cache would turn every board's next crawl
 * into a full download, which is exactly the stampede the ETag handling exists to prevent.
 * Redis holds the schedule (cheap to rebuild), Postgres holds the state (expensive to lose).
 */
public record CompanyBoard(
        UUID id,
        UUID companyId,
        String companyName,
        String source,
        String boardToken,
        boolean enabled,
        String etag,
        Instant lastModified,
        String lastContentHash,
        Duration crawlInterval,
        Instant lastCrawledAt,
        Instant lastChangedAt,
        int consecutiveUnchanged,
        double observedPostingsPerDay) {

    public CompanyBoard withValidators(String newEtag, Instant newLastModified, String contentHash) {
        return new CompanyBoard(id, companyId, companyName, source, boardToken, enabled,
                newEtag, newLastModified, contentHash, crawlInterval, lastCrawledAt,
                lastChangedAt, consecutiveUnchanged, observedPostingsPerDay);
    }
}
