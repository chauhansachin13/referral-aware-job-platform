package com.referralhub.app.dev;

import com.referralhub.search.SearchService;
import com.referralhub.search.query.SearchRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Drives concurrent searches against the live stack and reports the latency distribution.
 *
 * <p>Complements the JMH suite rather than duplicating it. JMH measures the application's own
 * arithmetic with the OpenSearch round trip excluded; this measures what a caller actually
 * experiences, including the network, the cluster, connection pooling and whatever contention
 * arises from running many queries at once. Those are different numbers and both are worth
 * knowing.
 *
 * <p>Ordered after {@link CorpusSeeder} so a single run can seed and then measure.
 */
@Component
@Order(100)
@ConditionalOnProperty(prefix = "referralhub.loadgen", name = "drive-searches", havingValue = "true")
public class SearchLoadDriver implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SearchLoadDriver.class);

    /** A mix of exact, paraphrased and filtered queries, as real traffic is. */
    private static final String[] QUERIES = {
            "senior software engineer payments", "k8s platform", "container orchestration",
            "machine learning ranking", "data pipeline spark", "site reliability",
            "backend engineer kafka", "frontend react accessibility", "staff engineer search",
            "infrastructure as code terraform", "sde-2", "member of technical staff"};

    private final SearchService search;
    private final LoadGeneratorProperties properties;

    public SearchLoadDriver(SearchService search, LoadGeneratorProperties properties) {
        this.search = search;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int threads = properties.getSearchThreads();
        int perThread = properties.getSearchesPerThread();
        int total = threads * perThread;

        log.info("Driving {} searches across {} threads", total, threads);

        long[] latenciesNanos = new long[total];
        AtomicInteger cursor = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch startLine = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long wallStart;
        long wallEnd;
        try {
            List<Callable<Void>> workers = new ArrayList<>(threads);
            for (int t = 0; t < threads; t++) {
                final int threadIndex = t;
                workers.add(() -> {
                    Random random = new Random(properties.getSeed() + threadIndex);
                    startLine.await();
                    for (int i = 0; i < perThread; i++) {
                        String query = QUERIES[random.nextInt(QUERIES.length)];
                        long began = System.nanoTime();
                        try {
                            search.search(new SearchRequest(query, null, null,
                                    random.nextBoolean() ? Boolean.TRUE : null, null, 20, null));
                        } catch (RuntimeException e) {
                            failures.incrementAndGet();
                        }
                        latenciesNanos[cursor.getAndIncrement()] = System.nanoTime() - began;
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = workers.stream().map(pool::submit).toList();
            wallStart = System.nanoTime();
            startLine.countDown();
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.MINUTES);
            }
            wallEnd = System.nanoTime();
        } finally {
            pool.shutdownNow();
        }

        long[] sorted = java.util.Arrays.copyOf(latenciesNanos, cursor.get());
        java.util.Arrays.sort(sorted);
        double wallSeconds = (wallEnd - wallStart) / 1_000_000_000.0;

        log.info("""
                Search load results
                  queries      : {} ({} failed)
                  threads      : {}
                  wall time    : {}s
                  throughput   : {} queries/second
                  p50          : {} ms
                  p95          : {} ms
                  p99          : {} ms
                  p999         : {} ms
                  max          : {} ms""",
                sorted.length, failures.get(), threads,
                String.format("%.2f", wallSeconds),
                String.format("%.0f", sorted.length / Math.max(wallSeconds, 1e-9)),
                millis(percentile(sorted, 0.50)), millis(percentile(sorted, 0.95)),
                millis(percentile(sorted, 0.99)), millis(percentile(sorted, 0.999)),
                millis(sorted.length == 0 ? 0 : sorted[sorted.length - 1]));
    }

    private static long percentile(long[] sorted, double quantile) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        return sorted[Math.min(Math.max(index, 0), sorted.length - 1)];
    }

    private static String millis(long nanos) {
        return String.format("%.2f", nanos / 1_000_000.0);
    }
}
