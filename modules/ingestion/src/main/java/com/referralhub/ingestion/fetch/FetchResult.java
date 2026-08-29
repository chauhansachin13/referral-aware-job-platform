package com.referralhub.ingestion.fetch;

import java.time.Duration;
import java.time.Instant;

/** The three things a conditional crawl of a board can produce. */
public sealed interface FetchResult {

    Duration elapsed();

    /**
     * The board told us nothing changed. This is the cheap path the whole conditional-fetch
     * machinery exists to reach: no body is transferred, nothing is parsed, nothing is hashed,
     * no row is written.
     */
    record NotModified(Duration elapsed) implements FetchResult {
    }

    record Fetched(String body, String etag, Instant lastModified, Duration elapsed)
            implements FetchResult {
    }

    record Failed(int status, String message, Duration elapsed) implements FetchResult {
    }
}
