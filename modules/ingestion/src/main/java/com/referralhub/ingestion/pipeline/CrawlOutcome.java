package com.referralhub.ingestion.pipeline;

import java.time.Duration;
import java.util.UUID;

/** What one crawl of one board did. */
public record CrawlOutcome(
        UUID boardId,
        Status status,
        int postingsSeen,
        int postingsChanged,
        Duration elapsed,
        String error) {

    public enum Status {
        /** The board answered 304: no body, no parse, no writes. */
        NOT_MODIFIED,
        /** A body arrived but nothing a job seeker would notice had changed. */
        UNCHANGED,
        /** Real changes; events were emitted. */
        CHANGED,
        /** The host's token bucket was empty and the wait budget ran out. */
        RATE_LIMITED,
        /** Transport error, non-2xx status, or a body the adapter could not parse. */
        FAILED
    }

    public static CrawlOutcome notModified(UUID boardId, Duration elapsed) {
        return new CrawlOutcome(boardId, Status.NOT_MODIFIED, 0, 0, elapsed, null);
    }

    public static CrawlOutcome rateLimited(UUID boardId) {
        return new CrawlOutcome(boardId, Status.RATE_LIMITED, 0, 0, Duration.ZERO, null);
    }

    public static CrawlOutcome failed(UUID boardId, Duration elapsed, String error) {
        return new CrawlOutcome(boardId, Status.FAILED, 0, 0, elapsed, error);
    }
}
