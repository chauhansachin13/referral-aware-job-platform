package com.referralhub.ingestion.ratelimit;

import java.time.Duration;

/** Per-host politeness budget, shared by every crawler process. */
public interface DistributedRateLimiter {

    /** Takes one token if the host's bucket has one, without blocking. */
    RateLimitDecision tryAcquire(String host);

    /**
     * Takes one token, waiting up to {@code maxWait} for the bucket to refill.
     *
     * @return {@code true} if a token was taken.
     */
    boolean acquire(String host, Duration maxWait) throws InterruptedException;
}
