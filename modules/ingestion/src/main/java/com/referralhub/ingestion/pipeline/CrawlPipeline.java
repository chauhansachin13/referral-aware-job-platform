package com.referralhub.ingestion.pipeline;

import com.referralhub.common.error.NotFoundException;
import com.referralhub.ingestion.adapter.AdapterParseException;
import com.referralhub.ingestion.adapter.SourceAdapter;
import com.referralhub.ingestion.adapter.AdapterRegistry;
import com.referralhub.ingestion.board.BoardStore;
import com.referralhub.ingestion.board.CompanyBoard;
import com.referralhub.ingestion.config.IngestionProperties;
import com.referralhub.ingestion.fetch.ConditionalFetcher;
import com.referralhub.ingestion.fetch.FetchResult;
import com.referralhub.ingestion.ratelimit.DistributedRateLimiter;
import com.referralhub.ingestion.schedule.CrawlQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One crawl, end to end, with the network call outside any transaction.
 *
 * <p>Order matters here and is the whole design:
 * <ol>
 *   <li>take a token for the host, or give up — politeness is not best-effort;</li>
 *   <li>conditional GET with the validators we were handed last time;</li>
 *   <li>304 costs one small write and nothing else;</li>
 *   <li>otherwise hand the bytes to {@link CrawlIngestor}, which persists them before parsing;</li>
 *   <li>reschedule on the adaptive interval, whatever happened.</li>
 * </ol>
 */
@Service
public class CrawlPipeline {

    private static final Logger log = LoggerFactory.getLogger(CrawlPipeline.class);

    private final BoardStore boards;
    private final AdapterRegistry adapters;
    private final ConditionalFetcher fetcher;
    private final DistributedRateLimiter rateLimiter;
    private final CrawlIngestor ingestor;
    private final CrawlQueue queue;
    private final IngestionProperties properties;

    private final Timer crawlTimer;
    private final Counter notModifiedCounter;
    private final Counter changedCounter;
    private final Counter failedCounter;
    private final Counter rateLimitedCounter;

    public CrawlPipeline(BoardStore boards,
                         AdapterRegistry adapters,
                         ConditionalFetcher fetcher,
                         DistributedRateLimiter rateLimiter,
                         CrawlIngestor ingestor,
                         CrawlQueue queue,
                         IngestionProperties properties,
                         MeterRegistry meters) {
        this.boards = boards;
        this.adapters = adapters;
        this.fetcher = fetcher;
        this.rateLimiter = rateLimiter;
        this.ingestor = ingestor;
        this.queue = queue;
        this.properties = properties;

        this.crawlTimer = Timer.builder("referralhub.crawl.duration")
                .description("Wall time of one board crawl").register(meters);
        this.notModifiedCounter = meters.counter("referralhub.crawl.outcome", "status", "not_modified");
        this.changedCounter = meters.counter("referralhub.crawl.outcome", "status", "changed");
        this.failedCounter = meters.counter("referralhub.crawl.outcome", "status", "failed");
        this.rateLimitedCounter = meters.counter("referralhub.crawl.outcome", "status", "rate_limited");
    }

    public CrawlOutcome crawl(UUID boardId) {
        CompanyBoard board = boards.findById(boardId)
                .orElseThrow(() -> new NotFoundException("Board", boardId));
        return crawl(board);
    }

    public CrawlOutcome crawl(CompanyBoard board) {
        long startedAt = System.nanoTime();
        try {
            return crawlTimer.recordCallable(() -> doCrawl(board));
        } catch (Exception e) {
            log.error("Crawl of board {} blew up", board.id(), e);
            failedCounter.increment();
            reschedule(board, ingestor.nextInterval(board, false));
            return CrawlOutcome.failed(board.id(),
                    Duration.ofNanos(System.nanoTime() - startedAt), e.toString());
        }
    }

    private CrawlOutcome doCrawl(CompanyBoard board) {
        SourceAdapter adapter = adapters.require(board.source());
        String host = adapter.rateLimitHost(board);

        boolean permitted;
        try {
            permitted = rateLimiter.acquire(host, properties.getCrawl().getRateLimitWait());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CrawlOutcome.rateLimited(board.id());
        }

        if (!permitted) {
            // Put the board back at the front of the queue: it is due, we are just being polite.
            rateLimitedCounter.increment();
            queue.schedule(board.id(), Instant.now().plusSeconds(30));
            return CrawlOutcome.rateLimited(board.id());
        }

        FetchResult result = fetcher.fetch(adapter.boardUri(board), board.etag(), board.lastModified());
        CrawlOutcome outcome = switch (result) {
            case FetchResult.NotModified notModified -> {
                notModifiedCounter.increment();
                yield ingestor.recordNotModified(board, notModified.elapsed());
            }
            case FetchResult.Failed failed -> {
                failedCounter.increment();
                yield ingestor.recordFailure(board, failed.elapsed(), failed.status(), failed.message());
            }
            case FetchResult.Fetched fetched -> ingest(board, fetched);
        };

        reschedule(board, boards.findById(board.id())
                .map(CompanyBoard::crawlInterval)
                .orElse(board.crawlInterval()));
        return outcome;
    }

    private CrawlOutcome ingest(CompanyBoard board, FetchResult.Fetched fetched) {
        try {
            CrawlOutcome outcome = ingestor.ingest(board, fetched.body(), fetched.etag(),
                    fetched.lastModified(), fetched.elapsed());
            if (outcome.status() == CrawlOutcome.Status.CHANGED) {
                changedCounter.increment();
            }
            return outcome;
        } catch (AdapterParseException e) {
            // The bytes are already stored, so this is recoverable offline. Do not treat it as
            // "the board is empty" — that would close every posting the company has.
            log.warn("Adapter {} could not parse board {}: {}", board.source(), board.id(), e.getMessage());
            failedCounter.increment();
            return ingestor.recordFailure(board, fetched.elapsed(), 200, e.getMessage());
        }
    }

    private void reschedule(CompanyBoard board, Duration interval) {
        queue.schedule(board.id(), Instant.now().plus(interval));
    }
}
