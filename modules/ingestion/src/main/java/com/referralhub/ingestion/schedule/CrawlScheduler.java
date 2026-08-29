package com.referralhub.ingestion.schedule;

import com.referralhub.ingestion.board.BoardStore;
import com.referralhub.ingestion.config.IngestionProperties;
import com.referralhub.ingestion.pipeline.CrawlPipeline;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pulls due boards off the Redis queue and runs them on a bounded pool.
 *
 * <p>Every replica does this. Because {@link CrawlQueue#claimDue} removes the ids atomically,
 * two replicas never crawl the same board at the same moment, and adding replicas adds crawl
 * throughput without any coordination beyond Redis.
 */
@Component
@ConditionalOnProperty(prefix = "referralhub.ingestion", name = "crawl-enabled",
        havingValue = "true", matchIfMissing = true)
public class CrawlScheduler {

    private static final Logger log = LoggerFactory.getLogger(CrawlScheduler.class);

    private final CrawlQueue queue;
    private final CrawlPipeline pipeline;
    private final BoardStore boards;
    private final IngestionProperties properties;
    private final ExecutorService workers;

    public CrawlScheduler(CrawlQueue queue,
                          CrawlPipeline pipeline,
                          BoardStore boards,
                          IngestionProperties properties) {
        this.queue = queue;
        this.pipeline = pipeline;
        this.boards = boards;
        this.properties = properties;
        this.workers = Executors.newFixedThreadPool(properties.getCrawl().getWorkers(),
                Thread.ofPlatform().name("crawler-", 0).daemon().factory());
    }

    /**
     * Seeds the queue on startup.
     *
     * <p>Redis holds the schedule, Postgres holds the truth. A flushed Redis therefore costs one
     * burst of crawls, not a permanently stalled crawler — and the rate limiter keeps that burst
     * from reaching any single host as a burst.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedQueue() {
        List<UUID> seeded = boards.findEnabled().stream()
                .map(board -> {
                    Instant due = board.lastCrawledAt() == null
                            ? Instant.now()
                            : board.lastCrawledAt().plus(board.crawlInterval());
                    queue.schedule(board.id(), due);
                    return board.id();
                })
                .toList();
        log.info("Seeded crawl queue with {} boards", seeded.size());
    }

    @Scheduled(fixedDelayString = "${referralhub.ingestion.crawl.tick-millis:1000}")
    public void tick() {
        try {
            List<UUID> due = queue.claimDue(properties.getCrawl().getBatchSize());
            for (UUID boardId : due) {
                workers.submit(() -> {
                    try {
                        pipeline.crawl(boardId);
                    } catch (Exception e) {
                        log.error("Crawl worker failed for board {}", boardId, e);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Crawl scheduler tick failed", e);
        }
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        workers.shutdown();
        if (!workers.awaitTermination(20, TimeUnit.SECONDS)) {
            workers.shutdownNow();
        }
    }
}
