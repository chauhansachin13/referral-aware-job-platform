package com.referralhub.trust.capacity;

import com.referralhub.common.error.RateLimitedException;
import com.referralhub.trust.config.TrustProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-seeker daily request budget.
 *
 * <p>A cap exists because the marketplace's scarce resource is referrer attention, not database
 * rows. Without one, a handful of seekers spraying every open role would consume the whole
 * platform's goodwill and every referrer would learn to ignore the notifications.
 *
 * <p>Kept in Redis with a TTL rather than counted from Postgres: this is checked on the hot path
 * of every request creation, and it is a counter that is allowed to be lost on a flush — the
 * worst case is that one seeker gets a fresh allowance a few hours early.
 */
@Component
public class SeekerQuota {

    private static final String KEY_PREFIX = "quota:seeker:";

    private final StringRedisTemplate redis;
    private final TrustProperties properties;

    public SeekerQuota(StringRedisTemplate redis, TrustProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    private static String keyFor(UUID seekerId, Instant now) {
        return KEY_PREFIX + seekerId + ":" + now.truncatedTo(ChronoUnit.DAYS).getEpochSecond();
    }

    /**
     * Consumes one unit of the seeker's daily budget.
     *
     * @throws RateLimitedException when the budget is exhausted
     */
    public void consume(UUID seekerId) {
        Instant now = Instant.now();
        String key = keyFor(seekerId, now);

        Long used = redis.opsForValue().increment(key);
        if (used != null && used == 1L) {
            // First request of the day: set the window's lifetime once.
            redis.expire(key, Duration.ofDays(2));
        }

        int cap = properties.getSeekerDailyRequestCap();
        if (used != null && used > cap) {
            Instant tomorrow = now.truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(1));
            throw new RateLimitedException(
                    "Daily referral request limit of " + cap + " reached",
                    Duration.between(now, tomorrow));
        }
    }

    /** Gives a unit back when a request could not be created after all. */
    public void refund(UUID seekerId) {
        redis.opsForValue().decrement(keyFor(seekerId, Instant.now()));
    }

    public int remaining(UUID seekerId) {
        String value = redis.opsForValue().get(keyFor(seekerId, Instant.now()));
        int used = value == null ? 0 : Integer.parseInt(value);
        return Math.max(0, properties.getSeekerDailyRequestCap() - used);
    }
}
