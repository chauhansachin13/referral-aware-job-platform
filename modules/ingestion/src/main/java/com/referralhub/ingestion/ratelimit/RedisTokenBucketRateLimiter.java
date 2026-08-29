package com.referralhub.ingestion.ratelimit;

import com.referralhub.ingestion.config.IngestionProperties;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Token bucket whose state lives in Redis and whose arithmetic runs inside Redis.
 *
 * <p>The refill-and-consume step is a Lua script so it is atomic. That is the difference
 * between a rate limiter and a rate suggestion: with a client-side read-modify-write, twenty
 * concurrent workers all read "9 tokens left", all decide they may proceed, and the host sees
 * twenty requests against a bucket that held nine.
 */
@Component
public class RedisTokenBucketRateLimiter implements DistributedRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);
    private static final String KEY_PREFIX = "ratelimit:host:";

    private final StringRedisTemplate redis;
    private final IngestionProperties properties;
    private final RedisScript<List> script;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis, IngestionProperties properties) {
        this.redis = redis;
        this.properties = properties;
        DefaultRedisScript<List> tokenBucket = new DefaultRedisScript<>();
        tokenBucket.setLocation(new ClassPathResource("redis/token_bucket.lua"));
        tokenBucket.setResultType(List.class);
        this.script = tokenBucket;
    }

    @Override
    public RateLimitDecision tryAcquire(String host) {
        IngestionProperties.RateLimit config = properties.rateLimitFor(host);
        long ttlMillis = Math.max(60_000L, (long) (config.getCapacity() / config.getPermitsPerSecond() * 4_000));

        @SuppressWarnings("unchecked")
        List<Long> result = (List<Long>) redis.execute(
                script,
                List.of(KEY_PREFIX + host),
                String.valueOf(config.getPermitsPerSecond()),
                String.valueOf(config.getCapacity()),
                String.valueOf(System.currentTimeMillis()),
                "1",
                String.valueOf(ttlMillis));

        if (result == null || result.size() < 3) {
            // A Redis outage must not become an accidental licence to hammer an ATS.
            log.warn("Rate limiter script returned no decision for host {}; denying", host);
            return RateLimitDecision.denied(Duration.ofSeconds(1));
        }
        boolean allowed = result.get(0) == 1L;
        return allowed
                ? RateLimitDecision.allowed(result.get(2))
                : RateLimitDecision.denied(Duration.ofMillis(result.get(1)));
    }

    @Override
    public boolean acquire(String host, Duration maxWait) throws InterruptedException {
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (true) {
            RateLimitDecision decision = tryAcquire(host);
            if (decision.allowed()) {
                return true;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            long sleepMillis = Math.min(
                    Math.max(decision.retryAfter().toMillis(), 10L),
                    Duration.ofNanos(remainingNanos).toMillis());
            Thread.sleep(sleepMillis);
        }
    }
}
