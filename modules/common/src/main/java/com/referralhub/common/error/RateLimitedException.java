package com.referralhub.common.error;

import java.time.Duration;

public class RateLimitedException extends DomainException {

    private final Duration retryAfter;

    public RateLimitedException(String message, Duration retryAfter) {
        super("rate_limited", message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
