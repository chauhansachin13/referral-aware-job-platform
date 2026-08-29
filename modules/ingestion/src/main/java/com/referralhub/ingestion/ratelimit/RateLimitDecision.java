package com.referralhub.ingestion.ratelimit;

import java.time.Duration;

/** The outcome of one attempt to take tokens from a host's bucket. */
public record RateLimitDecision(boolean allowed, Duration retryAfter, long tokensRemainingMillis) {

    public static RateLimitDecision allowed(long tokensRemainingMillis) {
        return new RateLimitDecision(true, Duration.ZERO, tokensRemainingMillis);
    }

    public static RateLimitDecision denied(Duration retryAfter) {
        return new RateLimitDecision(false, retryAfter, 0);
    }
}
