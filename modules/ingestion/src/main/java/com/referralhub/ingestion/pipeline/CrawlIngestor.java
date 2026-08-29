package com.referralhub.ingestion.pipeline;

import com.referralhub.common.events.JobIngested;
import com.referralhub.common.outbox.TransactionalEventPublisher;
import com.referralhub.ingestion.adapter.AdapterRegistry;
import com.referralhub.ingestion.adapter.ParsedPosting;
import com.referralhub.ingestion.board.BoardStore;
import com.referralhub.ingestion.board.CompanyBoard;
import com.referralhub.ingestion.config.IngestionProperties;
import com.referralhub.ingestion.fetch.ContentHasher;
import com.referralhub.ingestion.raw.PostingUpsert;
import com.referralhub.ingestion.raw.RawPostingStore;
import com.referralhub.ingestion.schedule.AdaptiveInterval;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of a crawl: everything from "we have bytes" to "the events are durable".
 *
 * <p>Separated from {@link CrawlPipeline} so that the HTTP call is provably outside the
 * transaction. Holding a database transaction open across a network fetch to a third party is
 * how a slow ATS turns into connection-pool exhaustion.
 */
@Service
public class CrawlIngestor {

    private static final Logger log = LoggerFactory.getLogger(CrawlIngestor.class);

    private final AdapterRegistry adapters;
    private final BoardStore boards;
    private final RawPostingStore postings;
    private final TransactionalEventPublisher events;
    private final IngestionProperties properties;

    public CrawlIngestor(AdapterRegistry adapters,
                         BoardStore boards,
                         RawPostingStore postings,
                         TransactionalEventPublisher events,
                         IngestionProperties properties) {
        this.adapters = adapters;
        this.boards = boards;
        this.postings = postings;
        this.events = events;
        this.properties = properties;
    }

    @Transactional
    public CrawlOutcome ingest(CompanyBoard board, String body, String etag, Instant lastModified,
                               Duration elapsed) {
        String rawHash = ContentHasher.raw(body);

        // Cheapest path after a 304: the board sends no validators, but the bytes are identical
        // to the last ones we stored. Compared against the latest payload only — see
        // RawPostingStore.lastPayloadHasHash for why history is the wrong thing to match.
        if (postings.lastPayloadHasHash(board.id(), rawHash)) {
            Duration next = nextInterval(board, false);
            boards.recordNotModified(board.id(), next);
            postings.logCrawl(board.id(), "UNCHANGED_RAW", 200, elapsed.toMillis(), 0, 0, null);
            return new CrawlOutcome(board.id(), CrawlOutcome.Status.UNCHANGED, 0, 0, elapsed, null);
        }

        // Durable before parseable: a parser bug is now replayable rather than fatal.
        postings.savePayload(board.id(), 200, rawHash, body);

        List<ParsedPosting> parsed = adapters.require(board.source()).parse(body, board);
        String semanticHash = ContentHasher.semantic(parsed);
        boolean semanticallyChanged = !semanticHash.equals(board.lastContentHash());

        if (!semanticallyChanged) {
            Duration next = nextInterval(board, false);
            boards.recordFetched(board.id(), etag, lastModified, semanticHash, next, false,
                    updatedRate(board, 0));
            postings.logCrawl(board.id(), "UNCHANGED_SEMANTIC", 200, elapsed.toMillis(),
                    parsed.size(), 0, null);
            return new CrawlOutcome(board.id(), CrawlOutcome.Status.UNCHANGED,
                    parsed.size(), 0, elapsed, null);
        }

        List<PostingUpsert> upserts = postings.upsertAll(board, parsed);
        int changedCount = 0;
        Instant fetchedAt = Instant.now();

        for (PostingUpsert upsert : upserts) {
            if (!upsert.changed()) {
                continue;
            }
            changedCount++;
            events.publish(new JobIngested(
                    upsert.id(),
                    board.companyId(),
                    board.source(),
                    upsert.externalId(),
                    upsert.title(),
                    upsert.contentHash(),
                    fetchedAt));
        }

        // Only ever run when the parse produced something; an empty board is far more likely to
        // be an upstream outage than a company closing every role at once.
        if (!parsed.isEmpty()) {
            postings.closeMissing(board.id(), parsed.stream().map(ParsedPosting::externalId).toList());
        }

        Duration next = nextInterval(board, true);
        boards.recordFetched(board.id(), etag, lastModified, semanticHash, next, true,
                updatedRate(board, changedCount));
        postings.logCrawl(board.id(), "CHANGED", 200, elapsed.toMillis(),
                parsed.size(), changedCount, null);

        log.debug("Board {} ({}): {} postings, {} changed", board.boardToken(), board.source(),
                parsed.size(), changedCount);

        return new CrawlOutcome(board.id(), CrawlOutcome.Status.CHANGED,
                parsed.size(), changedCount, elapsed, null);
    }

    /** Applies the 304 bookkeeping: no body arrived, so only the cadence moves. */
    @Transactional
    public CrawlOutcome recordNotModified(CompanyBoard board, Duration elapsed) {
        Duration next = nextInterval(board, false);
        boards.recordNotModified(board.id(), next);
        postings.logCrawl(board.id(), "NOT_MODIFIED", 304, elapsed.toMillis(), 0, 0, null);
        return CrawlOutcome.notModified(board.id(), elapsed);
    }

    @Transactional
    public CrawlOutcome recordFailure(CompanyBoard board, Duration elapsed, int status, String error) {
        // Failures back off on the same curve as "nothing changed": a board that is down should
        // not be retried at the cadence of a board that posts hourly.
        Duration next = nextInterval(board, false);
        boards.recordFailure(board.id(), next);
        postings.logCrawl(board.id(), "FAILED", status == 0 ? null : status,
                elapsed.toMillis(), 0, 0, error);
        return CrawlOutcome.failed(board.id(), elapsed, error);
    }

    Duration nextInterval(CompanyBoard board, boolean changed) {
        int unchanged = changed ? 0 : board.consecutiveUnchanged() + 1;
        return AdaptiveInterval.next(board.observedPostingsPerDay(), unchanged, properties.getCrawl());
    }

    private double updatedRate(CompanyBoard board, int changedPostings) {
        Instant since = board.lastCrawledAt() != null
                ? board.lastCrawledAt()
                : Instant.now().minus(board.crawlInterval());
        return AdaptiveInterval.updateRate(
                board.observedPostingsPerDay(),
                changedPostings,
                Duration.between(since, Instant.now()),
                properties.getCrawl().getRateSmoothing());
    }
}
