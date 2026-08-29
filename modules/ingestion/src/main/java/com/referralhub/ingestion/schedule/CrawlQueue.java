package com.referralhub.ingestion.schedule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The crawl schedule: a Redis sorted set of board id scored by next-due timestamp.
 *
 * <p>A sorted set gives "what is due now" in O(log n + m) regardless of how many thousands of
 * boards are registered, and rescheduling is a single ZADD. The equivalent in Postgres would be
 * a hot row-locked polling query on the busiest table in the system.
 *
 * <p>Losing this key is survivable — {@link CrawlBootstrapper} rebuilds it from
 * {@code company_board}, which is why the durable crawl state lives there and not here.
 */
@Component
public class CrawlQueue {

    static final String KEY = "crawl:due";

    private final StringRedisTemplate redis;
    private final RedisScript<List> claimScript;

    public CrawlQueue(StringRedisTemplate redis) {
        this.redis = redis;
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/claim_due.lua"));
        script.setResultType(List.class);
        this.claimScript = script;
    }

    public void schedule(UUID boardId, Instant dueAt) {
        redis.opsForZSet().add(KEY, boardId.toString(), dueAt.toEpochMilli());
    }

    /** Atomically removes and returns up to {@code limit} boards whose time has come. */
    @SuppressWarnings("unchecked")
    public List<UUID> claimDue(int limit) {
        List<String> claimed = (List<String>) redis.execute(
                claimScript,
                List.of(KEY),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(limit));
        if (claimed == null || claimed.isEmpty()) {
            return List.of();
        }
        return claimed.stream().map(UUID::fromString).toList();
    }

    public long size() {
        Long size = redis.opsForZSet().zCard(KEY);
        return size == null ? 0 : size;
    }

    public void clear() {
        redis.delete(KEY);
    }
}
