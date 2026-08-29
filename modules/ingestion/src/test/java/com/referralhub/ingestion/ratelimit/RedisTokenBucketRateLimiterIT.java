package com.referralhub.ingestion.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.common.testing.PlatformContainers;
import com.referralhub.common.testing.RequiresDocker;
import com.referralhub.ingestion.config.IngestionProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The claim this test defends: twenty workers cannot collectively exceed one host's budget.
 *
 * <p>This is the failure a naive limiter has, and it only appears under real concurrency against
 * real Redis. A client-side implementation passes every single-threaded test ever written and
 * then lets twenty pods issue twenty requests against a bucket holding nine tokens, because they
 * all read the same value before any of them wrote.
 */
@Tag("integration")
@RequiresDocker
class RedisTokenBucketRateLimiterIT {

    private static final int THREADS = 20;
    private static final double PERMITS_PER_SECOND = 5.0;
    private static final double CAPACITY = 10.0;

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisTokenBucketRateLimiter limiter;

    @BeforeAll
    static void startRedis() {
        var container = PlatformContainers.redis();
        var config = new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();

        IngestionProperties properties = new IngestionProperties();
        properties.getDefaultRateLimit().setPermitsPerSecond(PERMITS_PER_SECOND);
        properties.getDefaultRateLimit().setCapacity(CAPACITY);
        limiter = new RedisTokenBucketRateLimiter(redis, properties);
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void clearBuckets() {
        var keys = redis.keys("ratelimit:host:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("20 threads hammering one host never collectively exceed capacity + refill")
    void concurrentWorkersRespectOneHostBudget() throws Exception {
        String host = "boards-api.greenhouse.io";
        int attemptsPerThread = 25;

        AtomicInteger granted = new AtomicInteger();
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        long startedAt;
        long finishedAt;
        try {
            List<Callable<Void>> workers = IntStream.range(0, THREADS)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        startLine.await();
                        for (int attempt = 0; attempt < attemptsPerThread; attempt++) {
                            if (limiter.tryAcquire(host).allowed()) {
                                granted.incrementAndGet();
                            }
                        }
                        return null;
                    })
                    .toList();

            List<Future<Void>> futures = workers.stream().map(pool::submit).toList();
            startedAt = System.nanoTime();
            startLine.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            finishedAt = System.nanoTime();
        } finally {
            pool.shutdownNow();
        }

        double elapsedSeconds = (finishedAt - startedAt) / 1_000_000_000.0;
        // The bucket starts full and refills while the threads run. Anything above that ceiling
        // means two workers spent the same token.
        int ceiling = (int) Math.ceil(CAPACITY + PERMITS_PER_SECOND * elapsedSeconds) + 1;

        assertThat(granted.get())
                .as("500 attempts across 20 threads in %.3fs may yield at most %d permits",
                        elapsedSeconds, ceiling)
                .isLessThanOrEqualTo(ceiling);

        // And it must not be trivially correct by refusing everything.
        assertThat(granted.get()).isGreaterThanOrEqualTo((int) CAPACITY);
    }

    @Test
    @DisplayName("a single caller drains exactly the burst capacity and is then refused")
    void burstIsBoundedByCapacity() {
        String host = "api.lever.co";

        int granted = 0;
        for (int i = 0; i < 50; i++) {
            if (limiter.tryAcquire(host).allowed()) {
                granted++;
            }
        }

        assertThat(granted).isEqualTo((int) CAPACITY);
        assertThat(limiter.tryAcquire(host).allowed()).isFalse();
    }

    @Test
    @DisplayName("hosts have independent buckets")
    void bucketsAreScopedPerHost() {
        String drained = "api.ashbyhq.com";
        for (int i = 0; i < (int) CAPACITY; i++) {
            limiter.tryAcquire(drained);
        }

        assertThat(limiter.tryAcquire(drained).allowed()).isFalse();
        assertThat(limiter.tryAcquire("boards-api.greenhouse.io").allowed()).isTrue();
    }

    @Test
    @DisplayName("a refused caller is told how long to wait, and the wait is enough")
    void retryAfterIsUsable() throws Exception {
        String host = "wait.example.com";
        for (int i = 0; i < (int) CAPACITY; i++) {
            limiter.tryAcquire(host);
        }

        RateLimitDecision denied = limiter.tryAcquire(host);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isPositive();

        assertThat(limiter.acquire(host, denied.retryAfter().plus(Duration.ofSeconds(2)))).isTrue();
    }

    @Test
    @DisplayName("tokens come back over time rather than being gone for good")
    void bucketRefills() throws Exception {
        String host = "refill.example.com";
        for (int i = 0; i < (int) CAPACITY; i++) {
            limiter.tryAcquire(host);
        }
        assertThat(limiter.tryAcquire(host).allowed()).isFalse();

        // At 5 permits/second, roughly 3 tokens are back after 600ms.
        Thread.sleep(700);

        int granted = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire(host).allowed()) {
                granted++;
            }
        }
        assertThat(granted).isBetween(2, 6);
    }
}
