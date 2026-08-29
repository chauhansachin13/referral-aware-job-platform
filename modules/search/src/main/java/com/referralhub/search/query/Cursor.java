package com.referralhub.search.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opaque pagination token.
 *
 * <p>Honest about its limits: because the two retrievers are fused in the application rather
 * than inside OpenSearch, there is no single sort key to hand to {@code search_after}. The
 * cursor therefore carries the offset into the fused list plus a hash of the query, and page
 * two re-runs retrieval and skips forward.
 *
 * <p>That is fine for the first few pages, which is where essentially all job-search traffic
 * lives, and deliberately capped rather than left to degrade quietly: past
 * {@link #MAX_OFFSET} the API refuses instead of running an ever more expensive query. The
 * query hash makes a cursor from a different search fail loudly rather than silently paginating
 * through the wrong result set.
 */
public record Cursor(int offset, int queryHash) {

    public static final int MAX_OFFSET = 1_000;

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((offset + ":" + queryHash).getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String encoded, int expectedQueryHash) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed cursor", e);
        }
        String[] parts = decoded.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed cursor");
        }
        int offset;
        int queryHash;
        try {
            offset = Integer.parseInt(parts[0]);
            queryHash = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed cursor", e);
        }
        if (queryHash != expectedQueryHash) {
            throw new IllegalArgumentException(
                    "Cursor belongs to a different query; start again from the first page");
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException(
                    "Pagination beyond " + MAX_OFFSET + " results is not supported; narrow the query");
        }
        return new Cursor(offset, queryHash);
    }
}
