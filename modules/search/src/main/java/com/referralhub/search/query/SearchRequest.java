package com.referralhub.search.query;

import java.util.List;

/**
 * A search as the API layer received it.
 *
 * @param cursor opaque continuation token from a previous response, or null for the first page
 */
public record SearchRequest(
        String query,
        String location,
        List<String> levels,
        Boolean remote,
        String companySlug,
        int size,
        String cursor) {

    public static final int MAX_SIZE = 100;

    public SearchRequest {
        query = query == null ? "" : query.trim();
        size = size <= 0 ? 20 : Math.min(size, MAX_SIZE);
        levels = levels == null ? List.of() : levels;
    }

    public boolean hasFilters() {
        return (location != null && !location.isBlank())
                || !levels.isEmpty()
                || remote != null
                || (companySlug != null && !companySlug.isBlank());
    }
}
